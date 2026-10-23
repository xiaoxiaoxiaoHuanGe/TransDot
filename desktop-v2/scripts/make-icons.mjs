import fs from 'node:fs/promises'
import path from 'node:path'
import sharp from 'sharp'

const root = new URL('../', import.meta.url)
const src = await fs.readFile(new URL('assets/transdot.svg', root))
const dest = new URL('src-tauri/icons/', root)
await fs.mkdir(dest, { recursive: true })
const sizes = [16, 24, 32, 48, 64, 128, 256]
const buffers = await Promise.all(sizes.map(s => sharp(src).resize(s,s).png().toBuffer()))
await fs.writeFile(new URL('src-tauri/icons/icon.png', root),buffers.at(-1))
// ICO files can contain PNG-compressed frames. Write entries with real dimensions/offsets.
const header = Buffer.alloc(6 + sizes.length*16)
header.writeUInt16LE(0,0);header.writeUInt16LE(1,2);header.writeUInt16LE(sizes.length,4)
let offset=header.length
sizes.forEach((size,index)=>{
  const position=6+index*16
  header[position]=size===256?0:size
  header[position+1]=size===256?0:size
  header[position+2]=0;header[position+3]=0
  header.writeUInt16LE(1,position+4)
  header.writeUInt16LE(32,position+6)
  header.writeUInt32LE(buffers[index].length,position+8)
  header.writeUInt32LE(offset,position+12)
  offset+=buffers[index].length
})
await fs.writeFile(new URL('src-tauri/icons/icon.ico',root),Buffer.concat([header,...buffers]))
console.log('TransDot blue icon generated: PNG + Windows multi-size ICO')
