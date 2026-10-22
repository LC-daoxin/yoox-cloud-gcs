import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: '.',
  testMatch: 'map-browser.spec.ts',
  timeout: 120000,
  workers: 1,
  reporter: 'list',
  outputDir: '../test-results',
  use: {
    baseURL: process.env.YOOX_MAP_TEST_URL || 'http://127.0.0.1:8080',
    viewport: { width: 1440, height: 1000 },
    launchOptions: {
      executablePath: process.env.YOOX_CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      args: ['--enable-unsafe-swiftshader']
    }
  }
})
