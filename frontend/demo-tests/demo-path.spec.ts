import { test, expect, type Page } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { verifyDeployedBuild } from '../real-auth-tests/deployed-checks'
import { readFinding, type ImpactFinding } from '../src/features/impact/impactFindings'

// Real deployed public API + browser flow only. Event/ReviewTask observation is separately owned.
test('STCN-102 deployed public demo path: potential, explicit adoption, confirmed, label approval and publication', async ({ browser, request }, info) => {
  const env = (key: string) => process.env[key]!
  const site = String(info.project.use.baseURL)
  const gateway = String(info.config.metadata.gatewayOrigin)
  const orgId = env('M3_EXPECTED_ORG_ID'); const product = env('M3_OWN_PRODUCT_ID'); const jurisdiction = env('M3_DEMO_JURISDICTION')
  const source = await verifyDeployedBuild(request, { site, gateway, authority: String(info.config.metadata.authority),
    audience: String(info.config.metadata.audience), callerPath: String(info.config.metadata.callerPath) })
  const observations: Array<{ method: string; path: string; status: number; correlation: string }> = []
  const contexts = await Promise.all([0, 1, 2].map(() => browser.newContext({ baseURL: site })))

  async function login(page: Page, username: string, password: string, expected: { subject: string; org_id: string; org_type: string; roles: string[] }) {
    await page.goto('./')
    await page.getByRole('button', { name: 'Continue with Keycloak', exact: true }).click()
    await expect(page.locator('#username')).toBeVisible()
    expect(new URL(page.url()).origin === info.config.metadata.identityOrigin, 'Login must remain on the agreed IdP').toBe(true)
    await page.locator('#username').fill(username)
    await page.locator('#password').fill(password).catch(() => { throw new Error('Credential entry failed; details omitted') })
    await page.locator('#kc-login').click()
    await expect(page.getByRole('heading', { name: 'Your organisation’s workspace', exact: true })).toBeVisible({ timeout: 30000 })
    const pending = page.waitForResponse(r => r.url() === gateway + String(info.config.metadata.callerPath))
    await page.getByRole('button', { name: 'Verify service identity', exact: true }).click()
    const result = await pending; expect(result.status()).toBe(200)
    const caller = await result.json()
    expect(caller.subject).toBe(expected.subject); expect(caller.org_id).toBe(expected.org_id); expect(caller.org_type).toBe(expected.org_type)
    expect([...caller.roles].sort()).toEqual([...new Set(expected.roles)].sort())
    return result.request().headers().authorization
  }
  async function api(authorization: string, method: 'GET' | 'POST', path: string, data?: unknown) {
    const correlation = randomUUID()
    const result = await request.fetch(gateway + path, { method, data, maxRedirects: 0,
      headers: { Authorization: authorization, 'X-Correlation-ID': correlation }, timeout: 15000 })
      .catch(() => { throw new Error('Demo request failed; credential details omitted. Check its outcome before rerunning.') })
    observations.push({ method, path, status: result.status(), correlation })
    return result
  }
  async function findings(authorization: string, kind: string) {
    const items: ImpactFinding[] = []; let cursor: string | null = null
    const seen = new Set<string>()
    do {
      const params = new URLSearchParams({ kind, limit: '100' }); if (cursor) params.set('cursor', cursor)
      const response = await api(authorization, 'GET', `/api/compliance/impact-findings?${params}`)
      expect(response.status()).toBe(200)
      const body = await response.json()
      expect(body.organisationId).toBe(orgId)
      expect(body.items.every((f: { organisationId: string; kind: string }) => f.organisationId === orgId && f.kind === kind)).toBe(true)
      items.push(...body.items.map((item: unknown) => readFinding(item, orgId))); cursor = body.nextCursor
      expect(cursor === null || (typeof cursor === 'string' && !!cursor.trim()), 'Read an opaque cursor or null').toBe(true)
      if (cursor) { expect(seen.has(cursor), 'Cursor must not repeat').toBe(false); seen.add(cursor) }
      expect(seen.size, 'Keep the agreed demo read bounded').toBeLessThanOrEqual(50)
    } while (cursor)
    return items
  }
  async function inspectFinding(page: Page, kind: string, findingId: string) {
    await page.getByRole('button', { name: 'Impact inbox', exact: true }).click()
    await page.getByLabel('Finding kind', { exact: true }).selectOption(kind)
    const listResult = () => page.waitForResponse(r => new URL(r.url()).pathname === '/api/compliance/impact-findings'
      && r.request().method() === 'GET', { timeout: 15000 })
    let loaded = listResult()
    await page.getByRole('button', { name: 'Refresh inbox', exact: true }).click()
    expect((await loaded).status()).toBe(200)
    await expect(page.getByRole('button', { name: 'Refresh inbox', exact: true })).toBeEnabled()
    for (let n = 0; n < 250; n++) {
      const target = page.getByRole('button', { name: `View finding ${findingId}`, exact: true })
      if (await target.count()) { await target.click(); break }
      loaded = listResult()
      await page.getByRole('button', { name: 'Next page', exact: true }).click()
      expect((await loaded).status()).toBe(200)
      await expect(page.getByRole('button', { name: 'Refresh inbox', exact: true })).toBeEnabled()
    }
    await expect(page.getByRole('heading', { name: `Finding ${findingId}`, exact: true })).toBeVisible()
    await expect(page.locator('.impact-allergens')).toContainText(env('M3_DEMO_EXPECTED_ALLERGEN_ID'))
  }
  async function action(page: Page, name: string, path: string, status: number) {
    const pending = page.waitForResponse(r => new URL(r.url()).pathname === path && r.request().method() === 'POST')
    await page.getByRole('button', { name, exact: true }).click()
    const result = await pending; expect(result.status(), name).toBe(status)
    return result.json()
  }
  try {
    const supplier = await contexts[0].newPage(); const maker = await contexts[1].newPage(); const checker = await contexts[2].newPage()
    const supplierToken = await login(supplier, env('M3_DEMO_SUPPLIER_USERNAME'), env('M3_DEMO_SUPPLIER_PASSWORD'), {
      subject: env('M3_DEMO_SUPPLIER_SUBJECT'), org_id: env('M3_DEMO_SUPPLIER_ORG_ID'), org_type: 'SUPPLIER', roles: env('M3_DEMO_SUPPLIER_ROLES').split(',').map(v => v.trim()) })
    const makerToken = await login(maker, env('M3_TEST_USERNAME'), env('M3_TEST_PASSWORD'), {
      subject: env('M3_EXPECTED_SUBJECT'), org_id: orgId, org_type: 'MANUFACTURER', roles: env('M3_EXPECTED_ROLES').split(',').map(v => v.trim()) })
    await login(checker, env('M3_DEMO_CHECKER_USERNAME'), env('M3_DEMO_CHECKER_PASSWORD'), {
      subject: env('M3_DEMO_CHECKER_SUBJECT'), org_id: orgId, org_type: 'MANUFACTURER', roles: env('M3_DEMO_CHECKER_ROLES').split(',').map(v => v.trim()) })
    const baselineResponse = await api(makerToken, 'GET', `/api/labels/current?${new URLSearchParams({ productId: product, jurisdictionCode: jurisdiction })}`)
    expect(baselineResponse.status(), 'M1/M4 must prepare the real baseline published label before demo writes').toBe(200)
    const baseline = await baselineResponse.json()
    expect(baseline.labelVersionId).toBe(env('M3_DEMO_BASELINE_LABEL_ID')); expect(baseline.lifecycleStatus).toBe('PUBLISHED')
    const productResponse = await api(makerToken, 'GET', `/api/formulations/products/${product}`)
    expect(productResponse.status()).toBe(200)
    const owned = await productResponse.json(); expect(owned.organisationId).toBe(orgId)
    expect(owned.currentFormulaVersion.id).toBe(env('M3_DEMO_CURRENT_FORMULA_ID'))
    const formulaResponse = await api(makerToken, 'GET', `/api/formulations/formula-versions/${owned.currentFormulaVersion.id}`)
    expect(formulaResponse.status()).toBe(200); const formula = await formulaResponse.json()
    expect(formula.organisationId).toBe(orgId); expect(formula.formulaVersionId).toBe(owned.currentFormulaVersion.id)
    expect(baseline.formulaVersionId).toBe(formula.formulaVersionId)
    const oldSpecResponse = await api(supplierToken, 'GET', `/api/specifications/versions/${env('M3_DEMO_PREVIOUS_SPEC_ID')}`)
    expect(oldSpecResponse.status()).toBe(200); const oldSpec = await oldSpecResponse.json()
    expect(oldSpec.materialId).toBe(env('M3_DEMO_MATERIAL_ID')); expect(oldSpec.lifecycleStatus).toBe('RELEASED')
    expect(oldSpec.organisationId).toBe(env('M3_DEMO_SUPPLIER_ORG_ID'))
    expect(oldSpec.components.some((c: { ingredientId: string }) => c.ingredientId === env('M3_DEMO_ADDED_INGREDIENT_ID'))).toBe(false)
    expect(formula.items.some((i: any) => i.materialId === oldSpec.materialId && i.specificationVersion.id === oldSpec.specificationVersionId)).toBe(true)
    const created = await api(supplierToken, 'POST', '/api/specifications/versions', {
      materialId: oldSpec.materialId, effectiveDate: new Date().toISOString().slice(0, 10), provenanceId: env('M3_DEMO_PROVENANCE_ID'),
      components: [...oldSpec.components.map((c: any) => ({ ingredientId: c.ingredientId, rawPhrase: c.rawPhrase, matchRule: c.matchRule })),
        { ingredientId: env('M3_DEMO_ADDED_INGREDIENT_ID'), rawPhrase: 'soy lecithin', matchRule: 'Owner-approved demo statement' }],
    })
    expect(created.status()).toBe(201); const spec = await created.json()
    expect((await api(supplierToken, 'POST', `/api/specifications/versions/${spec.specificationVersionId}/release`)).status()).toBe(200)
    let potential: any
    await expect.poll(async () => {
      potential = (await findings(makerToken, 'POTENTIAL')).find(f => f.formulaVersion.id === formula.formulaVersionId
        && f.specificationChange.candidate.id === spec.specificationVersionId && f.outcome === 'REVIEW_REQUIRED')
      return Boolean(potential)
    }, { timeout: 60000, intervals: [2000] }).toBe(true)
    await inspectFinding(maker, 'POTENTIAL', potential.findingId)
    const adopted = await api(makerToken, 'POST', '/api/formulations/formula-versions', {
      productId: product, provenanceId: env('M3_DEMO_PROVENANCE_ID'), items: formula.items.map((i: any) => ({ materialId: i.materialId,
        specificationVersionId: i.materialId === oldSpec.materialId ? spec.specificationVersionId : i.specificationVersion.id, quantity: i.quantity, unit: i.unit })),
    })
    expect(adopted.status()).toBe(201); const nextFormula = await adopted.json()
    expect((await api(makerToken, 'POST', `/api/formulations/formula-versions/${nextFormula.formulaVersionId}/release`,
      { expectedCurrentFormulaVersionId: formula.formulaVersionId })).status()).toBe(200)
    let confirmed: any
    await expect.poll(async () => {
      confirmed = (await findings(makerToken, 'CONFIRMED')).find(f => f.formulaVersion.id === nextFormula.formulaVersionId
        && f.specificationChange.candidate.id === spec.specificationVersionId && f.outcome === 'REVIEW_REQUIRED')
      return Boolean(confirmed)
    }, { timeout: 60000, intervals: [2000] }).toBe(true)
    await inspectFinding(maker, 'CONFIRMED', confirmed.findingId)
    await maker.getByRole('button', { name: 'Labels', exact: true }).click()
    await maker.getByLabel('Product ID', { exact: true }).fill(product)
    await maker.getByLabel('Jurisdiction code', { exact: true }).fill(jurisdiction)
    const label = await action(maker, 'Create draft', '/api/labels/drafts', 201)
    expect(label.formulaVersionId).toBe(nextFormula.formulaVersionId)
    expect(confirmed.unresolvedAllergens, 'Resolve unknown ingredients before accepting the demo label').toHaveLength(0)
    const factsResponse = await api(makerToken, 'GET', `/api/labels/${label.labelVersionId}/declarations`)
    expect(factsResponse.status()).toBe(200); const facts = await factsResponse.json()
    expect(confirmed.requiredAllergens.includes(env('M3_DEMO_EXPECTED_ALLERGEN_ID'))).toBe(true)
    for (const allergen of confirmed.requiredAllergens as string[]) {
      if (facts.declarations.some((d: { allergenId: string }) => d.allergenId === allergen)) continue
      await maker.getByRole('button', { name: 'Add declaration', exact: true }).click()
      await maker.getByLabel('Allergen ID', { exact: true }).last().fill(allergen)
    }
    const save = maker.waitForResponse(r => new URL(r.url()).pathname === `/api/labels/${label.labelVersionId}/declarations` && r.request().method() === 'PUT')
    await maker.getByRole('button', { name: 'Save declarations', exact: true }).click(); expect((await save).status()).toBe(200)
    const validated = await action(maker, 'Validate draft', `/api/labels/${label.labelVersionId}/validations`, 201)
    expect(validated.status).toBe('PASSED')
    await action(maker, 'Submit for review', `/api/labels/${label.labelVersionId}/submit`, 200)
    await checker.getByRole('button', { name: 'Labels', exact: true }).click()
    await checker.getByLabel('Label version ID', { exact: true }).fill(label.labelVersionId)
    await checker.getByRole('button', { name: 'Open label', exact: true }).click()
    await action(checker, 'Approve version', `/api/labels/${label.labelVersionId}/decisions`, 201)
    await checker.getByLabel('I have checked this version and want to publish it.', { exact: true }).check()
    await action(checker, 'Publish version', `/api/labels/${label.labelVersionId}/publish`, 200)
    await checker.getByLabel('Current product ID', { exact: true }).fill(product)
    await checker.getByLabel('Current jurisdiction code', { exact: true }).fill(jurisdiction)
    await checker.getByRole('button', { name: 'Load current label', exact: true }).click()
    await expect(checker.getByText('This is the current published label returned by the service.', { exact: true })).toBeVisible()
    const publishedResponse = await api(makerToken, 'GET', `/api/labels/current?${new URLSearchParams({ productId: product, jurisdictionCode: jurisdiction })}`)
    expect(publishedResponse.status()).toBe(200); const published = await publishedResponse.json()
    expect(published.labelVersionId).toBe(label.labelVersionId); expect(published.versionNumber).toBe(label.versionNumber)
    await mkdir('test-results/demo-path', { recursive: true })
    await checker.screenshot({ path: 'test-results/demo-path/published.png', fullPage: true })
    await writeFile('test-results/demo-path/browser-public-flow.json', JSON.stringify({
      scope: 'Real deployed public demo/browser flow only; event projection and exactly-one ReviewTask need owner observation',
      source, at: new Date().toISOString(), product, spec: { id: spec.specificationVersionId, versionNumber: spec.versionNumber },
      formula: { id: nextFormula.formulaVersionId, versionNumber: nextFormula.versionNumber },
      baselineLabel: { id: baseline.labelVersionId, versionNumber: baseline.versionNumber },
      label: { id: published.labelVersionId, versionNumber: published.versionNumber },
      potentialFindingId: potential.findingId, confirmedFindingId: confirmed.findingId,
      reviewTask: 'NOT_OBSERVED_BY_PUBLIC_BROWSER', labelEventProjection: 'OWNER_EVIDENCE_REQUIRED', observations,
    }, null, 2))
  } finally { for (const context of contexts) await context.close() }
})
