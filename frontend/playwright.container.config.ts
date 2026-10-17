import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './container-tests', outputDir: './test-results/container', workers: 1, reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:5193/', trace: 'off',
    ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) },
  webServer: {
    command: 'npm run build && node scripts/write-pages-release.mjs && npm run preview -- --host 127.0.0.1 --port 5193 --strictPort',
    url: 'http://127.0.0.1:5193/', reuseExistingServer: false,
    env: { VITE_AUTH_ENABLED: 'true', VITE_SITE_BASE: '/', VITE_GATEWAY_ORIGIN: '/',
      VITE_OIDC_AUTHORITY: 'https://identity.example.test/auth/realms/spectrace',
      VITE_OIDC_AUDIENCE: 'spectrace-api', VITE_CALLER_CLAIMS_PATH: '/api/specifications/caller-claims',
      GITHUB_SHA: 'cccccccccccccccccccccccccccccccccccccccc' },
  },
})
