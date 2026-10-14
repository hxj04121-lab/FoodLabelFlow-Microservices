import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'

const gateway = 'http://localhost:18080'
const productId = 'prod_usda_1118468'
const observed: Array<{ operation: string; status: number; correlation: string }> = []

// Mutations are restricted to the isolated CI database. Never use seed identities on staging.
test.beforeEach(async ({}, info) => {
  test.skip(process.env.GITHUB_ACTIONS !== 'true' || info.config.metadata.deployed === true,
    'G2 fixture preparation writes only to the disposable real-services CI stack')
})
async function login(page: Page, username: string) {
  const realm = JSON.parse(await readFile('../deploy/keycloak/realm/spectrace-realm.json', 'utf8'))
  const user = realm.users.find((u: { username: string }) => u.username === username)
  expect(Boolean(user), 'The named CI identity must exist').toBe(true)
  await page.goto('./')
  await page.getByRole('button', { name: 'Continue with Keycloak', exact: true }).click()
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(user.credentials.find((c: { type: string }) => c.type === 'password').value)
    .catch(() => { throw new Error('CI credential entry failed') })
  await page.locator('#kc-login').click()
  await expect(page.getByRole('heading', { name: 'Your organisation’s workspace', exact: true })).toBeVisible({ timeout: 30000 })
  const result = page.waitForResponse(r => r.url() === gateway + '/api/specifications/caller-claims')
  await page.getByRole('button', { name: 'Verify service identity', exact: true }).click()
  const response = await result
  expect(response.status()).toBe(200)
  expect((await response.json()).subject).toBe(user.id)
  return response.request().headers().authorization
}
async function api(request: APIRequestContext, authorization: string, method: 'GET' | 'POST', path: string, body?: unknown) {
  const correlation = randomUUID()
  const response = await request.fetch(gateway + path, { method, data: body, maxRedirects: 0,
    headers: { Authorization: authorization, 'X-Correlation-ID': correlation } })
    .catch(() => { throw new Error('CI business request failed; credentials omitted') })
  observed.push({ operation: method + ' ' + path, status: response.status(), correlation })
  return response
}
async function labelAction(page: Page, button: string, suffix: string, status: number) {
  const target = page.getByRole('button', { name: button, exact: true })
  await expect(target).toBeEnabled({ timeout: 15000 })
  const pending = page.waitForResponse(r => new URL(r.url()).pathname.endsWith(suffix) && r.request().method() === 'POST', { timeout: 15000 })
  await target.click()
  const response = await pending
  expect(response.status(), button).toBe(status)
  return response.json()
}

test('real G2 browser workflow validates, resubmits, approves, publishes and reads the current label', async ({ browser, request }) => {
  test.setTimeout(180000)
  observed.length = 0
  const supplierContext = await browser.newContext({ baseURL: 'http://localhost:5173' })
  const makerContext = await browser.newContext({ baseURL: 'http://localhost:5173', recordVideo: { dir: 'test-results/real-g2/video' } })
  const checkerContext = await browser.newContext({ baseURL: 'http://localhost:5173', recordVideo: { dir: 'test-results/real-g2/video' } })
  try {
    const supplier = await supplierContext.newPage(); const maker = await makerContext.newPage(); const checker = await checkerContext.newPage()
    const supplierToken = await login(supplier, 'supplier.beta.maker')
    const makerToken = await login(maker, 'manufacturer.north.maker')
    await login(checker, 'manufacturer.north.checker')
    // Seed rows do not emit events. Prepare a released specification and formula through owners' public APIs.
    const createdSpec = await api(request, supplierToken, 'POST', '/api/specifications/versions', {
      materialId: 'mat_chocolate_base', effectiveDate: new Date().toISOString().slice(0, 10), provenanceId: 'prov_project_seed',
      components: [{ ingredientId: 'ing_cocoa', rawPhrase: 'cocoa', matchRule: 'CI exact cocoa' }],
    })
    expect(createdSpec.status()).toBe(201)
    const spec = await createdSpec.json()
    expect((await api(request, supplierToken, 'POST', `/api/specifications/versions/${spec.specificationVersionId}/release`)).status()).toBe(200)
    await expect.poll(async () => {
      const result = await api(request, makerToken, 'GET', '/api/formulations/released-specifications?materialId=mat_chocolate_base&limit=100')
      expect(result.status()).toBe(200)
      return (await result.json()).items.some((item: { specificationVersion: { id: string } }) => item.specificationVersion.id === spec.specificationVersionId)
    }, { timeout: 30000 }).toBe(true)
    const original = await api(request, makerToken, 'GET', `/api/formulations/products/${productId}`)
    const current = (await original.json()).currentFormulaVersion.id
    const createdFormula = await api(request, makerToken, 'POST', '/api/formulations/formula-versions', {
      productId, provenanceId: 'prov_project_seed', items: [{ materialId: 'mat_chocolate_base', specificationVersionId: spec.specificationVersionId, quantity: 1, unit: 'KG' }],
    })
    expect(createdFormula.status()).toBe(201)
    const formula = await createdFormula.json()
    expect((await api(request, makerToken, 'POST', `/api/formulations/formula-versions/${formula.formulaVersionId}/release`,
      { expectedCurrentFormulaVersionId: current })).status()).toBe(200)
    // Operator observation of this disposable broker only; browser/business calls stay on Gateway.
    // 60 actual owner seed formulas + this release must reach the Label consumer before draft creation.
    await expect.poll(async () => {
      const headers = { Authorization: `Basic ${Buffer.from('spectrace:local-only-rabbitmq').toString('base64')}` }
      const queue = await request.get('http://localhost:15672/api/queues/%2F/label-workflow.formula-published', { headers, maxRedirects: 0 })
      const dlq = await request.get('http://localhost:15672/api/queues/%2F/label-workflow.formula-published.dlq', { headers, maxRedirects: 0 })
      expect(queue.status()).toBe(200); expect(dlq.status()).toBe(200)
      expect((await dlq.json()).messages, 'Formula replay must not dead-letter').toBe(0)
      const state = await queue.json()
      return state.message_stats?.ack >= 61 && state.messages === 0
    }, { timeout: 30000, intervals: [1000] }).toBe(true)
    // Wait for the real consumer-owned projection, not a guessed sleep or injected database row.
    // Only an explicit 409 LABEL conflict (before insert) is retried; accepted/uncertain writes are never retried.
    let label: { labelVersionId: string; formulaVersionId: string; ruleSetVersionId: string } | undefined
    await maker.getByRole('button', { name: 'Labels', exact: true }).click()
    await maker.getByLabel('Product ID', { exact: true }).fill(productId)
    await maker.getByLabel('Jurisdiction code', { exact: true }).fill('US')
    await expect.poll(async () => {
      if (label) return true
      const pending = maker.waitForResponse(r => new URL(r.url()).pathname === '/api/labels/drafts' && r.request().method() === 'POST')
      await maker.getByRole('button', { name: 'Create draft', exact: true }).click()
      const response = await pending
      if (response.status() === 201) { label = await response.json(); return true }
      expect(response.status(), 'Only a confirmed pre-insert projection conflict can be retried').toBe(409)
      expect((await response.json()).code).toBe('DATA_CONFLICT')
      return false
    }, { timeout: 30000, intervals: [1000] }).toBe(true)
    expect(label!.formulaVersionId).toBe(formula.formulaVersionId)
    expect(label!.ruleSetVersionId).toBe('ruleset_us_falcpa_demo_v1')
    await labelAction(maker, 'Validate draft', '/validations', 201)
    await expect(maker.getByRole('button', { name: 'Submit for review', exact: true })).toBeEnabled()
    await labelAction(maker, 'Submit for review', '/submit', 200)
    await expect(maker.getByRole('button', { name: 'Approve version', exact: true })).toHaveCount(0)
    await checker.getByRole('button', { name: 'Labels', exact: true }).click()
    await checker.getByLabel('Label version ID', { exact: true }).fill(label!.labelVersionId)
    await checker.getByRole('button', { name: 'Open label', exact: true }).click()
    await labelAction(checker, 'Request changes', '/decisions', 201)
    await maker.getByRole('button', { name: 'Open label', exact: true }).click()
    await expect(maker.getByText('DRAFT', { exact: true })).toBeVisible()
    const saved = maker.waitForResponse(r => new URL(r.url()).pathname.endsWith('/declarations') && r.request().method() === 'PUT', { timeout: 15000 })
    await maker.getByRole('button', { name: 'Save declarations', exact: true }).click()
    expect((await saved).status()).toBe(200)
    await expect(maker.getByText('Declarations saved. Validate this exact draft again before submitting.', { exact: true })).toBeVisible()
    await expect(maker.getByRole('button', { name: 'Submit for review', exact: true })).toBeDisabled()
    await labelAction(maker, 'Validate draft', '/validations', 201)
    await labelAction(maker, 'Submit for review', '/submit', 200)
    await checker.getByRole('button', { name: 'Open label', exact: true }).click()
    await labelAction(checker, 'Approve version', '/decisions', 201)
    await expect(checker.getByRole('button', { name: 'Publish version', exact: true })).toBeDisabled()
    await checker.getByLabel('I have checked this version and want to publish it.', { exact: true }).check()
    await labelAction(checker, 'Publish version', '/publish', 200)
    await checker.getByLabel('Current product ID', { exact: true }).fill(productId)
    await checker.getByLabel('Current jurisdiction code', { exact: true }).fill('US')
    await checker.getByRole('button', { name: 'Load current label', exact: true }).click()
    await expect(checker.getByText('This is the current published label returned by the service.', { exact: true })).toBeVisible()
    const currentLabel = await api(request, makerToken, 'GET', `/api/labels/current?productId=${productId}&jurisdictionCode=US`)
    expect(currentLabel.status()).toBe(200)
    expect((await currentLabel.json()).labelVersionId).toBe(label!.labelVersionId)
    const southContext = await browser.newContext({ baseURL: 'http://localhost:5173' })
    try {
      const southToken = await login(await southContext.newPage(), 'manufacturer.south.maker')
      const denied = await api(request, southToken, 'GET', `/api/labels/${label!.labelVersionId}`)
      expect(denied.status()).toBe(403)
      expect((await denied.json()).code).toBe('AUTHORIZATION_DENIED')
    } finally { await southContext.close() }
    await mkdir('test-results/real-signin', { recursive: true })
    await checker.screenshot({ path: 'test-results/real-signin/g2-published.png', fullPage: true })
    await writeFile('test-results/real-signin/g2-workflow.json', JSON.stringify({
      scope: 'Real disposable CI Keycloak/Gateway/Specification/Formulation/Compliance/Label; no AWS or G3 ReviewTask acceptance',
      source: process.env.GITHUB_SHA, productId, label, formulaVersionId: formula.formulaVersionId, specificationVersionId: spec.specificationVersionId,
      checks: ['PKCE separate maker/checker', 'real rule resolver/validation', 'request changes and resubmit', 'approve/publish/current label', 'foreign label 403'], observed,
    }, null, 2))
  } finally { await supplierContext.close(); await makerContext.close(); await checkerContext.close() }
})
