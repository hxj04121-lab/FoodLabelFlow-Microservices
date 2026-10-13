import type { Identity } from '@/auth/session'

export type LabelAction = 'create' | 'validate' | 'submit' | 'approve' | 'requestChanges' | 'reject' | 'publish'
const actionRoles: Record<LabelAction, string> = {
  create: 'LABEL_OFFICER', validate: 'LABEL_OFFICER', submit: 'LABEL_OFFICER',
  approve: 'APPROVER', requestChanges: 'APPROVER', reject: 'APPROVER', publish: 'PUBLISHER',
}
// Presentation only: the service must enforce permissions, ownership and maker-checker.
export function canLabelAction(identity: Identity | undefined, action: LabelAction): boolean {
  return identity?.organisationType === 'MANUFACTURER' && identity.roles.includes(actionRoles[action])
}

export type LabelDraft = {
  labelVersionId: string; productId: string; formulaVersionId: string; ruleSetVersionId: string
  jurisdictionCode: string; versionNumber: number
  lifecycleStatus: 'DRAFT' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'PUBLISHED' | 'SUPERSEDED'
}
export type ValidationFinding = {
  resultCode: string; severity: 'INFO' | 'WARNING' | 'ERROR'; passed: boolean; blocking: boolean; message: string
}
export type LabelValidation = {
  validationRunId: string; labelVersionId: string; formulaVersionId: string; ruleSetVersionId: string
  jurisdictionCode: string; status: 'PASSED' | 'FAILED'; findings: ValidationFinding[]
}
export type Decision = 'APPROVE' | 'REQUEST_CHANGES' | 'REJECT'
export type LabelDecision = { decisionId: string; labelVersionId: string; decision: Decision; decidedAt: string; comments?: string | null }
export type LabelPublication = { publicationRecordId: string; labelVersionId: string; lifecycleStatus: 'PUBLISHED'; publishedAt: string }
export type DeclarationSource = 'MIGRATED_PUBLIC_LABEL' | 'FORMULA_DERIVED' | 'SYSTEM_PROPOSED' | 'USER_ENTERED'
export type LabelDeclaration = { allergenId: string; declarationType: 'CONTAINS'; declarationSource: DeclarationSource; displayText: string | null }
export type LabelDeclarationFacts = { labelVersionId: string; formulaVersionId: string; ruleSetVersionId: string; jurisdictionCode: string; declarations: LabelDeclaration[] }
const isText = (value: unknown): value is string => typeof value === 'string' && !!value.trim()
const isRecord = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)

export function readLabel(value: unknown, expected?: { labelVersionId?: string; productId?: string; jurisdictionCode?: string }): LabelDraft {
  if (!isRecord(value) || !['labelVersionId', 'productId', 'formulaVersionId', 'ruleSetVersionId', 'jurisdictionCode'].every(key => isText(value[key]))
    || !Number.isSafeInteger(value.versionNumber) || Number(value.versionNumber) < 1
    || !['DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'PUBLISHED', 'SUPERSEDED'].includes(String(value.lifecycleStatus))
    || (expected && Object.entries(expected).some(([key, expectedValue]) => value[key] !== expectedValue))) {
    throw new Error('The service returned a different or unreadable label. Open the label again before continuing.')
  }
  return value as LabelDraft
}

export function readValidation(value: unknown, label: LabelDraft): LabelValidation {
  if (!isRecord(value) || !isText(value.validationRunId) || !['labelVersionId', 'formulaVersionId', 'ruleSetVersionId', 'jurisdictionCode']
    .every(key => value[key] === label[key as keyof LabelDraft]) || !['PASSED', 'FAILED'].includes(String(value.status))
    || !Array.isArray(value.findings) || !value.findings.every(finding => isRecord(finding)
      && isText(finding.resultCode) && isText(finding.message) && ['INFO', 'WARNING', 'ERROR'].includes(String(finding.severity))
      && typeof finding.passed === 'boolean' && typeof finding.blocking === 'boolean'
      && (!finding.blocking || (finding.severity === 'ERROR' && !finding.passed)))
    || (value.status === 'PASSED' ? value.findings.some(finding => finding.blocking) : !value.findings.some(finding => finding.blocking))) {
    throw new Error('The validation result does not match this draft or is unreadable. Open the label and validate it again.')
  }
  return value as LabelValidation
}

export function canSubmitLabel(label: LabelDraft | null, validation: LabelValidation | null): boolean {
  if (!label || label.lifecycleStatus !== 'DRAFT' || !validation || validation.status !== 'PASSED') return false
  try { readValidation(validation, label); return true } catch { return false }
}

export const labelPath = (id: string) => `/api/labels/${encodeURIComponent(id.trim())}`
export const currentLabelPath = (productId: string, jurisdictionCode: string) =>
  `/api/labels/current?${new URLSearchParams({ productId: productId.trim(), jurisdictionCode: jurisdictionCode.trim() })}`

export function readCurrentLabel(value: unknown, productId: string, jurisdictionCode: string): LabelDraft {
  const label = readLabel(value, { productId, jurisdictionCode })
  if (label.lifecycleStatus !== 'PUBLISHED') throw new Error('The service did not return the current published label.')
  return label
}

export function readDeclarationFacts(value: unknown, label: LabelDraft): LabelDeclarationFacts {
  if (!isRecord(value) || value.labelVersionId !== label.labelVersionId
    || value.formulaVersionId !== label.formulaVersionId || value.ruleSetVersionId !== label.ruleSetVersionId
    || value.jurisdictionCode !== label.jurisdictionCode || !Array.isArray(value.declarations)
    || !value.declarations.every(declaration => isRecord(declaration) && isText(declaration.allergenId)
      && declaration.declarationType === 'CONTAINS'
      && ['MIGRATED_PUBLIC_LABEL', 'FORMULA_DERIVED', 'SYSTEM_PROPOSED', 'USER_ENTERED'].includes(String(declaration.declarationSource))
      && (declaration.displayText === null || typeof declaration.displayText === 'string'))) {
    throw new Error('The service returned declarations for a different or unreadable label. Open the label again before continuing.')
  }
  return value as LabelDeclarationFacts
}

export function readDecision(value: unknown, labelVersionId: string, decision: Decision): LabelDecision {
  if (!isRecord(value) || !isText(value.decisionId) || value.labelVersionId !== labelVersionId || value.decision !== decision
    || !isText(value.decidedAt) || !Number.isFinite(Date.parse(value.decidedAt))
    || (value.comments !== undefined && value.comments !== null && typeof value.comments !== 'string')) {
    throw new Error('The service did not confirm this decision. Open the label before trying again.')
  }
  return value as LabelDecision
}

export function readPublication(value: unknown, labelVersionId: string): LabelPublication {
  if (!isRecord(value) || !isText(value.publicationRecordId) || value.labelVersionId !== labelVersionId
    || value.lifecycleStatus !== 'PUBLISHED' || !isText(value.publishedAt) || !Number.isFinite(Date.parse(value.publishedAt))) {
    throw new Error('The service did not confirm publication. Open the label before trying again.')
  }
  return value as LabelPublication
}
