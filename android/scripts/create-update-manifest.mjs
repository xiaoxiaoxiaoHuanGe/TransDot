import { readFileSync, writeFileSync, mkdirSync, readdirSync } from 'node:fs'
import { basename, dirname, join, resolve } from 'node:path'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'

// Usage: node create-update-manifest.mjs --arm64 APK --universal APK --notes FILE --output update.json
const args = process.argv.slice(2)
const options = Object.fromEntries(args.reduce((pairs, value, index) => index % 2 === 0 ? [...pairs, [value, args[index + 1]]] : pairs, []))
if (!options['--arm64'] && !options['--universal']) throw new Error('至少提供 --arm64 APK 或 --universal APK。')
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT
if (!sdk) throw new Error('请先设置 ANDROID_HOME。')
const buildTools = options['--build-tools'] || join(sdk, 'build-tools', readdirSync(join(sdk, 'build-tools')).filter((name) => /^\d+\.\d+\.\d+$/.test(name)).sort((a,b) => a.localeCompare(b, undefined, { numeric: true })).at(-1))
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
const aapt = join(buildTools, process.platform === 'win32' ? 'aapt.exe' : 'aapt')
const output = resolve(options['--output'] || 'update.json')
mkdirSync(dirname(output), { recursive: true })
let versionCode, versionName, certificate
const assets = []
for (const [option, abi] of [['--arm64', 'arm64-v8a'], ['--universal', 'universal']]) {
  if (!options[option]) continue
  const apk = resolve(options[option])
  const metadata = execFileSync(aapt, ['dump', 'badging', apk], { encoding: 'utf8' })
  const info = metadata.match(/^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/m)
  if (!info || info[1] !== 'com.transdot.transferassistant') throw new Error(`不是正式版 TransDot APK: ${apk}`)
  const minSdk = Number(metadata.match(/^sdkVersion:'(\d+)'/m)?.[1])
  if (!minSdk) throw new Error(`无法读取最低 Android 版本: ${apk}`)
  const actualAbis = [...(metadata.match(/^native-code: (.+)$/m)?.[1] || '').matchAll(/'([^']+)'/g)].map((match) => match[1])
  if (abi === 'arm64-v8a' && (actualAbis.length !== 1 || actualAbis[0] !== abi)) throw new Error('arm64 包的实际 ABI 不匹配。')
  if (abi === 'universal' && !['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'].every((value) => actualAbis.includes(value))) throw new Error('universal 包缺少预期 ABI。')
  const verification = execFileSync(java, ['-jar', join(buildTools, 'lib', 'apksigner.jar'), 'verify', '--print-certs', apk], { encoding: 'utf8' })
  const digest = verification.match(/Signer #1 certificate SHA-256 digest: (\w+)/)?.[1]
  if (!digest) throw new Error(`无法验证 APK 签名: ${apk}`)
  if (versionCode !== undefined && (versionCode !== Number(info[2]) || versionName !== info[3] || certificate !== digest)) throw new Error('各架构 APK 的版本或签名不一致。')
  versionCode = Number(info[2]); versionName = info[3]; certificate = digest
  const filename = basename(apk)
  const sha256 = createHash('sha256').update(readFileSync(apk)).digest('hex')
  assets.push({ abi, url: `https://github.com/xiaoxiaoxiaoHuanGe/TransDot/releases/download/v${versionName}/${encodeURIComponent(filename)}`, size: readFileSync(apk).length, sha256, minSdk })
  writeFileSync(join(dirname(output), `${filename}.sha256`), `${sha256}  ${filename}\n`)
}
const manifest = { versionCode, versionName, notes: options['--notes'] ? readFileSync(resolve(options['--notes']), 'utf8') : '', assets }
writeFileSync(output, JSON.stringify(manifest, null, 2) + '\n')
process.stdout.write(`已验证版本和签名，生成 ${output}\n`)
