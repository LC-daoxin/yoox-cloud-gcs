#!/usr/bin/env node
// Runs only on the connected build machine, after extraction and asset download.
import { createReadStream } from 'node:fs'
import { cp, open, readFile, readdir, stat, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import path from 'node:path'
import { offlineMapStyle } from './map-style.mjs'
import { PMTiles } from '../web-console/node_modules/pmtiles/dist/esm/index.js'

const [directory, version, source, assetsCommit] = process.argv.slice(2)
if (!directory || !/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(version || '')) {
  throw new Error('Usage: node scripts/write-map-package.mjs RELEASE_DIR VERSION SOURCE ASSETS_COMMIT')
}
const handle = await open(path.join(directory, 'china.pmtiles'), 'r')
const archive = new PMTiles({
  getKey: () => 'china',
  async getBytes(offset, length) {
    const buffer = Buffer.alloc(length)
    const { bytesRead } = await handle.read(buffer, 0, length, offset)
    if (bytesRead !== length) throw new Error('地图包截断')
    return { data: buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + bytesRead) }
  }
})
const header = await archive.getHeader()
const metadata = await archive.getMetadata()
if (header.minZoom !== 0 || header.maxZoom !== 15 || header.tileType !== 1) {
  throw new Error('全国包必须包含 0–15 级 MVT 矢量瓦片')
}
// Verify actual tile availability, not just the bounding box in the header.
const locations = {
  beijing: [116.397, 39.909], shanghai: [121.473, 31.230], guangzhou: [113.264, 23.129],
  chengdu: [104.066, 30.572], urumqi: [87.616, 43.825], lhasa: [91.132, 29.660],
  harbin: [126.642, 45.756], haikou: [110.198, 20.044], hongkong: [114.169, 22.319],
  macau: [113.543, 22.198], taipei: [121.565, 25.033]
}
const samples = []
for (const [name, [lng, lat]] of Object.entries(locations)) {
  for (const z of [6, 10, 15]) {
    const n = 2 ** z
    const x = Math.floor((lng + 180) / 360 * n)
    const radians = lat * Math.PI / 180
    const y = Math.floor((1 - Math.asinh(Math.tan(radians)) / Math.PI) / 2 * n)
    const tile = await archive.getZxy(z, x, y)
    if (!tile || tile.data.byteLength === 0) throw new Error(`缺少验收瓦片 ${name}/${z}`)
    samples.push({ name, z, x, y, bytes: tile.data.byteLength })
  }
}
const tilesBytes = (await stat(path.join(directory, 'china.pmtiles'))).size
const rangeProbeOffset = tilesBytes > 2 ** 32 + 127 ? 2 ** 32 : Math.max(0, tilesBytes - 127)
const rangeProbe = Buffer.alloc(127)
const probeRead = await handle.read(rangeProbe, 0, rangeProbe.length, rangeProbeOffset)
if (probeRead.bytesRead !== rangeProbe.length) throw new Error('地图大文件探针读取失败')
const rangeProbeSha256 = createHash('sha256').update(rangeProbe).digest('hex')
await handle.close()

const style = offlineMapStyle()
await cp(new URL('../web-console/public/licenses/', import.meta.url), path.join(directory, 'licenses'), { recursive: true })
await writeFile(path.join(directory, 'style.json'), JSON.stringify(style, null, 2) + '\n')
for (const file of [
  'fonts/Noto Sans Regular/0-255.pbf',
  'fonts/NotoSansCJKsc-Regular.otf', 'fonts/Noto-CJK-LICENSE.txt',
  'fonts/OFL.txt', 'sprites/LICENSE.md',
  'sprites/v4/dark.json', 'sprites/v4/dark.png',
  'sprites/v4/dark@2x.json', 'sprites/v4/dark@2x.png'
]) {
  if (!(await stat(path.join(directory, file))).size) throw new Error(`缺少离线字体或图标 ${file}`)
}
const cjk = await readFile(path.join(directory, 'fonts/NotoSansCJKsc-Regular.otf'))
if (cjk.subarray(0, 4).toString() !== 'OTTO' || cjk.length < 1000000) {
  throw new Error('中文字体不是完整 OTF 文件')
}
await writeFile(path.join(directory, 'NOTICE.txt'), [
  'YOOX Cloud GCS 全国离线普通地图',
  'Map data © OpenStreetMap contributors, https://www.openstreetmap.org/copyright',
  'Protomaps basemap produced work: https://docs.protomaps.com/basemaps/downloads',
  'Data license: ODbL 1.0, https://opendatacommons.org/licenses/odbl/1-0/',
  'Font and sprite license texts are included in fonts/ and sprites/.',
  `Source: ${source}`, `Assets commit: ${assetsCommit}`,
  'Coverage is defined by coverage.geojson; data detail varies by location.',
  'Zoom 16–20 overzooms zoom 15 data and does not add new details.',
  ''
].join('\n'))
await writeFile(path.join(directory, 'manifest.json'), JSON.stringify({
  version, source, assetsCommit, cjkCommit: 'f8d157532fbfaeda587e826d4cd5b21a49186f7c', schema: 'protomaps-v4',
  stylePackage: '@protomaps/basemaps@5.7.2', coordinateSystem: 'WGS84',
  styleTheme: 'yoox-dark-v1',
  minZoom: header.minZoom, maxZoom: header.maxZoom,
  bounds: [header.minLon, header.minLat, header.maxLon, header.maxLat],
  tilesBytes, rangeProbeOffset, rangeProbeLength: rangeProbe.length, rangeProbeSha256,
  metadata, samples
}, null, 2) + '\n')
async function files(dir, prefix = '') {
  const result = []
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const relative = path.posix.join(prefix, entry.name)
    if (entry.isDirectory()) result.push(...await files(path.join(dir, entry.name), relative))
    else if (entry.isFile() && relative !== 'SHA256SUMS') result.push(relative)
    else if (!entry.isFile()) throw new Error(`不支持链接或特殊文件: ${relative}`)
  }
  return result.sort()
}
const checksums = []
for (const file of await files(directory)) {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(path.join(directory, file))) hash.update(chunk)
  checksums.push(`${hash.digest('hex')}  ${file}`)
}
await writeFile(path.join(directory, 'SHA256SUMS'), checksums.join('\n') + '\n')
console.log(JSON.stringify({ version, tilesBytes: (await stat(path.join(directory, 'china.pmtiles'))).size, verifiedTiles: samples.length }))
