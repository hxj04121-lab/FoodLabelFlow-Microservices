import assert from 'node:assert/strict'
import { defineConfig } from '@playwright/test'
import deployed from './playwright.deployed-signin.config'

// Unlike the read-only G1 smoke, this profile publishes owner-approved disposable demo resources.
assert(process.env.M3_DEMO_ALLOW_WRITES === 'true', 'Explicitly enable the agreed disposable demo writes')
for (const key of ['M3_DEMO_SUPPLIER_USERNAME', 'M3_DEMO_SUPPLIER_PASSWORD', 'M3_DEMO_SUPPLIER_SUBJECT',
  'M3_DEMO_SUPPLIER_ORG_ID', 'M3_DEMO_SUPPLIER_ROLES', 'M3_DEMO_CHECKER_USERNAME', 'M3_DEMO_CHECKER_PASSWORD',
  'M3_DEMO_CHECKER_SUBJECT', 'M3_DEMO_CHECKER_ROLES', 'M3_DEMO_MATERIAL_ID', 'M3_DEMO_PREVIOUS_SPEC_ID',
  'M3_DEMO_CURRENT_FORMULA_ID', 'M3_DEMO_BASELINE_LABEL_ID', 'M3_DEMO_ADDED_INGREDIENT_ID', 'M3_DEMO_PROVENANCE_ID',
  'M3_DEMO_JURISDICTION', 'M3_DEMO_EXPECTED_ALLERGEN_ID']) {
  assert(process.env[key]?.trim(), `${key} must be supplied by the demo owner`)
}
assert(process.env.M3_DEMO_CHECKER_SUBJECT !== process.env.M3_EXPECTED_SUBJECT
  && process.env.M3_DEMO_CHECKER_USERNAME !== process.env.M3_TEST_USERNAME, 'Use a distinct maker and checker')
for (const role of ['LABEL_OFFICER', 'CHANGE_MANAGER', 'ADMIN_DATA_MAINTENANCE']) {
  assert(process.env.M3_EXPECTED_ROLES!.split(',').map(v => v.trim()).includes(role), `Maker must have ${role}`)
}
for (const role of ['APPROVER', 'PUBLISHER']) {
  assert(process.env.M3_DEMO_CHECKER_ROLES!.split(',').map(v => v.trim()).includes(role), `Checker must have ${role}`)
}
assert(process.env.M3_DEMO_SUPPLIER_ROLES!.split(',').map(v => v.trim()).includes('ADMIN_DATA_MAINTENANCE'), 'Supplier needs the specification maintenance role')
for (const key of ['M3_DEMO_MATERIAL_ID', 'M3_DEMO_PREVIOUS_SPEC_ID', 'M3_DEMO_CURRENT_FORMULA_ID',
  'M3_DEMO_BASELINE_LABEL_ID', 'M3_DEMO_ADDED_INGREDIENT_ID', 'M3_DEMO_PROVENANCE_ID', 'M3_DEMO_EXPECTED_ALLERGEN_ID']) {
  assert(/^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$/.test(process.env[key]!), `${key} must be an agreed resource ID`)
}

export default defineConfig({ ...deployed, testDir: './demo-tests', testMatch: 'demo-path.spec.ts',
  outputDir: './test-results/demo-path', timeout: 300000,
  // No live traces/video or failure screenshots, including credential-bearing preparation requests.
  use: { ...deployed.use, trace: 'off', video: 'off', screenshot: 'off' },
})
