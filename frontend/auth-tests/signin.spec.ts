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
      const id = jwt({ iss: issuer, aud: 'spectrace-web', sub: 'test-user', iat: now, exp: now + 120,
        nonce: mode === 'bad-nonce' ? 'foreign-nonce' : stored.nonce })
      sentToken = jwt({ iss: issuer, aud: mode === 'bad-audience' ? 'another-api' : 'spectrace-api-test', sub: 'test-user',
        iat: now, exp: now + 120, org_id: mode === 'multi-org' ? ['org-one', 'org-two'] : 'org-one',
        org_type: mode === 'array-org-type' ? ['MANUFACTURER'] : 'MANUFACTURER',
        ...(mode === 'roleless' ? {} : { roles: ['LABEL_OFFICER', 'APPROVER'] }) })
      json({ access_token: sentToken, token_type: 'Bearer', id_token: id, expires_in: mode === 'expires' ? 2 : 120 })
    } else if (url.pathname === '/api/labels/whoami') {
      const roles = mode === 'caller-role-mismatch' ? ['LABEL_OFFICER']
        : mode === 'caller-role-delimiter' ? ['APPROVER\nLABEL_OFFICER'] : ['APPROVER', 'LABEL_OFFICER']
      json({ subject: 'test-user', org_id: mode === 'foreign-caller' ? 'another-org' : 'org-one', org_type: 'MANUFACTURER', roles })
    } else if (url.pathname === '/api/formulations/products') {
      observedAuthorization = req.headers.authorization || ''
      expect(req.headers['x-auth-provider']).toBeUndefined()
      expect(req.headers['x-external-subject']).toBeUndefined()
      expect(req.headers['x-correlation-id']).toBeTruthy()
      json(apiStatus === 200 ? { items: [{ productId: 'product-one', organisationId: mode === 'foreign-product' ? 'another-org' : 'org-one',
        productDescription: 'Organisation product', brandOwner: 'Test manufacturer', normalizedCategory: 'TEST', currentFormulaVersion: null }], nextCursor: null } : {}, apiStatus)
    } else if (url.pathname.endsWith('/logout')) {
      res.writeHead(302, { Location: url.searchParams.get('post_logout_redirect_uri')! }); res.end()
    } else json({}, 404)
  })
  await new Promise<void>(resolve => server.listen(8191, '127.0.0.1', resolve))
})
test.afterAll(async () => { await new Promise<void>(resolve => server.close(() => resolve())) })
test.beforeEach(() => { mode = 'ok'; apiStatus = 200; observedAuthorization = ''; exchanges = 0; verifiedPkce = false; codes.clear() })
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
test('does not verify a service caller from another organisation', async ({ page }) => {
  await login(page); await expect(page.getByRole('button', { name: 'Verify service identity' })).toBeVisible()
  mode = 'foreign-caller'; await page.getByRole('button', { name: 'Verify service identity' }).click()
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
