import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { readFile, mkdir, writeFile } from 'node:fs/promises'

// These identities and URLs belong to the isolated local/CI realm, never deployed accounts.
test.beforeEach(async ({}, testInfo) => {
  test.skip(testInfo.config.metadata.deployed === true, 'Local seed accounts are excluded from deployed verification')
})

const gateway = 'http://localhost:18080'
const callerPath = '/api/specifications/caller-claims'
const evidence: { user: string; path: string; status: number; boundary: string }[] = []

async function login(page: Page, username: string) {
  const realm = JSON.parse(await readFile('../deploy/keycloak/realm/spectrace-realm.json', 'utf8'))
  const user = realm.users.find((entry: { username: string }) => entry.username === username)
  expect(user, 'The named local seed identity must exist').toBeTruthy()
  const password = user.credentials.find((entry: { type: string }) => entry.type === 'password').value
  await page.goto('./')
  await page.getByRole('button', { name: 'Continue with Keycloak', exact: true }).click()
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(password).catch(() => { throw new Error('Local seed credential entry failed') })
  await page.locator('#kc-login').click()
  await expect(page.getByRole('heading', { name: 'Your organisation’s workspace' })).toBeVisible({ timeout: 30000 })
  const pending = page.waitForResponse(response => response.url() === gateway + callerPath)
  await page.getByRole('button', { name: 'Verify service identity' }).click()
  const response = await pending
  expect(response.status()).toBe(200)
  const identity = await response.json()
  expect(identity.subject).toBe(user.id)
  expect(identity.org_id).toBe(user.attributes.org_id[0])
  expect(identity.org_type).toBe(user.attributes.org_type[0])
  expect([...identity.roles].sort()).toEqual([...(user.realmRoles || [])].sort())
  // The actual PKCE request supplies this token; it is kept only in this test's memory.
  const authorization = response.request().headers().authorization
  expect(Boolean(authorization?.startsWith('Bearer ')), 'The browser must propagate its real bearer token').toBe(true)
  return authorization
}

async function get(request: APIRequestContext, username: string, authorization: string,
  path: string, expected: number, origin = gateway) {
  const response = await request.get(origin + path, {
    headers: { Authorization: authorization, 'X-Correlation-ID': 'm3-seeded-access' },
  })
  expect(response.status(), `${username} ${path}`).toBe(expected)
  expect(response.headers()['x-correlation-id']).toBe('m3-seeded-access')
  const body = await response.json()
  if (expected === 403) {
    expect(body.code).toBe('AUTHORIZATION_DENIED')
    expect(body).not.toHaveProperty('items')
    expect(body).not.toHaveProperty('productId')
    expect(body).not.toHaveProperty('formulaVersionId')
  }
  evidence.push({ user: username, path, status: response.status(), boundary: origin === gateway ? 'Gateway' : 'private CI service' })
  return body
}

test('north maker reads its 30 seeded products and exact released resources; south resources are denied', async ({ page, request }) => {
  const user = 'manufacturer.north.maker'
  const authorization = await login(page, user)
  const products = await get(request, user, authorization, '/api/formulations/products?limit=100', 200)
  expect(products.items).toHaveLength(30)
  expect(products.nextCursor).toBeNull()
  expect(products.items.every((product: { organisationId: string }) => product.organisationId === 'org-manufacturer-north')).toBe(true)
  const product = await get(request, user, authorization, '/api/formulations/products/prod_usda_1118468', 200)
  expect(product.currentFormulaVersion).toEqual({ id: 'formula_1118468_v1', versionNumber: 1 })
  const formula = await get(request, user, authorization, '/api/formulations/formula-versions/formula_1118468_v1', 200)
  expect(formula.lifecycleStatus).toBe('RELEASED')
  expect(formula.isCurrentReleased).toBe(true)
  expect(formula.organisationId).toBe('org-manufacturer-north')
  const trace = await get(request, user, authorization, '/api/formulations/formula-versions/formula_1118468_v1/trace', 200)
  expect(trace.formulaVersion).toEqual({ id: 'formula_1118468_v1', versionNumber: 1 })
  const specification = await get(request, user, authorization, '/api/specifications/versions/spec_chocolate_v1', 200)
  expect(specification.organisationId).toBe('org-supplier-beta')
  expect(specification.lifecycleStatus).toBe('RELEASED')
  await get(request, user, authorization, '/api/formulations/products/prod_usda_1123666', 403)
  // Diagnostic-only private request proves the owner's tenant check, independently of the Gateway.
  await get(request, user, authorization, '/api/formulations/products/prod_usda_1123666', 403, 'http://localhost:18085')
  const parts = authorization.substring('Bearer '.length).split('.')
  parts[2] = (parts[2].startsWith('A') ? 'B' : 'A') + parts[2].substring(1)
  for (const header of [undefined, 'Bearer ' + parts.join('.')]) {
    const denied = await request.get(gateway + callerPath, { headers: header ? { Authorization: header } : {} })
    expect(denied.status()).toBe(401)
    expect((await denied.json()).code).toBe('AUTHENTICATION_REQUIRED')
  }
})

test('south maker cannot read north exact formulas through Gateway or the private service', async ({ page, request }) => {
  const user = 'manufacturer.south.maker'
  const authorization = await login(page, user)
  const products = await get(request, user, authorization, '/api/formulations/products?limit=100', 200)
  expect(products.items).toHaveLength(30)
  expect(products.items.every((product: { organisationId: string }) => product.organisationId === 'org-manufacturer-south')).toBe(true)
  await get(request, user, authorization, '/api/formulations/formula-versions/formula_1118468_v1', 403)
  await get(request, user, authorization, '/api/formulations/formula-versions/formula_1118468_v1', 403, 'http://localhost:18085')
})

test('supplier access cannot reveal manufacturer products or create another supplier specification', async ({ page, request }) => {
  const user = 'supplier.alpha.maker'
  const authorization = await login(page, user)
  await get(request, user, authorization, '/api/formulations/products', 403)
  const denied = await request.post(gateway + '/api/specifications/versions', {
    headers: { Authorization: authorization },
    data: { materialId: 'mat_chocolate_base', effectiveDate: '2026-10-13', provenanceId: 'prov_project_seed',
      components: [{ ingredientId: 'ing_cocoa', rawPhrase: 'cocoa', matchRule: 'supplier declaration' }] },
  })
  expect(denied.status()).toBe(403)
  expect((await denied.json()).code).toBe('AUTHORIZATION_DENIED')
  evidence.push({ user, path: 'POST /api/specifications/versions (beta material)', status: 403, boundary: 'Gateway' })
})

test('role-less identity retains caller access while business writes are denied', async ({ page, request }) => {
  const user = 'spectrace.no-role'
  const authorization = await login(page, user)
  const denied = await request.post(gateway + '/api/specifications/versions', { headers: { Authorization: authorization }, data: {} })
  expect(denied.status()).toBe(403)
  expect((await denied.json()).code).toBe('AUTHORIZATION_DENIED')
  evidence.push({ user, path: 'POST /api/specifications/versions', status: 403, boundary: 'Gateway role guard' })
})

test.afterAll(async () => {
  if (evidence.length) {
    await mkdir('test-results/real-signin', { recursive: true })
    await writeFile('test-results/real-signin/seeded-access.json', JSON.stringify({
      scope: 'Local/CI Keycloak PKCE -> Gateway -> current-checkout Spec/Formulation with seeded MySQL; not AWS acceptance',
      writes: 'denied requests only; no draft/release or volume reset', checks: evidence,
    }, null, 2))
  }
})
