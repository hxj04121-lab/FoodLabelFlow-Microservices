import { useEffect, useState, useSyncExternalStore } from 'react'
import { Layers3 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { LabelWorkspace } from '@/features/labels/LabelWorkspace'
import { ImpactInbox } from '@/features/impact/ImpactInbox'
import { callerClaimsPath, canSignIn, getSession, matchesCaller, publicApiGet, signIn, signOut, startSession, subscribeSession, type CallerIdentity } from './session'

export function SignInWorkspace() {
  const session = useSyncExternalStore(subscribeSession, getSession)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  const [products, setProducts] = useState<Array<{ productId: string; organisationId: string; productDescription: string }> | null>(null)
  const [caller, setCaller] = useState<CallerIdentity | null>(null)
  const [callerNotice, setCallerNotice] = useState('')
  const [callerBusy, setCallerBusy] = useState(false)
  const [view, setView] = useState<'workspace' | 'labels' | 'impact'>('workspace')
  const [linkedLabel, setLinkedLabel] = useState('')
  useEffect(() => { void startSession() }, [])
  useEffect(() => { setProducts(null); setNotice(''); setCaller(null); setCallerNotice(''); setView('workspace'); setLinkedLabel('') }, [session.status, session.identity])
  async function verifyCaller() {
    if (!callerClaimsPath) return
    const identity = getSession().identity
    setCallerBusy(true); setCaller(null); setCallerNotice('')
    try {
      const value = await publicApiGet<CallerIdentity>(callerClaimsPath)
      if (!matchesCaller(value, identity)) throw new Error('The service identity does not match your signed-in organisation.')
      if (getSession().identity === identity) setCaller(value)
    } catch (error) { setCallerNotice(error instanceof Error ? error.message : 'Could not verify your service identity. Try again.') }
    finally { setCallerBusy(false) }
  }
  async function loadProducts() {
    const identity = getSession().identity
    setBusy(true); setNotice(''); setProducts(null)
    try {
      const page = await publicApiGet<{ items: Array<{ productId: string; organisationId: string; productDescription: string }>; nextCursor: string | null }>('/api/formulations/products?limit=20')
      if (!Array.isArray(page.items) || page.items.some(p => !p || typeof p.productId !== 'string' || typeof p.productDescription !== 'string' || p.organisationId !== identity?.organisationId)) throw new Error('The service returned an unreadable product list.')
      if (getSession().identity === identity) {
        setProducts(page.items)
        if (page.nextCursor) setNotice('Showing the first 20 products.')
      }
    } catch (error) { setNotice(error instanceof Error ? error.message : 'Could not load your products. Try again.') }
    finally { setBusy(false) }
  }
  return <div className="signin-workspace">
    <header className="signin-brand"><Layers3 aria-hidden="true" size={25} /><strong>SpecTrace</strong><span>Food label workspace</span></header>
    <main className="signin-content">
      {session.status !== 'signedIn' ? <section className="signin-entry" aria-labelledby="signin-title">
        <h1 id="signin-title">Sign in to your organisation</h1>
        <p>Use your team account to access your organisation’s food label workspace.</p>
        {session.status === 'loading' ? <p role="status">Connecting to the sign-in service…</p> : <>
          {session.message && <p role="alert" className="signin-notice">{session.message}</p>}
          <Button size="lg" disabled={!canSignIn()} onClick={() => void signIn()}>Continue with Keycloak</Button>
        </>}
      </section> : <>
        <div className="signin-heading"><div><h1>Your organisation’s workspace</h1><p>Signed in as {session.identity?.subject}</p></div><Button variant="outline" onClick={() => void signOut()}>Sign out</Button></div>
        <nav className="workspace-navigation" aria-label="Workspace sections">
          <Button variant="ghost" aria-current={view === 'workspace' ? 'page' : undefined} onClick={() => setView('workspace')}>Workspace</Button>
          <Button variant="ghost" aria-current={view === 'labels' ? 'page' : undefined} onClick={() => setView('labels')}>Labels</Button>
          <Button variant="ghost" aria-current={view === 'impact' ? 'page' : undefined} onClick={() => setView('impact')}>Impact inbox</Button>
        </nav>
        {view === 'impact' && session.identity ? <ImpactInbox key={`${session.identity.subject}:${session.identity.organisationId}`} identity={session.identity}
          openLabel={id => { setLinkedLabel(id); setView('labels') }} />
          : view === 'labels' && session.identity ? <LabelWorkspace key={`${session.identity.subject}:${session.identity.organisationId}:${linkedLabel}`} identity={session.identity} initialLabelId={linkedLabel} /> : <>
        <section className="signin-identity" aria-label="Signed-in identity"><dl>
          <div><dt>Organisation</dt><dd>{session.identity?.organisationId}</dd></div>
          <div><dt>Organisation type</dt><dd>{session.identity?.organisationType}</dd></div>
          <div><dt>Roles</dt><dd>{session.identity?.roles.length ? session.identity.roles.join(', ') : 'No assigned roles'}</dd></div>
        </dl></section>
        {callerClaimsPath && <section className="signin-products" aria-labelledby="caller-title">
          <div className="signin-heading"><div><h2 id="caller-title">Service identity</h2><p>Check the organisation recognised by your workspace service.</p></div><Button disabled={callerBusy} onClick={() => void verifyCaller()}>{callerBusy ? 'Checking…' : 'Verify service identity'}</Button></div>
          {callerNotice && <p role="alert" className="signin-notice">{callerNotice}</p>}
          {caller && <p role="status">Service identity verified: {caller.org_id} · {caller.org_type}</p>}
        </section>}
        {session.identity?.organisationType === 'MANUFACTURER' && <section className="signin-products" aria-labelledby="products-title">
          <div className="signin-heading"><div><h2 id="products-title">Your products</h2><p>Read products available to your signed-in organisation.</p></div><Button disabled={busy} onClick={() => void loadProducts()}>{busy ? 'Loading…' : 'Load products'}</Button></div>
          {notice && <p role="status" className="signin-notice">{notice}</p>}
          {products && (products.length ? <ul>{products.map(p => <li key={p.productId}><strong>{p.productDescription}</strong><span>{p.productId}</span></li>)}</ul> : <p role="status">No products are available to your organisation.</p>)}
        </section>}
        </>}
      </>}
    </main>
  </div>
}
