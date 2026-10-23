'use strict';

function normalizeServerUrl(value) {
  if (typeof value !== 'string' || !value.trim()) throw new Error('请填写服务器 HTTPS 地址。');
  let parsed;
  try { parsed = new URL(value.trim()); } catch { throw new Error('服务器地址无效。'); }
  const loopback = parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1';
  if (parsed.protocol !== 'https:' && !(loopback && parsed.protocol === 'http:')) {
    throw new Error('服务器必须使用 HTTPS（本机 localhost 调试除外）。');
  }
  if (parsed.username || parsed.password || parsed.pathname !== '/' || parsed.search || parsed.hash) {
    throw new Error('只填写服务器地址和端口，不要包含路径、账号或参数。');
  }
  return parsed.origin;
}

function isServerPage(pageUrl, serverUrl) {
  if (!serverUrl || typeof pageUrl !== 'string') return false;
  try { return new URL(pageUrl).origin === new URL(serverUrl).origin; }
  catch { return false; }
}

module.exports = { normalizeServerUrl, isServerPage };
