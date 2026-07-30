import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/manage': 'http://localhost:9000',
      '/map': 'http://localhost:9000',
      '/media': 'http://localhost:9000',
      '/wayline': 'http://localhost:9000',
      '/control': 'http://localhost:9000',
      '/storage': 'http://localhost:9000',
      '/actuator': 'http://localhost:9000',
      '/webrtc': {
        target: 'http://localhost:8889',
        changeOrigin: true
      }
    }
  }
})
