import assert from 'node:assert/strict'
import type { APIRequestContext } from '@playwright/test'

type Target = { site: string; gateway: string; authority: string; callerPath: string; audience: string }

/** Check the existing build, never assume that test-process variables changed its configuration. */
export async function verifyDeployedBuild(request: APIRequestContext, target: Target) {
  const response = await request.get(new URL('g1-release.json', target.site).href, { maxRedirects: 0 })
    .catch(() => { throw new Error('Pages build metadata is unavailable') })
  assert(response.status() === 200, 'Publish the Pages build and its g1-release.json before verification')
  const build = await response.json()
  verifyBuild(build, target)
  const discovery = await request.get(`${target.authority}/.well-known/openid-configuration`, { maxRedirects: 0 })
    .catch(() => { throw new Error('Public OIDC discovery is unavailable') })
  assert(discovery.status() === 200, 'Public OIDC discovery must respond successfully')
  verifyDiscovery(await discovery.json(), target.authority, target.gateway)
  return build.commit as string
}

export function verifyBuild(build: Record<string, unknown>, target: Target) {
  assert(typeof build.commit === 'string', 'Pages build must record its actual source commit')
  assert(/^[a-f0-9]{40}$/.test(build.commit), 'Pages build must record its actual source commit')
  const gateway = build.gatewayOrigin === '/' ? new URL(target.site).origin : build.gatewayOrigin
  assert(gateway === target.gateway && build.authority === target.authority
    && build.callerPath === target.callerPath && build.audience === target.audience
    && build.siteBase === new URL(target.site).pathname, 'Published Pages configuration differs from the test target')
}

export function verifyDiscovery(document: Record<string, unknown>, authority: string, gateway: string) {
  assert(document.issuer === authority, 'Discovery issuer differs from the configured issuer')
  const origins = [new URL(authority).origin, new URL(gateway).origin]
  for (const key of ['authorization_endpoint', 'token_endpoint', 'jwks_uri', 'end_session_endpoint']) {
    assert(typeof document[key] === 'string', 'OIDC metadata is missing a required public endpoint')
    const endpoint = new URL(document[key])
    assert(endpoint.protocol === 'https:' && !endpoint.username && !endpoint.password
      && origins.includes(endpoint.origin), 'OIDC endpoint must use an agreed public HTTPS origin')
  }
  assert(Array.isArray(document.code_challenge_methods_supported)
    && document.code_challenge_methods_supported.includes('S256'), 'OIDC must support PKCE S256')
}

/** Only bounded reads/preflights; no resource mutation, volume reset or load/failure experiment. */
export async function verifyDeployedAccess(request: APIRequestContext,
  input: { gateway: string; site: string; orgId: string; ownProduct: string; foreignProduct: string; callerPath: string },
  authorization: string) {
  const headers = { Authorization: authorization, 'X-Correlation-ID': 'm3-g1-deployed-access', Origin: new URL(input.site).origin }
  const get = (path: string, auth = headers) => request.get(new URL(path, input.gateway).href, { headers: auth, maxRedirects: 0 })
    .catch(() => { throw new Error('Deployed Gateway probe failed; credential details omitted') })
  const own = await get(`/api/formulations/products/${encodeURIComponent(input.ownProduct)}`)
  assert(own.status() === 200, 'The approved manufacturer must be able to read its own test product')
  if (new URL(input.site).origin !== new URL(input.gateway).origin) {
    assert(own.headers()['access-control-allow-origin'] === new URL(input.site).origin,
      'The actual Pages origin must be allowed by deployed CORS')
  }
  const product = await own.json()
  assert(product.productId === input.ownProduct && product.organisationId === input.orgId,
    'Own-product response must match the expected product and caller organisation')
  const foreign = await get(`/api/formulations/products/${encodeURIComponent(input.foreignProduct)}`)
  assert(foreign.status() === 403, 'A foreign-organisation product must return 403')
  const denied = await foreign.json()
  assert(denied.code === 'AUTHORIZATION_DENIED' && !('productId' in denied) && !('items' in denied),
    'A cross-organisation denial must not expose resource content')
  const parts = authorization.substring('Bearer '.length).split('.')
  assert(parts.length === 3 && parts[2].length > 0, 'The authenticated browser must propagate a signed JWT')
  parts[2] = (parts[2].startsWith('A') ? 'B' : 'A') + parts[2].substring(1)
  const tampered = await get(input.callerPath, { ...headers, Authorization: `Bearer ${parts.join('.')}` })
  assert(tampered.status() === 401 && (await tampered.json()).code === 'AUTHENTICATION_REQUIRED',
    'A signature-tampered token must return canonical 401')
  for (const path of ['/auth/admin', '/auth/realms/spectrace/account/', '/internal/validations']) {
    const response = await get(path)
    assert([401, 403, 404].includes(response.status()), 'Private/admin/account routes must remain unavailable externally')
  }
  const cors = await request.fetch(new URL(input.callerPath, input.gateway).href, {
    method: 'OPTIONS', maxRedirects: 0,
    headers: { Origin: 'https://m3-denied-origin.invalid', 'Access-Control-Request-Method': 'GET' },
  }).catch(() => { throw new Error('Deployed CORS probe is unavailable') })
  assert(cors.status() === 403 && !cors.headers()['access-control-allow-origin'], 'An unapproved origin must be denied')
  return { ownProduct: 200, foreignProduct: 403, tamperedToken: 401, privateRoutes: 'denied', pagesOrigin: 'allowed', foreignOrigin: 403 }
}
