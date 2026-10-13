import assert from 'node:assert/strict'
import { defineConfig } from '@playwright/test'
import './scripts/check-pages-config.mjs'

for (const key of ['M3_DEPLOYED_BASE_URL', 'M3_TEST_USERNAME', 'M3_TEST_PASSWORD',
  'M3_EXPECTED_SUBJECT', 'M3_EXPECTED_ORG_ID', 'M3_EXPECTED_ORG_TYPE', 'M3_EXPECTED_ROLES']) {
  assert(process.env[key]?.trim(), `${key} must be supplied for deployed verification`)
}
const base = new URL(process.env.M3_DEPLOYED_BASE_URL!)
assert(base.protocol === 'https:' && !base.username && !base.password && !base.search && !base.hash,
  'The deployed site must be a clean HTTPS URL')
assert(base.pathname === process.env.VITE_SITE_BASE, 'The deployed site must use the configured root callback path')
assert(['SUPPLIER', 'MANUFACTURER'].includes(process.env.M3_EXPECTED_ORG_TYPE!), 'Expected organisation type is unsupported')
assert(process.env.M3_EXPECTED_SUBJECT !== process.env.M3_TEST_USERNAME, 'Expected subject must be the native user ID')
assert(process.env.M3_EXPECTED_ROLES!.split(',').every(role => role.trim()), 'Expected roles must be a nonempty comma-separated list')

export default defineConfig({
  testDir: './real-auth-tests', testMatch: 'signin.spec.ts', outputDir: './test-results/deployed-signin', workers: 1, reporter: 'list', timeout: 120000,
  metadata: { deployed: true, gatewayOrigin: process.env.VITE_GATEWAY_ORIGIN,
    callerPath: process.env.VITE_CALLER_CLAIMS_PATH, identityOrigin: new URL(process.env.VITE_OIDC_AUTHORITY!).origin },
  // The deployed build already exists. Never start Vite or store live login secrets in traces.
  use: { baseURL: base.href, trace: 'off', screenshot: 'off', video: 'off',
    ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) },
})
