import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './real-auth-tests', outputDir: './test-results/real-signin', workers: 1,
  reporter: 'list', use: { baseURL: 'http://localhost:5173', trace: 'on', screenshot: 'only-on-failure',
    ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) },
  webServer: { command: 'npm run dev -- --host localhost --port 5173 --strictPort', url: 'http://localhost:5173', reuseExistingServer: false,
    env: { VITE_AUTH_ENABLED: 'true', VITE_OIDC_AUTHORITY: 'http://localhost:18080/auth/realms/spectrace',
      VITE_OIDC_AUDIENCE: 'spectrace-api', VITE_GATEWAY_ORIGIN: 'http://localhost:18080', VITE_CALLER_CLAIMS_PATH: '/api/specifications/caller-claims' } },
})
