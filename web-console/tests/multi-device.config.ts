import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir: '.', testMatch: 'multi-device.spec.ts', timeout: 45000,
  workers: 1, reporter: 'list', outputDir: '../test-results/multi-device',
  use: {
    baseURL: 'http://127.0.0.1:5174', viewport: { width: 1600, height: 1000 },
    launchOptions: {
      executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      args: ['--enable-unsafe-swiftshader']
    }
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5174',
    url: 'http://127.0.0.1:5174', reuseExistingServer: true
  }
})
