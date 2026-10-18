import { test, expect } from '@playwright/test'
import { createHash, generateKeyPairSync, sign } from 'node:crypto'
import { verifyBuild } from '../real-auth-tests/deployed-checks'

const issuer = 'https://identity.example.test/auth/realms/spectrace'
const callerPath = '/api/specifications/caller-claims'
const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
function jwt(payload: object) {
  const input = `${Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url')}.${Buffer.from(JSON.stringify(payload)).toString('base64url')}`
  return `${input}.${sign('RSA-SHA256', Buffer.from(input), privateKey).toString('base64url')}`
}

test('optional font CSS cannot block the sign-in entry when the font host hangs', async ({ page }) => {
  let requested = false
  await page.route('https://fonts.googleapis.com/**', () => { requested = true })
  // Leave the font request pending: authentication must load without its response.
  await page.goto('/', { timeout: 5000 })
  await expect(page.getByRole('button', { name: 'Continue with Keycloak', exact: true })).toBeEnabled()
  await expect.poll(() => requested).toBe(true)
})

test('root metadata resolves same-origin and rejects a foreign Gateway target', async ({ request, baseURL }) => {
  const response = await request.get('g1-release.json')
  expect(response.status()).toBe(200)
  const build = await response.json()
  const target = { site: baseURL!, gateway: new URL(baseURL!).origin, authority: issuer, audience: 'spectrace-api', callerPath }
  expect(build.gatewayOrigin).toBe('/')
  expect(build.siteBase).toBe('/')
  expect(() => verifyBuild(build, target)).not.toThrow()
  expect(() => verifyBuild(build, { ...target, gateway: 'https://foreign.example.test' })).toThrow()
})

test('root SPA reload, PKCE callback and authenticated /api use the same browser origin', async ({ page, baseURL }) => {
  let nonce = '', challenge = '', redirect = '', accessToken = '', apiUrl = '', bearer = ''
  const identity = { subject: 'container-fixture-user', org_id: 'org-fixture', org_type: 'MANUFACTURER', roles: ['LABEL_OFFICER'] }
  await page.route(`${issuer}/**`, async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('openid-configuration')) {
      await route.fulfill({ json: { issuer, authorization_endpoint: `${issuer}/authorize`, token_endpoint: `${issuer}/token`,
        jwks_uri: `${issuer}/certs`, end_session_endpoint: `${issuer}/logout`, response_types_supported: ['code'],
        subject_types_supported: ['public'], id_token_signing_alg_values_supported: ['RS256'] } })
    } else if (url.pathname.endsWith('/authorize')) {
      expect(url.searchParams.get('client_id')).toBe('spectrace-web')
      expect(url.searchParams.get('code_challenge_method')).toBe('S256')
      redirect = url.searchParams.get('redirect_uri')!
      expect(redirect).toBe(baseURL)
      nonce = url.searchParams.get('nonce')!; challenge = url.searchParams.get('code_challenge')!
      const callback = new URL(redirect)
      callback.searchParams.set('code', 'fixture-code'); callback.searchParams.set('state', url.searchParams.get('state')!)
      await route.fulfill({ status: 302, headers: { Location: callback.href } })
    } else if (url.pathname.endsWith('/token')) {
      const form = new URLSearchParams(route.request().postData()!)
      expect(form.get('redirect_uri')).toBe(redirect)
      expect(createHash('sha256').update(form.get('code_verifier')!).digest('base64url')).toBe(challenge)
      const now = Math.floor(Date.now() / 1000)
      accessToken = jwt({ ...identity, sub: identity.subject, iss: issuer, aud: 'spectrace-api', iat: now, exp: now + 120 })
      await route.fulfill({ json: { access_token: accessToken, token_type: 'Bearer', expires_in: 120,
        id_token: jwt({ sub: identity.subject, iss: issuer, aud: 'spectrace-web', nonce, iat: now, exp: now + 120 }) } })
    } else await route.abort()
  })
  await page.route(new URL(callerPath, baseURL).href, async route => {
    apiUrl = route.request().url(); bearer = route.request().headers().authorization
    await route.fulfill({ json: identity })
  })
  await page.goto('./labels')
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Sign in to your organisation', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Continue with Keycloak', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: 'Continue with Keycloak', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Your organisation’s workspace', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Verify service identity', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('Service identity verified: org-fixture')
  expect(apiUrl).toBe(new URL(callerPath, baseURL).href)
  expect(bearer === `Bearer ${accessToken}`).toBe(true)
  expect(new URL(page.url()).search).toBe('')
})
