// 高德地图 JS API 2.0 动态加载器。Key 从运行时配置或构建时环境变量读取，只加载一次。

interface YooxRuntime {
  amapKey?: string
  amapSecurityCode?: string
}

declare global {
  interface Window {
    AMap?: any
    _AMapSecurityConfig?: { securityJsCode: string }
    YOOX_RUNTIME?: YooxRuntime
    __amapLoading?: Promise<any>
  }
}

export function getAmapKey(): string {
  return window.YOOX_RUNTIME?.amapKey || (import.meta as any).env?.VITE_AMAP_KEY || ''
}

function getSecurityCode(): string {
  return window.YOOX_RUNTIME?.amapSecurityCode || (import.meta as any).env?.VITE_AMAP_SECURITY_CODE || ''
}

export function loadAMap(): Promise<any> {
  if (window.AMap) return Promise.resolve(window.AMap)
  if (window.__amapLoading) return window.__amapLoading

  const key = getAmapKey()
  if (!key) return Promise.reject(new Error('未配置高德地图 Key，请在 runtime-config.js 中填写 amapKey'))

  const securityCode = getSecurityCode()
  if (securityCode) window._AMapSecurityConfig = { securityJsCode: securityCode }

  window.__amapLoading = new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`
    script.async = true
    script.onerror = () => {
      window.__amapLoading = undefined
      reject(new Error('高德地图脚本加载失败，请检查 Key、安全密钥与网络'))
    }
    script.onload = () => {
      if (window.AMap) resolve(window.AMap)
      else reject(new Error('高德地图脚本已加载但 AMap 未就绪'))
    }
    document.head.appendChild(script)
  })
  return window.__amapLoading
}
