import { publicApiGet, publicApiPost, PublicApiError, type Identity } from '@/auth/session'

export type VersionRef = { id: string; versionNumber: number }
export type ProductRecord = { productId: string; organisationId: string; productDescription: string; brandOwner: string;
  normalizedCategory: string; currentFormulaVersion: VersionRef | null }
export type FormulaItem = { formulaItemId: string; sequenceNo: number; materialId: string; specificationVersion: VersionRef; quantity: number | null; unit: string | null }
export type FormulaRecord = { formulaVersionId: string; organisationId: string; productId: string; versionNumber: number;
  lifecycleStatus: 'DRAFT' | 'RELEASED' | 'RETIRED'; isCurrentReleased: boolean; provenanceId: string;
  createdBySubject: string; releasedBySubject: string | null; releasedAt: string | null; items: FormulaItem[] }
export type SupplierRecord = { supplierId: string; organisationId: string; supplierCode: string; supplierName: string }
export type MaterialRecord = { materialId: string; organisationId: string; supplierId: string; materialCode: string; materialName: string; description: string | null; provenanceId: string }
export type SpecificationRecord = { specificationVersionId: string; organisationId: string; materialId: string; versionNumber: number;
  lifecycleStatus: 'RELEASED'; effectiveDate: string; provenanceId: string;
  components: Array<{ specComponentId: string; sequenceNo: number; ingredientId: string; ingredientName: string; rawPhrase: string; matchStatus: string }> }
export type ReleasedSpec = { specificationVersion: VersionRef; materialId: string; supplierId: string; supplierOrganisationId: string; effectiveDate: string }
export type FormulaTrace = { formulaVersion: VersionRef; productId: string; items: Array<{ formulaItemId: string; sequenceNo: number;
  materialId: string; specificationVersion: VersionRef; supplierId: string; supplierOrganisationId: string; effectiveDate: string }> }
export type CatalogPage<T> = { items: T[]; nextCursor: string | null }
export type FormulaInput = { productId: string; provenanceId: string; items: Array<{ materialId: string; specificationVersionId: string; quantity: number | null; unit: string | null }> }

const unreadable = () => new Error('The service returned unreadable or unrelated catalog information. Refresh and check the reference.')
function object(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) throw unreadable(); return value as Record<string, unknown> }
function text(value: unknown): string { if (typeof value !== 'string' || !value.trim()) throw unreadable(); return value }
function nullableText(value: unknown): string | null { if (value === null) return null; if (typeof value !== 'string') throw unreadable(); return value }
function id(value: unknown): string { const result = text(value); if (!/^\S{1,128}$/.test(result)) throw unreadable(); return result }
function subject(value: unknown): string { const result = text(value); if (result.length > 255) throw unreadable(); return result }
function integer(value: unknown): number { if (!Number.isSafeInteger(value) || Number(value) < 1) throw unreadable(); return Number(value) }
function date(value: unknown): string { const result = text(value); if (!/^\d{4}-\d{2}-\d{2}$/.test(result) || !Number.isFinite(Date.parse(result))) throw unreadable(); return result }
function ref(value: unknown): VersionRef { const v = object(value); return { id: id(v.id), versionNumber: integer(v.versionNumber) } }
function rows<T>(value: unknown, read: (row: unknown) => T): T[] { if (!Array.isArray(value)) throw unreadable(); return value.map(read) }
export const canReadProducts = (identity: Identity) => identity.organisationType === 'MANUFACTURER'
export const canWriteFormulas = (identity: Identity) => canReadProducts(identity) && identity.roles.includes('ADMIN_DATA_MAINTENANCE')

export function readProduct(value: unknown, identity: Identity, expectedId?: string): ProductRecord {
  const p = object(value)
  if (p.organisationId !== identity.organisationId || (expectedId && p.productId !== expectedId)) throw unreadable()
  return { productId: id(p.productId), organisationId: id(p.organisationId), productDescription: text(p.productDescription),
    brandOwner: text(p.brandOwner), normalizedCategory: text(p.normalizedCategory), currentFormulaVersion: p.currentFormulaVersion === null ? null : ref(p.currentFormulaVersion) }
}
export function readFormula(value: unknown, identity: Identity, productId: string, expectedId?: string): FormulaRecord {
  const f = object(value)
  if (f.organisationId !== identity.organisationId || f.productId !== productId || (expectedId && f.formulaVersionId !== expectedId)
    || !['DRAFT', 'RELEASED', 'RETIRED'].includes(String(f.lifecycleStatus)) || typeof f.isCurrentReleased !== 'boolean'
    || (f.isCurrentReleased && f.lifecycleStatus !== 'RELEASED')) throw unreadable()
  const items = rows(f.items, value => {
    const i = object(value)
    if (i.quantity !== null && (typeof i.quantity !== 'number' || !Number.isFinite(i.quantity) || i.quantity <= 0)) throw unreadable()
    if ((i.quantity === null) !== (i.unit === null)) throw unreadable()
    return { formulaItemId: id(i.formulaItemId), sequenceNo: integer(i.sequenceNo), materialId: id(i.materialId),
      specificationVersion: ref(i.specificationVersion), quantity: i.quantity as number | null, unit: nullableText(i.unit) }
  })
  if (new Set(items.map(i => i.formulaItemId)).size !== items.length || new Set(items.map(i => i.sequenceNo)).size !== items.length) throw unreadable()
  return { formulaVersionId: id(f.formulaVersionId), organisationId: id(f.organisationId), productId: id(f.productId), versionNumber: integer(f.versionNumber),
    lifecycleStatus: f.lifecycleStatus as FormulaRecord['lifecycleStatus'], isCurrentReleased: f.isCurrentReleased, provenanceId: id(f.provenanceId),
    createdBySubject: subject(f.createdBySubject), releasedBySubject: f.releasedBySubject === null ? null : subject(f.releasedBySubject), releasedAt: nullableText(f.releasedAt), items }
}
export function readSupplier(value: unknown): SupplierRecord {
  const s = object(value); return { supplierId: id(s.supplierId), organisationId: id(s.organisationId), supplierCode: text(s.supplierCode), supplierName: text(s.supplierName) }
}
export function readMaterial(value: unknown): MaterialRecord {
  const m = object(value); return { materialId: id(m.materialId), organisationId: id(m.organisationId), supplierId: id(m.supplierId),
    materialCode: text(m.materialCode), materialName: text(m.materialName), description: nullableText(m.description), provenanceId: id(m.provenanceId) }
}
export function readSpecification(value: unknown, materialId: string): SpecificationRecord {
  const s = object(value)
  if (s.materialId !== materialId || s.lifecycleStatus !== 'RELEASED') throw unreadable()
  return { specificationVersionId: id(s.specificationVersionId), organisationId: id(s.organisationId), materialId: id(s.materialId),
    versionNumber: integer(s.versionNumber), lifecycleStatus: 'RELEASED', effectiveDate: date(s.effectiveDate), provenanceId: id(s.provenanceId),
    components: rows(s.components, value => { const c = object(value); return { specComponentId: id(c.specComponentId), sequenceNo: integer(c.sequenceNo),
      ingredientId: id(c.ingredientId), ingredientName: text(c.ingredientName), rawPhrase: text(c.rawPhrase), matchStatus: text(c.matchStatus) } }) }
}
export function readReleasedSpec(value: unknown): ReleasedSpec {
  const s = object(value); return { specificationVersion: ref(s.specificationVersion), materialId: id(s.materialId), supplierId: id(s.supplierId),
    supplierOrganisationId: id(s.supplierOrganisationId), effectiveDate: date(s.effectiveDate) }
}
export function readTrace(value: unknown, formula: FormulaRecord): FormulaTrace {
  const t = object(value), version = ref(t.formulaVersion)
  if (version.id !== formula.formulaVersionId || version.versionNumber !== formula.versionNumber || t.productId !== formula.productId) throw unreadable()
  const items = rows(t.items, value => { const i = object(value); return { formulaItemId: id(i.formulaItemId), sequenceNo: integer(i.sequenceNo),
    materialId: id(i.materialId), specificationVersion: ref(i.specificationVersion), supplierId: id(i.supplierId),
    supplierOrganisationId: id(i.supplierOrganisationId), effectiveDate: date(i.effectiveDate) } })
  if (items.length !== formula.items.length || new Set(items.map(i => i.formulaItemId)).size !== items.length
    || items.some(i => !formula.items.some(f => f.formulaItemId === i.formulaItemId && f.materialId === i.materialId && f.sequenceNo === i.sequenceNo
      && f.specificationVersion.id === i.specificationVersion.id && f.specificationVersion.versionNumber === i.specificationVersion.versionNumber))) throw unreadable()
  return { formulaVersion: version, productId: formula.productId, items }
}
export async function catalogPage<T>(path: string, read: (value: unknown) => T, key: (value: T) => string, signal: AbortSignal, cursor?: string): Promise<CatalogPage<T>> {
  const params = new URLSearchParams({ limit: '20' }); if (cursor) params.set('cursor', cursor)
  const p = object(await publicApiGet<unknown>(path + (path.includes('?') ? '&' : '?') + params, signal))
  const items = rows(p.items, read)
  if (items.length > 20 || new Set(items.map(key)).size !== items.length
    || (p.nextCursor !== null && (typeof p.nextCursor !== 'string' || !p.nextCursor.trim() || p.nextCursor.length > 4096 || p.nextCursor === cursor))) throw unreadable()
  return { items, nextCursor: p.nextCursor as string | null }
}
export async function createGatewayFormula(input: FormulaInput, identity: Identity, signal: AbortSignal) {
  const value = await publicApiPost<unknown>('/api/formulations/formula-versions', input, signal)
  try {
    const f = readFormula(value, identity, input.productId), items = [...f.items].sort((a, b) => a.sequenceNo - b.sequenceNo)
    if (f.lifecycleStatus !== 'DRAFT' || f.provenanceId !== input.provenanceId || f.createdBySubject !== identity.subject
      || items.length !== input.items.length || items.some((item, index) => {
        const requested = input.items[index]
        return item.sequenceNo !== index + 1 || item.materialId !== requested.materialId || item.specificationVersion.id !== requested.specificationVersionId
          || item.quantity !== requested.quantity || item.unit !== requested.unit
      })) throw unreadable()
    return f
  }
  catch { throw new PublicApiError('The draft result could not be confirmed. Check server history before another write.', 201, 'INVALID_RESPONSE', null, true) }
}
export async function releaseGatewayFormula(formula: FormulaRecord, expected: string | null, identity: Identity, signal: AbortSignal) {
  const value = await publicApiPost<unknown>(`/api/formulations/formula-versions/${encodeURIComponent(formula.formulaVersionId)}/release`, { expectedCurrentFormulaVersionId: expected }, signal)
  try { const f = readFormula(value, identity, formula.productId, formula.formulaVersionId)
    if (f.lifecycleStatus !== 'RELEASED' || !f.isCurrentReleased || f.versionNumber !== formula.versionNumber || f.provenanceId !== formula.provenanceId
      || f.createdBySubject !== formula.createdBySubject || f.releasedBySubject !== identity.subject || !f.releasedAt || !Number.isFinite(Date.parse(f.releasedAt))
      || f.items.length !== formula.items.length || f.items.some(i => !formula.items.some(previous => previous.formulaItemId === i.formulaItemId
        && previous.sequenceNo === i.sequenceNo && previous.materialId === i.materialId && previous.specificationVersion.id === i.specificationVersion.id
        && previous.specificationVersion.versionNumber === i.specificationVersion.versionNumber && previous.quantity === i.quantity && previous.unit === i.unit))) throw unreadable()
    return f }
  catch { throw new PublicApiError('The release result could not be confirmed. Check server history before another write.', 200, 'INVALID_RESPONSE', null, true) }
}
export function catalogError(error: unknown): string {
  if (!(error instanceof PublicApiError)) return error instanceof Error ? error.message : 'The catalog request could not be completed.'
  const messages: Record<number, string> = { 400: 'Check the catalog entries and try again.', 403: 'You do not have permission to view or change this resource.',
    404: 'This catalog resource was not found. Check the reference.', 409: 'The formula or current version has changed. Refresh before another action.',
    422: 'Required specification data is not ready. Refresh after the service has processed it.', 429: 'Too many requests. Wait before trying again.' }
  const message = error.outcomeUnknown ? 'The write result is unconfirmed. Check server history; do not repeat the write.' : messages[error.status] || 'The catalog service is unavailable. Try again later.'
  return message + (error.traceId ? ` Reference: ${error.traceId}` : '')
}
