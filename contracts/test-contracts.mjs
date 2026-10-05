import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { parse } from 'yaml';

const here = dirname(fileURLToPath(import.meta.url));
const read = (path) => readFileSync(resolve(here, path), 'utf8');
const json = (path) => JSON.parse(read(path));
const clone = (value) => structuredClone(value);
const base = 'https://contracts.spectrace.invalid/';
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
for (const directory of ['openapi', 'events']) {
  for (const name of readdirSync(resolve(here, directory)).filter((n) => n.endsWith('.schema.json'))) {
    const path = `${directory}/${name}`;
    const schema = json(path);
    assert(ajv.validateSchema(schema), `${path}: ${ajv.errorsText()}`);
    ajv.addSchema(schema, base + path);
  }
}
const args = process.argv.slice(2);
const mode = args.length === 0 ? 'all' : args.length === 2 && args[0] === '--scope' ? args[1] : 'invalid';
assert(['all', 'events', 'http'].includes(mode), 'Use --scope events|http or omit for all');
const api = mode === 'events' ? null : parse(read('openapi/compliance.v1.yaml'));
function rewrite(value) {
  if (Array.isArray(value)) return value.map(rewrite);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, item]) => {
    if (key === '$ref') return [key, item.replace('#/components/schemas/', '#/$defs/')];
    return [key, rewrite(item)];
  }));
  return value;
}
const root = { $id: base + 'openapi/http.json', $schema: 'https://json-schema.org/draft/2020-12/schema',
  $defs: api ? rewrite(api.components.schemas) : {} };
if (api) ajv.addSchema(root);
const eventValidator = ajv.getSchema(base + 'events/impact-finding.v1.schema.json');
const httpValidator = (name) => ajv.compile({ $ref: `${base}openapi/http.json#/$defs/${name}` });
const errorValidator = ajv.compile({ $ref: `${base}openapi/compliance-types.v1.schema.json#/$defs/ApiError` });
const valid = (validator, value) => assert(validator(value), ajv.errorsText(validator.errors));
const invalid = (validator, value) => assert.equal(validator(value), false, 'Invalid fixture must be rejected');

// Relationships between arrays/fields cannot be expressed as JSON Schema set subtraction.
function semantics(event) {
  const p = event.payload;
  assert.equal(event.organisationId, p.organisationId, 'Envelope/payload tenant must match');
  assert.equal(event.aggregateId, p.findingId, 'Aggregate is the persisted finding');
  const missing = p.requiredAllergens.filter((a) => !p.declaredAllergens.includes(a)).sort((left, right) => left.localeCompare(right, 'en'));
  assert.deepEqual([...p.missingAllergens].sort((left, right) => left.localeCompare(right, 'en')), missing, 'missing = required minus declared');
  const outcome = missing.length || p.unresolvedAllergens.length ? 'REVIEW_REQUIRED' : 'NO_ACTION';
  assert.equal(p.outcome, outcome);
}
let count = 0;
function check(name, action) { action(); count++; console.log(`PASS ${name}`); }
const source = json('examples/compliance/impact-potential-missing.json');
if (mode !== 'http') {
const events = readdirSync(resolve(here, 'examples/compliance')).filter((n) => n.startsWith('impact-')).map((n) => json(`examples/compliance/${n}`));
for (const event of events) check(`event ${event.payload.kind}/${event.payload.outcome}/${event.payload.findingId}`, () => {
  valid(eventValidator, event); semantics(event);
});
const source = json('examples/compliance/impact-potential-missing.json');
function badEvent(name, mutate, semantic = false) {
  check(name, () => { const bad = clone(source); mutate(bad);
    if (semantic) assert.throws(() => semantics(bad)); else invalid(eventValidator, bad); });
}
badEvent('missing envelope correlation', (e) => { delete e.correlationId; });
badEvent('unknown event type', (e) => { e.eventType = 'ImpactFinding.v2'; });
badEvent('wrong producer', (e) => { e.producer = 'formulation-service'; });
badEvent('event ID must be UUID', (e) => { e.eventId = 'invented-id'; });
badEvent('timestamp must be UTC', (e) => { e.occurredAt = '2026-10-01T16:00:00+08:00'; });
badEvent('missing pinned version', (e) => { delete e.payload.ruleSetVersion; });
badEvent('version must be positive', (e) => { e.payload.formulaVersion.versionNumber = 0; });
badEvent('invalid kind', (e) => { e.payload.kind = 'ADOPTED'; });
badEvent('NO_ACTION cannot hide missing allergens', (e) => { e.payload.outcome = 'NO_ACTION'; });
badEvent('REVIEW_REQUIRED needs evidence', (e) => { e.payload.missingAllergens = []; e.payload.unresolvedAllergens = []; });
badEvent('allergen lists have set semantics', (e) => { e.payload.requiredAllergens.push('all_soy'); });
badEvent('tenant mismatch', (e) => { e.organisationId = 'manufacturer_02'; }, true);
badEvent('aggregate mismatch', (e) => { e.aggregateId = 'finding_other'; }, true);
badEvent('set difference mismatch', (e) => { e.payload.missingAllergens = ['all_wheat']; }, true);
const unresolved = json('examples/compliance/impact-unresolved.json');
check('unresolved cannot silently pass', () => { unresolved.payload.outcome = 'NO_ACTION'; invalid(eventValidator, unresolved); });
}
if (mode !== 'events') {
check('full draft snapshot', () => valid(httpValidator('DraftSnapshot'), json('examples/compliance/validation-request.json')));
check('partial snapshot rejected', () => { const d = json('examples/compliance/validation-request.json'); delete d.declarations; invalid(httpValidator('DraftSnapshot'), d); });
for (const status of ['passed', 'failed']) check(`validation ${status}`, () => valid(httpValidator('ValidationRun'), json(`examples/compliance/validation-${status}.json`)));
check('PASSED cannot contain blocking ERROR', () => { const r = json('examples/compliance/validation-failed.json'); r.status = 'PASSED'; invalid(httpValidator('ValidationRun'), r); });
check('FAILED must preserve blocking evidence', () => { const r = json('examples/compliance/validation-failed.json'); r.findings = []; invalid(httpValidator('ValidationRun'), r); });
check('blocking result must be ERROR and failed', () => { const r = json('examples/compliance/validation-failed.json'); r.findings[0].severity = 'WARNING'; invalid(httpValidator('ValidationRun'), r); });
check('canonical ApiError', () => valid(errorValidator, json('examples/compliance/api-error.json')));
check('error has no invented evidence field', () => { const e = json('examples/compliance/api-error.json'); delete e.evidenceId; invalid(errorValidator, e); });
check('preview only returns POTENTIAL', () => {
  const preview = { organisationId: source.organisationId, correlationId: source.correlationId, findings: [source.payload] };
  valid(httpValidator('ImpactPreview'), preview);
  preview.findings[0] = json('examples/compliance/impact-confirmed-missing.json').payload;
  invalid(httpValidator('ImpactPreview'), preview);
});
check('validation requires idempotency header and JWT', () => {
  const operation = api.paths['/internal/validations'].post;
  assert(operation.parameters.some((p) => p.name === 'Idempotency-Key' && p.in === 'header' && p.required));
  assert.deepEqual(api.security, [{ bearerAuth: [] }]);
});
check('all target operations propagate correlation and canonical errors', () => {
  for (const path of Object.values(api.paths)) for (const operation of Object.values(path)) {
    assert(operation.parameters.some((p) => p.$ref === '#/components/parameters/CorrelationId'));
    for (const [code, response] of Object.entries(operation.responses)) {
      const resolved = response.$ref ? api.components.responses[response.$ref.split('/').at(-1)] : response;
      assert(resolved.headers['X-Correlation-ID'].required);
      if (Number(code) >= 400) assert.equal(resolved.content['application/json'].schema.$ref, './compliance-types.v1.schema.json#/$defs/ApiError');
    }
  }
});
check('ApiError preserves baseline canonical wire fields', () => {
  const baseline = parse(read('../docs/contracts/allergen-validation-api-v1.yaml')).components.schemas.ApiError;
  const shared = json('openapi/compliance-types.v1.schema.json').$defs.ApiError;
  assert.deepEqual(shared.required, baseline.required);
  assert.equal(shared.additionalProperties, baseline.additionalProperties);
  for (const [name, property] of Object.entries(baseline.properties)) assert.deepEqual(shared.properties[name].type, property.type);
});


}

check('FINAL input and work-order provenance pinned', () => {
  const source = json('source-manifest.json');
  assert.equal(source.sourceCommit, 'f6abf4a043b7f587c6e3315f8a2b8b78e7e40b60');
  assert.equal(source.sourceState, 'unmerged identified input');
  assert.equal(source.assignment.workOrder, 'G0-M2');
  assert.deepEqual(source.assignment.subtasks, ['STCN-26', 'STCN-27', 'STCN-28', 'STCN-29']);
  assert(source.files.some((file) => file.path.endsWith('03_SpecTrace-CN_Architecture_Source_of_Truth_FINAL.md')));
  assert(source.files.every((file) => /^[0-9a-f]{40}$/.test(file.gitBlobSha)));
});
check('M2 profile references the M1 canonical envelope and every required field', () => {
  const event = json('events/impact-finding.v1.schema.json');
  const fields = json('source-manifest.json').canonicalEnvelopeFields;
  assert.equal(event.allOf[0].$ref, 'event-envelope.v1.schema.json');
  assert.equal(event.$defs?.EnvelopeInterface, undefined);
  assert.deepEqual(json('events/event-envelope.v1.schema.json').required, fields);
  assert.equal(json('openapi/compliance-types.v1.schema.json').$defs.EventEnvelope, undefined);
  for (const field of fields) {
    const bad = clone(source);
    delete bad[field];
    invalid(eventValidator, bad);
  }
});
check('unrecognised envelope field rejected', () => {
  const bad = clone(source); bad.internalSecret = 'not a contract field'; invalid(eventValidator, bad);
});
check('empty or zero envelope identifiers rejected', () => {
  for (const [field, value] of [['organisationId', ''], ['correlationId', ''], ['aggregateVersion', 0], ['schemaVersion', 2]]) {
    const bad = clone(source); bad[field] = value; invalid(eventValidator, bad);
  }
});
if (mode === 'all') check('declared capacity/failover targets preserved and FINAL-bound', () => {
  const targets = json('../docs/swe5001/test-plan-G0-M2-targets.v1.json');
  assert.equal(targets.declaredAt, '2026-10-01');
  assert.equal(targets.sourceCommit, json('source-manifest.json').sourceCommit);
  assert(targets.source.includes('/final/03_SpecTrace-CN_Architecture_Source_of_Truth_FINAL.md'));
  assert.deepEqual(targets.dataset, { seed: 5001, releasedFormulaVersions: 5000, manufacturers: 20, formulaVersionsPerManufacturer: 250 });
  assert.deepEqual(targets.replicas, [1, 3]);
  assert.equal(targets.perReplica.cpuLimit, '1'); assert.equal(targets.perReplica.memoryLimit, '2Gi');
  assert.deepEqual(targets.slo, { p95MillisecondsMaximum: 2000, errorFractionExclusiveMaximum: 0.01, droppedIterationsMaximum: 0 });
  assert.equal(targets.scaleOut.minimumThreeToOneCapacityRatio, 2);
  assert.equal(targets.failover.loadFractionOfMeasuredThreeReplicaCapacity, 0.5);
  assert.equal(targets.failover.errorFractionExclusiveMaximum, 0.01);
  assert.equal(targets.failover.continuousErrorWindowSecondsMaximum, 10);
  assert.equal(targets.failover.sloRecoverySecondsMaximum, 60);
  assert.equal(targets.failover.replacementReadySecondsExclusiveMaximum, 180);
});
check('all local M2 schema references resolve at their final paths', () => {
  for (const directory of ['events', 'openapi']) for (const name of readdirSync(resolve(here, directory)).filter((n) => n.endsWith('.schema.json'))) {
    const file = resolve(here, directory, name);
    function visit(value) {
      if (Array.isArray(value)) return value.forEach(visit);
      if (value && typeof value === 'object') {
        if (value.$ref && !value.$ref.startsWith('#')) {
          const path = resolve(dirname(file), value.$ref.split('#')[0]);
          assert.doesNotThrow(() => readFileSync(path), `${file}: ${value.$ref}`);
        }
        Object.values(value).forEach(visit);
      }
    }
    visit(json(`${directory}/${name}`));
  }
});
console.log(`${count} local M2 contract checks passed (scope: ${mode}). Run check:envelope for the actual canonical artifact; human approval remains separate.`);
