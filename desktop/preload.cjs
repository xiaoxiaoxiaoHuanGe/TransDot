'use strict';

// Runs in Electron's isolated preload world, NOT inside the server-hosted React bundle.
// This makes clipboard reception work with an already deployed TransDot server.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('transdotDesktop', Object.freeze({
  setupInfo: () => ipcRenderer.invoke('transdot:setupInfo'),
  configure: (url) => ipcRenderer.invoke('transdot:configure', url),
}));

if (location.protocol === 'https:' ||
    (location.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(location.hostname))) {
  const ids = new Set();
  let ownDeviceID = '';
  let socket = null;
  let retryTimer = null;
  let stopped = false;
  let serial = 0;
  let retryDelay = 1400;

  function setRetry(delay) {
    if (stopped || retryTimer) return;
    retryTimer = setTimeout(() => {
      retryTimer = null;
      void connectIfPaired();
    }, delay);
  }

  async function blobToPngDataUrl(blob) {
    const img = new Image();
    const objectUrl = URL.createObjectURL(blob);
    try {
      await new Promise((resolve, reject) => {
        img.onload = resolve;
        img.onerror = () => reject(new Error('Cannot decode image'));
        img.src = objectUrl;
      });
      const { naturalWidth: width, naturalHeight: height } = img;
      if (!width || !height || width * height > 32 * 1024 * 1024) return null;
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const context = canvas.getContext('2d');
      if (!context) return null;
      context.drawImage(img, 0, 0);
      return canvas.toDataURL('image/png');
    } finally {
      URL.revokeObjectURL(objectUrl);
    }
  }

  function safeImageDownloadPath(value) {
    if (typeof value !== 'string') return null;
    try {
      const url = new URL(value, location.origin);
      if (url.origin !== location.origin ||
          !/^\/api\/v1\/files\/[^/]+\/download$/.test(url.pathname) ||
          url.search || url.hash) return null;
      return url.pathname;
    } catch { return null; }
  }

  async function handleNewMessage(message) {
    if (!message || typeof message !== 'object' || typeof message.id !== 'string' ||
        message.source_device_id === ownDeviceID || ids.has(message.id)) return;
    if (!['text', 'image'].includes(message.type)) return;
    if (ids.size >= 1024) ids.clear();
    ids.add(message.id);
    const current = ++serial;

    try {
      if (!await ipcRenderer.invoke('transdot:enabled') || stopped) return;
      if (message.type === 'text') {
        if (typeof message.text_content !== 'string') return;
        if (current === serial) await ipcRenderer.invoke('transdot:writeText', message.text_content);
        return;
      }
      if (message.file?.status !== 'available' || message.file.size_bytes > 12 * 1024 * 1024) return;
      const downloadPath = safeImageDownloadPath(message.file?.download_url);
      if (!downloadPath) return;
      const response = await fetch(downloadPath, { credentials: 'same-origin', cache: 'no-store' });
      if (!response.ok || stopped) return;
      const blob = await response.blob();
      if (blob.size > 12 * 1024 * 1024 || !blob.type.startsWith('image/')) return;
      const dataUrl = await blobToPngDataUrl(blob);
      if (dataUrl && current === serial && !stopped) {
        await ipcRenderer.invoke('transdot:writeImage', dataUrl);
      }
    } catch (error) {
      console.warn('[TransDot Desktop] Clipboard reception failed:', error);
    }
  }

  async function connectIfPaired() {
    if (stopped || socket) return;
    try {
      const response = await fetch('/api/v1/auth/session', {
        credentials: 'same-origin', cache: 'no-store',
      });
      if (!response.ok) { setRetry(2500); return; }
      const session = await response.json();
      if (!session.authenticated || typeof session.device_id !== 'string') {
        setRetry(2500);
        return;
      }
      ownDeviceID = session.device_id;
      const url = new URL('/ws', location.href);
      url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
      socket = new WebSocket(url.href);
      socket.onopen = () => { retryDelay = 1400; };
      socket.onmessage = (event) => {
        try {
          const payload = JSON.parse(event.data);
          if (payload?.type === 'message.created') void handleNewMessage(payload.data);
          if (payload?.type === 'device.revoked' || payload?.type === 'device.replaced') {
            socket?.close();
          }
        } catch (error) {
          console.warn('[TransDot Desktop] Invalid event:', error);
        }
      };
      socket.onclose = () => {
        socket = null;
        setRetry(retryDelay);
        retryDelay = Math.min(retryDelay * 2, 10000);
      };
      socket.onerror = () => { /* Close event performs retry. */ };
    } catch (error) {
      console.warn('[TransDot Desktop] Waiting for server:', error);
      setRetry(2500);
    }
  }

  window.addEventListener('pagehide', () => {
    stopped = true;
    serial++;
    if (retryTimer) clearTimeout(retryTimer);
    socket?.close();
    socket = null;
  });
  void connectIfPaired();
}
