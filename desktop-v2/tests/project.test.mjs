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
