import { expect, type Page } from '@playwright/test'

// Explicit HTTP fixtures. These are not staging resources or acceptance evidence.
export async function gatewayCatalogFixture(page: Page, token: () => string,
  scenario: 'ok' | 'foreign' | 'unauthorized' | 'conflict' | 'uncertain' | 'wrong-draft' | 'wrong-release' | 'wrong-current' | 'long-subject' | 'repeat-cursor' | 'empty-specs' = 'ok') {
  const requests: Array<{ method: string; path: string; body: unknown; correlation: string }> = []
  const product = { productId: 'product-one', organisationId: 'org-one', productDescription: 'North cocoa',
    brandOwner: 'North', normalizedCategory: 'Cocoa', currentFormulaVersion: { id: 'formula-one', versionNumber: 1 } }
  const spec = { specificationVersion: { id: 'spec-one', versionNumber: 1 }, materialId: 'material-one',
    supplierId: 'supplier-one', supplierOrganisationId: 'supplier-org', effectiveDate: '2020-01-01', receivedAt: '2026-10-01T00:00:00Z' }
  const item = { formulaItemId: 'item-one', sequenceNo: 1, materialId: 'material-one',
    specificationVersion: spec.specificationVersion, quantity: 1, unit: 'kg' }
  const original = { formulaVersionId: 'formula-one', organisationId: 'org-one', productId: 'product-one',
    versionNumber: 1, lifecycleStatus: 'RELEASED', isCurrentReleased: true, createdBySubject: 'old-maker',
    releasedBySubject: 'old-maker', releasedAt: '2026-10-01T00:00:00Z', provenanceId: 'prov-one', items: [item] }
  let draft: (Omit<typeof original, 'releasedAt' | 'releasedBySubject'> & { releasedAt: string | null; releasedBySubject: string | null }) | null = null
  let conflict = scenario === 'conflict'
  const cors = { 'Access-Control-Allow-Origin': 'http://127.0.0.1:5191',
    'Access-Control-Allow-Headers': 'authorization,content-type,x-correlation-id', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' }
  await page.route(/http:\/\/127\.0\.0\.1:8191\/api\/(formulations|specifications)\/(?!caller-claims)/, async route => {
    const request = route.request(), url = new URL(request.url()), method = request.method(), path = url.pathname
    if (method === 'OPTIONS') { await route.fulfill({ status: 204, headers: cors }); return }
    expect(request.headers().authorization).toBe(`Bearer ${token()}`)
    expect(request.headers()['x-correlation-id']).toBeTruthy()
    for (const header of ['x-auth-provider', 'x-external-subject', 'x-org-id']) expect(request.headers()[header]).toBeUndefined()
    const body = request.postDataJSON() as Record<string, unknown> | null
    requests.push({ method, path: path + url.search, body, correlation: request.headers()['x-correlation-id'] })
    const json = (value: unknown, status = 200) => route.fulfill({ status, headers: cors, json: value })
    const list = (items: unknown[], nextCursor: string | null = null) => json({ items, nextCursor })
    if (scenario === 'unauthorized') { await json({ code: 'AUTHENTICATION_REQUIRED' }, 401); return }
    if (path === '/api/formulations/products') {
      await list(url.searchParams.has('cursor') ? [{ ...product, productId: 'product-two', productDescription: 'North bread', currentFormulaVersion: null }]
        : [{ ...product, organisationId: scenario === 'foreign' ? 'another-org' : 'org-one' }], url.searchParams.has('cursor') && scenario !== 'repeat-cursor' ? null : 'opaque +&next=')
    } else if (path === '/api/formulations/products/product-one') await json(product)
    else if (path === '/api/formulations/products/product-one/formula-versions') await list([...(draft ? [draft] : []), original])
    else if (path === '/api/formulations/released-specifications') await list(scenario === 'empty-specs' ? [] : [spec])
    else if (path === '/api/formulations/formula-versions' && method === 'POST') {
      if (scenario === 'uncertain') { await route.abort(); return }
      expect(body?.productId).toBe('product-one'); expect(body?.provenanceId).toBe('prov-one')
      expect(body?.items).toEqual([{ materialId: 'material-one', specificationVersionId: 'spec-one', quantity: 1, unit: 'kg' }])
      draft = { ...original, formulaVersionId: 'formula-two', versionNumber: scenario === 'conflict' ? 3 : 2, lifecycleStatus: 'DRAFT', isCurrentReleased: false,
        createdBySubject: scenario === 'long-subject' ? 's'.repeat(129) : 'test-user', releasedBySubject: null, releasedAt: null }
      await json(scenario === 'wrong-draft' ? { ...draft, provenanceId: 'different-provenance' } : draft, 201)
    } else if (path === '/api/formulations/formula-versions/formula-two/release') {
      expect(body).toEqual({ expectedCurrentFormulaVersionId: product.currentFormulaVersion.id })
      if (conflict) { conflict = false; product.currentFormulaVersion = { id: 'formula-concurrent', versionNumber: 2 }; original.isCurrentReleased = false; await json({ code: 'CURRENT_FORMULA_CHANGED' }, 409); return }
      draft = { ...draft!, lifecycleStatus: 'RELEASED', isCurrentReleased: true, releasedBySubject: scenario === 'long-subject' ? 's'.repeat(129) : 'test-user', releasedAt: '2026-10-22T00:00:00Z' }
      await json(scenario === 'wrong-release' ? { ...draft, items: [{ ...item, quantity: 99 }] } : scenario === 'wrong-current' ? { ...draft, isCurrentReleased: false } : draft)
    } else if (path.endsWith('/formula-one/trace')) await json({ formulaVersion: { id: 'formula-one', versionNumber: 1 }, productId: 'product-one',
      items: [{ formulaItemId: 'item-one', sequenceNo: 1, materialId: 'material-one', specificationVersion: spec.specificationVersion,
        supplierId: 'supplier-one', supplierOrganisationId: 'supplier-org', effectiveDate: '2020-01-01' }] })
    else if (path === '/api/formulations/formula-versions/formula-one') await json(original)
    else if (path === '/api/formulations/formula-versions/formula-two' && draft) await json(draft)
    else if (path === '/api/specifications/suppliers') await list([{ supplierId: 'supplier-one', organisationId: 'supplier-org', supplierCode: 'SUP-1', supplierName: 'Beta supplier' }])
    else if (path === '/api/specifications/materials') await list([{ materialId: 'material-one', organisationId: 'supplier-org', supplierId: 'supplier-one', materialCode: 'MAT-1', materialName: 'Cocoa base', description: 'Cocoa', provenanceId: 'prov-one' }])
    else if (path === '/api/specifications/versions') await list([{ specificationVersionId: 'spec-one', organisationId: 'supplier-org', materialId: 'material-one',
      versionNumber: 1, lifecycleStatus: 'RELEASED', effectiveDate: '2020-01-01', releasedAt: '2026-10-01T00:00:00Z', createdBySubject: 'supplier-maker', provenanceId: 'prov-one',
      components: [{ specComponentId: 'component-one', sequenceNo: 1, ingredientId: 'ingredient-one', ingredientName: 'Cocoa', rawPhrase: 'cocoa', matchRule: 'exact', matchStatus: 'MATCHED' }] }])
    else await json({ code: 'FIXTURE_ROUTE_NOT_FOUND' }, 404)
  })
  return requests
}
