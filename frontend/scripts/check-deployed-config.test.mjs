import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const cwd = fileURLToPath(new URL('../', import.meta.url))
// --list loads the actual config without launching a browser or contacting these fixture URLs.
const fixture = {
  VITE_OIDC_AUTHORITY: 'https://identity.example.test/auth/realms/spectrace',
  VITE_GATEWAY_ORIGIN: 'https://gateway.example.test', VITE_OIDC_AUDIENCE: 'spectrace-api',
  VITE_CALLER_CLAIMS_PATH: '/api/specifications/caller-claims', VITE_SITE_BASE: '/FoodLabelFlow-Microservices/',
  M3_DEPLOYED_BASE_URL: 'https://hxj04121-lab.github.io/FoodLabelFlow-Microservices/',
  M3_TEST_USERNAME: 'fixture-maker', M3_TEST_PASSWORD: 'fixture-only-password',
  M3_EXPECTED_SUBJECT: 'fixture-native-id', M3_EXPECTED_ORG_ID: 'org-fixture',
  M3_EXPECTED_ORG_TYPE: 'MANUFACTURER', M3_EXPECTED_ROLES: 'LABEL_OFFICER',
}
function list(overrides = {}) {
  return spawnSync(process.execPath, ['node_modules/@playwright/test/cli.js', 'test',
    '--config', 'playwright.deployed-signin.config.ts', '--list'],
  { cwd, env: { ...process.env, ...fixture, ...overrides }, encoding: 'utf8', timeout: 30000 })
}

test('the deployed profile reuses the real login test', () => {
  const result = list()
  assert.equal(result.status, 0, result.stderr)
  assert.match(result.stdout, /real PKCE login reaches the Specification caller-claims endpoint/)
})

test('missing secrets, insecure URLs, internal routes and wrong callbacks fail before browser execution', () => {
  for (const overrides of [
    { M3_TEST_PASSWORD: '' }, { M3_EXPECTED_SUBJECT: 'fixture-maker' },
    { VITE_GATEWAY_ORIGIN: 'http://localhost:18080' },
    { VITE_CALLER_CLAIMS_PATH: '/api/specifications/internal/caller' },
    { M3_DEPLOYED_BASE_URL: 'https://hxj04121-lab.github.io/' },
  ]) {
    const result = list(overrides)
    assert.equal(result.status, 1, result.stderr)
    assert.doesNotMatch(result.stdout + result.stderr, /fixture-only-password/)
  }
})
