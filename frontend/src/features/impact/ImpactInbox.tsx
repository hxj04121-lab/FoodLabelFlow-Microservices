import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Button } from '@/components/ui/button'
import { getSession, publicApiGet, PublicApiError, type Identity } from '@/auth/session'
import { canReadImpact, findingPath, findingsPath, readFinding, readFindings, type ImpactFilters,
  type ImpactFinding, type ImpactPage, type VersionReference } from './impactFindings'

export function ImpactInbox({ identity, openLabel }: { identity: Identity; openLabel: (id: string) => void }) {
  const [filters, setFilters] = useState<ImpactFilters>({ kind: '', outcome: '' })
  const [page, setPage] = useState<ImpactPage | null>(null)
  const [finding, setFinding] = useState<ImpactFinding | null>(null)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [trace, setTrace] = useState<string | null>(null)
  const [version, setVersion] = useState<{ title: string; reference: VersionReference; status: string } | null>(null)
  const pending = useRef<AbortController | null>(null)
  const cursors = useRef(new Set<string>())
  const heading = useRef<HTMLHeadingElement>(null)
  const allowed = canReadImpact(identity)
  useEffect(() => () => pending.current?.abort(), [])
  useEffect(() => { if (finding) heading.current?.focus() }, [finding])

  function changeFilters(next: ImpactFilters) {
    pending.current?.abort(); pending.current = null; cursors.current.clear()
    setFilters(next); setPage(null); setFinding(null); setVersion(null); setError(''); setTrace(null); setBusy('')
  }
  async function read(action: string, load: (signal: AbortSignal) => Promise<void>) {
    if (!allowed || pending.current) return
    const controller = new AbortController(); pending.current = controller
    setBusy(action); setError(''); setTrace(null)
    try { await load(controller.signal) }
    catch (err) {
      if (!controller.signal.aborted && getSession().identity === identity) {
        setError(err instanceof PublicApiError && [404, 422, 503].includes(err.status)
          ? ({ 404: 'This finding or version was not found. Refresh the inbox or check the reference.',
              422: 'Required projections are not ready. Wait, then refresh the inbox.',
              503: 'The impact service is unavailable. Try refreshing later.' }[err.status]!)
          : err instanceof Error ? err.message : 'Could not load the inbox. Try again.')
        setTrace(err instanceof PublicApiError ? err.traceId : null)
      }
    } finally { if (pending.current === controller) { pending.current = null; setBusy('') } }
  }
  function loadPage(cursor?: string) {
    if (pending.current || !allowed) return
    setPage(null); setFinding(null); setVersion(null)
    if (!cursor) cursors.current.clear()
    void read('list', async signal => {
      const result = readFindings(await publicApiGet<unknown>(findingsPath(filters, cursor), signal), identity.organisationId, filters)
      if (signal.aborted || getSession().identity !== identity) return
      if (result.nextCursor && (result.nextCursor === cursor || cursors.current.has(result.nextCursor))) throw new Error('The inbox cursor repeated. Refresh the inbox to restart pagination.')
      if (cursor) cursors.current.add(cursor)
      setPage(result)
    })
  }
  function loadFinding(id: string) {
    if (pending.current) return
    setFinding(null); setVersion(null)
    void read('finding', async signal => {
      const result = readFinding(await publicApiGet<unknown>(findingPath(id), signal), identity.organisationId, id)
      if (!signal.aborted && getSession().identity === identity) setFinding(result)
    })
  }
  function loadVersion(title: string, ref: VersionReference, kind: 'specifications' | 'formulations') {
    if (pending.current) return
    setVersion(null)
    void read('version', async signal => {
      const specification = kind === 'specifications'
      const path = `/api/${kind}/${specification ? 'versions' : 'formula-versions'}/${encodeURIComponent(ref.id)}`
      const result = await publicApiGet<Record<string, unknown>>(path, signal)
      if (signal.aborted || getSession().identity !== identity) return
      if (!result || result[specification ? 'specificationVersionId' : 'formulaVersionId'] !== ref.id || result.versionNumber !== ref.versionNumber
        || (!specification && result.organisationId !== identity.organisationId)
        || !['DRAFT', 'RELEASED', 'RETIRED'].includes(String(result.lifecycleStatus))) throw new Error('The service returned a different or unreadable version. Refresh the finding before continuing.')
      setVersion({ title, reference: ref, status: String(result.lifecycleStatus) })
    })
  }
  function refresh(event: FormEvent) { event.preventDefault(); loadPage() }

  return <section className="impact-inbox" aria-labelledby="impact-title">
    <h2 id="impact-title">Impact inbox</h2>
    <p>Review specification changes and their allergen evidence for {identity.organisationId}.</p>
    {!allowed ? <p role="status" className="signin-notice">Impact findings require a manufacturer account with the CHANGE_MANAGER role.</p> : <>
      <form className="impact-filters" onSubmit={refresh}>
        <label>Finding kind<select aria-label="Finding kind" value={filters.kind} disabled={!!busy} onChange={e => changeFilters({ ...filters, kind: e.target.value as ImpactFilters['kind'] })}>
          <option value="">All kinds</option><option value="POTENTIAL">POTENTIAL</option><option value="CONFIRMED">CONFIRMED</option>
        </select></label>
        <label>Finding outcome<select aria-label="Finding outcome" value={filters.outcome} disabled={!!busy} onChange={e => changeFilters({ ...filters, outcome: e.target.value as ImpactFilters['outcome'] })}>
          <option value="">All outcomes</option><option value="NO_ACTION">NO_ACTION</option><option value="REVIEW_REQUIRED">REVIEW_REQUIRED</option>
        </select></label>
        <Button type="submit" disabled={!!busy}>{busy === 'list' ? 'Loading inbox…' : 'Refresh inbox'}</Button>
      </form>
      {busy && <p role="status">{busy === 'finding' ? 'Loading finding evidence…' : busy === 'version' ? 'Loading version…' : 'Loading findings…'}</p>}
      {error && <div role="alert" className="impact-error"><p>{error}</p>{trace && <small>Reference: {trace}</small>}</div>}
      {!page && !busy && !error && <p>Choose filters and refresh to load findings. This inbox does not change specifications, formulas or labels.</p>}
      {page && <>
        {page.items.length === 0 ? <p role="status">No findings match these filters. An empty inbox does not establish a NO_ACTION conclusion.</p> : <div className="impact-table-wrap" role="region" aria-label="Impact findings" tabIndex={0}>
          <table><caption>Current page · {page.items.length} {page.items.length === 1 ? 'finding' : 'findings'}</caption><thead><tr><th scope="col">Kind / outcome</th><th scope="col">Formula / jurisdiction</th><th scope="col">Evidence</th><th scope="col">Details</th></tr></thead>
            <tbody>{page.items.map(item => <tr key={item.findingId}>
              <td><strong>{item.kind}</strong><span>{item.outcome}</span></td>
              <td>{item.formulaVersion.id}<span>v{item.formulaVersion.versionNumber} · {item.jurisdiction}</span></td>
              <td>{item.missingAllergens.length} missing<span>{item.unresolvedAllergens.length} unresolved</span></td>
              <td><Button variant="outline" disabled={!!busy} onClick={() => loadFinding(item.findingId)} aria-label={`View finding ${item.findingId}`}>View evidence</Button></td>
            </tr>)}</tbody>
          </table>
        </div>}
        {page.nextCursor && <Button variant="outline" disabled={!!busy} onClick={() => loadPage(page.nextCursor!)}>Next page</Button>}
      </>}
      {finding && <section className="impact-detail" aria-labelledby="impact-detail-title">
        <h3 id="impact-detail-title" ref={heading} tabIndex={-1}>Finding {finding.findingId}</h3>
        <p className="impact-state">{finding.kind} · {finding.outcome} · {finding.jurisdiction}</p>
        <p>{finding.kind === 'POTENTIAL' ? 'Potential impact only. The formula has not been adopted by this finding, and it does not create a review task.'
          : finding.outcome === 'REVIEW_REQUIRED' ? 'Confirmed impact requiring review. Review task delivery must be verified separately; this finding does not confirm a task was created.'
            : 'Confirmed evaluation with no missing or unresolved allergens in this finding.'}</p>
        <dl className="impact-allergens">{(['requiredAllergens', 'declaredAllergens', 'missingAllergens'] as const).map((key, i) => <div key={key}>
          <dt>{['Required allergens', 'Declared allergens', 'Missing allergens'][i]}</dt><dd>{finding[key].length ? finding[key].join(', ') : 'None recorded'}</dd>
        </div>)}</dl>
        <h4>Unresolved evidence</h4>
        {finding.unresolvedAllergens.length ? <ul className="impact-unresolved">{finding.unresolvedAllergens.map((item, i) => <li key={i}>
          <strong>{item.rawPhrase}</strong><p>{item.reason} · Ingredient: {item.ingredientId ?? 'Not identified'}</p>
          <dl><div><dt>Formula item</dt><dd>{item.formulaItemId}</dd></div><div><dt>Specification / component</dt><dd>{item.specificationVersionId} / {item.specComponentId}</dd></div></dl>
        </li>)}</ul> : <p>No unresolved evidence recorded.</p>}
        <h4>Bound versions</h4>
        <div className="impact-version-links">
          {(['previous', 'candidate'] as const).map(key => <Button key={key} variant="outline" disabled={!!busy} onClick={() => loadVersion(`${key === 'previous' ? 'Previous' : 'Candidate'} specification`, finding.specificationChange[key], 'specifications')}>
            {key === 'previous' ? 'Previous' : 'Candidate'} specification: {finding.specificationChange[key].id} · v{finding.specificationChange[key].versionNumber}
          </Button>)}
          <Button variant="outline" disabled={!!busy} onClick={() => loadVersion('Formula', finding.formulaVersion, 'formulations')}>Formula: {finding.formulaVersion.id} · v{finding.formulaVersion.versionNumber}</Button>
          <Button variant="outline" disabled={!!busy} onClick={() => openLabel(finding.labelVersion.id)}>Open label: {finding.labelVersion.id} · v{finding.labelVersion.versionNumber}</Button>
        </div>
        <p>Rule set: {finding.ruleSetVersion.id} · v{finding.ruleSetVersion.versionNumber}</p>
        {version && <p role="status">{version.title} verified: {version.reference.id} · v{version.reference.versionNumber} · {version.status}</p>}
      </section>}
    </>}
  </section>
}
