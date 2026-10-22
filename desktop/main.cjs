'use strict';

const { app, BrowserWindow, Tray, Menu, clipboard, nativeImage, ipcMain } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { normalizeServerUrl, isServerPage } = require('./security.cjs');

const ICON_PNG = 'iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAA00lEQVR42t2XMQ7CMAxF0y/3DowwcDIGOBK3YwSJIxCpTEiVSWjsOE6LxzTOc74dVx6Op+cUEvaiGCxtjJRcp9Zgfi4PBB7wXwx4wlMseMN5EAidDT1uP1ehuwKkcbpdd8n1w+UhT4EVfOlbzob9+T7VgmvU6P8KWtxe4oMWcIkv1cg3z7M2UFjAtU9wO0W4ygB4zrU1UNSItIeX1AWsDtpsJyz+F5SmQ6oWLKXVpEqswP/1gdzE4mFjpJV0wh4qfJhYGh5bwr+K0CMIzqDcBq/x/A3aXVSIs1er/AAAAABJRU5ErkJggg==';
const MAX_PNG_BYTES = 12 * 1024 * 1024;
const MAX_PIXELS = 32 * 1024 * 1024;
const PNG_HEADER = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

let mainWindow = null;
let tray = null;
let quitting = false;
let config = { serverUrl: '', clipboardEnabled: true };

const setupPageUrl = pathToFileURL(path.join(__dirname, 'setup.html')).href;

function configPath() {
  return path.join(app.getPath('userData'), 'desktop-settings.json');
}

function readConfig() {
  try {
    const saved = JSON.parse(fs.readFileSync(configPath(), 'utf8'));
    return {
      serverUrl: saved.serverUrl ? normalizeServerUrl(saved.serverUrl) : '',
      clipboardEnabled: saved.clipboardEnabled !== false,
    };
  } catch {
    return { serverUrl: '', clipboardEnabled: true };
  }
}

function saveConfig() {
  fs.mkdirSync(path.dirname(configPath()), { recursive: true });
  fs.writeFileSync(configPath(), JSON.stringify(config, null, 2), { mode: 0o600 });
}

function isTrustedRemote(event) {
  return mainWindow &&
    !mainWindow.isDestroyed() &&
    event.sender === mainWindow.webContents &&
    event.senderFrame === mainWindow.webContents.mainFrame &&
    isServerPage(event.senderFrame.url, config.serverUrl);
}

function isTrustedSetup(event) {
  return mainWindow &&
    !mainWindow.isDestroyed() &&
    event.sender === mainWindow.webContents &&
    event.senderFrame === mainWindow.webContents.mainFrame &&
    event.senderFrame.url === setupPageUrl;
}

function showWindow() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  mainWindow.show();
  if (mainWindow.isMinimized()) mainWindow.restore();
  mainWindow.focus();
}

function showSetup() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  void mainWindow.loadFile(path.join(__dirname, 'setup.html'));
  showWindow();
}

function refreshTrayMenu() {
  if (!tray) return;
  tray.setToolTip('TransDot · 手机到电脑剪贴板');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '打开 TransDot', click: showWindow },
    { type: 'separator' },
    {
      label: '自动复制手机发送的文字和图片',
      type: 'checkbox',
      checked: config.clipboardEnabled,
      click: (item) => {
        config.clipboardEnabled = item.checked;
        saveConfig();
        refreshTrayMenu();
      },
    },
    {
      label: '开机启动',
      type: 'checkbox',
      enabled: app.isPackaged && process.platform === 'win32',
      checked: app.isPackaged && process.platform === 'win32' && app.getLoginItemSettings().openAtLogin,
      click: (item) => {
        app.setLoginItemSettings({ openAtLogin: item.checked, args: ['--hidden'] });
        refreshTrayMenu();
      },
    },
    { label: '更换服务器', click: showSetup },
    { type: 'separator' },
    { label: '退出 TransDot', click: () => { quitting = true; app.quit(); } },
  ]));
}

function createWindow() {
  const icon = nativeImage.createFromBuffer(Buffer.from(ICON_PNG, 'base64'));
  mainWindow = new BrowserWindow({
    title: 'TransDot',
    width: 1180,
    height: 800,
    minWidth: 780,
    minHeight: 560,
    show: false,
    autoHideMenuBar: true,
    icon,
    backgroundColor: '#f7faff',
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      webSecurity: true,
      backgroundThrottling: false,
      webviewTag: false,
    },
  });
  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  mainWindow.webContents.on('will-navigate', (event, target) => {
    if (!isServerPage(target, config.serverUrl) && target !== setupPageUrl) event.preventDefault();
  });
  mainWindow.webContents.session.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  mainWindow.on('close', (event) => {
    if (!quitting) { event.preventDefault(); mainWindow.hide(); }
  });
  if (config.serverUrl) {
    void mainWindow.loadURL(config.serverUrl).catch(() => showSetup());
  } else {
    void mainWindow.loadFile(path.join(__dirname, 'setup.html'));
  }
  if (!process.argv.includes('--hidden') || !config.serverUrl) showWindow();
}

function registerIPC() {
  ipcMain.handle('transdot:setupInfo', (event) => {
    if (!isTrustedSetup(event)) throw new Error('Unauthorized');
    return { serverUrl: config.serverUrl };
  });

  ipcMain.handle('transdot:configure', (event, value) => {
    if (!isTrustedSetup(event)) throw new Error('Unauthorized');
    const serverUrl = normalizeServerUrl(value);
    config.serverUrl = serverUrl;
    saveConfig();
    void mainWindow.loadURL(serverUrl).catch(() => showSetup());
    return true;
  });

  ipcMain.handle('transdot:enabled', (event) =>
    !!isTrustedRemote(event) && config.clipboardEnabled
  );

  ipcMain.handle('transdot:writeText', (event, value) => {
    if (!isTrustedRemote(event) || !config.clipboardEnabled) return false;
    if (typeof value !== 'string' || !value || Buffer.byteLength(value, 'utf8') > 150 * 1024) return false;
    clipboard.writeText(value, 'clipboard');
    return true;
  });

  ipcMain.handle('transdot:writeImage', (event, value) => {
    if (!isTrustedRemote(event) || !config.clipboardEnabled) return false;
    const prefix = 'data:image/png;base64,';
    if (typeof value !== 'string' || !value.startsWith(prefix)) return false;
    const encoded = value.slice(prefix.length);
    if (encoded.length > Math.ceil(MAX_PNG_BYTES * 4 / 3) + 4) return false;
    const imageBytes = Buffer.from(encoded, 'base64');
    if (imageBytes.length < 24 || imageBytes.length > MAX_PNG_BYTES || !imageBytes.subarray(0, 8).equals(PNG_HEADER)) return false;
    const width = imageBytes.readUInt32BE(16);
    const height = imageBytes.readUInt32BE(20);
    if (!width || !height || width * height > MAX_PIXELS) return false;
    const bitmap = nativeImage.createFromBuffer(imageBytes);
    if (bitmap.isEmpty()) return false;
    clipboard.writeImage(bitmap, 'clipboard');
    return true;
  });
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', showWindow);
  app.on('before-quit', () => { quitting = true; });
  app.on('window-all-closed', () => { /* Tray keeps receiving while the window is hidden. */ });
  app.whenReady().then(() => {
    config = readConfig();
    registerIPC();
    tray = new Tray(nativeImage.createFromBuffer(Buffer.from(ICON_PNG, 'base64')).resize({ width: 16, height: 16 }));
    tray.on('double-click', showWindow);
    refreshTrayMenu();
    createWindow();
    app.on('activate', showWindow);
  });
}
