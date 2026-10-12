import { test, expect } from '@playwright/test'
import { spawnSync } from 'node:child_process'

test('Pages base assets, hash deep link and browser reload load the protected sign-in entry', async ({ page }) => {
  const failures: string[] = []
  page.on('requestfailed', r => failures.push(r.url()))
  await page.goto('./#/labels')
  await expect(page.getByRole('heading', { name: 'Sign in to your organisation' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Continue with Keycloak' })).toBeEnabled()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Sign in to your organisation' })).toBeVisible()
  const scripts = await page.locator('script[src]').evaluateAll(nodes => nodes.map(n => n.getAttribute('src')))
  expect(scripts.every(src => src?.startsWith('/FoodLabelFlow-Microservices/assets/'))).toBe(true)
  expect(failures).toEqual([])
})
test('an unsolicited callback fails closed and is cleaned at the exact Pages base', async ({ page }) => {
  await page.goto('./?code=unrequested&state=foreign-state')
  await expect(page.getByRole('alert')).toContainText('Sign-in could not be verified')
  expect(new URL(page.url()).pathname).toBe('/FoodLabelFlow-Microservices/')
  expect(new URL(page.url()).search).toBe('')
})
test('deployment configuration rejects missing values and insecure production addresses', () => {
  const env = { ...process.env, VITE_OIDC_AUTHORITY: 'https://identity.example.test/auth/realms/test',
    VITE_GATEWAY_ORIGIN: 'https://gateway.example.test', VITE_OIDC_AUDIENCE: 'spectrace-api-test', VITE_SITE_BASE: '/FoodLabelFlow-Microservices/', VITE_CALLER_CLAIMS_PATH: '/api/labels/whoami' }
  const run = (patch: Record<string, string>) => spawnSync(process.execPath, ['scripts/check-pages-config.mjs'], { env: { ...env, ...patch } })
  expect(run({}).status).toBe(0)
  for (const patch of [{ VITE_OIDC_AUTHORITY: '' }, { VITE_GATEWAY_ORIGIN: 'http://gateway.example.test' },
    { VITE_GATEWAY_ORIGIN: 'https://gateway.example.test/api' }, { VITE_OIDC_AUDIENCE: '' }, { VITE_SITE_BASE: '*' }, { VITE_CALLER_CLAIMS_PATH: '' }, { VITE_CALLER_CLAIMS_PATH: '/internal/validations' }]) {
    expect(run(patch).status).not.toBe(0)
  }
})
