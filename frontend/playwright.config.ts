import { defineConfig } from '@playwright/test'
const externalBaseUrl = process.env.PLAYWRIGHT_BASE_URL
const localPort = process.env.PLAYWRIGHT_PORT || '5173'
export default defineConfig({
  testDir: './tests',
  workers: 1,
  use: {
    baseURL: externalBaseUrl || `http://127.0.0.1:${localPort}`,
    ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}),
    viewport: { width: 1440, height: 1000 },
    screenshot: 'only-on-failure',
  },
  reporter: 'list',
  // Fixture runs must not reuse a developer's authenticated Vite session.
  webServer: externalBaseUrl ? undefined : { command: `npm run dev -- --host 127.0.0.1 --port ${localPort} --strictPort`, url: `http://127.0.0.1:${localPort}`, reuseExistingServer: false,
    env: { VITE_AUTH_ENABLED: 'false', VITE_SITE_BASE: '/' } },
})
