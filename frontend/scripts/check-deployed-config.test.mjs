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
  M3_OWN_PRODUCT_ID: 'prod-fixture-own', M3_FOREIGN_PRODUCT_ID: 'prod-fixture-foreign',
}
function list(overrides = {}, config = 'playwright.deployed-signin.config.ts') {
  return spawnSync(process.execPath, ['node_modules/@playwright/test/cli.js', 'test',
    '--config', config, '--list'],
  { cwd, env: { ...process.env, ...fixture, ...overrides }, encoding: 'utf8', timeout: 30000 })
}

test('the deployed profile reuses the real login test', () => {
  const result = list()
  assert.equal(result.status, 0, result.stderr)
  assert.match(result.stdout, /real PKCE login reaches the Specification caller-claims endpoint/)
  assert.doesNotMatch(result.stdout, /seeded-access/)
})

test('root deployment resolves a same-origin Gateway without changing the public /api paths', () => {
  const result = list({ VITE_SITE_BASE: '/', VITE_GATEWAY_ORIGIN: '/', M3_DEPLOYED_BASE_URL: 'https://staging.example.test/' })
  assert.equal(result.status, 0, result.stderr)
  assert.notEqual(list({ VITE_GATEWAY_ORIGIN: '/' }).status, 0)
  for (const gateway of ['/api', '//foreign.example.test', '']) {
    assert.notEqual(list({ VITE_SITE_BASE: '/', VITE_GATEWAY_ORIGIN: gateway, M3_DEPLOYED_BASE_URL: 'https://staging.example.test/' }).status, 0)
  }
})

test('the demo profile requires explicit writes, separate maker/checker and actual baseline resources', () => {
  const demo = {
    M3_DEMO_ALLOW_WRITES: 'true', M3_EXPECTED_ROLES: 'LABEL_OFFICER,CHANGE_MANAGER,ADMIN_DATA_MAINTENANCE',
    M3_DEMO_SUPPLIER_USERNAME: 'fixture-supplier', M3_DEMO_SUPPLIER_PASSWORD: 'fixture-only-password',
    M3_DEMO_SUPPLIER_SUBJECT: 'supplier-native', M3_DEMO_SUPPLIER_ORG_ID: 'org-supplier', M3_DEMO_SUPPLIER_ROLES: 'ADMIN_DATA_MAINTENANCE',
    M3_DEMO_CHECKER_USERNAME: 'fixture-checker', M3_DEMO_CHECKER_PASSWORD: 'fixture-only-password',
    M3_DEMO_CHECKER_SUBJECT: 'checker-native', M3_DEMO_CHECKER_ROLES: 'APPROVER,PUBLISHER',
    M3_DEMO_MATERIAL_ID: 'mat-fixture', M3_DEMO_PREVIOUS_SPEC_ID: 'spec-fixture-v1',
    M3_DEMO_CURRENT_FORMULA_ID: 'formula-fixture-v1', M3_DEMO_BASELINE_LABEL_ID: 'label-fixture-v1',
    M3_DEMO_ADDED_INGREDIENT_ID: 'ing-fixture', M3_DEMO_PROVENANCE_ID: 'prov-fixture',
    M3_DEMO_JURISDICTION: 'US', M3_DEMO_EXPECTED_ALLERGEN_ID: 'all-fixture',
  }
  const config = 'playwright.demo-path.config.ts'
  const valid = list(demo, config)
  assert.equal(valid.status, 0, valid.stderr)
  assert.match(valid.stdout, /STCN-102 deployed public demo path/)
  assert.doesNotMatch(valid.stdout, /real PKCE|seeded-access|real G2 browser/)
  for (const overrides of [{ M3_DEMO_ALLOW_WRITES: '' }, { M3_DEMO_BASELINE_LABEL_ID: '' },
    { M3_DEMO_CHECKER_SUBJECT: 'fixture-native-id' }, { M3_DEMO_CHECKER_ROLES: 'APPROVER' },
    { M3_DEMO_PREVIOUS_SPEC_ID: '../private' }, { M3_DEMO_SUPPLIER_PASSWORD: '' }]) {
    const result = list({ ...demo, ...overrides }, config)
    assert.equal(result.status, 1, result.stderr)
    assert.doesNotMatch(result.stdout + result.stderr, /fixture-only-password/)
  }
})

test('missing secrets, insecure URLs, internal routes and wrong callbacks fail before browser execution', () => {
  for (const overrides of [
    { M3_TEST_PASSWORD: '' }, { M3_EXPECTED_SUBJECT: 'fixture-maker' },
    { VITE_GATEWAY_ORIGIN: 'http://localhost:18080' },
    { VITE_CALLER_CLAIMS_PATH: '/api/specifications/internal/caller' },
    { M3_DEPLOYED_BASE_URL: 'https://hxj04121-lab.github.io/' },
    { M3_OWN_PRODUCT_ID: '' }, { M3_FOREIGN_PRODUCT_ID: '../private' },
    { M3_FOREIGN_PRODUCT_ID: 'prod-fixture-own' }, { M3_EXPECTED_ORG_TYPE: 'SUPPLIER' },
    { VITE_OIDC_AUTHORITY: 'https://identity.example.test/auth/realms/spectrace/' },
  ]) {
    const result = list(overrides)
    assert.equal(result.status, 1, result.stderr)
    assert.doesNotMatch(result.stdout + result.stderr, /fixture-only-password/)
  }
})
