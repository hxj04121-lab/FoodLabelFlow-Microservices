# Staging frontend container implementation plan

> **For agentic workers:** Use executing-plans inline. User supplied the delivery specification; work on XFY, do not merge PRs or deploy EKS. The private handoff stays outside Git.

**Goal:** Give M5 a tested static frontend image for the same-origin staging root.

**Architecture:** Keep the existing authenticated React application and Pages deployment. Add a separate staging Dockerfile with non-root nginx on 8080. ALB owns `/api` and `/auth`; nginx serves static files and SPA fallback only. Public Vite settings are build-time inputs, never credentials.

**Tech stack:** Existing Node/Vite/React/Playwright; nginx unprivileged image.

**Spec:** User request, 16 October: same-origin `/api`, supplied public issuer, existing registered client, nginx Dockerfile/fallback, exact image/tag/registry or build handoff, executable commands and variable checklist, no hardcoded deployment domain.

## Constraints and review focus

- Preserve existing Pages absolute-origin configuration and local legacy Dockerfile.
- Accept exactly `/` for same-origin Gateway resolution; public API paths already contain `/api`, so never prepend it twice.
- Keep `spectrace-web` and PKCE S256; require exact root login/logout callbacks to be registered by M4.
- Never serve SPA HTML for missing assets, `/api`, or `/auth`; no legacy `backend:8080` staging proxy.
- Exclude local env files, credentials and test evidence from Docker context.
- Distinguish a tested image/publication from actual EKS rollout and full live acceptance.

## Task 1: Root authenticated build

- [ ] Add failing configuration and production-browser cases for root/same-origin setup, metadata, PKCE root callback, Bearer API request and Pages compatibility.
- [ ] Resolve `/` to the browser origin, extend existing build guards/metadata verification and normalize the deployed test target.
- [ ] Run root cases, Pages cases, authenticated regression, deployed-config guards and TypeScript/build.

## Task 2: Container and handoff

- [ ] Add staging Dockerfile/nginx configuration, strengthen dockerignore, and executable HTTP container smoke.
- [ ] Add CI build/smoke and trusted-branch GHCR publication when the actual public issuer variable exists; fixture builds must never be published as staging images.
- [ ] Write exact M5 build/push/save/dist commands, build variables and callback/ALB prerequisites; identify local Docker availability honestly.
- [ ] Obtain one fresh whole-change review, fix important findings, verify, commit/push XFY and update the existing PR scope/evidence. No EKS mutation or PR merge.
