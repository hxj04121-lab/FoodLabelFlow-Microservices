import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './auth-tests', outputDir: './test-results/signin', workers: 1, reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:5191', trace: 'on', screenshot: 'only-on-failure',
    ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5191 --strictPort', url: 'http://127.0.0.1:5191', reuseExistingServer: false,
    env: { VITE_AUTH_ENABLED: 'true', VITE_OIDC_AUTHORITY: 'http://127.0.0.1:8191/auth/realms/test',
      VITE_GATEWAY_ORIGIN: 'http://127.0.0.1:8191', VITE_OIDC_AUDIENCE: 'spectrace-api-test', VITE_CALLER_CLAIMS_PATH: '/api/specifications/caller-claims' },
  },
})
