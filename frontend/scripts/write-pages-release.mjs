import assert from 'node:assert/strict'
import { writeFileSync } from 'node:fs'
import './check-pages-config.mjs'

assert(/^[a-f0-9]{40}$/.test(process.env.GITHUB_SHA || ''), 'Record the actual Pages source SHA')
writeFileSync('dist/g1-release.json', JSON.stringify({
  commit: process.env.GITHUB_SHA, gatewayOrigin: process.env.VITE_GATEWAY_ORIGIN,
  authority: process.env.VITE_OIDC_AUTHORITY, audience: process.env.VITE_OIDC_AUDIENCE,
  callerPath: process.env.VITE_CALLER_CLAIMS_PATH, siteBase: process.env.VITE_SITE_BASE,
}, null, 2))
