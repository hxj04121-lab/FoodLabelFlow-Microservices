import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Panel } from '@/components/catalog-shared'
import { publicApiGet, type Identity } from '@/auth/session'
import { canReadProducts, canWriteFormulas, catalogPage, readFormula, readMaterial, readProduct, readSpecification, readSupplier, readTrace,
  type CatalogPage, type FormulaRecord, type FormulaTrace, type MaterialRecord, type ProductRecord } from './gatewayCatalogApi'
import { useCatalogPage, useCatalogRead } from './useCatalogRead'
import { GatewayFormulaEditor } from './GatewayFormulaEditor'

function ReadState({ busy, error, retry }: { busy: boolean; error: string; retry: () => void }) {
  return <>{busy && <p role="status">Loading catalog information…</p>}{error && <div className="error-notice" role="alert"><p>{error}</p><Button variant="outline" onClick={retry}>Retry</Button></div>}</>
}
function Paging({ next, busy, load }: { next: string | null; busy: boolean; load: (cursor?: string) => void }) {
  return <div className="pagination"><span>Only returned records are shown.</span><div>
    <Button variant="outline" disabled={busy} onClick={() => load()}>First page</Button>
    <Button variant="outline" disabled={busy || !next} onClick={() => next && load(next)}>Next page</Button>
  </div></div>
}

export function GatewayProducts({ identity, formulas = false }: { identity: Identity; formulas?: boolean }) {
  if (!canReadProducts(identity)) return <Panel><h1>{formulas ? 'Formula versions' : 'Products'}</h1><p>Product and formula access requires a manufacturer account.</p></Panel>
  return <ProductList identity={identity} formulas={formulas} />
}
function ProductList({ identity, formulas }: { identity: Identity; formulas: boolean }) {
  const listing = useCatalogPage(identity, '/api/formulations/products', v => readProduct(v, identity), p => p.productId)
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<ProductRecord | null>(null)
  if (selected) return <ProductDetails key={selected.productId} product={selected} identity={identity} back={() => { setSelected(null); listing.load() }} />
  const items = listing.page?.items.filter(p => `${p.productId} ${p.productDescription} ${p.brandOwner ?? ''} ${p.normalizedCategory ?? ''}`.toLowerCase().includes(query.toLowerCase()))
  return <section className="gateway-catalog"><div className="page-title"><div><h1>{formulas ? 'Formula versions' : 'Products'}</h1>
    <p>{formulas ? 'Open a product to inspect its versions, trace materials, or prepare a new formula.' : 'Products visible to your signed-in organisation.'}</p></div></div>
    <div className="source-notice">Organisation: {identity.organisationId} · Live Gateway data</div>
    <ReadState {...listing} retry={() => listing.load()} />
    {listing.page && <Panel><div className="catalog-toolbar"><label>Search this page<input value={query} onChange={e => setQuery(e.target.value)} placeholder="Product name, brand or ID" /></label>
      <span>{listing.page.items.length} products on this page</span></div>
      <div className="table-scroll" role="region" aria-label="Product catalog" tabIndex={0}><table><thead><tr>
        <th scope="col">Product</th><th scope="col">Brand / category</th><th scope="col">Current formula</th><th scope="col">Details</th>
      </tr></thead><tbody>{items?.map(p => <tr key={p.productId}><td><strong>{p.productDescription}</strong><br /><code>{p.productId}</code></td>
        <td>{p.brandOwner ?? 'Not supplied'}<br />{p.normalizedCategory ?? 'Not supplied'}</td>
        <td>{p.currentFormulaVersion ? <>Version {p.currentFormulaVersion.versionNumber}<br /><code>{p.currentFormulaVersion.id}</code></> : 'No released formula'}</td>
        <td><Button variant="outline" aria-label={`Open product ${p.productDescription}`} onClick={() => setSelected(p)}>Open product</Button></td></tr>)}</tbody></table></div>
      {!items?.length && <p>No products match this page. Try another page or search term.</p>}
      <Paging next={listing.page.nextCursor} busy={listing.busy} load={listing.load} />
    </Panel>}
  </section>
}

function ProductDetails({ product: initial, identity, back }: { product: ProductRecord; identity: Identity; back: () => void }) {
  const request = useCatalogRead(identity)
  const [product, setProduct] = useState<ProductRecord | null>(null)
  const [history, setHistory] = useState<CatalogPage<FormulaRecord> | null>(null)
  const [trace, setTrace] = useState<FormulaTrace | null>(null)
  const [editor, setEditor] = useState(false)
  const [existingDraft, setExistingDraft] = useState<FormulaRecord | undefined>()
  const seen = useRef(new Set<string>())
  const productPath = `/api/formulations/products/${encodeURIComponent(initial.productId)}`
  function load(cursor?: string) {
    if (request.busy) return
    setHistory(null); setTrace(null)
    if (!cursor) seen.current.clear()
    void request.read(async signal => ({
      product: readProduct(await publicApiGet(productPath, signal), identity, initial.productId),
      history: await catalogPage(`${productPath}/formula-versions`, v => readFormula(v, identity, initial.productId), f => f.formulaVersionId, signal, cursor),
    }), result => {
      if (cursor) seen.current.add(cursor)
      if (result.history.nextCursor && seen.current.has(result.history.nextCursor)) throw new Error('The formula history cursor repeated. Refresh to start again.')
      setProduct(result.product); setHistory(result.history)
    })
  }
  useEffect(() => { load() }, [initial.productId, identity]) // eslint-disable-line react-hooks/exhaustive-deps
  if (editor && product) return <GatewayFormulaEditor product={product} identity={identity} existing={existingDraft} close={() => { setEditor(false); setExistingDraft(undefined); load() }} />
  return <section className="gateway-catalog"><div className="page-title"><div><h1>{initial.productDescription}</h1><p><code>{initial.productId}</code></p></div>
    <Button variant="outline" onClick={back}>Back to products</Button></div>
    <ReadState {...request} retry={() => load()} />
    {product && <Panel><h2>Product record</h2><dl className="gateway-facts"><div><dt>Organisation</dt><dd>{product.organisationId}</dd></div>
      <div><dt>Brand</dt><dd>{product.brandOwner ?? 'Not supplied'}</dd></div><div><dt>Category</dt><dd>{product.normalizedCategory ?? 'Not supplied'}</dd></div>
      <div><dt>Current formula</dt><dd>{product.currentFormulaVersion ? `${product.currentFormulaVersion.id} · v${product.currentFormulaVersion.versionNumber}` : 'No released formula'}</dd></div></dl>
      {canWriteFormulas(identity) ? <Button disabled={request.busy} onClick={() => { setExistingDraft(undefined); setEditor(true) }}>Create formula</Button>
        : <p>Creating or releasing formulas requires ADMIN_DATA_MAINTENANCE.</p>}
    </Panel>}
    {history && <Panel><h2>Formula history</h2><p>{history.items.length} versions on this page</p>
      {!history.items.length && <p>No formula versions were returned.</p>}
      {history.items.map(f => <article className="gateway-version" key={f.formulaVersionId}><h3>Version {f.versionNumber} · {f.lifecycleStatus}{f.isCurrentReleased ? ' · Current' : ''}</h3>
        <code>{f.formulaVersionId}</code><p>Provenance: {f.provenanceId} · {f.releasedAt ? `Released ${f.releasedAt}` : 'Not released'}</p>
        <ul>{f.items.map(i => <li key={i.formulaItemId}>{i.materialId} · {i.specificationVersion.id} v{i.specificationVersion.versionNumber}
          {i.quantity !== null ? ` · ${i.quantity} ${i.unit}` : ' · Quantity not supplied'}</li>)}</ul>
        <Button variant="outline" disabled={request.busy} aria-label={`View trace ${f.formulaVersionId}`} onClick={() => {
          setTrace(null); void request.read(async signal => readTrace(await publicApiGet(`/api/formulations/formula-versions/${encodeURIComponent(f.formulaVersionId)}/trace`, signal), f), setTrace)
        }}>View trace</Button>
        {f.lifecycleStatus === 'DRAFT' && canWriteFormulas(identity) && <Button disabled={request.busy} aria-label={`Release draft ${f.formulaVersionId}`} onClick={() => { setExistingDraft(f); setEditor(true) }}>Open draft for release</Button>}
      </article>)}
      <Paging next={history.nextCursor} busy={request.busy} load={load} />
    </Panel>}
    {trace && <section className="panel" role="region" aria-label="Formula trace"><h2>Formula trace</h2><p>{trace.formulaVersion.id} · v{trace.formulaVersion.versionNumber}</p>
      {trace.items.map(i => <article className="gateway-version" key={i.formulaItemId}><h3>{i.materialId}</h3><p>Specification: {i.specificationVersion.id} · v{i.specificationVersion.versionNumber}</p>
        <p>Supplier: {i.supplierId} · {i.supplierOrganisationId}</p><p>Effective: {i.effectiveDate}</p></article>)}
    </section>}
  </section>
}

export function GatewaySuppliers({ identity }: { identity: Identity }) {
  const listing = useCatalogPage(identity, '/api/specifications/suppliers', readSupplier, s => s.supplierId)
  return <section className="gateway-catalog"><div className="page-title"><div><h1>Suppliers</h1><p>Supplier records returned by the Specification service.</p></div></div>
    <ReadState {...listing} retry={() => listing.load()} />
    {listing.page && <Panel><div className="table-scroll" role="region" aria-label="Supplier catalog" tabIndex={0}><table><thead><tr>
      <th scope="col">Supplier</th><th scope="col">Code</th><th scope="col">Organisation</th><th scope="col">Materials</th>
    </tr></thead><tbody>{listing.page.items.map(s => <tr key={s.supplierId}><td><strong>{s.supplierName}</strong><br /><code>{s.supplierId}</code></td>
      <td>{s.supplierCode}</td><td>{s.organisationId}</td><td><Link to={`/materials?supplier=${encodeURIComponent(s.supplierId)}`}>View materials</Link></td></tr>)}</tbody></table></div>
      {!listing.page.items.length && <p>No suppliers were returned.</p>}<Paging next={listing.page.nextCursor} busy={listing.busy} load={listing.load} />
    </Panel>}
  </section>
}
export function GatewayMaterials({ identity }: { identity: Identity }) {
  const [params] = useSearchParams(), supplier = params.get('supplier')
  const listing = useCatalogPage(identity, '/api/specifications/materials' + (supplier ? `?supplierId=${encodeURIComponent(supplier)}` : ''), readMaterial, m => m.materialId)
  const [selected, setSelected] = useState<MaterialRecord | null>(null)
  return <section className="gateway-catalog"><div className="page-title"><div><h1>Materials & specs</h1><p>Inspect released specification versions and their ingredient matches.</p></div></div>
    {supplier && <p>Supplier filter: <code>{supplier}</code> · <Link to="/materials">All materials</Link></p>}
    <ReadState {...listing} retry={() => listing.load()} />
    {listing.page && <Panel><div className="table-scroll" role="region" aria-label="Material catalog" tabIndex={0}><table><thead><tr>
      <th scope="col">Material</th><th scope="col">Supplier</th><th scope="col">Description</th><th scope="col">Specifications</th>
    </tr></thead><tbody>{listing.page.items.map(m => <tr key={m.materialId}><td><strong>{m.materialName}</strong><br /><code>{m.materialId}</code></td>
      <td>{m.supplierId}</td><td>{m.description || 'Not supplied'}</td><td><Button variant="outline" aria-label={`View specifications ${m.materialName}`} onClick={() => setSelected(m)}>View specifications</Button></td></tr>)}</tbody></table></div>
      {!listing.page.items.length && <p>No materials were returned.</p>}<Paging next={listing.page.nextCursor} busy={listing.busy} load={listing.load} />
    </Panel>}
    {selected && <MaterialSpecifications key={selected.materialId} material={selected} identity={identity} close={() => setSelected(null)} />}
  </section>
}
function MaterialSpecifications({ material, identity, close }: { material: MaterialRecord; identity: Identity; close: () => void }) {
  const listing = useCatalogPage(identity, `/api/specifications/versions?materialId=${encodeURIComponent(material.materialId)}&status=RELEASED`, v => readSpecification(v, material.materialId), s => s.specificationVersionId)
  return <section className="panel" role="region" aria-label="Released specifications"><div className="section-head"><h2>{material.materialName} · Released specifications</h2><Button variant="outline" onClick={close}>Close specifications</Button></div>
    <ReadState {...listing} retry={() => listing.load()} />
    {listing.page && <>{!listing.page.items.length && <p>No released versions were returned.</p>}{listing.page.items.map(s => <article className="gateway-version" key={s.specificationVersionId}>
      <h3>Version {s.versionNumber} · {s.lifecycleStatus}</h3><code>{s.specificationVersionId}</code><p>Effective {s.effectiveDate} · Provenance {s.provenanceId}</p>
      <ul>{s.components.map(c => <li key={c.specComponentId}>{c.ingredientName} · {c.matchStatus} · “{c.rawPhrase}”</li>)}</ul>
    </article>)}<Paging next={listing.page.nextCursor} busy={listing.busy} load={listing.load} /></>}
  </section>
}
