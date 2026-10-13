import { test, expect, type Page } from '@playwright/test'
import { createServer, type Server } from 'node:http'
import { createHash, generateKeyPairSync, sign, randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'

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
let labelRequests: Array<{ method: string; path: string; body: unknown; authenticated: boolean; correlation: string }> = []
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
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
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
    } else if (url.pathname.startsWith('/api/labels/')) {
      let text = ''; for await (const chunk of req) text += chunk
      labelRequests.push({ method: req.method!, path: url.pathname, body: text ? JSON.parse(text) : null,
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
      } else if (url.pathname === '/api/labels/drafts' && req.method === 'POST') {
        labelCreator = actorSubject
        json(labelScenario === 'malformed-label' ? { ...label, versionNumber: 0 } : label, 201)
      } else if (url.pathname === '/api/labels/label-one' && req.method === 'GET') {
        if (labelScenario === 'decision-refresh-error' && decisionRecorded) json({ code: 'SERVICE_UNAVAILABLE' }, 503)
        else json(labelScenario === 'wrong-label' ? { ...label, labelVersionId: 'another-label' } : label)
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
          lifecycleStatus: 'PUBLISHED', publishedAt: '2026-10-13T05:01:00Z' }) }
      } else json({ code: 'LABEL_NOT_FOUND' }, 404)
    } else if (url.pathname.endsWith('/logout')) {
      res.writeHead(302, { Location: url.searchParams.get('post_logout_redirect_uri')! }); res.end()
    } else json({}, 404)
  })
  await new Promise<void>(resolve => server.listen(8191, '127.0.0.1', resolve))
})
test.afterAll(async () => { await new Promise<void>(resolve => server.close(() => resolve())) })
test.beforeEach(() => { mode = 'ok'; apiStatus = 200; observedAuthorization = ''; exchanges = 0; verifiedPkce = false; codes.clear()
  actorRoles = ['LABEL_OFFICER', 'APPROVER']; actorType = 'MANUFACTURER'; actorSubject = 'test-user'; labelCreator = 'original-maker'; decisionRecorded = false; labelScenario = 'ok'; labelDelay = 0; label = freshLabel(); labelRequests = [] })
async function login(page: Page) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Continue with Keycloak', exact: true }).click()
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
  await page.screenshot({ path: 'test-results/signin/desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: 'test-results/signin/mobile.png', fullPage: true })
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
async function createDraft(page: Page) {
  await page.getByLabel('Product ID', { exact: true }).fill('product-one')
  await page.getByLabel('Jurisdiction code').fill('SG')
  await page.getByRole('button', { name: 'Create draft', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Label version 1', exact: true })).toBeVisible()
}

test('STCN-74 creates, validates and submits an exact label through authenticated public POSTs', async ({ page }) => {
  await labels(page); await createDraft(page)
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeDisabled()
  await page.getByRole('button', { name: 'Validate draft', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Validation passed', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Submit for review' })).toBeEnabled()
  await page.screenshot({ path: 'test-results/signin/labels-flow-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: 'test-results/signin/labels-flow-mobile.png', fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.getByRole('button', { name: 'Submit for review' }).click()
  await expect(page.getByRole('status')).toContainText('Submitted for review')
  await expect(page.getByText('PENDING REVIEW', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Submit for review' })).toHaveCount(0)
  expect(labelRequests.map(request => [request.method, request.path, request.body])).toEqual([
    ['POST', '/api/labels/drafts', { productId: 'product-one', jurisdictionCode: 'SG' }],
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
  expect(labelRequests).toHaveLength(1)
})

test('STCN-77 suppliers cannot gain label writes from a label-officer role', async ({ page }) => {
  actorType = 'SUPPLIER'; await labels(page)
  await expect(page.getByText('Open an existing version', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Create draft', exact: true })).toHaveCount(0)
  expect(labelRequests).toHaveLength(0)
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
    await page.getByLabel('Product ID', { exact: true }).fill('product-one'); await page.getByLabel('Jurisdiction code').fill('SG')
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
  await page.getByLabel('Product ID', { exact: true }).fill('product-one'); await page.getByLabel('Jurisdiction code').fill('SG')
  await page.getByRole('button', { name: 'Create draft', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('unreadable label')
  await expect(page.getByRole('button', { name: 'Create draft', exact: true })).toBeDisabled()
  expect(labelRequests).toHaveLength(1)
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
  await page.getByLabel('Product ID', { exact: true }).fill('product-one'); await page.getByLabel('Jurisdiction code').fill('SG')
  await page.getByRole('button', { name: 'Create draft', exact: true }).dblclick()
  await expect(page.getByRole('heading', { name: 'Label version 1', exact: true })).toBeVisible()
  expect(labelRequests).toHaveLength(1)
  await page.getByRole('button', { name: 'Validate draft', exact: true }).click()
  await page.getByRole('button', { name: 'Workspace', exact: true }).click()
  await page.getByRole('button', { name: 'Labels', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Label version 1', exact: true })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Validation passed', exact: true })).toHaveCount(0)
})

test('STCN-77 a write 401 clears the identity and label screen', async ({ page }) => {
  await labels(page); labelScenario = 'error-401'
  await page.getByLabel('Product ID', { exact: true }).fill('product-one'); await page.getByLabel('Jurisdiction code').fill('SG')
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
      const tracked = window as Window & { labelPostAttempts: number }
      tracked.labelPostAttempts = 0
      const send = window.fetch.bind(window)
      window.fetch = (input, init) => { if (init?.method === 'POST') tracked.labelPostAttempts++; return send(input, init) }
    })
    await page.getByLabel('Product ID', { exact: true }).fill('product-one'); await page.getByLabel('Jurisdiction code').fill('SG')
    await page.getByRole('button', { name: 'Create draft', exact: true }).click()
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Create draft', exact: true })).toBeDisabled()
    await expect(page.getByRole('heading', { name: 'Label version 1', exact: true })).toHaveCount(0)
    expect(await page.evaluate(() => (window as Window & { labelPostAttempts: number }).labelPostAttempts)).toBe(1)
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
  await page.screenshot({ path: 'test-results/signin/labels-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: 'test-results/signin/labels-mobile.png', fullPage: true })
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
  await page.screenshot({ path: 'test-results/signin/labels-review-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: 'test-results/signin/labels-review-mobile.png', fullPage: true })
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
  await page.screenshot({ path: 'test-results/signin/labels-published-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: 'test-results/signin/labels-published-mobile.png', fullPage: true })
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
