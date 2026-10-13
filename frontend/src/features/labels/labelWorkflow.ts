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
