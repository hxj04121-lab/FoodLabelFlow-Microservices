import { test, expect } from '@playwright/test'
import { writeFile } from 'node:fs/promises'

// Real Keycloak and Gateway plus the pinned M4-starter test service. No route/session mocks.
test('real PKCE login reaches an independently JWT-validating caller service', async ({ page, request }) => {
  let callerResponse: Record<string, unknown> | undefined
  page.on('response', async response => {
    if (response.url().endsWith('/api/labels/whoami') && response.status() === 200) callerResponse = await response.json()
  })
  await page.goto('/')
  await page.getByRole('button', { name: 'Continue with Keycloak', exact: true }).click()
  await page.locator('#username').fill('manufacturer.north.maker')
  await page.locator('#password').fill('LocalDevOnly!manufacturer.north.maker')
  await page.locator('#kc-login').click()
  await expect(page.getByRole('heading', { name: 'Your organisation’s workspace' })).toBeVisible({ timeout: 30000 })
  await expect(page.getByText('org-manufacturer-north', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Verify service identity' }).click()
  await expect(page.getByRole('status')).toContainText('Service identity verified: org-manufacturer-north', { timeout: 15000 })
  expect(callerResponse?.org_id).toBe('org-manufacturer-north')
  expect(callerResponse?.org_type).toBe('MANUFACTURER')
  expect(typeof callerResponse?.subject).toBe('string')
  expect(callerResponse?.subject).not.toBe('manufacturer.north.maker')
  const denied = await request.get('http://localhost:18080/api/labels/whoami')
  expect(denied.status()).toBe(401)
  expect((await denied.json()).code).toBe('AUTHENTICATION_REQUIRED')
  await page.screenshot({ path: 'test-results/real-signin/verified.png', fullPage: true })
  await writeFile('test-results/real-signin/claims.json', JSON.stringify({
    scope: 'real local Keycloak/Gateway with M4-starter test service; no AWS LB or deployed Label acceptance',
    gatewayOrigin: 'http://localhost:18080', callerPath: '/api/labels/whoami', caller: callerResponse,
  }, null, 2))
})
