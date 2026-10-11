import assert from 'node:assert/strict'

for (const key of ['VITE_OIDC_AUTHORITY', 'VITE_GATEWAY_ORIGIN']) {
  assert(process.env[key], `${key} must be configured before Pages deployment`)
  const url = new URL(process.env[key])
  assert(url.protocol === 'https:' && !url.username && !url.password && !url.search && !url.hash, `${key} must be a public HTTPS URL`)
  if (key === 'VITE_GATEWAY_ORIGIN') assert(url.pathname === '/', 'Gateway must be an origin without a path')
}
assert(process.env.VITE_OIDC_AUDIENCE?.trim(), 'VITE_OIDC_AUDIENCE must be configured')
assert(/^\/[A-Za-z0-9._-]+\/$/.test(process.env.VITE_SITE_BASE || ''), 'Pages requires the exact repository base path')
console.log('Pages public configuration is present; runtime/realm verification remains a deployment check.')
