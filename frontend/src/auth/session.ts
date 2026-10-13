import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'

export const authEnabled = import.meta.env.VITE_AUTH_ENABLED === 'true'
export const callerClaimsPath = import.meta.env.VITE_CALLER_CLAIMS_PATH?.trim()
export type CallerIdentity = { subject: string; org_id: string; org_type: string; roles: string[] }
export type Identity = { subject: string; organisationId: string; organisationType: string; roles: string[] }
export function matchesCaller(value: CallerIdentity, identity: Identity | undefined): boolean {
  if (!identity || !value || value.subject !== identity.subject || value.org_id !== identity.organisationId
    || value.org_type !== identity.organisationType || !Array.isArray(value.roles)
    || value.roles.some(r => typeof r !== 'string')) return false
  const actual = new Set(value.roles)
  const expected = new Set(identity.roles)
  return actual.size === expected.size && [...actual].every(role => expected.has(role))
}
type Session = { status: 'loading' | 'signedOut' | 'signedIn' | 'error'; identity?: Identity; message?: string }
let session: Session = { status: 'loading' }
let manager: UserManager | undefined
let user: User | undefined
let gateway = ''
let audience = ''
let authority = ''
let startup: Promise<void> | undefined
const listeners = new Set<() => void>()
export const getSession = () => session
export const canSignIn = () => !!manager
export const subscribeSession = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener) } }
function publish(next: Session) { session = next; listeners.forEach(fn => fn()) }

function secureUrl(value: string | undefined): URL {
  if (!value) throw new Error('Sign-in is not configured. Ask the workspace administrator to configure the identity provider and gateway.')
  const url = new URL(value)
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
  if ((url.protocol !== 'https:' && !(local && url.protocol === 'http:')) || url.username || url.password || url.search || url.hash) {
    throw new Error('Sign-in configuration is invalid. Ask the workspace administrator to check the HTTPS addresses.')
  }
  return url
}

function createManager() {
  authority = secureUrl(import.meta.env.VITE_OIDC_AUTHORITY).href.replace(/\/$/, '')
  const api = secureUrl(import.meta.env.VITE_GATEWAY_ORIGIN)
  if (api.pathname !== '/') throw new Error('The gateway address must be an origin without a path.')
  gateway = api.origin
  audience = import.meta.env.VITE_OIDC_AUDIENCE ?? ''
  if (!audience.trim()) throw new Error('Sign-in is not configured. Ask the workspace administrator to configure the token audience.')
  const callback = new URL(import.meta.env.BASE_URL, window.location.origin).href
  manager = new UserManager({
    authority, client_id: 'spectrace-web', redirect_uri: callback, post_logout_redirect_uri: callback,
    response_type: 'code', response_mode: 'query', scope: 'openid',
    automaticSilentRenew: false, monitorSession: false, loadUserInfo: false,
    fetchRequestCredentials: 'omit', requestTimeoutInSeconds: 15,
    // Tokens live only in memory. Only the temporary PKCE transaction survives the redirect.
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    stateStore: new WebStorageStateStore({ prefix: 'spectrace.pkce.', store: window.sessionStorage }),
    staleStateAgeInSeconds: 300,
  })
  manager.events.addAccessTokenExpired(() => void endSession('Your session expired. Sign in again to continue.'))
  return manager
}

function accept(result: User) {
  const part = result.access_token.split('.')[1]
  if (!part || result.expired || result.token_type.toLowerCase() !== 'bearer') throw new Error('INVALID_TOKEN')
  const bytes = Uint8Array.from(atob(part.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0))
  const claims = JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>
  const aud = claims.aud
  if (claims.iss !== authority || !(aud === audience || (Array.isArray(aud) && aud.includes(audience)))
    || typeof claims.exp !== 'number' || claims.exp * 1000 <= Date.now()
    || (typeof claims.nbf === 'number' && claims.nbf * 1000 > Date.now())
    || typeof claims.sub !== 'string' || !claims.sub.trim() || claims.sub !== result.profile.sub
    || typeof claims.org_id !== 'string' || !/^\S{1,128}$/.test(claims.org_id)
    || typeof claims.org_type !== 'string' || !['SUPPLIER', 'MANUFACTURER'].includes(claims.org_type)
    || (claims.roles !== undefined && (!Array.isArray(claims.roles) || claims.roles.length > 100
    || claims.roles.some(r => typeof r !== 'string' || !r.trim() || r.length > 128)))) throw new Error('INVALID_TOKEN')
  // These claims drive presentation; the Gateway and every service independently verify the JWT.
  user = result
  publish({ status: 'signedIn', identity: {
    subject: claims.sub, organisationId: claims.org_id, organisationType: String(claims.org_type), roles: (claims.roles ?? []) as string[],
  } })
}

export function startSession(): Promise<void> {
  if (startup) return startup
  startup = (async () => {
    const params = new URLSearchParams(window.location.search)
    const callback = ['code', 'state', 'error'].some(k => params.has(k))
    try {
      const m = createManager()
      if (callback) {
        accept(await m.signinRedirectCallback())
      } else {
        await m.clearStaleState()
        publish({ status: 'signedOut' })
      }
    } catch (error) {
      user = undefined
      await manager?.removeUser()
      publish({ status: 'error', message: !manager && error instanceof Error ? error.message :
        'Sign-in could not be verified. Try again; if the problem continues, contact your workspace administrator.' })
    } finally {
      if (callback) {
        window.history.replaceState(null, '', new URL(import.meta.env.BASE_URL, window.location.origin).pathname)
        const store = manager?.settings.stateStore
        if (store) for (const key of await store.getAllKeys()) await store.remove(key)
      }
    }
  })()
  return startup
}

export async function signIn() {
  if (!manager || session.status === 'loading') return
  publish({ status: 'loading' })
  try { await manager.signinRedirect({ nonce: crypto.randomUUID() }) }
  catch { publish({ status: 'error', message: 'The sign-in service is unavailable. Please try again.' }) }
}

async function endSession(message?: string) {
  user = undefined
  await manager?.removeUser()
  publish({ status: 'signedOut', message })
}

export async function signOut() {
  const hint = user?.id_token
  await endSession()
  try { await manager?.signoutRedirect({ id_token_hint: hint }) }
  catch { publish({ status: 'signedOut', message: 'This workspace session ended. The identity provider could not be reached; close its session before switching accounts.' }) }
}

export class PublicApiError extends Error {
  constructor(message: string, public status: number, public code: string,
    public traceId: string | null = null, public outcomeUnknown = false) { super(message) }
}

export const publicApiGet = <T>(path: string, signal?: AbortSignal) => publicApiRequest<T>('GET', path, undefined, signal)
export const publicApiPost = <T>(path: string, body?: unknown, signal?: AbortSignal) => publicApiRequest<T>('POST', path, body, signal)
export const publicApiPut = <T>(path: string, body: unknown, signal?: AbortSignal) => publicApiRequest<T>('PUT', path, body, signal)

async function publicApiRequest<T>(method: 'GET' | 'POST' | 'PUT', path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const isWrite = method !== 'GET'
  if (!user || user.expired || session.status !== 'signedIn') {
    await endSession('Your session expired. Sign in again to continue.')
    throw new Error('Sign-in is required.')
  }
  const url = new URL(path, gateway)
  const rawPath = path.split('?')[0]
  if (url.origin !== gateway || !path.startsWith('/api/')
    || !/^\/api\/(specifications|formulations|compliance|labels)(\/|$)/.test(url.pathname)
    || url.hash || /%(2f|5c|2e|25|3b)/i.test(rawPath) || /[\\;]/.test(rawPath) || rawPath.includes('//')
    || /(?:^|\/)\.{1,2}(?:\/|$)/.test(rawPath) || /\/(internal|admin|actuator)(\/|$)/.test(url.pathname)) {
    throw new Error('The requested API route is unavailable.')
  }
  const requestUser = user
  const correlationId = crypto.randomUUID()
  let response: Response
  try {
    response = await fetch(url, { method, body: body === undefined ? undefined : JSON.stringify(body),
      signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(15000)]) : AbortSignal.timeout(15000),
      credentials: 'omit', redirect: 'error', headers: {
        Authorization: `Bearer ${requestUser.access_token}`, 'X-Correlation-ID': correlationId, Accept: 'application/json',
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      } })
  } catch {
    throw new PublicApiError(isWrite
      ? 'The result could not be confirmed. Check the label before trying again.'
      : 'The service could not be reached. Try again.', 0, 'NETWORK_ERROR', correlationId, isWrite)
  }
  if (user !== requestUser) throw new Error('Your session changed. Load the information again after signing in.')
  if (response.status === 401) {
    await endSession('Your session is no longer accepted. Sign in again to continue.')
    throw new Error('Sign-in is required.')
  }
  const value = await response.json().catch(() => null)
  if (user !== requestUser) throw new Error('Your session changed. Load the information again after signing in.')
  if (!response.ok) {
    const messages: Record<number, string> = {
      400: 'Some information was not accepted. Check your entries.',
      403: 'You do not have permission to perform this action or view this information.',
      404: 'This label or service could not be found. Check the ID or contact your workspace administrator.',
      409: 'The label has changed or is not ready for this action. Open it again before continuing.',
      422: 'Required validation information is unavailable. Contact your workspace administrator.',
      429: 'Too many requests. Wait before trying again.',
    }
    const code = typeof value?.code === 'string' ? value.code : 'HTTP_ERROR'
    const traceId = response.headers.get('X-Correlation-ID') || correlationId
    throw new PublicApiError(code === 'LABEL_VALIDATION_REQUIRED' ? 'Validate this draft again before submitting it.'
      : messages[response.status] || 'The service is unavailable. Check the label before trying again.',
    response.status, code, /^[A-Za-z0-9._:-]{1,128}$/.test(traceId) ? traceId : correlationId,
    isWrite && response.status >= 500)
  }
  if (value === null) throw new PublicApiError('The service returned an unreadable result. Open the label again before continuing.',
    response.status, 'INVALID_RESPONSE', correlationId, isWrite)
  return value as T
}
