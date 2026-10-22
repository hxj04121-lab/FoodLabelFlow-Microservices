import { useEffect, useRef, useState } from 'react'
import { useBlocker } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { DiscardChangesDialog } from '@/components/DiscardChangesDialog'
import { getSession, PublicApiError, type Identity } from '@/auth/session'
import { canWriteSpecifications, catalogError, catalogPage, createGatewaySpecification, readIngredient, releaseGatewaySpecification,
  type IngredientRecord, type MaterialRecord, type SpecificationInput, type SpecificationRecord } from './gatewayCatalogApi'
import { useCatalogRead } from './useCatalogRead'

type Row = { key: number; ingredientId: string; rawPhrase: string }
const today = () => new Date().toISOString().slice(0, 10)

/** Supplier maintenance of a material: copy the latest released version, edit its components, save a draft, then release it. */
export function GatewaySpecificationEditor({ identity, material, latest, close }: {
  identity: Identity; material: MaterialRecord; latest?: SpecificationRecord; close: () => void
}) {
  const optionsRead = useCatalogRead(identity)
  const [ingredients, setIngredients] = useState<IngredientRecord[]>([])
  const [ingredientsReady, setIngredientsReady] = useState(false)
  const sequence = useRef(0)
  const initialRows = (): Row[] => (latest ? [...latest.components].sort((a, b) => a.sequenceNo - b.sequenceNo) : [])
    .map(c => ({ key: ++sequence.current, ingredientId: c.ingredientId, rawPhrase: c.rawPhrase }))
  const [rows, setRows] = useState<Row[]>(() => { const r = initialRows(); return r.length ? r : [{ key: ++sequence.current, ingredientId: '', rawPhrase: '' }] })
  const [effectiveDate, setEffectiveDate] = useState(today)
  const [provenance, setProvenance] = useState(latest?.provenanceId ?? '')
  const [matchRule, setMatchRule] = useState('Supplier statement')
  const [touched, setTouched] = useState(false)
  const [created, setCreated] = useState<SpecificationRecord | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [message, setMessage] = useState('')
  const [uncertain, setUncertain] = useState(false), [conflict, setConflict] = useState(false), [confirm, setConfirm] = useState(false)
  const [discard, setDiscard] = useState(false)
  const pending = useRef<AbortController | null>(null)
  const dirty = !created && touched
  const blocker = useBlocker(({ currentLocation, nextLocation }) => (busy || dirty)
    && currentLocation.pathname + currentLocation.search !== nextLocation.pathname + nextLocation.search)
  const allowed = canWriteSpecifications(identity, material)
  const loading = busy || optionsRead.busy
  const edit = (change: () => void) => { setTouched(true); change() }
  function loadIngredients() {
    setIngredientsReady(false); setIngredients([])
    void optionsRead.read(async signal => {
      const all: IngredientRecord[] = [], cursors = new Set<string>(), ids = new Set<string>()
      let cursor: string | undefined
      do {
        const page = await catalogPage('/api/specifications/ingredients', readIngredient, i => i.ingredientId, signal, cursor)
        for (const option of page.items) {
          if (ids.has(option.ingredientId)) throw new Error('Ingredient results repeated. Reload the ingredients before creating a draft.')
          ids.add(option.ingredientId); all.push(option)
        }
        cursor = page.nextCursor ?? undefined
        if (cursor && (cursors.has(cursor) || all.length >= 1000)) throw new Error('Ingredients could not be loaded completely. Ask the service owner to check pagination.')
        if (cursor) cursors.add(cursor)
      } while (cursor)
      return all
    }, value => { setIngredients(value); setIngredientsReady(true) })
  }
  useEffect(() => { if (allowed) loadIngredients() }, [identity]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => () => pending.current?.abort(), [])
  useEffect(() => {
    if (!dirty && !busy) return
    const beforeUnload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', beforeUnload)
    return () => window.removeEventListener('beforeunload', beforeUnload)
  }, [dirty, busy])
  function input(): SpecificationInput {
    if (!/^\S{1,128}$/.test(provenance.trim())) throw new Error('Enter an existing provenance ID without whitespace (up to 128 characters).')
    if (!/^\d{4}-\d{2}-\d{2}$/.test(effectiveDate) || !Number.isFinite(Date.parse(effectiveDate))) throw new Error('Enter the effective date as YYYY-MM-DD.')
    const rule = matchRule.trim()
    if (!rule || rule.length > 500) throw new Error('Enter a match rule of up to 500 characters.')
    if (new Set(rows.map(r => r.ingredientId)).size !== rows.length) throw new Error('Each ingredient can appear only once in a specification.')
    return { materialId: material.materialId, effectiveDate, provenanceId: provenance.trim(), components: rows.map((r, index) => {
      if (!ingredients.some(i => i.ingredientId === r.ingredientId)) throw new Error(`Choose an ingredient for component ${index + 1}.`)
      const phrase = r.rawPhrase.trim()
      if (!phrase || phrase.length > 300) throw new Error(`Component ${index + 1}: enter the supplier wording (up to 300 characters).`)
      return { ingredientId: r.ingredientId, rawPhrase: phrase, matchRule: rule }
    }) }
  }
  async function mutate(release: boolean) {
    if (!allowed || getSession().identity !== identity || pending.current || loading || uncertain || conflict
      || (release ? !created || created.lifecycleStatus !== 'DRAFT' || !confirm : !!created || !ingredientsReady)) return
    let body: SpecificationInput | undefined
    try { if (!release) body = input() } catch (e) { setError(catalogError(e)); return }
    const controller = new AbortController(); pending.current = controller
    setBusy(true); setError(''); setMessage(''); setConfirm(false)
    try {
      const result = release ? await releaseGatewaySpecification(created!, identity, controller.signal)
        : await createGatewaySpecification(body!, identity, controller.signal)
      if (!controller.signal.aborted && getSession().identity === identity) {
        setCreated(result)
        setMessage(release ? `Specification version ${result.versionNumber} released. Manufacturers can adopt it once Formulation has received it.`
          : `Draft version ${result.versionNumber} saved. Review and confirm before release.`)
      }
    } catch (e) {
      if (!controller.signal.aborted && getSession().identity === identity) {
        setError(catalogError(e))
        if (e instanceof PublicApiError && e.status === 409) setConflict(true)
        if (!(e instanceof PublicApiError) || e.outcomeUnknown) setUncertain(true)
      }
    } finally { if (pending.current === controller) { pending.current = null; setBusy(false) } }
  }
  function leave() { if (busy) return; if (dirty) setDiscard(true); else close() }
  const name = (ingredientId: string) => ingredients.find(i => i.ingredientId === ingredientId)?.canonicalName ?? ingredientId
  if (!allowed) return <section className="panel"><h2>Specification versions</h2><p>Specification writes require the owning supplier with ADMIN_DATA_MAINTENANCE.</p></section>
  return <section className="panel gateway-form-editor" role="region" aria-label="Specification editor"><div className="section-head"><div>
    <h2>{created ? `${material.materialName} · Specification version ${created.versionNumber}` : `${material.materialName} · New specification version`}</h2>
    <p>{latest ? <>Starts from released version {latest.versionNumber} · <code>{latest.specificationVersionId}</code></> : 'No released version yet.'}</p></div>
    <Button variant="outline" disabled={busy} onClick={leave}>Back to specifications</Button></div>
    <p className="source-notice">Changes are saved only when you choose Save specification draft or Confirm release. The service checks your role and organisation.</p>
    {(error || optionsRead.error) && <p className="error-notice" role="alert">{error || optionsRead.error}</p>}
    {loading && <p role="status">{busy ? 'Saving the requested operation…' : 'Loading ingredients…'}</p>}
    {message && <p role="status">{message}</p>}
    {uncertain && <p className="error-notice">The write outcome is unconfirmed. Do not repeat it. Check server history or contact the service owner.</p>}
    {conflict && <p>The specification changed or another draft exists. Go back to the specifications and reopen the latest version.</p>}
    {!created ? <form onSubmit={e => { e.preventDefault(); void mutate(false) }}>
      <fieldset disabled={loading || uncertain}><legend>New specification inputs</legend>
        <div className="gateway-form-pair"><label>Effective date<input type="date" value={effectiveDate} onChange={e => edit(() => setEffectiveDate(e.target.value))} required /></label>
          <label>Provenance ID<input value={provenance} onChange={e => edit(() => setProvenance(e.target.value))} maxLength={128} required /></label></div>
        <label>Match rule<input value={matchRule} onChange={e => edit(() => setMatchRule(e.target.value))} maxLength={500} required /></label>
        {rows.map((row, index) => <fieldset className="gateway-form-item" key={row.key}><legend>Component {index + 1}</legend>
          <div className="gateway-form-pair"><label>Ingredient {index + 1}<select aria-label={`Ingredient ${index + 1}`} value={row.ingredientId} required
            onChange={e => edit(() => setRows(rs => rs.map(r => r.key === row.key ? { ...r, ingredientId: e.target.value } : r)))}>
            <option value="">Select an ingredient</option>{ingredients.map(i => <option key={i.ingredientId} value={i.ingredientId}>{i.canonicalName} · {i.ingredientId}</option>)}</select></label>
            <label>Supplier wording {index + 1}<input value={row.rawPhrase} maxLength={300} required
              onChange={e => edit(() => setRows(rs => rs.map(r => r.key === row.key ? { ...r, rawPhrase: e.target.value } : r)))} /></label></div>
          <Button type="button" variant="outline" disabled={rows.length === 1} onClick={() => edit(() => setRows(rs => rs.filter(r => r.key !== row.key)))}>Remove component {index + 1}</Button>
        </fieldset>)}
        <div className="formula-actions"><Button type="button" variant="outline" disabled={rows.length >= 100}
          onClick={() => edit(() => setRows(rs => [...rs, { key: ++sequence.current, ingredientId: '', rawPhrase: '' }]))}>Add component</Button>
          <Button type="submit" disabled={!ingredientsReady || conflict}>Save specification draft</Button>
          {optionsRead.error && <Button type="button" variant="outline" onClick={loadIngredients}>Reload ingredients</Button>}</div>
      </fieldset>
    </form> : <><h3>Saved specification</h3><p><code>{created.specificationVersionId}</code> · {created.lifecycleStatus} · Effective {created.effectiveDate} · Provenance {created.provenanceId}</p>
      <ul>{[...created.components].sort((a, b) => a.sequenceNo - b.sequenceNo).map(c => <li key={c.specComponentId}>{name(c.ingredientId)} · {c.matchStatus} · “{c.rawPhrase}”</li>)}</ul>
      {created.lifecycleStatus === 'DRAFT' && <div className="formula-actions"><Button disabled={loading || uncertain || conflict} onClick={() => setConfirm(true)}>Release specification</Button></div>}
      {confirm && <div className="source-notice" role="group" aria-label="Confirm specification release"><div><p>Release version {created.versionNumber} of {material.materialName}? Manufacturers whose formulas use
        the previous version will receive impact findings.</p>
        <Button variant="outline" disabled={loading} onClick={() => setConfirm(false)}>Cancel release</Button><Button disabled={loading} onClick={() => void mutate(true)}>Confirm release</Button></div></div>}
    </>}
    <DiscardChangesDialog open={discard || blocker.state === 'blocked'} onContinue={() => { setDiscard(false); if (blocker.state === 'blocked') blocker.reset() }}
      onDiscard={() => { if (busy) return; setDiscard(false); if (blocker.state === 'blocked') blocker.proceed(); else close() }} />
  </section>
}
