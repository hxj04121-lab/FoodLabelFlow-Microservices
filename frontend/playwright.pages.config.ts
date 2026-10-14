import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './pages-tests', outputDir: './test-results/pages', workers: 1, reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:5192/FoodLabelFlow-Microservices/', trace: 'on',
    ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) },
  webServer: {
    command: 'npm run build && npm run preview -- --host 127.0.0.1 --port 5192 --strictPort',
    url: 'http://127.0.0.1:5192/FoodLabelFlow-Microservices/', reuseExistingServer: false,
    // Reserved test domain; no real deployment address or credentials are assumed.
    env: { VITE_AUTH_ENABLED: 'true', VITE_SITE_BASE: '/FoodLabelFlow-Microservices/',
      VITE_OIDC_AUTHORITY: 'https://identity.example.test/auth/realms/test',
      VITE_GATEWAY_ORIGIN: 'https://gateway.example.test', VITE_OIDC_AUDIENCE: 'spectrace-api-test' },
  },
})
