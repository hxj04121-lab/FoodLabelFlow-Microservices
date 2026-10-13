import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Button } from '@/components/ui/button'
import { getSession, publicApiGet, publicApiPost, publicApiPut, PublicApiError, type Identity } from '@/auth/session'
import { canLabelAction, canSubmitLabel, currentLabelPath, labelPath, readCurrentLabel, readDeclarationFacts,
  readLabel, readValidation, readDecision, readPublication, type DeclarationSource, type Decision, type LabelAction,
  type LabelDeclaration, type LabelDraft, type LabelValidation, type LabelDecision, type LabelPublication } from './labelWorkflow'

type WorkspaceAction = LabelAction | 'open' | 'current' | 'saveDeclarations'
const declarationSources: DeclarationSource[] = ['MIGRATED_PUBLIC_LABEL', 'FORMULA_DERIVED', 'SYSTEM_PROPOSED', 'USER_ENTERED']
const emptyDeclaration = (): LabelDeclaration => ({ allergenId: '', declarationType: 'CONTAINS', declarationSource: 'USER_ENTERED', displayText: null })

export function LabelWorkspace({ identity }: { identity: Identity }) {
  const [productId, setProductId] = useState('')
  const [jurisdiction, setJurisdiction] = useState('')
  const [currentProductId, setCurrentProductId] = useState('')
  const [currentJurisdiction, setCurrentJurisdiction] = useState('')
  const [labelId, setLabelId] = useState('')
  const [label, setLabel] = useState<LabelDraft | null>(null)
  const [declarations, setDeclarations] = useState<LabelDeclaration[]>([])
  const [validation, setValidation] = useState<LabelValidation | null>(null)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [traceId, setTraceId] = useState<string | null>(null)
  const [notice, setNotice] = useState('')
  const [reloadRequired, setReloadRequired] = useState(false)
  const [comments, setComments] = useState('')
  const [decision, setDecision] = useState<LabelDecision | null>(null)
  const [publication, setPublication] = useState<LabelPublication | null>(null)
  const [confirmPublish, setConfirmPublish] = useState(false)
  const pending = useRef<AbortController | null>(null)
  const resultHeading = useRef<HTMLHeadingElement>(null)
  const writable = canLabelAction(identity, 'create')
  useEffect(() => () => pending.current?.abort(), [])
  useEffect(() => { if (label) resultHeading.current?.focus() }, [label, validation])

  async function run(action: WorkspaceAction) {
    const permission = action === 'saveDeclarations' ? 'create' : action
    if (pending.current || (action !== 'open' && action !== 'current'
      && (!canLabelAction(identity, permission as LabelAction) || reloadRequired))) return
    if ((action === 'validate' || action === 'submit') && (!label || label.lifecycleStatus !== 'DRAFT')) return
    if (action === 'submit' && !canSubmitLabel(label, validation)) return
    const decisions: Partial<Record<WorkspaceAction, Decision>> = { approve: 'APPROVE', requestChanges: 'REQUEST_CHANGES', reject: 'REJECT' }
    const requestedDecision = decisions[action]
    if (requestedDecision && (!label || label.lifecycleStatus !== 'PENDING_REVIEW' || comments.length > 1000)) return
    if (action === 'publish' && (!label || label.lifecycleStatus !== 'APPROVED' || !confirmPublish)) return
    const controller = new AbortController()
    pending.current = controller
    setBusy(action); setError(''); setTraceId(null); setNotice(''); setValidation(null)
    if (action === 'open' || action === 'create' || action === 'current') {
      setLabel(null); setDeclarations([]); setDecision(null); setPublication(null); setComments(''); setConfirmPublish(false)
    }
    let decisionRecorded = false
    try {
      if (action === 'current') {
        const product = currentProductId.trim(); const jurisdictionCode = currentJurisdiction.trim()
        const next = readCurrentLabel(await publicApiGet<unknown>(currentLabelPath(product, jurisdictionCode), controller.signal), product, jurisdictionCode)
        const facts = readDeclarationFacts(await publicApiGet<unknown>(`${labelPath(next.labelVersionId)}/declarations`, controller.signal), next)
        if (controller.signal.aborted || getSession().identity !== identity) return
        setLabel(next); setDeclarations(facts.declarations); setLabelId(next.labelVersionId); setReloadRequired(false)
        setNotice('Current published label loaded from the service.')
      } else if (action === 'open' || action === 'create') {
        const request = { productId: productId.trim(), jurisdictionCode: jurisdiction.trim() }
        const value = action === 'open' ? await publicApiGet<unknown>(labelPath(labelId), controller.signal)
          : await publicApiPost<unknown>('/api/labels/drafts', request, controller.signal)
        const next = readLabel(value, action === 'open' ? { labelVersionId: labelId.trim() } : request)
        if (action === 'create' && next.lifecycleStatus !== 'DRAFT') throw new Error('The service did not confirm a new draft. Open the label before trying again.')
        const facts = readDeclarationFacts(await publicApiGet<unknown>(`${labelPath(next.labelVersionId)}/declarations`, controller.signal), next)
        if (controller.signal.aborted || getSession().identity !== identity) return
        setLabel(next); setDeclarations(facts.declarations); setLabelId(next.labelVersionId); setReloadRequired(false)
        if (action === 'create') { setProductId(''); setJurisdiction('') }
        setNotice(action === 'create' ? 'Draft created. Validate it before submitting for review.' : 'Label loaded.')
      } else if (action === 'validate' && label) {
        const result = readValidation(await publicApiPost<unknown>(`${labelPath(label.labelVersionId)}/validations`, undefined, controller.signal), label)
        if (controller.signal.aborted || getSession().identity !== identity) return
        setValidation(result)
        setNotice(result.status === 'PASSED' ? 'Validation passed. This draft is ready to submit for review.' : 'Validation failed. Resolve the blocking findings before submitting.')
      } else if (action === 'submit' && label) {
        const next = readLabel(await publicApiPost<unknown>(`${labelPath(label.labelVersionId)}/submit`, undefined, controller.signal),
          { labelVersionId: label.labelVersionId, productId: label.productId, jurisdictionCode: label.jurisdictionCode })
        if (next.lifecycleStatus !== 'PENDING_REVIEW' || next.formulaVersionId !== label.formulaVersionId
          || next.ruleSetVersionId !== label.ruleSetVersionId || next.versionNumber !== label.versionNumber) {
          throw new Error('The service did not confirm submission of this version. Open the label before continuing.')
        }
        if (controller.signal.aborted || getSession().identity !== identity) return
        setLabel(next); setNotice('Submitted for review. This version is now waiting for a reviewer.')
      } else if (requestedDecision && label) {
        const recorded = readDecision(await publicApiPost<unknown>(`${labelPath(label.labelVersionId)}/decisions`,
          { decision: requestedDecision, comments: comments.trim() || null }, controller.signal), label.labelVersionId, requestedDecision)
        if (controller.signal.aborted || getSession().identity !== identity) return
        setDecision(recorded); setComments(''); setReloadRequired(true); setConfirmPublish(false)
        decisionRecorded = true
        setNotice('Decision recorded. Loading the latest label status…')
        const next = readLabel(await publicApiGet<unknown>(labelPath(label.labelVersionId), controller.signal),
          { labelVersionId: label.labelVersionId, productId: label.productId, jurisdictionCode: label.jurisdictionCode })
        const facts = requestedDecision === 'REQUEST_CHANGES' && next.lifecycleStatus === 'DRAFT'
          ? readDeclarationFacts(await publicApiGet<unknown>(`${labelPath(label.labelVersionId)}/declarations`, controller.signal), next)
          : null
        if (controller.signal.aborted || getSession().identity !== identity) return
        setLabel(next)
        if (facts) setDeclarations(facts.declarations)
        setReloadRequired(false)
        setNotice(facts ? 'Changes requested. Edit the declarations, save them, then validate and resubmit.'
          : 'Decision recorded. The latest label status is shown below.')
      } else if (action === 'publish' && label) {
        const recorded = readPublication(await publicApiPost<unknown>(`${labelPath(label.labelVersionId)}/publish`, undefined, controller.signal), label.labelVersionId)
        if (controller.signal.aborted || getSession().identity !== identity) return
        setPublication(recorded); setConfirmPublish(false); setLabel({ ...label, lifecycleStatus: recorded.lifecycleStatus })
        setNotice('Publication confirmed for this label version.')
      } else if (action === 'saveDeclarations' && label) {
        const request = { declarations: declarations.map(declaration => ({ ...declaration,
          allergenId: declaration.allergenId.trim(), displayText: declaration.displayText?.trim() || null })) }
        const facts = readDeclarationFacts(await publicApiPut<unknown>(`${labelPath(label.labelVersionId)}/declarations`, request, controller.signal), label)
        if (controller.signal.aborted || getSession().identity !== identity) return
        setDeclarations(facts.declarations); setValidation(null); setReloadRequired(false)
        setNotice('Declarations saved. Validate this exact draft again before submitting.')
      }
      resultHeading.current?.focus()
    } catch (failure) {
      if (controller.signal.aborted || getSession().identity !== identity) return
      setError(failure instanceof Error ? failure.message : 'The request failed. Open the label before continuing.')
      if (decisionRecorded) setNotice('A decision was recorded. Open the label to confirm its latest status.')
      if (failure instanceof PublicApiError) setTraceId(failure.traceId)
      if (action !== 'open' && action !== 'current'
        && (!(failure instanceof PublicApiError) || failure.outcomeUnknown || failure.status === 409)) setReloadRequired(true)
    } finally {
      if (!controller.signal.aborted) setBusy('')
      if (pending.current === controller) pending.current = null
    }
  }
  function create(event: FormEvent) { event.preventDefault(); if (productId.trim() && jurisdiction.trim()) void run('create') }
  function open(event: FormEvent) { event.preventDefault(); if (labelId.trim()) void run('open') }
  function loadCurrent(event: FormEvent) { event.preventDefault(); if (currentProductId.trim() && currentJurisdiction.trim()) void run('current') }
  const declarationsValid = declarations.every(declaration => declaration.allergenId.trim()
    && declarationSources.includes(declaration.declarationSource))
    && new Set(declarations.map(declaration => declaration.allergenId.trim())).size === declarations.length

  return <section className="g2-label-workspace" aria-labelledby="labels-title">
    <h2 id="labels-title">Labels</h2>
    <p>Prepare a draft, submit it for review, and publish an approved version.</p>
    <div className="g2-label-entry-grid">
      {writable ? <form onSubmit={create} aria-labelledby="create-g2-label-title">
        <fieldset disabled={!!busy}><legend id="create-g2-label-title">Create a draft</legend>
          <label htmlFor="g2-label-product">Product ID</label>
          <input id="g2-label-product" required value={productId} onChange={event => setProductId(event.target.value)} autoComplete="off" />
          <label htmlFor="g2-label-jurisdiction">Jurisdiction code</label>
          <input id="g2-label-jurisdiction" required value={jurisdiction} onChange={event => setJurisdiction(event.target.value)} autoComplete="off" />
          <p className="g2-label-hint">Use a product from your organisation. Its released formula and rule set are selected by the service.</p>
          <Button type="submit" disabled={reloadRequired || !productId.trim() || !jurisdiction.trim()}>{busy === 'create' ? 'Creating draft…' : 'Create draft'}</Button>
        </fieldset>
      </form> : <div className="g2-label-readonly"><h3>Open an existing version</h3><p>Creating, validating and submitting drafts requires the label officer role. Open a version to see the actions available to your account.</p></div>}
      <form onSubmit={open} aria-labelledby="open-g2-label-title">
        <fieldset disabled={!!busy}><legend id="open-g2-label-title">Open a label version</legend>
          <label htmlFor="g2-label-version">Label version ID</label>
          <input id="g2-label-version" required value={labelId} onChange={event => setLabelId(event.target.value)} autoComplete="off" />
          <p className="g2-label-hint">Open the exact version you want to work on.</p>
          <Button type="submit" variant="outline" disabled={!labelId.trim()}>{busy === 'open' ? 'Opening label…' : 'Open label'}</Button>
        </fieldset>
      </form>
      <form onSubmit={loadCurrent} aria-labelledby="current-g2-label-title">
        <fieldset disabled={!!busy}><legend id="current-g2-label-title">Find the current published label</legend>
          <label htmlFor="g2-current-label-product">Current product ID</label>
          <input id="g2-current-label-product" required value={currentProductId} onChange={event => setCurrentProductId(event.target.value)} autoComplete="off" />
          <label htmlFor="g2-current-label-jurisdiction">Current jurisdiction code</label>
          <input id="g2-current-label-jurisdiction" required value={currentJurisdiction} onChange={event => setCurrentJurisdiction(event.target.value)} autoComplete="off" />
          <p className="g2-label-hint">The service returns the current published version for your organisation.</p>
          <Button type="submit" variant="outline" disabled={!currentProductId.trim() || !currentJurisdiction.trim()}>
            {busy === 'current' ? 'Loading current label…' : 'Load current label'}
          </Button>
        </fieldset>
      </form>
    </div>
    {busy && <p role="status" className="g2-label-feedback">{({ create: 'Creating draft…', open: 'Opening label…', current: 'Loading current label…', saveDeclarations: 'Saving declarations…',
      validate: 'Validating draft…', submit: 'Submitting for review…', approve: 'Recording approval…', requestChanges: 'Requesting changes…', reject: 'Recording rejection…', publish: 'Publishing label…' })[busy]}</p>}
    {error && <div role="alert" className="g2-label-feedback g2-label-error"><p>{error}</p>{traceId && <small>Reference: {traceId}</small>}</div>}
    {reloadRequired && <p className="g2-label-feedback">Open the label again before another change. If creation was not confirmed, ask your workspace administrator to check the reference above.</p>}
    {notice && <p role="status" className="g2-label-feedback">{notice}</p>}
    {label && <section className="g2-label-detail" aria-labelledby="g2-label-detail-title" aria-busy={!!busy}>
      <div className="g2-label-detail-heading"><h3 id="g2-label-detail-title" tabIndex={-1} ref={resultHeading}>Label version {label.versionNumber}</h3>
        <span className="g2-label-state">{label.lifecycleStatus.replaceAll('_', ' ')}</span></div>
      <dl className="g2-label-facts">
        {([['Label ID', label.labelVersionId], ['Product', label.productId], ['Formula version', label.formulaVersionId],
          ['Rule set version', label.ruleSetVersionId], ['Jurisdiction', label.jurisdictionCode]]).map(([name, value]) => <div key={name}><dt>{name}</dt><dd>{value}</dd></div>)}
      </dl>
      {label.lifecycleStatus === 'PUBLISHED' && <p className="g2-label-hint">This is the current published label returned by the service.</p>}
      {writable && label.lifecycleStatus === 'DRAFT' && <section className="g2-label-validation" aria-labelledby="g2-label-declarations-title">
        <h4 id="g2-label-declarations-title">Edit declarations</h4>
        <p className="g2-label-hint">Saving replaces the declaration set and advances the server-owned draft revision. Validate again before submitting.</p>
        {declarations.length === 0 && <p>No declarations are stored for this draft.</p>}
        {declarations.map((declaration, index) => <fieldset key={`${declaration.allergenId}-${index}`} disabled={!!busy || reloadRequired}>
          <legend>Declaration {index + 1}</legend>
          <label htmlFor={`g2-label-allergen-${index}`}>Allergen ID</label>
          <input id={`g2-label-allergen-${index}`} required value={declaration.allergenId}
            onChange={event => setDeclarations(current => current.map((item, itemIndex) => itemIndex === index ? { ...item, allergenId: event.target.value } : item))} />
          <label htmlFor={`g2-label-source-${index}`}>Declaration source</label>
          <select id={`g2-label-source-${index}`} value={declaration.declarationSource}
            onChange={event => setDeclarations(current => current.map((item, itemIndex) => itemIndex === index ? { ...item, declarationSource: event.target.value as DeclarationSource } : item))}>
            {declarationSources.map(source => <option key={source} value={source}>{source.replaceAll('_', ' ')}</option>)}
          </select>
          <label htmlFor={`g2-label-display-${index}`}>Display text (optional)</label>
          <input id={`g2-label-display-${index}`} value={declaration.displayText ?? ''}
            onChange={event => setDeclarations(current => current.map((item, itemIndex) => itemIndex === index ? { ...item, displayText: event.target.value || null } : item))} />
          <Button type="button" variant="outline" disabled={!!busy || reloadRequired}
            onClick={() => setDeclarations(current => current.filter((_, itemIndex) => itemIndex !== index))}>Remove declaration</Button>
        </fieldset>)}
        <div className="g2-label-actions">
          <Button type="button" variant="outline" disabled={!!busy || reloadRequired}
            onClick={() => setDeclarations(current => [...current, emptyDeclaration()])}>Add declaration</Button>
          <Button type="button" disabled={!!busy || reloadRequired || !declarationsValid}
            onClick={() => void run('saveDeclarations')}>{busy === 'saveDeclarations' ? 'Saving declarations…' : 'Save declarations'}</Button>
        </div>
      </section>}
      {writable && label.lifecycleStatus === 'DRAFT' && <div className="g2-label-actions">
        <Button variant="outline" disabled={!!busy || reloadRequired} onClick={() => void run('validate')}>Validate draft</Button>
        <Button disabled={!!busy || reloadRequired || !canSubmitLabel(label, validation)} onClick={() => void run('submit')}>Submit for review</Button>
        {!validation && <p>Validate this draft before submitting it.</p>}
      </div>}
      {validation && <section className="g2-label-validation" aria-labelledby="g2-label-validation-title">
        <h4 id="g2-label-validation-title">Validation {validation.status === 'PASSED' ? 'passed' : 'failed'}</h4>
        <p className="g2-label-hint">Run {validation.validationRunId}</p>
        {validation.findings.length === 0 ? <p>No findings were reported for this validation.</p> : <ul>
          {validation.findings.map((finding, index) => <li key={`${finding.resultCode}-${index}`}>
            <strong>{{ ERROR: 'Error', WARNING: 'Warning', INFO: 'Information' }[finding.severity]} · {finding.resultCode}</strong>
            <p>{finding.message}</p>
            <small>{finding.blocking ? 'Blocks submission' : 'Does not block submission'} · {finding.passed ? 'Check passed' : 'Check not passed'}</small>
          </li>)}
        </ul>}
      </section>}
      {label.lifecycleStatus === 'PENDING_REVIEW' && canLabelAction(identity, 'approve') && <section className="g2-label-validation" aria-labelledby="g2-review-title">
        <h4 id="g2-review-title">Review this version</h4>
        <p>The creator cannot approve this version. Approval requires a different reviewer.</p>
        <label htmlFor="g2-review-comments">Review comments (optional)</label>
        <textarea id="g2-review-comments" maxLength={1000} rows={3} value={comments} disabled={!!busy || reloadRequired}
          onChange={event => setComments(event.target.value)} />
        <div className="g2-label-actions">
          <Button disabled={!!busy || reloadRequired} onClick={() => void run('approve')}>Approve version</Button>
          <Button variant="outline" disabled={!!busy || reloadRequired} onClick={() => void run('requestChanges')}>Request changes</Button>
          <Button variant="outline" disabled={!!busy || reloadRequired} onClick={() => void run('reject')}>Reject version</Button>
        </div>
      </section>}
      {decision && <section className="g2-label-validation" aria-labelledby="g2-decision-title"><h4 id="g2-decision-title">Decision recorded</h4>
        <p>{({ APPROVE: 'Approved', REQUEST_CHANGES: 'Changes requested', REJECT: 'Rejected' })[decision.decision]} · {decision.decidedAt}</p>
        {decision.comments && <p>{decision.comments}</p>}<p className="g2-label-hint">Decision {decision.decisionId}</p>
      </section>}
      {label.lifecycleStatus === 'APPROVED' && canLabelAction(identity, 'publish') && <section className="g2-label-validation" aria-labelledby="g2-publish-title">
        <h4 id="g2-publish-title">Publish this version</h4>
        <p>Publishing replaces the current label for this product and jurisdiction.</p>
        <label className="g2-publish-confirm"><input type="checkbox" checked={confirmPublish} disabled={!!busy || reloadRequired}
          onChange={event => setConfirmPublish(event.target.checked)} />I have checked this version and want to publish it.</label>
        <Button disabled={!!busy || reloadRequired || !confirmPublish} onClick={() => void run('publish')}>Publish version</Button>
      </section>}
      {publication && <section className="g2-label-validation" aria-labelledby="g2-publication-title"><h4 id="g2-publication-title">Publication receipt</h4>
        <p>Published {publication.publishedAt}</p><p className="g2-label-hint">Record {publication.publicationRecordId}</p>
        <p>Open this label version again to check its latest status.</p>
      </section>}
    </section>}
  </section>
}
