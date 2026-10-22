import { useEffect, useSyncExternalStore } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Layers3, ShieldCheck } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Shell } from '@/app/Shell'
import { canSignIn, getSession, signIn, startSession, subscribeSession } from './session'

export function SignInWorkspace() {
  const session = useSyncExternalStore(subscribeSession, getSession)
  const location = useLocation(), navigate = useNavigate()
  useEffect(() => { void startSession() }, [])
  useEffect(() => { if (session.status === 'signedIn' && session.returnTo) void navigate(session.returnTo, { replace: true }) }, [session.status, session.returnTo, navigate])
  if (session.status === 'signedIn' && session.identity) return <Shell key={`${session.identity.subject}:${session.identity.organisationId}`} identity={session.identity} />
  return <div className="signin-workspace"><a className="skip-link" href="#auth-main">Skip to main content</a>
    <header className="signin-brand"><div className="wordmark"><span className="logo"><Layers3 aria-hidden="true" size={23} /></span><strong>SpecTrace</strong><span className="workspace-label">Food label workspace</span></div></header>
    <main className="signin-content" id="auth-main" tabIndex={-1}><section className="signin-entry" aria-labelledby="signin-title">
      <div className="signin-entry-symbol" aria-hidden="true"><ShieldCheck size={28} /></div><h1 id="signin-title">Sign in to your organisation</h1>
      <p>Use your team account to access your organisation’s food label workspace.</p>
      {session.status === 'loading' ? <p role="status">Connecting to the sign-in service…</p> : <>
        {session.message && <p role="alert" className="signin-notice">{session.message}</p>}
        <Button size="lg" disabled={!canSignIn()} onClick={() => void signIn(location.pathname + location.search)}>Continue with Keycloak</Button>
      </>}
    </section></main>
  </div>
}
