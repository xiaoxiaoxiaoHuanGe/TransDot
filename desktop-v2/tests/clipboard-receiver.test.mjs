import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'

const script = fs.readFileSync(new URL('../src-tauri/src/inject.js', import.meta.url), 'utf8')

async function createMockReceiver() {
  const calls = []
  const sockets = []
  const location = {
    origin: 'https://transdot.example.com:9004',
    href: 'https://transdot.example.com:9004/',
    protocol: 'https:',
  }
  class FakeWebSocket {
    constructor(url) { this.url = url; sockets.push(this) }
    close() { this.onclose?.() }
  }
  const page = {
    location,
    __TRANSDOT_TRUSTED_ORIGIN__: location.origin,
    __TAURI__: {core: {invoke: async (name, args) => {
      calls.push({name, args})
      if (name === 'clipboard_enabled') return true
      return undefined
    }}},
    addEventListener() {},
  }
  page.top = page
  const document = {readyState: 'complete',getElementById() {return null}}
  const environment = {
    window: page, location, document, WebSocket: FakeWebSocket,
    URL, setTimeout, clearTimeout, console,
    fetch: async path => {
      assert.equal(path, '/api/v1/auth/session')
      return {ok:true,json:async()=>({authenticated:true,device_id:'own-desktop'})}
    },
  }
  vm.runInNewContext(script, environment)
  for(let n=0;n<5;n++) await new Promise(resolve=>setImmediate(resolve))
  assert.equal(sockets.length,1,'paired session starts realtime listener')
  assert.match(sockets[0].url, /^wss:\/\//)
  return {calls,sockets}
}

test('Android text message really invokes the native clipboard command',async()=>{
  const {calls,sockets} = await createMockReceiver()
  sockets[0].onopen?.()
  sockets[0].onmessage({data:JSON.stringify({
    type:'message.created',
    data:{id:'android-1',type:'text',source_device_id:'phone-1',text_content:'hello 中文'},
  })})
  for(let n=0;n<8;n++) await new Promise(resolve=>setImmediate(resolve))
  assert.equal(calls.filter(call=>call.name==='copy_text').length,1)
  assert.equal(calls.find(call=>call.name==='copy_text').args.text,'hello 中文')
  assert.ok(calls.some(call=>call.name==='receiver_diagnostic' && call.args.status.includes('已写入')))
})

test('self-sent messages and duplicate realtime events never overwrite the clipboard',async()=>{
  const {calls,sockets} = await createMockReceiver()
  const send = data => sockets[0].onmessage({data:JSON.stringify({type:'message.created',data})})
  send({id:'own-1',type:'text',source_device_id:'own-desktop',text_content:'self'})
  const other={id:'remote-1',type:'text',source_device_id:'phone-1',text_content:'once'}
  send(other)
  send(other)
  for(let n=0;n<8;n++) await new Promise(resolve=>setImmediate(resolve))
  assert.equal(calls.filter(call=>call.name==='copy_text').length,1)
})
