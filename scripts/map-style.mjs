import { layers, namedFlavor } from '../web-console/node_modules/@protomaps/basemaps/dist/esm/index.js'

export function offlineMapStyle() {
  // Match the cockpit's dark palette while keeping road and locality labels
  // readable. Aircraft and target overlays remain above these base layers.
  const flavor = {
    ...namedFlavor('dark'),
    background: '#182c3c',
    earth: '#101d2b',
    water: '#183749',
    park_a: '#19352d',
    park_b: '#1a3b31',
    buildings: '#28343c',
    minor_service: '#354958',
    minor_a: '#3f5365',
    minor_b: '#354958',
    major: '#597282',
    highway: '#6a8295',
    roads_label_minor: '#a9becd',
    roads_label_major: '#bdcfdd',
    ocean_label: '#94b3c6',
    subplace_label: '#a9becd',
    city_label: '#d1e2ef',
    state_label: '#a9becd',
    country_label: '#bdcfdd',
    address_label: '#91a8b9'
  }
  return {
    version: 8,
    glyphs: 'fonts/{fontstack}/{range}.pbf',
    sprite: 'sprites/v4/dark',
    sources: {
      protomaps: {
        type: 'vector', url: 'pmtiles://china.pmtiles',
        attribution: '<a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">© OpenStreetMap contributors</a> · Protomaps'
      }
    },
    layers: layers('protomaps', flavor, { lang: 'zh-Hans' })
  }
}
