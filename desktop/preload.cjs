'use strict';
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('transdotDesktop', Object.freeze({
  enabled: () => ipcRenderer.invoke('transdot:enabled'),
  writeText: (text) => ipcRenderer.invoke('transdot:writeText', text),
  writeImage: (pngDataUrl) => ipcRenderer.invoke('transdot:writeImage', pngDataUrl),
  setupInfo: () => ipcRenderer.invoke('transdot:setupInfo'),
  configure: (url) => ipcRenderer.invoke('transdot:configure', url),
}));
