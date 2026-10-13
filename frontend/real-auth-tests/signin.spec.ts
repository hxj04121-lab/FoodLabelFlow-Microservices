import { test, expect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { verifyDeployedBuild, verifyDeployedAccess } from './deployed-checks'

// Real Keycloak, Gateway and the current checkout's Specification service with MySQL/RabbitMQ.
test('real PKCE login reaches the Specification caller-claims endpoint', async ({ page, request }, testInfo) => {
  const deployed = testInfo.config.metadata.deployed === true
  const gatewayOrigin = deployed ? String(testInfo.config.metadata.gatewayOrigin) : 'http://localhost:18080'
  const callerPath = deployed ? String(testInfo.config.metadata.callerPath) : '/api/specifications/caller-claims'
  const username = deployed ? process.env.M3_TEST_USERNAME! : 'manufacturer.north.maker'
  const password = deployed ? process.env.M3_TEST_PASSWORD! : 'LocalDevOnly!manufacturer.north.maker'
  const orgId = deployed ? process.env.M3_EXPECTED_ORG_ID! : 'org-manufacturer-north'
  const orgType = deployed ? process.env.M3_EXPECTED_ORG_TYPE! : 'MANUFACTURER'
  const subject = deployed ? process.env.M3_EXPECTED_SUBJECT! : 'd5fd8eab-b2b4-5419-9a99-4596cefb7f18'
  const roles = deployed ? process.env.M3_EXPECTED_ROLES!.split(',').map(role => role.trim())
    : ['ADMIN_DATA_MAINTENANCE', 'CHANGE_MANAGER', 'LABEL_OFFICER']
  const evidence = deployed ? 'test-results/deployed-signin' : 'test-results/real-signin'
  const deployedCommit = deployed ? await verifyDeployedBuild(request, {
    site: testInfo.project.use.baseURL!, gateway: gatewayOrigin, callerPath,
    authority: String(testInfo.config.metadata.authority), audience: String(testInfo.config.metadata.audience),
  }) : undefined
  await page.goto('./')
  await page.getByRole('button', { name: 'Continue with Keycloak', exact: true }).click()
  await expect(page.locator('#username')).toBeVisible()
  expect(new URL(page.url()).origin).toBe(deployed ? testInfo.config.metadata.identityOrigin : gatewayOrigin)
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(password).catch(() => { throw new Error('Password entry failed; credential details omitted') })
  await page.locator('#kc-login').click()
  await expect(page.getByRole('heading', { name: 'Your organisation’s workspace' })).toBeVisible({ timeout: 30000 })
  await expect(page.getByText(orgId, { exact: true })).toBeVisible()
  await expect(page.getByText(`Signed in as ${subject}`, { exact: true })).toBeVisible()
  const callerResult = page.waitForResponse(response => response.url() === new URL(callerPath, gatewayOrigin).href
    && response.request().method() === 'GET')
  await page.getByRole('button', { name: 'Verify service identity' }).click()
  const response = await callerResult
  expect(response.status()).toBe(200)
  const callerResponse = await response.json()
  await expect(page.getByRole('status')).toContainText(`Service identity verified: ${orgId}`, { timeout: 15000 })
  expect(callerResponse.org_id).toBe(orgId)
  expect(callerResponse.org_type).toBe(orgType)
  expect(callerResponse.subject).toBe(subject)
  expect([...callerResponse.roles].sort()).toEqual([...new Set(roles)].sort())
  const deployedAccess = deployed ? await verifyDeployedAccess(request, {
    gateway: gatewayOrigin, site: testInfo.project.use.baseURL!, orgId, callerPath,
    ownProduct: String(testInfo.config.metadata.ownProduct), foreignProduct: String(testInfo.config.metadata.foreignProduct),
  }, response.request().headers().authorization) : undefined
  const denied = await request.get(new URL(callerPath, gatewayOrigin).href)
  expect(denied.status()).toBe(401)
  expect((await denied.json()).code).toBe('AUTHENTICATION_REQUIRED')
  await mkdir(evidence, { recursive: true })
  await page.screenshot({ path: `${evidence}/verified.png`, fullPage: true })
  await page.getByRole('button', { name: 'Sign out', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Sign in to your organisation', exact: true })).toBeVisible({ timeout: 30000 })
  await expect(page.getByRole('button', { name: 'Continue with Keycloak', exact: true })).toBeEnabled()
  // Compare as a boolean so a failed logout cannot print a URL containing an id_token_hint.
  await expect.poll(() => page.url() === new URL(testInfo.project.use.baseURL!).href).toBe(true)
  if (deployed) {
    await page.goto(`${testInfo.project.use.baseURL}#/overview`)
    await page.reload()
    await expect(page.getByRole('heading', { name: 'Sign in to your organisation', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Continue with Keycloak', exact: true })).toBeEnabled()
  }
  await writeFile(`${evidence}/claims.json`, JSON.stringify({
    scope: deployed ? 'deployed build/discovery, browser login, caller, own/foreign product, tampered token, private route/CORS and logout smoke; owner/private-service/limits/window acceptance remains separate'
      : 'real local Keycloak/Gateway with current-checkout Specification service and MySQL/RabbitMQ; no AWS LB acceptance',
    site: testInfo.project.use.baseURL, gatewayOrigin, callerPath, deployedCommit, deployedAccess,
    caller: { subject: callerResponse.subject, org_id: callerResponse.org_id,
      org_type: callerResponse.org_type, roles: callerResponse.roles },
    checks: { caller: 200, anonymous: denied.status(), logoutCallback: true, hashReload: deployed || undefined },
  }, null, 2))
})
