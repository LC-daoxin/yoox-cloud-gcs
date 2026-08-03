import { readFile, writeFile } from 'node:fs/promises'
import { basename, extname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const portalDir = fileURLToPath(new URL('.', import.meta.url))
const assetsDir = join(portalDir, 'assets')
const remoteAssetsDir = process.env.OFFLINE_REMOTE_ASSETS_DIR || '/private/tmp/api-portal-offline-assets'
const outputPath = join(portalDir, '上云API-开发者文档-离线版.html')

const mimeTypes = {
  '.jpeg': 'image/jpeg',
  '.jpg': 'image/jpeg',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.webp': 'image/webp'
}

async function toDataUri(path) {
  const extension = extname(path).toLowerCase()
  const mimeType = mimeTypes[extension]
  if (!mimeType) throw new Error(`不支持的图片格式: ${path}`)
  const bytes = await readFile(path)
  return `data:${mimeType};base64,${bytes.toString('base64')}`
}

let html = await readFile(join(portalDir, 'index.html'), 'utf8')
const css = await readFile(join(assetsDir, 'styles.css'), 'utf8')
const js = await readFile(join(assetsDir, 'app.js'), 'utf8')

html = html.replace(
  /<link\s+rel="stylesheet"\s+href="assets\/styles\.css\?v=\d+"\s*\/?>/,
  `<style>\n${css.replaceAll('</style>', '<\\/style>')}\n</style>`
)

html = html.replace(
  /<script\s+src="assets\/app\.js\?v=\d+"><\/script>/,
  `<script>\n${js.replaceAll('</script>', '<\\/script>')}\n</script>`
)

const imageSources = [...html.matchAll(/<img\b[^>]*\bsrc="([^"]+)"/g)].map(match => match[1])
for (const source of [...new Set(imageSources)]) {
  if (source.startsWith('data:')) continue

  const imagePath = source.startsWith('http://') || source.startsWith('https://')
    ? join(remoteAssetsDir, basename(new URL(source).pathname))
    : join(portalDir, source.split('?')[0])
  const dataUri = await toDataUri(imagePath)
  html = html.replaceAll(`src="${source}"`, `src="${dataUri}"`)
}

html = html.replace(
  '<meta name="viewport" content="width=device-width, initial-scale=1.0" />',
  '<meta name="viewport" content="width=device-width, initial-scale=1.0" />\n  <meta name="offline-bundle" content="true" />'
)

await writeFile(outputPath, html)
console.log(outputPath)
