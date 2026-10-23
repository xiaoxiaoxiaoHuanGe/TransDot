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
test('tray menu stays focused on daily actions and shortcut settings',()=>{
 const rust=read('src-tauri/src/main.rs')
 assert.match(rust,/CheckMenuItem::with_id\(\s*app, "toggle", "自动复制"/)
 assert.match(rust,/CheckMenuItem::with_id\(\s*app, "auto", "开机启动"/)
 assert.match(rust,/快捷键设置/)
 assert.match(rust,/打开面板/)
 assert.match(rust,/完全退出/)
 assert.doesNotMatch(rust,/MenuItem::with_id\(app, "diagnostics"/)
 assert.doesNotMatch(rust,/MenuItem::with_id\(app, "test"/)
 assert.doesNotMatch(rust,/MenuItem::with_id\(app, "remote_test"/)
})

test('global shortcut is configurable, persistent, and defaults to Ctrl+Shift+T',()=>{
 const rust=read('src-tauri/src/main.rs')
 const ui=read('ui/index.html')
 const cargo=read('src-tauri/Cargo.toml')
 const settings=JSON.parse(read('src-tauri/capabilities/settings.json'))
 assert.match(rust,/DEFAULT_SHORTCUT: &str = "Ctrl\+Alt\+T"/)
 assert.match(rust,/fn normalize_shortcut/)
 assert.match(rust,/fn configure_shortcut/)
 assert.match(rust,/ShortcutState::Pressed/)
 assert.match(rust,/global_shortcut\(\)\.register/)
 assert.match(rust,/#\[serde\(rename_all = "camelCase", default\)\]/)
 assert.match(ui,/录制快捷键/)
 assert.match(ui,/恢复默认/)
 assert.match(ui,/configure_shortcut/)
 assert.match(cargo,/tauri-plugin-global-shortcut = "2"/)
 assert.ok(settings.permissions.includes('allow-configure-shortcut'))
})

test('receiver is reinjected after remote page load and has REST fallback',()=>{
  const main=read('src-tauri/src/main.rs')
  const js=read('src-tauri/src/inject.js')
  assert.match(main,/\.on_page_load/)
  assert.match(main,/window\.eval\(&script\)/)
  assert.match(js,/pollNewMessages/)
  assert.match(js,/latestInitialized/)
})

test('remote bridge self-test helper stays out of the tray menu',()=>{
 const rust=read('src-tauri/src/main.rs')
 const js=read('src-tauri/src/inject.js')
 assert.doesNotMatch(rust,/测试消息接收链路/)
 assert.doesNotMatch(rust,/__transdotDesktopTest/)
 assert.match(js,/window\.__transdotDesktopTest\s*=/)
 assert.match(js,/桌面消息接收链路测试成功/)
})


test('remote WebView custom clipboard commands have real Tauri ACL grants',()=>{
 const build=read('src-tauri/build.rs')
 const remote=JSON.parse(read('src-tauri/capabilities/remote.json'))
 const local=JSON.parse(read('src-tauri/capabilities/settings.json'))
 const remoteCommands=['copy_text','copy_image_png','clipboard_enabled','receiver_diagnostic']
 const localCommands=['get_settings','configure_server','configure_shortcut']
 assert.match(build,/AppManifest::new\(\)\.commands/)
 for(const command of [...remoteCommands,...localCommands]) {
   assert.ok(build.includes('"'+command+'"'), 'manifest must register '+command)
 }
 const kebab=command=>'allow-'+command.replaceAll('_','-')
 for(const command of remoteCommands) {
   assert.ok(remote.permissions.includes(kebab(command)),command+' must be allowed remotely')
   assert.ok(!local.permissions.includes(kebab(command)),command+' must not be allowed from settings')
 }
 for(const command of localCommands) {
   assert.ok(local.permissions.includes(kebab(command)),command+' must be allowed from local settings')
   assert.ok(!remote.permissions.includes(kebab(command)),command+' must not be allowed remotely')
 }
 assert.deepEqual(remote.windows,['remote'])
 assert.deepEqual(local.windows,['settings'])
})
