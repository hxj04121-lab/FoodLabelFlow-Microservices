import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Rate, Trend } from 'k6/metrics';

const targets = JSON.parse(open('../../docs/swe5001/test-plan-G0-M2-targets.v1.json'));
const dry = __ENV.DRY_RUN !== 'false';
const baseUrl = (__ENV.BASE_URL || 'http://127.0.0.1:8089').replace(/\/$/, '');
if (dry && !/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(baseUrl)) {
  throw new Error('Local dry run requires a loopback HTTP stub URL.');
}
const cases = new SharedArray('preview cases', () => JSON.parse(open(__ENV.REQUEST_FILE || 'fixtures/local-preview.json')).cases);
if (!cases.length) throw new Error('Request fixture must contain at least one reviewed case.');
const tokens = dry ? {} : JSON.parse(__ENV.AUTH_TOKENS_JSON || '{}');
const rate = dry ? 2 : Number(__ENV.RATE);
if (!dry && (!targets.arrivalRatesPerSecond.includes(rate) || !__ENV.TRIAL_ID || !['1', '3'].includes(__ENV.REPLICAS))) {
  throw new Error('Runtime candidate requires a predeclared RATE, TRIAL_ID and REPLICAS=1|3.');
}
if (!dry && cases.some(c => !tokens[c.request.organisationId])) {
  throw new Error('Supply organisation-bound tokens via transient AUTH_TOKENS_JSON.');
}
const slots = Math.ceil(rate * targets.slo.p95MillisecondsMaximum / 1000 * 1.5);
const contractErrors = new Rate('contract_errors');
const validatedDuration = new Trend('validated_response_duration', true);
export const options = {
  tags: { run_kind: dry ? 'local_stub' : 'runtime_candidate', trial: __ENV.TRIAL_ID || 'local', replicas: __ENV.REPLICAS || 'local_stub' },
  scenarios: dry ? {
    dry_run: { executor: 'ramping-arrival-rate', startRate: rate, timeUnit: '1s', preAllocatedVUs: slots,
      maxVUs: slots * 2, stages: [{ duration: '5s', target: rate }], gracefulStop: '3s' },
  } : {
    warmup: { executor: 'constant-arrival-rate', rate: 1, timeUnit: '1s', duration: `${targets.warmupSeconds}s`,
      preAllocatedVUs: 3, maxVUs: 6, gracefulStop: '0s' },
    measured_trial: { executor: 'ramping-arrival-rate', startTime: `${targets.warmupSeconds}s`, startRate: 1,
      timeUnit: '1s', preAllocatedVUs: slots, maxVUs: slots * 2, gracefulStop: '3s', stages: [
        { duration: `${targets.rateRampSeconds}s`, target: rate },
        { duration: `${targets.settleSeconds}s`, target: rate },
        { duration: `${targets.measuredHoldSeconds}s`, target: rate },
      ] },
  },
  thresholds: {
    'contract_errors{phase:hold}': [`rate<${targets.slo.errorFractionExclusiveMaximum}`],
    'validated_response_duration{phase:hold}': [`p(95)<=${targets.slo.p95MillisecondsMaximum}`],
    dropped_iterations: [`count==${targets.slo.droppedIterationsMaximum}`],
  },
};

function sameVersion(left, right) {
  return left && right && left.id === right.id && left.versionNumber === right.versionNumber;
}
const opaqueId = value => typeof value === 'string' && value.length > 0 && value.length <= 128 && !/\s/.test(value);
const versionReference = value => value && Object.keys(value).length === 2 && opaqueId(value.id) && Number.isInteger(value.versionNumber) && value.versionNumber >= 1;
const findingFields = ['findingId', 'organisationId', 'kind', 'outcome', 'requiredAllergens', 'declaredAllergens', 'missingAllergens', 'unresolvedAllergens', 'specificationChange', 'formulaVersion', 'labelVersion', 'ruleSetVersion', 'jurisdiction'];
function validFinding(finding, request) {
  if (!finding || finding.kind !== 'POTENTIAL' || finding.organisationId !== request.organisationId ||
      finding.ruleSetVersion?.id !== request.ruleSetVersionId || finding.jurisdiction !== request.jurisdiction ||
      !sameVersion(finding.specificationChange?.previous, request.specificationChange.previous) ||
      !sameVersion(finding.specificationChange?.candidate, request.specificationChange.candidate)) return false;
  if (Object.keys(finding).length !== findingFields.length || Object.keys(finding).some(key => !findingFields.includes(key)) || !opaqueId(finding.findingId)) return false;
  for (const name of ['formulaVersion', 'labelVersion', 'ruleSetVersion']) if (!versionReference(finding[name])) return false;
  if (Object.keys(finding.specificationChange).length !== 2 || !versionReference(finding.specificationChange.previous) || !versionReference(finding.specificationChange.candidate)) return false;
  for (const name of ['requiredAllergens', 'declaredAllergens', 'missingAllergens']) {
    if (!Array.isArray(finding[name]) || !finding[name].every(opaqueId) || new Set(finding[name]).size !== finding[name].length) return false;
  }
  if (!Array.isArray(finding.unresolvedAllergens) || finding.unresolvedAllergens.some(e => !e || Object.keys(e).length !== 6 ||
      !opaqueId(e.formulaItemId) || !opaqueId(e.specificationVersionId) || !opaqueId(e.specComponentId) ||
      !(e.ingredientId === null || typeof e.ingredientId === 'string') || typeof e.rawPhrase !== 'string' || !e.rawPhrase.length || !['UNMAPPED', 'AMBIGUOUS'].includes(e.reason))) return false;
  const unresolvedKeys = finding.unresolvedAllergens.map(e => JSON.stringify([
    e.formulaItemId, e.specificationVersionId, e.specComponentId, e.ingredientId, e.rawPhrase, e.reason,
  ]));
  if (new Set(unresolvedKeys).size !== unresolvedKeys.length) return false;
  if (typeof finding.jurisdiction !== 'string' || !finding.jurisdiction.length || finding.jurisdiction.length > 32) return false;
  const missing = finding.requiredAllergens.filter(a => !finding.declaredAllergens.includes(a));
  if (missing.length !== finding.missingAllergens.length || missing.some(a => !finding.missingAllergens.includes(a))) return false;
  const outcome = missing.length || finding.unresolvedAllergens.length ? 'REVIEW_REQUIRED' : 'NO_ACTION';
  return finding.outcome === outcome;
}
function validPreview(response, body, fixture, correlation) {
  return response.status === 200 && response.headers['X-Correlation-Id'] === correlation && body &&
    Object.keys(body).length === 3 && body.organisationId === fixture.request.organisationId && body.correlationId === correlation &&
    Array.isArray(body.findings) && body.findings.length === fixture.expected.findings.length &&
    new Set(body.findings.map(f => f?.findingId)).size === body.findings.length &&
    body.findings.every(f => validFinding(f, fixture.request) && fixture.expected.findings.some(e => e.findingId === f.findingId && e.outcome === f.outcome));
}
function phase() {
  if (dry) return 'hold';
  if (exec.scenario.name === 'warmup') return 'warmup';
  const elapsed = (Date.now() - exec.scenario.startTime) / 1000;
  return elapsed < targets.rateRampSeconds ? 'ramp' : elapsed < targets.rateRampSeconds + targets.settleSeconds ? 'settle' : 'hold';
}
export default function () {
  const started = Date.now();
  const fixture = cases[exec.scenario.iterationInTest % cases.length];
  const correlation = `corr-${__ENV.TRIAL_ID || 'local'}-${exec.vu.idInTest}-${exec.scenario.iterationInTest}`;
  const tags = { phase: phase() };
  const response = http.post(`${baseUrl}/api/compliance/impact-previews`, JSON.stringify(fixture.request), {
    headers: { 'Content-Type': 'application/json', 'X-Correlation-ID': correlation,
      Authorization: `Bearer ${dry ? 'stcn-local-stub' : tokens[fixture.request.organisationId]}` },
    timeout: `${targets.slo.p95MillisecondsMaximum}ms`, tags,
  });
  let body;
  try { body = response.json(); } catch { body = null; }
  const passed = validPreview(response, body, fixture, correlation);
  contractErrors.add(!passed, tags);
  validatedDuration.add(Date.now() - started, tags);
  check(response, { 'valid owned POTENTIAL preview and expected outcome': () => passed }, tags);
}
export function handleSummary(data) {
  const result = { stdout: `Local/runtime kind: ${dry ? 'local_stub' : 'runtime_candidate'}; threshold results saved with metrics.\n` };
  if (__ENV.RESULT_FILE) result[__ENV.RESULT_FILE] = JSON.stringify(data, null, 2) + '\n';
  return result;
}
