import { test, expect, type Page } from '@playwright/test'
import { createServer, type Server } from 'node:http'
import { createHash, generateKeyPairSync, sign, randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { readFileSync } from 'node:fs'

// Protocol fixture only: no real user, password, Keycloak, or business service is simulated as deployed.
const origin = 'http://127.0.0.1:8191'
const issuer = `${origin}/auth/realms/test`
const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
const codes = new Map<string, { nonce: string; challenge: string; redirect: string }>()
let server: Server
let mode = 'ok'
let apiStatus = 200
let sentToken = ''
let observedAuthorization = ''
let exchanges = 0
let verifiedPkce = false
let actorRoles = ['LABEL_OFFICER', 'APPROVER']
let actorType = 'MANUFACTURER'
let actorSubject = 'test-user'
let labelCreator = 'original-maker'
let decisionRecorded = false
let labelScenario = 'ok'
let labelDelay = 0
const freshLabel = () => ({ labelVersionId: 'label-one', productId: 'product-one', formulaVersionId: 'formula-one',
  ruleSetVersionId: 'rules-one', jurisdictionCode: 'SG', versionNumber: 1, lifecycleStatus: 'DRAFT' })
let label = freshLabel()
let labelRequests: Array<{ method: string; path: string; query: string; body: unknown; authenticated: boolean; correlation: string }> = []
let labelDeclarations: Array<{ allergenId: string; declarationType: 'CONTAINS'; declarationSource: string; displayText: string | null }> = []
let draftRevision = 0
const confirmedImpact = { ...JSON.parse(readFileSync('../contracts/examples/compliance/impact-confirmed-missing.json', 'utf8')).payload,
  organisationId: 'org-one', labelVersion: { id: 'label-one', versionNumber: 1 } }
const unresolvedImpact = { ...JSON.parse(readFileSync('../contracts/examples/compliance/impact-unresolved.json', 'utf8')).payload, organisationId: 'org-one' }
let impactScenario = 'ok'
let impactRequests: Array<{ path: string; query: string; authenticated: boolean; correlation: string }> = []
async function saveCoverage(page: Page) {
  if (process.env.VITE_COVERAGE !== 'true' || page.isClosed()) return
  const coverage = await page.evaluate(() => (window as Window & { __coverage__?: unknown }).__coverage__)
  if (coverage) {
    await mkdir('.nyc_output', { recursive: true })
    await writeFile(`.nyc_output/${randomUUID()}.json`, JSON.stringify(coverage))
  }
}
test.afterEach(async ({ page }) => { await saveCoverage(page) })
function jwt(payload: object) {
  const data = `${Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url')}.${Buffer.from(JSON.stringify(payload)).toString('base64url')}`
  return `${data}.${sign('RSA-SHA256', Buffer.from(data), privateKey).toString('base64url')}`
}
test.beforeAll(async () => {
  server = createServer(async (req, res) => {
    res.setHeader('Access-Control-Allow-Origin', 'http://127.0.0.1:5191')
    res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type, X-Correlation-ID')
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, OPTIONS')
    res.setHeader('Access-Control-Expose-Headers', 'X-Correlation-ID')
    if (req.method === 'OPTIONS') { res.end(); return }
    const url = new URL(req.url!, origin)
    const json = (body: unknown, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)) }
    if (url.pathname.endsWith('/.well-known/openid-configuration')) {
      json({ issuer, authorization_endpoint: `${issuer}/authorize`, token_endpoint: `${issuer}/token`,
        end_session_endpoint: `${issuer}/logout`, jwks_uri: `${issuer}/certs`,
        response_types_supported: ['code'], subject_types_supported: ['public'], id_token_signing_alg_values_supported: ['RS256'] })
    } else if (url.pathname.endsWith('/authorize')) {
      const params = url.searchParams
      if (params.get('client_id') !== 'spectrace-web' || params.get('response_type') !== 'code' || params.get('scope') !== 'openid'
        || params.get('code_challenge_method') !== 'S256' || !params.get('nonce') || !params.get('state')) { json({ error: 'invalid_request' }, 400); return }
      const code = randomUUID()
      codes.set(code, { nonce: params.get('nonce')!, challenge: params.get('code_challenge')!, redirect: params.get('redirect_uri')! })
      const callback = new URL(params.get('redirect_uri')!)
      callback.searchParams.set('state', mode === 'bad-state' ? 'foreign-state' : params.get('state')!)
      callback.searchParams.set('code', code)
      res.writeHead(302, { Location: callback.href }); res.end()
    } else if (url.pathname.endsWith('/token')) {
      exchanges++
      let text = ''; for await (const chunk of req) text += chunk
      const body = new URLSearchParams(text)
      const code = body.get('code')!; const stored = codes.get(code); codes.delete(code)
      verifiedPkce = !!stored && body.get('grant_type') === 'authorization_code' && body.get('client_id') === 'spectrace-web'
        && body.get('redirect_uri') === stored.redirect
        && createHash('sha256').update(body.get('code_verifier') || '').digest('base64url') === stored.challenge
      if (!verifiedPkce || !stored) { json({ error: 'invalid_grant' }, 400); return }
      if (mode === 'token-error') { json({ error: 'invalid_grant' }, 400); return }
      const now = Math.floor(Date.now() / 1000)
      const id = jwt({ iss: issuer, aud: 'spectrace-web', sub: actorSubject, iat: now, exp: now + 120,
        nonce: mode === 'bad-nonce' ? 'foreign-nonce' : stored.nonce })
      sentToken = jwt({ iss: issuer, aud: mode === 'bad-audience' ? 'another-api' : 'spectrace-api-test', sub: actorSubject,
        iat: now, exp: now + 120, org_id: mode === 'multi-org' ? ['org-one', 'org-two'] : 'org-one',
        org_type: mode === 'array-org-type' ? ['MANUFACTURER'] : actorType,
        ...(mode === 'roleless' ? {} : { roles: actorRoles }) })
      json({ access_token: sentToken, token_type: 'Bearer', id_token: id, expires_in: mode === 'expires' ? 2 : 120 })
    } else if (url.pathname === '/api/specifications/caller-claims') {
      const roles = mode === 'caller-role-mismatch' ? ['LABEL_OFFICER']
        : mode === 'caller-role-delimiter' ? ['APPROVER\nLABEL_OFFICER']
        : mode === 'foreign-roles' ? ['APPROVER', 'PUBLISHER'] : ['APPROVER', 'LABEL_OFFICER']
      json({ subject: actorSubject, org_id: mode === 'foreign-caller' ? 'another-org' : 'org-one', org_type: 'MANUFACTURER', roles })
    } else if (url.pathname === '/api/formulations/products') {
      observedAuthorization = req.headers.authorization || ''
      expect(req.headers['x-auth-provider']).toBeUndefined()
      expect(req.headers['x-external-subject']).toBeUndefined()
      expect(req.headers['x-correlation-id']).toBeTruthy()
      json(apiStatus === 200 ? { items: [{ productId: 'product-one', organisationId: mode === 'foreign-product' ? 'another-org' : 'org-one',
        productDescription: 'Organisation product', brandOwner: 'Test manufacturer', normalizedCategory: 'TEST', currentFormulaVersion: null }], nextCursor: null } : {}, apiStatus)
    } else if (url.pathname.startsWith('/api/compliance/impact-findings')) {
      impactRequests.push({ path: url.pathname, query: url.search, authenticated: req.headers.authorization === `Bearer ${sentToken}`,
        correlation: String(req.headers['x-correlation-id']) })
      res.setHeader('X-Correlation-ID', String(req.headers['x-correlation-id']))
      if (impactScenario === 'delay') await new Promise(resolve => setTimeout(resolve, 600))
      if (impactScenario.startsWith('error-')) { json({ code: 'FIXTURE_ERROR', message: 'PRIVATE_IMPACT_MUST_NOT_LEAK' }, Number(impactScenario.slice(6))); return }
      const item = { ...confirmedImpact }
      if (impactScenario === 'potential') item.kind = 'POTENTIAL'
      if (impactScenario === 'no-action') { item.kind = 'POTENTIAL'; item.outcome = 'NO_ACTION'; item.missingAllergens = [] }
      if (impactScenario === 'foreign-item') item.organisationId = 'another-org'
      if (impactScenario === 'bad-version') item.formulaVersion = { id: 'formula_001_v2', versionNumber: 0 }
      if (impactScenario === 'contradiction') item.outcome = 'NO_ACTION'
      if (impactScenario === 'wrong-detail') item.findingId = 'another-finding'
      if (url.pathname !== '/api/compliance/impact-findings') json(url.pathname.endsWith('finding_004') ? unresolvedImpact : item)
      else {
        const all = impactScenario === 'empty' ? [] : url.searchParams.has('cursor') ? [unresolvedImpact] : [item]
        const items = impactScenario === 'wrong-filter' ? all : all.filter(f => (!url.searchParams.has('kind') || f.kind === url.searchParams.get('kind'))
          && (!url.searchParams.has('outcome') || f.outcome === url.searchParams.get('outcome')))
        json({ organisationId: impactScenario === 'foreign-page' ? 'another-org' : 'org-one', items,
          nextCursor: impactScenario === 'repeat-cursor' ? url.searchParams.get('cursor') || 'cursor-one'
            : impactScenario === 'empty' || url.searchParams.has('cursor') ? null : 'opaque +&cursor=' })
      }
    } else if (/^\/api\/(specifications\/versions|formulations\/formula-versions)\//.test(url.pathname)) {
      impactRequests.push({ path: url.pathname, query: url.search, authenticated: req.headers.authorization === `Bearer ${sentToken}`,
        correlation: String(req.headers['x-correlation-id']) })
      const spec = url.pathname.includes('/specifications/')
      const id = url.pathname.split('/').pop()!
      json({ [spec ? 'specificationVersionId' : 'formulaVersionId']: id, organisationId: spec ? 'supplier-one' : 'org-one',
        versionNumber: id.endsWith('v1') ? 1 : 2, lifecycleStatus: 'RELEASED' })
    } else if (url.pathname.startsWith('/api/labels/')) {
      let text = ''; for await (const chunk of req) text += chunk
      labelRequests.push({ method: req.method!, path: url.pathname, query: url.search, body: text ? JSON.parse(text) : null,
        authenticated: req.headers.authorization === `Bearer ${sentToken}`, correlation: String(req.headers['x-correlation-id']) })
      expect(req.headers['x-auth-provider']).toBeUndefined()
      expect(req.headers['x-external-subject']).toBeUndefined()
      expect(req.headers['x-org-id']).toBeUndefined()
      expect(req.headers['idempotency-key']).toBeUndefined()
      res.setHeader('X-Correlation-ID', String(req.headers['x-correlation-id']))
      if (labelDelay) await new Promise(resolve => setTimeout(resolve, labelDelay))
      if (labelScenario === 'lost-response') { res.destroy(); return }
      if (labelScenario === 'empty-response') { json(null, 201); return }
      if (labelScenario.startsWith('error-')) {
        const status = Number(labelScenario.slice(6))
        json({ code: status === 409 ? 'LABEL_VALIDATION_REQUIRED' : 'FIXTURE_ERROR', message: 'INTERNAL_DETAIL_MUST_NOT_LEAK' }, status)
      } else if (url.pathname === '/api/labels/current' && req.method === 'GET') {
        if (url.searchParams.get('productId') !== 'product-one' || url.searchParams.get('jurisdictionCode') !== 'SG') json({ code: 'LABEL_NOT_FOUND' }, 404)
        else json({ ...label, lifecycleStatus: 'PUBLISHED' })
      } else if (url.pathname === '/api/labels/drafts' && req.method === 'POST') {
        labelCreator = actorSubject
        json(labelScenario === 'malformed-label' ? { ...label, versionNumber: 0 } : label, 201)
      } else if (url.pathname === '/api/labels/label-one' && req.method === 'GET') {
        if (labelScenario === 'decision-refresh-error' && decisionRecorded) json({ code: 'SERVICE_UNAVAILABLE' }, 503)
        else json(labelScenario === 'wrong-label' ? { ...label, labelVersionId: 'another-label' } : label)
      } else if (url.pathname === '/api/labels/label-one/declarations' && req.method === 'GET') {
        json({ labelVersionId: label.labelVersionId, formulaVersionId: label.formulaVersionId,
          ruleSetVersionId: label.ruleSetVersionId, jurisdictionCode: label.jurisdictionCode, declarations: labelDeclarations })
      } else if (url.pathname === '/api/labels/label-one/declarations' && req.method === 'PUT') {
        const body = JSON.parse(text)
        if (!actorRoles.includes('LABEL_OFFICER') || label.lifecycleStatus !== 'DRAFT') json({ code: 'AUTHORIZATION_DENIED' }, 403)
        else {
          labelDeclarations = body.declarations; draftRevision++
          json({ labelVersionId: label.labelVersionId, formulaVersionId: label.formulaVersionId,
            ruleSetVersionId: label.ruleSetVersionId, jurisdictionCode: label.jurisdictionCode, declarations: labelDeclarations })
        }
      } else if (url.pathname.endsWith('/validations') && req.method === 'POST') {
        const failed = labelScenario === 'failed-validation'
        const result = { validationRunId: 'validation-one', labelVersionId: label.labelVersionId,
          formulaVersionId: label.formulaVersionId, ruleSetVersionId: label.ruleSetVersionId,
          jurisdictionCode: label.jurisdictionCode, status: failed ? 'FAILED' : 'PASSED',
          findings: failed ? [{ resultCode: 'MISSING_ALLERGEN', severity: 'ERROR', passed: false, blocking: true,
            message: 'The required soy declaration is missing.' }] : [] }
        if (labelScenario.startsWith('mismatch-')) Object.assign(result, { [labelScenario.slice(9)]: 'another-value' })
        if (labelScenario === 'contradictory-validation') result.findings.push({ resultCode: 'BLOCKING', severity: 'ERROR', passed: false, blocking: true, message: 'Blocked despite PASSED.' })
        if (labelScenario === 'nonblocking-error') result.findings.push({ resultCode: 'NONBLOCKING_RULE', severity: 'ERROR', passed: false, blocking: false, message: 'This rule did not pass, but does not block submission.' })
        json(result, 201)
      } else if (url.pathname.endsWith('/submit') && req.method === 'POST') {
        if (labelScenario === 'malformed-submit') json({ ...label, formulaVersionId: 'different-formula', lifecycleStatus: 'PENDING_REVIEW' })
        else { label.lifecycleStatus = 'PENDING_REVIEW'; json(label) }
      } else if (url.pathname.endsWith('/decisions') && req.method === 'POST') {
        const body = JSON.parse(text)
        if (!actorRoles.includes('APPROVER') || (body.decision === 'APPROVE' && labelCreator === actorSubject)) {
          json({ code: 'AUTHORIZATION_DENIED' }, 403)
        } else {
          decisionRecorded = true
          label.lifecycleStatus = body.decision === 'APPROVE' ? 'APPROVED' : body.decision === 'REJECT' ? 'REJECTED' : 'DRAFT'
          json({ decisionId: 'decision-one', labelVersionId: labelScenario === 'wrong-decision' ? 'another-label' : label.labelVersionId,
            decision: body.decision, comments: body.comments, decidedAt: '2026-10-13T05:00:00Z' }, 201)
        }
      } else if (url.pathname.endsWith('/publish') && req.method === 'POST') {
        if (!actorRoles.includes('PUBLISHER') || label.lifecycleStatus !== 'APPROVED') json({ code: 'LABEL_APPROVAL_REQUIRED' }, 409)
        else { label.lifecycleStatus = 'PUBLISHED'; json({ publicationRecordId: 'publication-one',
          labelVersionId: labelScenario === 'wrong-publication' ? 'another-label' : label.labelVersionId,
          lifecycleStatus: 'PUBLISHED', publishedAt: '2026-10-13T05:01:00Z' }, 201) }
      } else json({ code: 'LABEL_NOT_FOUND' }, 404)
    } else if (url.pathname.endsWith('/logout')) {
      res.writeHead(302, { Location: url.searchParams.get('post_logout_redirect_uri')! }); res.end()
    } else json({}, 404)
  })
  await new Promise<void>(resolve => server.listen(8191, '127.0.0.1', resolve))
})
test.afterAll(async () => { await new Promise<void>(resolve => server.close(() => resolve())) })
test.beforeEach(() => { mode = 'ok'; apiStatus = 200; observedAuthorization = ''; exchanges = 0; verifiedPkce = false; codes.clear()
  actorRoles = ['LABEL_OFFICER', 'APPROVER']; actorType = 'MANUFACTURER'; actorSubject = 'test-user'; labelCreator = 'original-maker'; decisionRecorded = false; labelScenario = 'ok'; labelDelay = 0; label = freshLabel(); labelRequests = []; labelDeclarations = []; draftRevision = 0; impactScenario = 'ok'; impactRequests = [] })
async function login(page: Page) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Continue with Keycloak', exact: true }).click()
}
async function capture(page: Page, path: string) {
  await page.evaluate(() => {
    window.scrollTo(0, 0)
    document.querySelectorAll<HTMLElement>('.table-scroll, .impact-table-wrap').forEach(element => { element.scrollLeft = 0 })
  })
  await page.screenshot({ path, fullPage: true })
}
test('PKCE round trip displays token organisation and roles, forwards bearer, and signs out', async ({ page }) => {
  await login(page)
  await expect(page.getByRole('heading', { name: 'Your organisation’s workspace' })).toBeVisible()
  expect(verifiedPkce).toBe(true); expect(exchanges).toBe(1)
  await page.getByRole('button', { name: 'Verify service identity' }).click()
  await expect(page.getByRole('status')).toContainText('Service identity verified: org-one')
  await expect(page.getByText('org-one', { exact: true })).toBeVisible()
  await expect(page.getByText('LABEL_OFFICER, APPROVER', { exact: true })).toBeVisible()
  expect(new URL(page.url()).search).toBe('')
  await page.getByRole('button', { name: 'Load products' }).click()
  await expect(page.getByText('Organisation product', { exact: true })).toBeVisible()
  expect(observedAuthorization).toBe(`Bearer ${sentToken}`)
  const storage = await page.evaluate(() => ({ local: JSON.stringify(localStorage), session: JSON.stringify(sessionStorage) }))
  expect(storage.local).not.toContain(sentToken); expect(storage.session).not.toContain(sentToken)
  expect(storage.session).not.toContain('code_verifier')
  await capture(page, 'test-results/signin/desktop.png')
  await page.setViewportSize({ width: 390, height: 844 })
  await capture(page, 'test-results/signin/mobile.png')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await saveCoverage(page)
  await page.getByRole('button', { name: 'Sign out', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Sign in to your organisation' })).toBeVisible()
})
for (const failure of ['bad-state', 'bad-nonce', 'bad-audience', 'multi-org', 'array-org-type', 'token-error']) {
  test(`rejects ${failure} and clears callback parameters`, async ({ page }) => {
    mode = failure; await login(page)
    await expect(page.getByRole('alert')).toContainText('Sign-in could not be verified')
    await expect(page.getByRole('heading', { name: 'Your organisation’s workspace' })).toHaveCount(0)
    expect(new URL(page.url()).search).toBe('')
    expect(await page.evaluate(() => Object.keys(sessionStorage).filter(k => k.startsWith('spectrace.pkce.')))).toEqual([])
    if (failure === 'bad-state') expect(exchanges).toBe(0)
  })
}
test('401 ends the local session without silently retrying', async ({ page }) => {
  await login(page); await expect(page.getByRole('button', { name: 'Load products' })).toBeVisible()
  apiStatus = 401; await page.getByRole('button', { name: 'Load products' }).click()
  await expect(page.getByRole('alert')).toContainText('session is no longer accepted')
})
test('403 keeps the identity and explains permission denial', async ({ page }) => {
  await login(page); await expect(page.getByRole('button', { name: 'Load products' })).toBeVisible()
  apiStatus = 403; await page.getByRole('button', { name: 'Load products' }).click()
  await expect(page.getByRole('status')).toContainText('do not have permission')
  await expect(page.getByText('org-one', { exact: true })).toBeVisible()
})
test('a role-less caller signs in but gets the service permission denial', async ({ page }) => {
  mode = 'roleless'; await login(page)
  await expect(page.getByText('No assigned roles', { exact: true })).toBeVisible()
  apiStatus = 403; await page.getByRole('button', { name: 'Load products' }).click()
  await expect(page.getByRole('status')).toContainText('do not have permission')
  await expect(page.getByText('org-one', { exact: true })).toBeVisible()
})
test('a product response for another organisation is never displayed', async ({ page }) => {
  await login(page); await expect(page.getByRole('button', { name: 'Load products' })).toBeVisible()
  mode = 'foreign-product'; await page.getByRole('button', { name: 'Load products' }).click()
  await expect(page.getByRole('status')).toContainText('unreadable product list')
  await expect(page.getByText('Organisation product', { exact: true })).toHaveCount(0)
})
for (const mismatch of ['foreign-caller', 'foreign-roles']) test(`does not verify a service caller with ${mismatch}`, async ({ page }) => {
  await login(page); await expect(page.getByRole('button', { name: 'Verify service identity' })).toBeVisible()
  mode = mismatch; await page.getByRole('button', { name: 'Verify service identity' }).click()
  await expect(page.getByRole('alert')).toContainText('does not match your signed-in organisation')
  await expect(page.getByText(/Service identity verified:/)).toHaveCount(0)
})

for (const failure of ['caller-role-mismatch', 'caller-role-delimiter']) {
  test(`does not verify a service caller with ${failure}`, async ({ page }) => {
    await login(page); await expect(page.getByRole('button', { name: 'Verify service identity' })).toBeVisible()
    mode = failure; await page.getByRole('button', { name: 'Verify service identity' }).click()
    await expect(page.getByRole('alert')).toContainText('does not match your signed-in organisation')
    await expect(page.getByText(/Service identity verified:/)).toHaveCount(0)
  })
}
test('expiry requires a new sign-in; reload never restores a stored token', async ({ page }) => {
  mode = 'expires'; await login(page)
  await expect(page.getByRole('heading', { name: 'Your organisation’s workspace' })).toBeVisible()
  await expect(page.getByRole('alert')).toContainText('session expired', { timeout: 7000 })
  mode = 'ok'; await login(page)
  await expect(page.getByRole('heading', { name: 'Your organisation’s workspace' })).toBeVisible()
  await saveCoverage(page)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Sign in to your organisation' })).toBeVisible()
})

async function labels(page: Page) {
  await login(page)
  await page.getByRole('button', { name: 'Labels', exact: true }).click()
}

async function inbox(page: Page) {
  actorRoles = ['CHANGE_MANAGER', 'LABEL_OFFICER']
  await login(page)
  await page.getByRole('button', { name: 'Impact inbox', exact: true }).click()
  await page.getByRole('button', { name: 'Refresh inbox', exact: true }).click()
}

test('STCN-101 reads exact confirmed evidence, version references and the linked label', async ({ page }) => {
  await inbox(page)
  await page.getByRole('button', { name: 'View finding finding_002', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Finding finding_002', exact: true })).toBeFocused()
  await expect(page.getByText('all_soy, all_wheat', { exact: true })).toBeVisible()
  await expect(page.locator('.impact-allergens')).toContainText('Declared allergensall_wheat')
  await expect(page.locator('.impact-allergens')).toContainText('Missing allergensall_soy')
  await expect(page.getByText(/Review task delivery must be verified separately/)).toBeVisible()
  await page.getByRole('button', { name: 'Previous specification:', exact: false }).click()
  await expect(page.getByRole('status')).toContainText('Previous specification verified: spec_001_v1')
  await page.getByRole('button', { name: 'Candidate specification:', exact: false }).click()
  await expect(page.getByRole('status')).toContainText('Candidate specification verified: spec_001_v2')
  await page.getByRole('button', { name: 'Formula:', exact: false }).click()
  await expect(page.getByRole('status')).toContainText('Formula verified: formula_001_v2')
  await capture(page, 'test-results/signin/impact-desktop.png')
  await page.setViewportSize({ width: 390, height: 844 })
  await capture(page, 'test-results/signin/impact-mobile.png')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(impactRequests.every(req => req.authenticated && req.correlation && !req.query.includes('org'))).toBe(true)
  await page.getByRole('button', { name: 'Open label:', exact: false }).click()
  await expect(page.getByLabel('Label version ID', { exact: true })).toHaveValue('label-one')
  await page.getByRole('button', { name: 'Open label', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Label version 1', exact: true })).toBeVisible()
})

test('STCN-101 keeps opaque pagination and displays unresolved provenance without an invented allergen', async ({ page }) => {
  await inbox(page)
  await page.getByRole('button', { name: 'Next page', exact: true }).click()
  await page.getByRole('button', { name: 'View finding finding_004', exact: true }).click()
  expect(new URLSearchParams(impactRequests[1].query).get('cursor')).toBe('opaque +&cursor=')
  await expect(page.getByText('Formula item', { exact: true }).first()).toBeVisible()
  await expect(page.locator('.impact-unresolved')).toContainText('UNMAPPED')
  await expect(page.locator('.impact-unresolved')).toContainText('natural flavouring (carrier undisclosed)')
  await expect(page.locator('.impact-unresolved')).toContainText('spec_003_v2_c02')
  await expect(page.getByRole('button', { name: 'Next page', exact: true })).toHaveCount(0)
})

test('STCN-101 changed filters discard previous evidence and restart cursor pagination', async ({ page }) => {
  await inbox(page)
  await page.getByRole('button', { name: 'View finding finding_002', exact: true }).click()
  await page.getByLabel('Finding kind', { exact: true }).selectOption('POTENTIAL')
  await expect(page.getByRole('heading', { name: 'Finding finding_002', exact: true })).toHaveCount(0)
  await page.getByLabel('Finding outcome', { exact: true }).selectOption('NO_ACTION')
  await page.getByRole('button', { name: 'Refresh inbox', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('An empty inbox does not establish a NO_ACTION conclusion')
  const params = new URLSearchParams(impactRequests.at(-1)!.query)
  expect(params.get('kind')).toBe('POTENTIAL'); expect(params.get('outcome')).toBe('NO_ACTION'); expect(params.has('cursor')).toBe(false)
})
test('STCN-101 potential and NO_ACTION findings remain evidence rather than adopted formulas or tasks', async ({ page }) => {
  impactScenario = 'potential'; await inbox(page)
  await page.getByRole('button', { name: 'View finding finding_002', exact: true }).click()
  await expect(page.getByText(/Potential impact only/)).toBeVisible()
  impactScenario = 'no-action'
  await page.getByRole('button', { name: 'Refresh inbox', exact: true }).click()
  await page.getByRole('button', { name: 'View finding finding_002', exact: true }).click()
  await expect(page.locator('.impact-state')).toContainText('NO_ACTION')
  await expect(page.locator('.impact-allergens')).toContainText('Missing allergensNone recorded')
  await expect(page.getByRole('button', { name: /adopt|create review task/i })).toHaveCount(0)
})
test('STCN-101 rejects findings that violate the selected filters', async ({ page }) => {
  await inbox(page)
  await page.getByLabel('Finding kind', { exact: true }).selectOption('POTENTIAL')
  impactScenario = 'wrong-filter'
  await page.getByRole('button', { name: 'Refresh inbox', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('outside your selected filters')
})

for (const scenario of ['foreign-item', 'foreign-page', 'bad-version', 'contradiction']) {
  test(`STCN-101 hides an entire unreadable inbox for ${scenario}`, async ({ page }) => {
    impactScenario = scenario; await inbox(page)
    await expect(page.getByRole('alert')).toContainText('unreadable')
    await expect(page.getByRole('button', { name: 'View finding finding_002', exact: true })).toHaveCount(0)
    await expect(page.getByText('all_soy', { exact: true })).toHaveCount(0)
  })
}
test('STCN-101 does not display another exact finding or a repeated cursor', async ({ page }) => {
  await inbox(page); impactScenario = 'wrong-detail'
  await page.getByRole('button', { name: 'View finding finding_002', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('different or unreadable')
  await expect(page.getByRole('heading', { name: /Finding finding/ })).toHaveCount(0)
  impactScenario = 'repeat-cursor'
  await page.getByRole('button', { name: 'Refresh inbox', exact: true }).click()
  await page.getByRole('button', { name: 'Next page', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('cursor repeated')
})
for (const status of [403, 404, 422, 429, 503]) {
  test(`STCN-101 explains ${status} without revealing service internals or retrying`, async ({ page }) => {
    impactScenario = `error-${status}`; await inbox(page)
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.locator('body')).not.toContainText('PRIVATE_IMPACT_MUST_NOT_LEAK')
    expect(impactRequests).toHaveLength(1)
    await expect(page.getByRole('button', { name: 'Refresh inbox', exact: true })).toBeEnabled()
  })
}
test('STCN-101 a read 401 ends the session and removes all evidence', async ({ page }) => {
  impactScenario = 'error-401'; await inbox(page)
  await expect(page.getByRole('heading', { name: 'Sign in to your organisation', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Impact inbox', exact: true })).toHaveCount(0)
})
test('STCN-101 supplier and roleless users cannot issue inbox reads', async ({ page }) => {
  actorRoles = ['CHANGE_MANAGER']; actorType = 'SUPPLIER'; await login(page)
  await page.getByRole('button', { name: 'Impact inbox', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('require a manufacturer account')
  await expect(page.getByRole('button', { name: 'Refresh inbox', exact: true })).toHaveCount(0)
  expect(impactRequests).toHaveLength(0)
  actorType = 'MANUFACTURER'; actorRoles = []; await login(page)
  await page.getByRole('button', { name: 'Impact inbox', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('CHANGE_MANAGER')
  expect(impactRequests).toHaveLength(0)
})
test('STCN-101 leaving during a read never restores evidence to another workspace view', async ({ page }) => {
  impactScenario = 'delay'; await inbox(page)
  await page.getByRole('button', { name: 'Labels', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Labels', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Impact inbox', exact: true }).click()
  await expect(page.getByRole('button', { name: 'View finding finding_002', exact: true })).toHaveCount(0)
})
async function createDraft(page: Page) {
  await page.getByLabel('Product ID', { exact: true }).fill('product-one')
  await page.getByLabel('Jurisdiction code', { exact: true }).fill('SG')
  await page.getByRole('button', { name: 'Create draft', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Label version 1', exact: true })).toBeVisible()
}

test('STCN-74 creates, validates and submits an exact label through authenticated public POSTs', async ({ page }) => {
  await labels(page); await createDraft(page)
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeDisabled()
  await page.getByRole('button', { name: 'Validate draft', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Validation passed', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeEnabled()
  await capture(page, 'test-results/signin/labels-flow-desktop.png')
  await page.setViewportSize({ width: 390, height: 844 })
  await capture(page, 'test-results/signin/labels-flow-mobile.png')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.getByRole('button', { name: 'Submit for review' }).click()
  await expect(page.getByRole('status')).toContainText('Submitted for review')
  await expect(page.getByText('PENDING REVIEW', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Submit for review' })).toHaveCount(0)
  expect(labelRequests.map(request => [request.method, request.path, request.body])).toEqual([
    ['POST', '/api/labels/drafts', { productId: 'product-one', jurisdictionCode: 'SG' }],
    ['GET', '/api/labels/label-one/declarations', null],
    ['POST', '/api/labels/label-one/validations', null], ['POST', '/api/labels/label-one/submit', null],
  ])
  for (const request of labelRequests) { expect(request.authenticated).toBe(true); expect(request.correlation).toMatch(/^[a-z0-9-]{36}$/) }
})

test('STCN-77 exact roles and organisation type control writes while keeping read access', async ({ page }) => {
  actorRoles = ['APPROVER', 'PUBLISHER']; await labels(page)
  await expect(page.getByText('Open an existing version', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Create draft', exact: true })).toHaveCount(0)
  await page.getByLabel('Label version ID').fill('label-one'); await page.getByRole('button', { name: 'Open label', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Label version 1', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Validate draft', exact: true })).toHaveCount(0)
  expect(labelRequests).toHaveLength(2)
})

test('STCN-77 suppliers cannot gain label writes from a label-officer role', async ({ page }) => {
  actorType = 'SUPPLIER'; await labels(page)
  await expect(page.getByText('Open an existing version', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Create draft', exact: true })).toHaveCount(0)
  expect(labelRequests).toHaveLength(0)
})

test('STCN-76 loads the current published label through its organisation-scoped query', async ({ page }) => {
  actorRoles = []; label.lifecycleStatus = 'PUBLISHED'
  await labels(page)
  await page.getByLabel('Current product ID').fill('product-one')
  await page.getByLabel('Current jurisdiction code').fill('SG')
  await page.getByRole('button', { name: 'Load current label', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Label version 1', exact: true })).toBeVisible()
  await expect(page.getByText('This is the current published label returned by the service.')).toBeVisible()
  expect(labelRequests[0].path).toBe('/api/labels/current')
  expect(labelRequests[0].query).toBe('?productId=product-one&jurisdictionCode=SG')
  expect(labelRequests.every(request => request.authenticated && request.correlation)).toBe(true)
})

test('STCN-74/75 edits declarations after REQUEST_CHANGES, then revalidates and resubmits', async ({ page }) => {
  await labels(page); await createDraft(page)
  await page.getByRole('button', { name: 'Add declaration', exact: true }).click()
  await page.getByLabel('Allergen ID').fill('all_soy')
  await page.getByLabel('Display text (optional)').fill('Soy lecithin')
  await page.getByRole('button', { name: 'Save declarations', exact: true }).click()
  await expect(page.getByText('Declarations saved. Validate this exact draft again before submitting.', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Validate draft', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeEnabled()
  await page.getByRole('button', { name: 'Submit for review' }).click()
  await expect(page.getByText('PENDING REVIEW', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Request changes', exact: true }).click()
  await expect(page.getByText('Changes requested. Edit the declarations, save them, then validate and resubmit.', { exact: true })).toBeVisible()
  await expect(page.getByLabel('Allergen ID')).toHaveValue('all_soy')
  await page.getByLabel('Display text (optional)').fill('Soy lecithin (updated)')
  await page.getByRole('button', { name: 'Save declarations', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeDisabled()
  await page.getByRole('button', { name: 'Validate draft', exact: true }).click()
  await page.getByRole('button', { name: 'Submit for review' }).click()
  await expect(page.getByText('PENDING REVIEW', { exact: true })).toBeVisible()
  const replacements = labelRequests.filter(request => request.method === 'PUT')
  expect(replacements.map(request => request.body)).toEqual([
    { declarations: [{ allergenId: 'all_soy', declarationType: 'CONTAINS', declarationSource: 'USER_ENTERED', displayText: 'Soy lecithin' }] },
    { declarations: [{ allergenId: 'all_soy', declarationType: 'CONTAINS', declarationSource: 'USER_ENTERED', displayText: 'Soy lecithin (updated)' }] },
  ])
  expect(draftRevision).toBe(2)
  expect(JSON.stringify(labelRequests)).not.toContain('draftRevision')
  expect(labelRequests.every(request => request.authenticated && /^[a-z0-9-]{36}$/.test(request.correlation))).toBe(true)
})

test('STCN-74 failed revalidation clears the previous pass and blocks submit', async ({ page }) => {
  await labels(page); await createDraft(page)
  await page.getByRole('button', { name: 'Validate draft', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeEnabled()
  labelScenario = 'failed-validation'
  await page.getByRole('button', { name: 'Validate draft', exact: true }).click()
  await expect(page.getByText('The required soy declaration is missing.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeDisabled()
})

for (const scenario of ['mismatch-labelVersionId', 'mismatch-formulaVersionId', 'mismatch-ruleSetVersionId', 'mismatch-jurisdictionCode', 'contradictory-validation']) {
  test(`STCN-74 fails closed on ${scenario}`, async ({ page }) => {
    await labels(page); await createDraft(page); labelScenario = scenario
    await page.getByRole('button', { name: 'Validate draft', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('validation result does not match')
    await expect(page.getByRole('button', { name: 'Submit for review' })).toBeDisabled()
  })
}

for (const status of [400, 403, 404, 422, 429, 503]) {
  test(`STCN-74 explains ${status} without retrying or leaking server details`, async ({ page }) => {
    await labels(page); labelScenario = `error-${status}`
    await page.getByLabel('Product ID', { exact: true }).fill('product-one'); await page.getByLabel('Jurisdiction code', { exact: true }).fill('SG')
    await page.getByRole('button', { name: 'Create draft', exact: true }).click()
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByRole('alert')).toContainText('Reference:')
    await expect(page.getByText('INTERNAL_DETAIL_MUST_NOT_LEAK')).toHaveCount(0)
    expect(labelRequests).toHaveLength(1)
    await expect(page.getByRole('heading', { name: 'Label version 1', exact: true })).toHaveCount(0)
    if (status === 503) await expect(page.getByRole('button', { name: 'Create draft', exact: true })).toBeDisabled()
  })
}

test('STCN-74 a stale submit needs a fresh read and validation', async ({ page }) => {
  await labels(page); await createDraft(page)
  await page.getByRole('button', { name: 'Validate draft', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeEnabled()
  labelScenario = 'error-409'; await page.getByRole('button', { name: 'Submit for review' }).click()
  await expect(page.getByRole('alert')).toContainText('Validate this draft again')
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeDisabled()
  labelScenario = 'ok'; await page.getByRole('button', { name: 'Open label', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('Label loaded')
  await expect(page.getByRole('button', { name: 'Validate draft', exact: true })).toBeEnabled()
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeDisabled()
})

test('STCN-74 malformed creation cannot become a draft or be silently retried', async ({ page }) => {
  await labels(page); labelScenario = 'malformed-label'
  await page.getByLabel('Product ID', { exact: true }).fill('product-one'); await page.getByLabel('Jurisdiction code', { exact: true }).fill('SG')
  await page.getByRole('button', { name: 'Create draft', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('unreadable label')
  await expect(page.getByRole('button', { name: 'Create draft', exact: true })).toBeDisabled()
  expect(labelRequests.filter(request => request.method === 'POST')).toHaveLength(1)
})

test('STCN-74 mismatched submission never displays success', async ({ page }) => {
  await labels(page); await createDraft(page)
  await page.getByRole('button', { name: 'Validate draft', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeEnabled()
  labelScenario = 'malformed-submit'; await page.getByRole('button', { name: 'Submit for review' }).click()
  await expect(page.getByRole('alert')).toContainText('did not confirm submission')
  await expect(page.getByText('PENDING REVIEW', { exact: true })).toHaveCount(0)
})

test('STCN-77 concurrent clicks and navigation cannot leave late label state', async ({ page }) => {
  await labels(page); labelDelay = 500
  await page.getByLabel('Product ID', { exact: true }).fill('product-one'); await page.getByLabel('Jurisdiction code', { exact: true }).fill('SG')
  await page.getByRole('button', { name: 'Create draft', exact: true }).dblclick()
  await expect(page.getByRole('heading', { name: 'Label version 1', exact: true })).toBeVisible()
  expect(labelRequests.filter(request => request.method === 'POST')).toHaveLength(1)
  await page.getByRole('button', { name: 'Validate draft', exact: true }).click()
  await page.getByRole('button', { name: 'Workspace', exact: true }).click()
  await page.getByRole('button', { name: 'Labels', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Label version 1', exact: true })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Validation passed', exact: true })).toHaveCount(0)
})

test('STCN-77 a write 401 clears the identity and label screen', async ({ page }) => {
  await labels(page); labelScenario = 'error-401'
  await page.getByLabel('Product ID', { exact: true }).fill('product-one'); await page.getByLabel('Jurisdiction code', { exact: true }).fill('SG')
  await page.getByRole('button', { name: 'Create draft', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('session is no longer accepted')
  await expect(page.getByRole('button', { name: 'Labels', exact: true })).toHaveCount(0)
})

test('STCN-77 public write helper refuses foreign, private and ambiguous routes', async ({ page }) => {
  await labels(page)
  const errors = await page.evaluate(async () => {
    // @ts-expect-error Vite resolves the existing authenticated module in this browser.
    const { publicApiPost } = await import('/src/auth/session.ts')
    const paths = ['https://foreign.example.test/api/labels/drafts', '/internal/validations',
      '/api/labels/internal/write', '/api/labels/../drafts', '/api/labels/a%2fb', '/api/labels//drafts', '/api/labels/a;v=1']
    return Promise.all(paths.map(async path => { try { await publicApiPost(path, {}); return false } catch { return true } }))
  })
  expect(errors.every(Boolean)).toBe(true); expect(labelRequests).toHaveLength(0)
})

for (const scenario of ['lost-response', 'empty-response']) {
  test(`STCN-77 ${scenario} leaves a write unconfirmed and blocks retries`, async ({ page }) => {
    await labels(page); labelScenario = scenario
    await page.evaluate(() => {
      const tracked = window as unknown as Window & { labelPostAttempts: number }
      tracked.labelPostAttempts = 0
      const send = window.fetch.bind(window)
      window.fetch = (input, init) => { if (init?.method === 'POST') tracked.labelPostAttempts++; return send(input, init) }
    })
    await page.getByLabel('Product ID', { exact: true }).fill('product-one'); await page.getByLabel('Jurisdiction code', { exact: true }).fill('SG')
    await page.getByRole('button', { name: 'Create draft', exact: true }).click()
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Create draft', exact: true })).toBeDisabled()
    await expect(page.getByRole('heading', { name: 'Label version 1', exact: true })).toHaveCount(0)
    expect(await page.evaluate(() => (window as unknown as Window & { labelPostAttempts: number }).labelPostAttempts)).toBe(1)
    // A browser transport may replay a dropped connection. The application must not issue another fetch.
    expect(new Set(labelRequests.map(request => request.correlation)).size).toBe(1)
  })
}

test('STCN-74 a different label response cannot replace the requested version', async ({ page }) => {
  await labels(page); labelScenario = 'wrong-label'
  await page.getByLabel('Label version ID').fill('label-one'); await page.getByRole('button', { name: 'Open label', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('different or unreadable label')
  await expect(page.getByText('another-label', { exact: true })).toHaveCount(0)
})

test('STCN-74 preserves severity separately from blocking and pass status', async ({ page }) => {
  await labels(page); await createDraft(page); labelScenario = 'nonblocking-error'
  await page.getByRole('button', { name: 'Validate draft', exact: true }).click()
  await expect(page.getByText('Error · NONBLOCKING_RULE', { exact: true })).toBeVisible()
  await expect(page.getByText('Does not block submission · Check not passed', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeEnabled()
  await capture(page, 'test-results/signin/labels-desktop.png')
  await page.setViewportSize({ width: 390, height: 844 })
  await capture(page, 'test-results/signin/labels-mobile.png')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

async function openReviewVersion(page: Page, status = 'PENDING_REVIEW') {
  label.lifecycleStatus = status
  await labels(page)
  await page.getByLabel('Label version ID').fill('label-one'); await page.getByRole('button', { name: 'Open label', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Label version 1', exact: true })).toBeVisible()
}

test('STCN-75/76 reviewer records approval and publisher explicitly confirms publication', async ({ page }) => {
  actorRoles = ['APPROVER', 'PUBLISHER']; actorSubject = 'reviewer-user'
  await openReviewVersion(page)
  await expect(page.getByRole('button', { name: 'Publish version', exact: true })).toHaveCount(0)
  await page.getByLabel('Review comments (optional)').fill('Checked the declared allergens.')
  await capture(page, 'test-results/signin/labels-review-desktop.png')
  await page.setViewportSize({ width: 390, height: 844 })
  await capture(page, 'test-results/signin/labels-review-mobile.png')
  await page.getByRole('button', { name: 'Approve version', exact: true }).click()
  await expect(page.getByText('APPROVED', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Decision recorded', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Publish version', exact: true })).toBeDisabled()
  await page.getByRole('checkbox').check()
  await page.getByRole('button', { name: 'Publish version', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Publication receipt', exact: true })).toBeVisible()
  await expect(page.getByText('PUBLISHED', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Publish version', exact: true })).toHaveCount(0)
  const posts = labelRequests.filter(request => request.method === 'POST')
  expect(posts.map(request => [request.path, request.body])).toEqual([
    ['/api/labels/label-one/decisions', { decision: 'APPROVE', comments: 'Checked the declared allergens.' }],
    ['/api/labels/label-one/publish', null],
  ])
  await page.setViewportSize({ width: 1280, height: 720 })
  await capture(page, 'test-results/signin/labels-published-desktop.png')
  await page.setViewportSize({ width: 390, height: 844 })
  await capture(page, 'test-results/signin/labels-published-mobile.png')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('STCN-75 self-approval is denied by the service and explained without guessing creator identity', async ({ page }) => {
  actorRoles = ['APPROVER']; labelCreator = actorSubject
  await openReviewVersion(page)
  await page.getByRole('button', { name: 'Approve version', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('do not have permission')
  await expect(page.getByText('The creator cannot approve this version. Approval requires a different reviewer.')).toBeVisible()
  await expect(page.getByText('APPROVED', { exact: true })).toHaveCount(0)
  expect(labelRequests.filter(request => request.method === 'POST').length).toBe(1)
})

for (const [button, status] of [['Request changes', 'DRAFT'], ['Reject version', 'REJECTED']]) {
  test(`STCN-75 the creator can ${button.toLowerCase()} when its approver role permits it`, async ({ page }) => {
    actorRoles = ['APPROVER']; labelCreator = actorSubject
    await openReviewVersion(page)
    await page.getByRole('button', { name: button, exact: true }).click()
    await expect(page.getByText(status, { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Publish version', exact: true })).toHaveCount(0)
  })
}

test('STCN-75 an acknowledged decision with a failed refresh stays locked without re-posting', async ({ page }) => {
  actorRoles = ['APPROVER']; await openReviewVersion(page); labelScenario = 'decision-refresh-error'
  await page.getByRole('button', { name: 'Approve version', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Decision recorded', exact: true })).toBeVisible()
  await expect(page.getByRole('alert')).toBeVisible()
  await expect(page.getByRole('status')).toContainText('A decision was recorded')
  await expect(page.getByRole('button', { name: 'Approve version', exact: true })).toBeDisabled()
  expect(labelRequests.filter(request => request.method === 'POST').length).toBe(1)
})

for (const scenario of ['wrong-decision', 'wrong-publication']) {
  test(`STCN-75/76 ${scenario} cannot confirm another version`, async ({ page }) => {
    actorRoles = ['APPROVER', 'PUBLISHER']; await openReviewVersion(page, scenario === 'wrong-decision' ? 'PENDING_REVIEW' : 'APPROVED')
    labelScenario = scenario
    if (scenario === 'wrong-decision') await page.getByRole('button', { name: 'Approve version', exact: true }).click()
    else { await page.getByRole('checkbox').check(); await page.getByRole('button', { name: 'Publish version', exact: true }).click() }
    await expect(page.getByRole('alert')).toContainText('did not confirm')
    await expect(page.getByRole('heading', { name: /Decision recorded|Publication receipt/ })).toHaveCount(0)
  })
}

test('STCN-77 a label officer cannot see review or publication actions', async ({ page }) => {
  actorRoles = ['LABEL_OFFICER']; await openReviewVersion(page)
  await expect(page.getByRole('button', { name: 'Approve version', exact: true })).toHaveCount(0)
  label.lifecycleStatus = 'APPROVED'; await page.getByRole('button', { name: 'Open label', exact: true }).click()
  await expect(page.getByText('APPROVED', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Publish version', exact: true })).toHaveCount(0)
})

for (const width of [1440, 900, 390]) {
  test(`unified authenticated surfaces remain usable at ${width}px`, async ({ page }) => {
    actorRoles = ['CHANGE_MANAGER', 'LABEL_OFFICER']
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/')
    await expect(page.getByRole('button', { name: 'Continue with Keycloak' })).toBeEnabled()
    await capture(page, `test-results/ui-polish/signin-${width}.png`)
    await page.getByRole('button', { name: 'Continue with Keycloak' }).click()
    await page.getByRole('button', { name: 'Load products' }).click()
    await expect(page.getByText('Organisation product', { exact: true })).toBeVisible()
    for (const view of ['Workspace', 'Labels', 'Impact inbox']) {
      await page.getByRole('button', { name: view, exact: true }).click()
      if (view === 'Labels') await createDraft(page)
      if (view === 'Impact inbox') {
        await page.getByRole('button', { name: 'Refresh inbox', exact: true }).click()
        await page.getByRole('button', { name: 'View finding finding_002', exact: true }).click()
        await expect(page.getByRole('heading', { name: 'Finding finding_002', exact: true })).toBeVisible()
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
      await expect(page.getByRole('button', { name: view, exact: true })).toHaveAttribute('aria-current', 'page')
      await capture(page, `test-results/ui-polish/auth-${view.replaceAll(' ', '-').toLowerCase()}-${width}.png`)
    }
  })
}
