import { useEffect, useRef, useState } from 'react'
import { useBlocker } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { DiscardChangesDialog } from '@/components/DiscardChangesDialog'
import { getSession, publicApiGet, PublicApiError, type Identity } from '@/auth/session'
import { canWriteFormulas, catalogError, catalogPage, createGatewayFormula, readFormula, readProduct, readReleasedSpec, releaseGatewayFormula,
  type FormulaInput, type FormulaRecord, type ProductRecord, type ReleasedSpec } from './gatewayCatalogApi'
import { useCatalogRead } from './useCatalogRead'

type Item = { key: number; specificationId: string; quantity: string; unit: string }
const blank = (key: number): Item => ({ key, specificationId: '', quantity: '', unit: '' })

export function GatewayFormulaEditor({ identity, product, existing, close }: {
  identity: Identity; product: ProductRecord; existing?: FormulaRecord; close: () => void
}) {
  const optionsRead = useCatalogRead(identity)
  const [options, setOptions] = useState<ReleasedSpec[]>([])
  const [optionsReady, setOptionsReady] = useState(false)
  const [provenance, setProvenance] = useState('')
  const [items, setItems] = useState<Item[]>([blank(1)])
  const sequence = useRef(1)
  const [created, setCreated] = useState<FormulaRecord | null>(existing ?? null)
  const [expected, setExpected] = useState<string | null>(product.currentFormulaVersion?.id ?? null)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [message, setMessage] = useState('')
  const [uncertain, setUncertain] = useState(false), [conflict, setConflict] = useState(false), [confirm, setConfirm] = useState(false)
  const [discard, setDiscard] = useState(false)
  const pending = useRef<AbortController | null>(null)
  const dirty = !created && (!!provenance || items.length > 1 || items.some(i => i.specificationId || i.quantity || i.unit))
  const blocker = useBlocker(({ currentLocation, nextLocation }) => (busy || dirty)
    && currentLocation.pathname + currentLocation.search !== nextLocation.pathname + nextLocation.search)
  const allowed = canWriteFormulas(identity)
  const productPath = `/api/formulations/products/${encodeURIComponent(product.productId)}`
  const loading = busy || optionsRead.busy
  function loadOptions() {
    setOptionsReady(false); setOptions([])
    void optionsRead.read(async signal => {
      const all: ReleasedSpec[] = [], cursors = new Set<string>(), ids = new Set<string>()
      let cursor: string | undefined
      do {
        const page = await catalogPage('/api/formulations/released-specifications', readReleasedSpec, s => s.specificationVersion.id, signal, cursor)
        for (const option of page.items) {
          if (ids.has(option.specificationVersion.id)) throw new Error('Specification results repeated. Reload the options before creating a draft.')
          ids.add(option.specificationVersion.id); all.push(option)
        }
        cursor = page.nextCursor ?? undefined
        if (cursor && (cursors.has(cursor) || all.length >= 1000)) throw new Error('Released specification options could not be loaded completely. Ask the service owner to check pagination.')
        if (cursor) cursors.add(cursor)
      } while (cursor)
      return all
    }, value => { setOptions(value); setOptionsReady(true) })
  }
  useEffect(() => { if (allowed && !existing) loadOptions() }, [identity, existing]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => () => pending.current?.abort(), [])
  useEffect(() => {
    if (!dirty && !busy) return
    const beforeUnload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', beforeUnload)
    return () => window.removeEventListener('beforeunload', beforeUnload)
  }, [dirty, busy])
  const effective = (s: ReleasedSpec) => s.effectiveDate <= new Date().toISOString().slice(0, 10)
  function input(): FormulaInput {
    if (!/^\S{1,128}$/.test(provenance.trim())) throw new Error('Enter an existing provenance ID without whitespace (up to 128 characters).')
    return { productId: product.productId, provenanceId: provenance.trim(), items: items.map((i, index) => {
      const specification = options.find(s => s.specificationVersion.id === i.specificationId && effective(s))
      if (!specification) throw new Error(`Choose a released, effective specification for item ${index + 1}.`)
      const quantity = i.quantity.trim(), unit = i.unit.trim()
      if (!!quantity !== !!unit || (quantity && (!/^\d+(\.\d{1,4})?$/.test(quantity) || !Number.isFinite(Number(quantity))
        || Number(quantity) <= 0 || Number(quantity) > 99999999.9999 || unit.length > 40))) {
        throw new Error(`Item ${index + 1}: enter a positive quantity with up to 8 integer and 4 decimal digits and a unit of up to 40 characters, or leave both empty.`)
      }
      return { materialId: specification.materialId, specificationVersionId: specification.specificationVersion.id,
        quantity: quantity ? Number(quantity) : null, unit: unit || null }
    }) }
  }
  async function mutate(release: boolean) {
    if (!allowed || getSession().identity !== identity || pending.current || loading || uncertain || conflict
      || (release ? !created || created.lifecycleStatus !== 'DRAFT' || !confirm : !!created || !optionsReady)) return
    let body: FormulaInput | undefined
    try { if (!release) body = input() } catch (e) { setError(catalogError(e)); return }
    const controller = new AbortController(); pending.current = controller
    setBusy(true); setError(''); setMessage(''); setConfirm(false)
    let wrote = false
    try {
      let baseline = expected
      if (!release) baseline = readProduct(await publicApiGet(productPath, controller.signal), identity, product.productId).currentFormulaVersion?.id ?? null
      wrote = true
      const result = release ? await releaseGatewayFormula(created!, baseline, identity, controller.signal)
        : await createGatewayFormula(body!, identity, controller.signal)
      if (!controller.signal.aborted && getSession().identity === identity) {
        setCreated(result); setExpected(baseline)
        setMessage(release ? `Formula version ${result.versionNumber} released. Refresh history to check the current product version.`
          : `Draft version ${result.versionNumber} saved. Review and confirm before release.`)
      }
    } catch (e) {
      if (!controller.signal.aborted && getSession().identity === identity) {
        setError(catalogError(e))
        if (e instanceof PublicApiError && e.status === 409) setConflict(true)
        if (wrote && (!(e instanceof PublicApiError) || e.outcomeUnknown)) setUncertain(true)
      }
    } finally { if (pending.current === controller) { pending.current = null; setBusy(false) } }
  }
  function refresh() {
    void optionsRead.read(async signal => ({ product: readProduct(await publicApiGet(productPath, signal), identity, product.productId),
      formula: created ? readFormula(await publicApiGet(`/api/formulations/formula-versions/${encodeURIComponent(created.formulaVersionId)}`, signal), identity, product.productId, created.formulaVersionId) : null,
    }), value => { setExpected(value.product.currentFormulaVersion?.id ?? null); if (value.formula) setCreated(value.formula)
      setConflict(false); setConfirm(false); setError(''); setMessage('Current version refreshed. Review it before confirming another action.') })
  }
  function leave() { if (busy) return; if (dirty) setDiscard(true); else close() }
  if (!allowed) return <section className="panel"><h1>Formula versions</h1><p>Formula writes require a manufacturer with ADMIN_DATA_MAINTENANCE.</p></section>
  return <section className="gateway-catalog gateway-form-editor"><div className="page-title"><div><h1>{created ? `Formula version ${created.versionNumber}` : 'Create formula'}</h1>
    <p>{product.productDescription} · <code>{product.productId}</code></p></div><Button variant="outline" disabled={busy} onClick={leave}>Back to history</Button></div>
    <p className="source-notice">Changes are saved only when you choose Save draft or Confirm release. The service checks your role and organisation.</p>
    {(error || optionsRead.error) && <p className="error-notice" role="alert">{error || optionsRead.error}</p>}
    {loading && <p role="status">{busy ? 'Saving the requested operation…' : 'Loading current references…'}</p>}
    {message && <p role="status">{message}</p>}
    {uncertain && <p className="error-notice">The write outcome is unconfirmed. Do not repeat it. Check server history or contact the service owner.</p>}
    {conflict && <p>The current formula changed or is not ready. Refresh the current version, then review and confirm again.</p>}
    {!created && optionsReady && !options.some(effective) && <p role="status">No released, effective specifications are available. Ask the service owner to publish and project the required versions.</p>}
    {!created ? <form className="panel" onSubmit={e => { e.preventDefault(); void mutate(false) }}>
      <fieldset disabled={loading || uncertain}><legend>New formula inputs</legend><label>Provenance ID<input value={provenance} onChange={e => setProvenance(e.target.value)} maxLength={128} required /></label>
        <p>Use an existing provenance record provided by your team.</p>
        {items.map((item, index) => <fieldset className="gateway-form-item" key={item.key}><legend>Item {index + 1}</legend>
          <label>Released specification {index + 1}<select aria-label={`Released specification ${index + 1}`} value={item.specificationId} onChange={e => setItems(rows => rows.map(row => row.key === item.key ? { ...row, specificationId: e.target.value } : row))} required>
            <option value="">Select a released specification</option>{options.map(s => <option key={s.specificationVersion.id} value={s.specificationVersion.id} disabled={!effective(s)}>
              {s.materialId} · {s.specificationVersion.id} · v{s.specificationVersion.versionNumber} · {s.effectiveDate}</option>)}</select></label>
          <div className="gateway-form-pair"><label>Quantity {index + 1}<input inputMode="decimal" value={item.quantity} maxLength={32} onChange={e => setItems(rows => rows.map(row => row.key === item.key ? { ...row, quantity: e.target.value } : row))} /></label>
            <label>Unit {index + 1}<input value={item.unit} maxLength={40} onChange={e => setItems(rows => rows.map(row => row.key === item.key ? { ...row, unit: e.target.value } : row))} /></label></div>
          <Button type="button" variant="outline" disabled={items.length === 1} onClick={() => setItems(rows => rows.filter(row => row.key !== item.key))}>Remove item {index + 1}</Button>
        </fieldset>)}
        <div className="formula-actions"><Button type="button" variant="outline" disabled={items.length >= 100} onClick={() => setItems(rows => [...rows, blank(++sequence.current)])}>Add item</Button>
          <Button type="submit" disabled={!optionsReady || !options.some(effective) || conflict}>Save formula draft</Button>
          {optionsRead.error && <Button type="button" variant="outline" onClick={loadOptions}>Reload specifications</Button>}</div>
      </fieldset>
    </form> : <section className="panel"><h2>Saved formula</h2><p><code>{created.formulaVersionId}</code> · {created.lifecycleStatus} · Provenance {created.provenanceId}</p>
      <ul>{created.items.map(i => <li key={i.formulaItemId}>{i.materialId} · {i.specificationVersion.id} · v{i.specificationVersion.versionNumber}{i.quantity === null ? '' : ` · ${i.quantity} ${i.unit}`}</li>)}</ul>
      <p>Expected current formula: <code>{expected ?? 'None'}</code></p><div className="formula-actions">
        {created.lifecycleStatus === 'DRAFT' && <Button disabled={loading || uncertain || conflict} onClick={() => setConfirm(true)}>Release formula</Button>}
        <Button variant="outline" disabled={loading} onClick={refresh}>Refresh current version</Button></div>
      {confirm && <div className="source-notice" role="group" aria-label="Confirm formula release"><div><p>Release version {created.versionNumber} for this product? Expected current version: {expected ?? 'None'}.</p>
        <Button variant="outline" disabled={loading} onClick={() => setConfirm(false)}>Cancel release</Button><Button disabled={loading} onClick={() => void mutate(true)}>Confirm release</Button></div></div>}
    </section>}
    <DiscardChangesDialog open={discard || blocker.state === 'blocked'} onContinue={() => { setDiscard(false); if (blocker.state === 'blocked') blocker.reset() }}
      onDiscard={() => { if (busy) return; setDiscard(false); if (blocker.state === 'blocked') blocker.proceed(); else close() }} />
  </section>
}
