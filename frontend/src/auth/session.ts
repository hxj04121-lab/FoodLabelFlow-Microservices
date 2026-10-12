import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'

export const authEnabled = import.meta.env.VITE_AUTH_ENABLED === 'true'
type Identity = { subject: string; organisationId: string; organisationType: string; roles: string[] }
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

export async function publicApiGet<T>(path: string, signal?: AbortSignal): Promise<T> {
  if (!user || user.expired || session.status !== 'signedIn') {
    await endSession('Your session expired. Sign in again to continue.')
    throw new Error('Sign-in is required.')
  }
  const url = new URL(path, gateway)
  if (url.origin !== gateway || !path.startsWith('/api/')
    || !/^\/api\/(specifications|formulations|compliance|labels)(\/|$)/.test(url.pathname)
    || /%(2f|5c|2e|25)/i.test(path) || /\/(internal|admin|actuator)(\/|$)/.test(url.pathname)) {
    throw new Error('The requested API route is unavailable.')
  }
  const requestUser = user
  const response = await fetch(url, { signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(15000)]) : AbortSignal.timeout(15000), credentials: 'omit', headers: {
    Authorization: `Bearer ${requestUser.access_token}`, 'X-Correlation-ID': crypto.randomUUID(), Accept: 'application/json',
  } })
  if (user !== requestUser) throw new Error('Your session changed. Load the information again after signing in.')
  if (response.status === 401) {
    await endSession('Your session is no longer accepted. Sign in again to continue.')
    throw new Error('Sign-in is required.')
  }
  if (response.status === 403) throw new Error('You do not have permission to view this information.')
  if (!response.ok) throw new Error('The service is unavailable. Try again later.')
  return response.json() as Promise<T>
}
