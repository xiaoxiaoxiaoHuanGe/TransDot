import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
const root=new URL('../',import.meta.url)
const read=p=>fs.readFileSync(new URL(p,root),'utf8')
test('Tauri uses system WebView2 instead of embedded Chromium',()=>{
 const config=JSON.parse(read('src-tauri/tauri.conf.json'))
 assert.equal(config.bundle.targets[0],'nsis')
 assert.equal(config.bundle.windows.webviewInstallMode.type,'downloadBootstrapper')
 assert.ok(!read('package.json').includes('electron'))
})
test('remote clipboard code requires server origin and authenticated session',()=>{
 const js=read('src-tauri/src/inject.js')
 assert.match(js,/TRUSTED_ORIGIN/)
 assert.match(js,/auth\/session/)
 assert.match(js,/message\.created/)
 assert.match(js,/copy_text/)
 assert.match(js,/copy_image_png/)
})
test('unique blue branded icon and Tauri tray included',()=>{
 assert.match(read('assets/transdot.svg'),/#1578f6/)
 assert.match(read('src-tauri/src/main.rs'),/TrayIconBuilder/)
})

test('remote HTTPS with a nonstandard port is authorized for native IPC',()=>{
 const capability=JSON.parse(read('src-tauri/capabilities/remote.json'))
 assert.ok(capability.remote.urls.includes('https://*:*'))
 assert.match(read('src-tauri/src/main.rs'),/origin_matches/)
})
test('installer supports simplified Chinese and optional English',()=>{
 const config=JSON.parse(read('src-tauri/tauri.conf.json'))
 assert.deepEqual(config.bundle.windows.nsis.languages,['SimpChinese','English'])
 assert.equal(config.bundle.windows.nsis.displayLanguageSelector,true)
 assert.equal(config.app.withGlobalTauri,true)
})
test('native tray provides clipboard self-test and privacy-safe diagnostic statuses',()=>{
 const rust=read('src-tauri/src/main.rs')
 assert.match(rust,/receiver_diagnostic/)
 assert.match(rust,/测试写入 Windows 剪贴板/)
 assert.match(rust,/复制接收器诊断信息/)
})

test('tray always explicitly displays enabled versus paused states and exact action',()=>{
  const main=read('src-tauri/src/main.rs')
  assert.match(main,/自动复制状态：已启用/)
  assert.match(main,/自动复制状态：已暂停/)
  assert.match(main,/暂停自动复制/)
  assert.match(main,/启用自动复制/)
  assert.match(main,/toggle_label\.set_text/)
  assert.match(main,/status_label\.set_text/)
})
test('receiver is reinjected after remote page load and has REST fallback',()=>{
  const main=read('src-tauri/src/main.rs')
  const js=read('src-tauri/src/inject.js')
  assert.match(main,/\.on_page_load/)
  assert.match(main,/window\.eval\(&script\)/)
  assert.match(js,/pollNewMessages/)
  assert.match(js,/latestInitialized/)
})

test('remote clipboard end-to-end self-test is wired in both Rust and injected JS',()=>{
 const rust=read('src-tauri/src/main.rs')
 const js=read('src-tauri/src/inject.js')
 assert.match(rust,/测试消息接收链路/)
 assert.match(rust,/__transdotDesktopTest/)
 assert.match(js,/window\.__transdotDesktopTest\s*=/)
 assert.match(js,/桌面消息接收链路测试成功/)
})
