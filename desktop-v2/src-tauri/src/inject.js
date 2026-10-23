/* Runs in the top-level remote TransDot document, not in the local settings page.
   The host only permits server origin navigation. Rust commands independently
   verify the same configured origin before touching the OS clipboard. */
(() => {
  if (window.top !== window ||
      window.location.origin !== window.__TRANSDOT_TRUSTED_ORIGIN__) return;
  if (window.__transdotDesktopReceiver) return;
  window.__transdotDesktopReceiver = true;
  let socket = null;
  let closed = false;
  let timer = null;
  let ownDeviceId = "";
  let counter = 0;
  let pollTimer = null;
  let polling = false;
  let latestInitialized = false;
  const seen = new Set();
  const invoke = (name, args) => {
    const fn = window.__TAURI__?.core?.invoke || window.__TAURI_INTERNALS__?.invoke;
    if (typeof fn !== "function") throw new Error("Tauri IPC unavailable");
    return fn(name, args);
  };
  // An actual end-to-end IPC test that does NOT need an Android message.
  // This validates remote page JS -> Tauri command -> Windows clipboard.
  window.__transdotDesktopTest = async () => {
    try {
      await invoke('copy_text', {text:'TransDot v2.0.3：桌面消息接收链路测试成功'});
      await updateStatus('桌面网页到 Windows 剪贴板的调用链路测试成功');
    } catch (error) {
      await updateStatus('桌面消息链路测试失败：' + String(error).slice(0,120));
    }
  };
  async function updateStatus(status) {
    try { await invoke('receiver_diagnostic', {status}); }
    catch (error) { console.warn('[TransDot v2] diagnostic unavailable:', error); }
  }
  function retry() {
    if (closed || timer) return;
    timer = setTimeout(() => { timer = null; connect(); }, 2300);
  }
  function safeImagePath(value) {
    try {
      const url = new URL(value, location.origin);
      if (url.origin !== location.origin || url.search || url.hash ||
          !/^\/api\/v1\/files\/[^/]+\/download$/.test(url.pathname)) return null;
      return url.pathname;
    } catch { return null; }
  }
  async function imageAsPngBase64(blob) {
    const objectUrl = URL.createObjectURL(blob);
    try {
      const img = new Image();
      await new Promise((yes, no) => {
        img.onload = yes; img.onerror = no;
        img.src = objectUrl;
      });
      if (!img.naturalWidth || !img.naturalHeight ||
          img.naturalWidth * img.naturalHeight > 12_000_000) return null;
      const canvas = document.createElement("canvas");
      canvas.width = img.naturalWidth; canvas.height = img.naturalHeight;
      canvas.getContext("2d").drawImage(img, 0, 0);
      const result = canvas.toDataURL("image/png").split(",", 2)[1];
      return result.length <= 16*1024*1024 ? result : null;
    } finally {
      URL.revokeObjectURL(objectUrl);
    }
  }
  async function onCreated(message) {
    if (!message || typeof message.id !== "string" || !["text", "image"].includes(message.type) ||
        message.source_device_id === ownDeviceId || seen.has(message.id)) return;
    if (seen.size > 1024) seen.clear();
    seen.add(message.id);
    const requestId = ++counter;
    await updateStatus('收到新' + (message.type === 'text' ? '文字' : '图片') + '，准备写入');
    try {
      if (!await invoke("clipboard_enabled")) {
        await updateStatus('收到消息，但自动复制已暂停或来源校验失败');
        return;
      }
      if (message.type === "text" && typeof message.text_content === "string") {
        if (requestId === counter) {
          await invoke("copy_text", {text: message.text_content});
          await updateStatus('最新文字已写入 Windows 剪贴板');
        }
      } else if (message.type === "image" &&
                 message.file?.status === "available" &&
                 message.file?.size_bytes <= 12*1024*1024) {
        const path = safeImagePath(message.file.download_url);
        if (!path) return;
        const response = await fetch(path, {credentials:"same-origin",cache:"no-store"});
        if (!response.ok) return;
        const blob = await response.blob();
        if (blob.size > 12*1024*1024 || !blob.type.startsWith("image/")) return;
        const data = await imageAsPngBase64(blob);
        if (data && requestId === counter) {
          await invoke("copy_image_png", {data});
          await updateStatus('最新图片已写入 Windows 剪贴板');
        }
      }
    } catch (error) {
      console.warn("[TransDot v2] Clipboard sync error:",error);
      await updateStatus('剪贴板操作失败: ' + String(error).slice(0,180));
    }
  }
  // REST is the durable source of truth. WebSocket wakes us immediately,
  // while this fallback handles dropped events or a disconnected socket.
  // The first successful fetch only seeds IDs: no historical clipboard writes.
  async function pollNewMessages() {
    if (closed || polling || !ownDeviceId) return;
    polling = true;
    try {
      const response = await fetch('/api/v1/messages?limit=50', {
        credentials: 'same-origin', cache: 'no-store'
      });
      if (!response.ok) throw new Error('消息校验 HTTP ' + response.status);
      const page = await response.json();
      if (!Array.isArray(page.messages)) throw new Error('消息列表格式无效');
      const sorted = page.messages.slice().sort((a,b) =>
        Date.parse(a.created_at || '') - Date.parse(b.created_at || ''));
      if (!latestInitialized) {
        for (const m of sorted) if (typeof m.id === 'string') seen.add(m.id);
        latestInitialized = true;
        await updateStatus('接收器已就绪：实时监听及消息校验均已启用');
      } else {
        for (const message of sorted) void onCreated(message);
      }
    } catch (error) {
      await updateStatus('消息校验失败：' + String(error).slice(0,120));
    } finally {
      polling = false;
    }
  }
  async function connect() {
    if (closed || socket || location.origin !== window.__TRANSDOT_TRUSTED_ORIGIN__) return;
    if (!window.__TAURI__?.core?.invoke && !window.__TAURI_INTERNALS__?.invoke) {
      console.warn('[TransDot v2] Native clipboard bridge unavailable on remote origin');
      if (document.body && !document.getElementById('__transdot_native_notice')) {
        const notice = document.createElement('aside');
        notice.id = '__transdot_native_notice';
        notice.textContent = 'TransDot 桌面剪贴板组件未连接；请使用新版安装包或查看系统托盘诊断。';
        notice.style.cssText = 'position:fixed;bottom:12px;right:12px;z-index:2147483647;padding:10px 14px;border:1px solid #e3bb76;border-radius:8px;background:#fff7e6;color:#8a5000;font:13px system-ui;max-width:360px';
        document.body.appendChild(notice);
      }
      retry();
      return;
    }
    try {
      const result = await fetch("/api/v1/auth/session",
        {credentials:"same-origin",cache:"no-store"});
      if (!result.ok) { await updateStatus('等待设备授权: HTTP ' + result.status); retry();return;}
      const session = await result.json();
      if (!session.authenticated || typeof session.device_id !== "string") {retry();return;}
      ownDeviceId = session.device_id;
      await updateStatus('已授权电脑，正在连接实时消息');
      if (!pollTimer) {
        await pollNewMessages();
        pollTimer = setInterval(() => { void pollNewMessages(); }, 4000);
      }
      const url = new URL("/ws", location.href);
      url.protocol = location.protocol === "https:" ? "wss:" : "ws:";
      socket = new WebSocket(url.toString());
      socket.onopen = () => { void updateStatus('WebSocket 已连接，等待新消息'); };
      socket.onmessage = event => {
        try {
          const packet = JSON.parse(event.data);
          if (packet.type === "message.created") void onCreated(packet.data);
          if (["device.revoked","device.replaced"].includes(packet.type)) socket?.close();
        } catch (error) {console.warn("[TransDot v2] Bad WebSocket event",error);}
      };
      socket.onclose = () => {
        socket = null;
        void updateStatus('实时消息已断开，尝试重连');
        retry();
      };
      socket.onerror = () => {};
    } catch (error) {
      console.warn("[TransDot v2] Waiting for session:",error);
      retry();
    }
  }
  window.addEventListener("pagehide",()=> {
    closed = true; counter++;
    if (timer) clearTimeout(timer);
    if (pollTimer) clearInterval(pollTimer);
    socket?.close(); socket=null;
  },{once:true});
  const start=()=>{connect();};
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded",start,{once:true});
  else start();
})();
