// URL() percent-encodes glyph template braces; MapLibre substitutes the literal
// placeholders, so preserve them after enforcing same-origin resource access.
export function localMapResource(path: string, base: string, origin: string): string {
  const url = new URL(path, base)
  if (url.origin !== origin) throw new Error('离线地图资源必须使用本站地址')
  return url.href.replaceAll('%7B', '{').replaceAll('%7D', '}')
}
