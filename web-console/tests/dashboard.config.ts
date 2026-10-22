import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: '.', testMatch: 'dashboard.spec.ts', timeout: 60000,
  workers: 1, reporter: 'list', outputDir: '../test-results/dashboard',
  use: {
    baseURL: process.env.YOOX_DASHBOARD_TEST_URL || 'http://127.0.0.1:5174', viewport: { width: 1440, height: 1000 },
    launchOptions: {
      executablePath: process.env.YOOX_CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      args: ['--enable-unsafe-swiftshader']
    }
  },
  webServer: process.env.YOOX_DASHBOARD_TEST_URL ? undefined : {
    command: 'npm run dev -- --host 127.0.0.1 --port 5174',
    url: 'http://127.0.0.1:5174', reuseExistingServer: true
  }
})
