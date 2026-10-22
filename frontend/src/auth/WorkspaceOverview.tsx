import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { callerClaimsPath, getSession, matchesCaller, publicApiGet, type CallerIdentity, type Identity } from './session'

export function WorkspaceOverview({ identity, statusOnly = false }: { identity: Identity; statusOnly?: boolean }) {
  const [busy, setBusy] = useState(false), [callerBusy, setCallerBusy] = useState(false)
  const [notice, setNotice] = useState(''), [callerNotice, setCallerNotice] = useState('')
  const [products, setProducts] = useState<Array<{ productId: string; organisationId: string; productDescription: string }> | null>(null)
  const [caller, setCaller] = useState<CallerIdentity | null>(null)
  async function verifyCaller() {
    if (!callerClaimsPath || callerBusy) return
    setCallerBusy(true); setCaller(null); setCallerNotice('')
    try {
      const value = await publicApiGet<CallerIdentity>(callerClaimsPath)
      if (!matchesCaller(value, identity)) throw new Error('The service identity does not match your signed-in organisation.')
      if (getSession().identity === identity) setCaller(value)
    } catch (error) { if (getSession().identity === identity) setCallerNotice(error instanceof Error ? error.message : 'Could not verify your service identity. Try again.') }
    finally { setCallerBusy(false) }
  }
  async function loadProducts() {
    if (busy) return
    setBusy(true); setNotice(''); setProducts(null)
    try {
      const page = await publicApiGet<{ items: Array<{ productId: string; organisationId: string; productDescription: string }>; nextCursor: string | null }>('/api/formulations/products?limit=20')
      if (!Array.isArray(page.items) || page.items.some(p => !p || typeof p.productId !== 'string' || typeof p.productDescription !== 'string' || p.organisationId !== identity.organisationId)) throw new Error('The service returned an unreadable product list.')
      if (getSession().identity === identity) { setProducts(page.items); if (page.nextCursor) setNotice('Showing the first 20 products. Open Products to browse the next page.') }
    } catch (error) { if (getSession().identity === identity) setNotice(error instanceof Error ? error.message : 'Could not load your products. Try again.') }
    finally { setBusy(false) }
  }
  return <section className="gateway-catalog workspace-overview">
    <div className="page-title"><div><h1>{statusOnly ? 'System status' : 'Overview'}</h1><p>Every version connected, from ingredient to label.</p></div></div>
    <section className="panel"><h2>Your organisation’s workspace</h2><p>Signed in as {identity.subject}</p>
      <dl className="gateway-facts"><div><dt>Organisation</dt><dd>{identity.organisationId}</dd></div>
        <div><dt>Organisation type</dt><dd>{identity.organisationType}</dd></div><div><dt>Roles</dt><dd>{identity.roles.length ? identity.roles.join(', ') : 'No assigned roles'}</dd></div></dl>
    </section>
    {!statusOnly && <section className="panel workspace-shortcuts"><h2>Continue your work</h2>
      <p><Link to="/products">Browse your products</Link> and <Link to="/formulas">formula versions</Link>, or inspect <Link to="/materials">materials and specifications</Link>.</p>
      <p><Link to="/impact">Inspect change impact</Link>, <Link to="/labels">prepare labels</Link> and <Link to="/reviews">review a label version</Link>.</p>
    </section>}
    {callerClaimsPath && <section className="panel" aria-labelledby="caller-title"><div className="section-head"><div><h2 id="caller-title">Service identity</h2>
      <p>Check the organisation recognised by your workspace service.</p></div><Button disabled={callerBusy} onClick={() => void verifyCaller()}>{callerBusy ? 'Checking…' : 'Verify service identity'}</Button></div>
      {callerNotice && <p role="alert" className="error-notice">{callerNotice}</p>}{caller && <p role="status">Service identity verified: {caller.org_id} · {caller.org_type}</p>}
      {statusOnly && <p>This verifies the public identity endpoint only. It does not report database, broker or all-service health.</p>}
    </section>}
    {!statusOnly && identity.organisationType === 'MANUFACTURER' && <section className="panel" aria-labelledby="products-title"><div className="section-head"><div><h2 id="products-title">Your products</h2>
      <p>Read products available to your signed-in organisation.</p></div><Button disabled={busy} onClick={() => void loadProducts()}>{busy ? 'Loading…' : 'Load products'}</Button></div>
      {notice && <p role="status">{notice}</p>}{products && (products.length ? <ul className="workspace-products">{products.map(p => <li key={p.productId}><strong>{p.productDescription}</strong><code>{p.productId}</code></li>)}</ul>
        : <p role="status">No products are available to your organisation.</p>)}
    </section>}
  </section>
}
