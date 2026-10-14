import { test, expect } from '@playwright/test'
import { createServer } from 'node:http'
import { mkdtemp, mkdir, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve, dirname, basename } from 'node:path'
import { spawnSync } from 'node:child_process'
import { verifyBuild, verifyDiscovery, verifyDeployedAccess } from '../real-auth-tests/deployed-checks'

const target = { site: 'https://pages.example.test/Project/', gateway: 'https://gateway.example.test',
  authority: 'https://identity.example.test/auth/realms/spectrace', audience: 'spectrace-api',
  callerPath: '/api/specifications/caller-claims' }

test('the actual Pages metadata writer records only public build inputs and detects stale deployment settings', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'm3-pages-release-'))
  try {
    await mkdir(join(directory, 'dist'))
    const result = spawnSync(process.execPath, [resolve('scripts/write-pages-release.mjs')], {
      cwd: directory, encoding: 'utf8', env: { ...process.env, GITHUB_SHA: 'a'.repeat(40),
        VITE_GATEWAY_ORIGIN: target.gateway, VITE_OIDC_AUTHORITY: target.authority,
        VITE_OIDC_AUDIENCE: target.audience, VITE_CALLER_CLAIMS_PATH: target.callerPath,
        VITE_SITE_BASE: '/Project/', M3_TEST_PASSWORD: 'fixture-secret-not-for-metadata' },
    })
    expect(result.status).toBe(0)
    const text = await readFile(join(directory, 'dist/g1-release.json'), 'utf8')
    expect(text).not.toContain('fixture-secret-not-for-metadata')
    const build = JSON.parse(text)
    expect(() => verifyBuild(build, target)).not.toThrow()
    expect(() => verifyBuild({ ...build, gatewayOrigin: 'https://old-gateway.example.test' }, target)).toThrow()
    expect(() => verifyBuild({ ...build, commit: 'unknown' }, target)).toThrow()
  } finally {
    if (dirname(resolve(directory)) !== resolve(tmpdir()) || !basename(directory).startsWith('m3-pages-release-')) {
      throw new Error('Temporary metadata cleanup target is outside its task directory')
    }
    await rm(directory, { recursive: true, force: true })
  }
})

test('OIDC preflight rejects issuer, private endpoint and PKCE mismatches before credentials are entered', () => {
  const metadata = { issuer: target.authority, code_challenge_methods_supported: ['S256'],
    authorization_endpoint: `${target.authority}/protocol/openid-connect/auth`,
    token_endpoint: `${target.authority}/protocol/openid-connect/token`,
    jwks_uri: `${target.authority}/protocol/openid-connect/certs`,
    end_session_endpoint: `${target.authority}/protocol/openid-connect/logout` }
  expect(() => verifyDiscovery(metadata, target.authority, target.gateway)).not.toThrow()
  for (const patch of [{ issuer: 'https://other.example.test/realm' },
    { token_endpoint: 'http://keycloak:8080/token' }, { jwks_uri: 'https://foreign.example.test/keys' },
    { code_challenge_methods_supported: ['plain'] }]) {
    expect(() => verifyDiscovery({ ...metadata, ...patch }, target.authority, target.gateway)).toThrow()
  }
})

test('real HTTP access probes accept correct isolation and reject an exposed foreign product', async ({ request }) => {
  let leak = false
  const token = 'Bearer header.payload.signature'
  const server = createServer((req, res) => {
    if (req.method === 'OPTIONS') { res.writeHead(403); res.end(); return }
    if (req.headers.origin === new URL(target.site).origin) res.setHeader('Access-Control-Allow-Origin', req.headers.origin)
    res.setHeader('Content-Type', 'application/json')
    if (req.url === '/api/formulations/products/own') { res.end(JSON.stringify({ productId: 'own', organisationId: 'org-own' })); return }
    if (req.url === '/api/formulations/products/foreign') {
      res.writeHead(leak ? 200 : 403); res.end(JSON.stringify(leak ? { productId: 'foreign' } : { code: 'AUTHORIZATION_DENIED' })); return
    }
    if (req.url === target.callerPath && req.headers.authorization !== token) {
      res.writeHead(401); res.end(JSON.stringify({ code: 'AUTHENTICATION_REQUIRED' })); return
    }
    res.writeHead(404); res.end('{}')
  })
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    const address = server.address() as { port: number }
    const input = { gateway: `http://127.0.0.1:${address.port}`, site: target.site,
      orgId: 'org-own', ownProduct: 'own', foreignProduct: 'foreign', callerPath: target.callerPath }
    const checks = await verifyDeployedAccess(request, input, token)
    expect(checks.foreignProduct).toBe(403)
    expect(checks.tamperedToken).toBe(401)
    leak = true
    await expect(verifyDeployedAccess(request, input, token)).rejects.toThrow('foreign-organisation product must return 403')
  } finally { await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve())) }
})
