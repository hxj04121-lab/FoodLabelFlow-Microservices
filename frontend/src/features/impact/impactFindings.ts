import type { Identity } from '@/auth/session'

export type VersionReference = { id: string; versionNumber: number }
export type ImpactKind = 'POTENTIAL' | 'CONFIRMED'
export type ImpactOutcome = 'NO_ACTION' | 'REVIEW_REQUIRED'
export type ImpactFilters = { kind: '' | ImpactKind; outcome: '' | ImpactOutcome }
export type ImpactFinding = {
  findingId: string; organisationId: string; kind: ImpactKind; outcome: ImpactOutcome
  requiredAllergens: string[]; declaredAllergens: string[]; missingAllergens: string[]
  unresolvedAllergens: Array<{ formulaItemId: string; specificationVersionId: string; specComponentId: string
    ingredientId: string | null; rawPhrase: string; reason: 'UNMAPPED' | 'AMBIGUOUS' }>
  specificationChange: { previous: VersionReference; candidate: VersionReference }
  formulaVersion: VersionReference; labelVersion: VersionReference; ruleSetVersion: VersionReference; jurisdiction: string
}
export type ImpactPage = { organisationId: string; items: ImpactFinding[]; nextCursor: string | null }
const record = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const id = (v: unknown): v is string => typeof v === 'string' && /^\S{1,128}$/.test(v)
const ids = (v: unknown): v is string[] => Array.isArray(v) && v.every(id) && new Set(v).size === v.length
const reference = (v: unknown): v is VersionReference => record(v) && id(v.id) && Number.isSafeInteger(v.versionNumber) && Number(v.versionNumber) > 0

// Presentation only; Compliance enforces IMPACT.RUN and verified manufacturer ownership.
export const canReadImpact = (identity: Identity) => identity.organisationType === 'MANUFACTURER' && identity.roles.includes('CHANGE_MANAGER')
export const findingPath = (findingId: string) => `/api/compliance/impact-findings/${encodeURIComponent(findingId)}`
export function findingsPath(filters: ImpactFilters, cursor?: string) {
  const params = new URLSearchParams({ limit: '20' })
  if (filters.kind) params.set('kind', filters.kind)
  if (filters.outcome) params.set('outcome', filters.outcome)
  if (cursor) params.set('cursor', cursor)
  return `/api/compliance/impact-findings?${params}`
}
export function readFinding(value: unknown, organisationId: string, expectedId?: string): ImpactFinding {
  if (!record(value) || !id(value.findingId) || value.organisationId !== organisationId
    || (expectedId !== undefined && value.findingId !== expectedId)
    || !['POTENTIAL', 'CONFIRMED'].includes(String(value.kind)) || !['NO_ACTION', 'REVIEW_REQUIRED'].includes(String(value.outcome))
    || !ids(value.requiredAllergens) || !ids(value.declaredAllergens) || !ids(value.missingAllergens)
    || !Array.isArray(value.unresolvedAllergens) || !value.unresolvedAllergens.every(item => record(item)
      && id(item.formulaItemId) && id(item.specificationVersionId) && id(item.specComponentId)
      && (item.ingredientId === null || typeof item.ingredientId === 'string')
      && typeof item.rawPhrase === 'string' && !!item.rawPhrase.trim() && ['UNMAPPED', 'AMBIGUOUS'].includes(String(item.reason)))
    || !record(value.specificationChange) || !reference(value.specificationChange.previous) || !reference(value.specificationChange.candidate)
    || !reference(value.formulaVersion) || !reference(value.labelVersion) || !reference(value.ruleSetVersion)
    || typeof value.jurisdiction !== 'string' || !value.jurisdiction.trim() || value.jurisdiction.length > 32
    || (value.outcome === 'REVIEW_REQUIRED') !== (value.missingAllergens.length > 0 || value.unresolvedAllergens.length > 0)) {
    throw new Error('The service returned different or unreadable finding evidence. Refresh the inbox before continuing.')
  }
  return value as ImpactFinding
}
export function readFindings(value: unknown, organisationId: string, filters: ImpactFilters): ImpactPage {
  if (!record(value) || value.organisationId !== organisationId || !Array.isArray(value.items) || value.items.length > 20
    || !(value.nextCursor === null || (typeof value.nextCursor === 'string' && !!value.nextCursor.trim()))) {
    throw new Error('The service returned an unreadable inbox. Refresh before continuing.')
  }
  const items = value.items.map(item => readFinding(item, organisationId))
  if (new Set(items.map(item => item.findingId)).size !== items.length
    || items.some(item => (filters.kind && item.kind !== filters.kind) || (filters.outcome && item.outcome !== filters.outcome))) {
    throw new Error('The service returned findings outside your selected filters. Refresh before continuing.')
  }
  return { organisationId, items, nextCursor: value.nextCursor as string | null }
}
