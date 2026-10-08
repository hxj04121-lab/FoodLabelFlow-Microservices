// G0-M1 (STCN-24, STCN-25) contract checks: canonical envelope, SpecificationPublished.v1,
// FormulaPublished.v1 and the Specification/Formulation OpenAPI v1. Run: npm run check:m1
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
    const schema = json(`${directory}/${name}`);
    assert(ajv.validateSchema(schema), `${directory}/${name}: ${ajv.errorsText()}`);
    ajv.addSchema(schema, base + `${directory}/${name}`);
  }
}
const schema = (path) => { const v = ajv.getSchema(base + path); assert(v, `missing schema ${path}`); return v; };
const envelope = schema('events/event-envelope.v1.schema.json');
const specEvent = schema('events/specification-published.v1.schema.json');
const formulaEvent = schema('events/formula-published.v1.schema.json');
const valid = (validator, value) => assert(validator(value), ajv.errorsText(validator.errors));
const invalid = (validator, value) => assert.equal(validator(value), false, 'invalid fixture must be rejected');

let count = 0;
function check(name, action) { action(); count++; console.log(`PASS ${name}`); }

// Relationships JSON Schema cannot express.
function specSemantics(e) {
  const p = e.payload;
  assert.equal(e.organisationId, p.organisationId, 'envelope/payload tenant must match');
  assert.equal(e.aggregateId, p.material.materialId, 'aggregate is the material specification series');
  assert.equal(e.aggregateVersion, p.specificationVersion.versionNumber, 'aggregateVersion is the released version');
  if (p.previousVersion) assert(p.previousVersion.versionNumber < p.specificationVersion.versionNumber, 'previous must be older');
  else assert.equal(p.specificationVersion.versionNumber, 1, 'only the first release has no previous version');
  assert.deepEqual(p.components.map((c) => c.sequenceNo), p.components.map((_, i) => i + 1), 'components numbered 1..n');
  assert.equal(new Set(p.components.map((c) => c.ingredientId)).size, p.components.length, 'ingredient listed once');
}
function formulaSemantics(e) {
  const p = e.payload;
  assert.equal(e.organisationId, p.organisationId, 'envelope/payload tenant must match');
  assert.equal(e.aggregateId, p.product.productId, 'aggregate is the product formula series');
  assert.equal(e.aggregateVersion, p.formulaVersion.versionNumber, 'aggregateVersion is the released version');
  if (p.previousFormulaVersion) assert(p.previousFormulaVersion.versionNumber < p.formulaVersion.versionNumber, 'previous must be older');
  assert.deepEqual(p.items.map((i) => i.sequenceNo), p.items.map((_, i) => i + 1), 'items numbered 1..n');
}

// ---- events
const specV1 = json('examples/specification/specification-published-first-release.json');
const specV2 = json('examples/specification/specification-published-soy-lecithin-v2.json');
const formula = json('examples/formulation/formula-published-adopts-v2.json');
for (const [name, e, v, sem] of [['spec first release', specV1, specEvent, specSemantics],
  ['spec soy-lecithin V2', specV2, specEvent, specSemantics], ['formula adopts V2', formula, formulaEvent, formulaSemantics]]) {
  check(`event ${name}`, () => { valid(v, e); valid(envelope, e); sem(e); });
}
const bad = (name, source, validator, mutate, semantic) => check(name, () => {
  const e = clone(source); mutate(e);
  if (semantic) assert.throws(() => semantic(e)); else invalid(validator, e);
});
bad('envelope: schemaVersion is pinned to 1', specV2, envelope, (e) => { e.schemaVersion = 2; });
bad('envelope: unknown producer', specV2, envelope, (e) => { e.producer = 'catalog-service'; });
bad('envelope: eventType must carry .vN', specV2, envelope, (e) => { e.eventType = 'SpecificationPublished'; });
bad('envelope: no extra fields', specV2, envelope, (e) => { e.tenant = 'x'; });
bad('envelope: UTC timestamps only', specV2, envelope, (e) => { e.occurredAt = '2026-10-01T15:55:00+08:00'; });
bad('spec: wrong producer', specV2, specEvent, (e) => { e.producer = 'formulation-service'; });
bad('spec: wrong event type', specV2, specEvent, (e) => { e.eventType = 'FormulaPublished.v1'; });
bad('spec: components required', specV2, specEvent, (e) => { e.payload.components = []; });
bad('spec: matchStatus vocabulary', specV2, specEvent, (e) => { e.payload.components[0].matchStatus = 'GUESSED'; });
bad('spec: previousVersion must be present (null allowed)', specV2, specEvent, (e) => { delete e.payload.previousVersion; });
bad('spec: provenance required', specV2, specEvent, (e) => { delete e.payload.provenance; });
bad('spec: tenant mismatch', specV2, specEvent, (e) => { e.organisationId = 'supplier_02'; }, specSemantics);
bad('spec: aggregate is the material', specV2, specEvent, (e) => { e.aggregateId = 'spec_001_v2'; }, specSemantics);
bad('spec: aggregateVersion follows release', specV2, specEvent, (e) => { e.aggregateVersion = 1; }, specSemantics);
bad('formula: quantity without unit', formula, formulaEvent, (e) => { e.payload.items[0].unit = null; });
bad('formula: unit without quantity', formula, formulaEvent, (e) => { e.payload.items[1].unit = 'kg'; });
bad('formula: quantity must be positive', formula, formulaEvent, (e) => { e.payload.items[0].quantity = 0; });
bad('formula: exact specification version required', formula, formulaEvent, (e) => { delete e.payload.items[0].specificationVersion; });
bad('formula: wrong producer', formula, formulaEvent, (e) => { e.producer = 'specification-service'; });
bad('formula: aggregate is the product', formula, formulaEvent, (e) => { e.aggregateId = 'formula_001_v2'; }, formulaSemantics);

// ---- cross-contract story: the M2 ImpactFinding examples must refer to the same versions
check('two-phase story is consistent across M1 and M2 examples', () => {
  const potential = json('examples/compliance/impact-potential-missing.json').payload;
  assert.deepEqual(potential.specificationChange.previous, specV2.payload.previousVersion);
  assert.deepEqual(potential.specificationChange.candidate, specV2.payload.specificationVersion);
  const adopted = formula.payload.items.find((i) => i.materialId === specV2.payload.material.materialId);
  assert.deepEqual(adopted.specificationVersion, specV2.payload.specificationVersion, 'formula adopts the released V2');
  assert.equal(formula.correlationId === specV2.correlationId, false, 'adoption is a separate request');
});

// ---- stable component / item IDs and UNRESOLVED traceability (M2 steward finding 1 on PR #8)
const specUnmapped = json('examples/specification/specification-published-unmapped-component.json');
const formulaSameSpec = json('examples/formulation/formula-published-two-items-same-spec.json');
check('event spec with UNMAPPED component', () => { valid(specEvent, specUnmapped); specSemantics(specUnmapped); });
check('event formula with two items on one specification', () => { valid(formulaEvent, formulaSameSpec); formulaSemantics(formulaSameSpec); });
check('component and item IDs are unique and present', () => {
  for (const e of [specV1, specV2, specUnmapped]) {
    const ids = e.payload.components.map((c) => c.specComponentId);
    assert.equal(new Set(ids).size, ids.length);
  }
  for (const e of [formula, formulaSameSpec]) {
    const ids = e.payload.items.map((i) => i.formulaItemId);
    assert.equal(new Set(ids).size, ids.length);
  }
  const allComponentIds = [specV1, specV2, specUnmapped].flatMap((e) => e.payload.components.map((c) => c.specComponentId));
  assert.equal(new Set(allComponentIds).size, allComponentIds.length, 'a new version gets new component IDs');
});
bad('spec: component without specComponentId', specV2, specEvent, (e) => { delete e.payload.components[0].specComponentId; });
bad('formula: item without formulaItemId', formula, formulaEvent, (e) => { delete e.payload.items[0].formulaItemId; });

// What Compliance derives for a formula: one UNRESOLVED entry per (formula item, unresolved component).
function deriveUnresolved(formulaEvt, specEvts) {
  const specs = new Map(specEvts.map((s) => [s.payload.specificationVersion.id, s.payload]));
  return formulaEvt.payload.items.flatMap((item) => {
    const spec = specs.get(item.specificationVersion.id);
    if (!spec) return [];
    return spec.components.filter((c) => c.matchStatus !== 'MATCHED').map((c) => ({
      formulaItemId: item.formulaItemId, specificationVersionId: spec.specificationVersion.id,
      specComponentId: c.specComponentId, ingredientId: c.ingredientId, rawPhrase: c.rawPhrase, reason: c.matchStatus,
    }));
  });
}
const m2Unresolved = ajv.compile({ $ref: `${base}events/impact-finding-payload.v1.schema.json#/properties/unresolvedAllergens/items` });
check('UNRESOLVED evidence traces to the exact formula item and specification component', () => {
  const evidence = deriveUnresolved(formulaSameSpec, [specUnmapped]);
  assert.equal(evidence.length, 2, 'two items on the same specification give two entries');
  assert.equal(new Set(evidence.map((x) => x.formulaItemId)).size, 2, 'entries are told apart by formulaItemId');
  for (const x of evidence) {
    valid(m2Unresolved, x);
    const items = formulaSameSpec.payload.items.filter((i) => i.formulaItemId === x.formulaItemId);
    assert.equal(items.length, 1, 'formulaItemId resolves to exactly one formula line');
    assert.equal(items[0].specificationVersion.id, x.specificationVersionId);
    const components = specUnmapped.payload.components.filter((c) => c.specComponentId === x.specComponentId);
    assert.equal(components.length, 1, 'specComponentId resolves to exactly one component of that version');
    assert.equal(components[0].rawPhrase, x.rawPhrase);
    assert.equal(components[0].matchStatus, x.reason);
  }
});
check('event redelivery yields identical evidence', () => {
  const first = deriveUnresolved(formulaSameSpec, [specUnmapped]);
  const again = deriveUnresolved(clone(formulaSameSpec), [clone(specUnmapped)]);
  assert.deepEqual(again, first);
});
check('soy-lecithin story stays free of UNRESOLVED evidence', () => {
  assert.deepEqual(deriveUnresolved(formula, [specV2]), []);
  assert.deepEqual(json('examples/compliance/impact-potential-missing.json').payload.unresolvedAllergens, []);
});

// ---- HTTP contracts
function rewrite(value) {
  if (Array.isArray(value)) return value.map(rewrite);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) =>
    [k, k === '$ref' ? v.replace('#/components/schemas/', '#/$defs/') : rewrite(v)]));
  return value;
}
const apis = { specification: parse(read('openapi/specification.v1.yaml')), formulation: parse(read('openapi/formulation.v1.yaml')) };
for (const [name, api] of Object.entries(apis)) {
  ajv.addSchema({ $id: `${base}openapi/${name}-http.json`, $schema: 'https://json-schema.org/draft/2020-12/schema',
    $defs: rewrite(api.components.schemas) });
}
const http = (api, def) => ajv.compile({ $ref: `${base}openapi/${api}-http.json#/$defs/${def}` });
check('create specification request', () => valid(http('specification', 'CreateSpecificationRequest'), json('examples/specification/create-specification-request.json')));
check('specification draft needs components', () => {
  const r = json('examples/specification/create-specification-request.json'); r.components = [];
  invalid(http('specification', 'CreateSpecificationRequest'), r);
});
check('create formula request', () => valid(http('formulation', 'CreateFormulaRequest'), json('examples/formulation/create-formula-request.json')));
check('formula request: quantity and unit together', () => {
  const r = json('examples/formulation/create-formula-request.json'); r.items[0].unit = null;
  invalid(http('formulation', 'CreateFormulaRequest'), r);
});
check('release formula request (pointer or null)', () => {
  const v = http('formulation', 'ReleaseFormulaRequest');
  valid(v, json('examples/formulation/release-formula-request.json'));
  valid(v, { expectedCurrentFormulaVersionId: null });
  invalid(v, {});
});
const prefixes = { specification: '/api/specifications/', formulation: '/api/formulations/' };
for (const [name, api] of Object.entries(apis)) {
  check(`${name}: gateway route prefix and JWT on every operation`, () => {
    assert.deepEqual(api.security, [{ bearerAuth: [] }]);
    for (const path of Object.keys(api.paths)) assert(path.startsWith(prefixes[name]), path);
  });
  check(`${name}: correlation ID and canonical ApiError on every response`, () => {
    for (const ops of Object.values(api.paths)) for (const operation of Object.values(ops)) {
      assert(operation.parameters.some((p) => p.$ref === '#/components/parameters/CorrelationId'), operation.operationId);
      for (const [code, response] of Object.entries(operation.responses)) {
        const r = response.$ref ? api.components.responses[response.$ref.split('/').at(-1)] : response;
        assert(r.headers['X-Correlation-ID'].required, `${operation.operationId} ${code}`);
        if (Number(code) >= 400) assert.equal(r.content['application/json'].schema.$ref, './compliance-types.v1.schema.json#/$defs/ApiError');
      }
    }
  });
}
check('other organisations get 403, never a hidden 404 (architecture v3 section 15; finding 2)', () => {
  for (const api of Object.values(apis)) {
    assert.equal(/not visible|other organisations get 404/i.test(JSON.stringify(api)), false);
    for (const ops of Object.values(api.paths)) for (const operation of Object.values(ops)) {
      assert(operation.responses['403'], `${operation.operationId} documents 403`);
    }
  }
  const productRead = apis.formulation.paths['/api/formulations/products/{productId}'].get.description;
  assert.match(productRead, /another organisation returns 403 AUTHORIZATION_DENIED/);
});
check('public representations expose the stable IDs', () => {
  assert(apis.specification.components.schemas.Component.required.includes('specComponentId'));
  assert(apis.formulation.components.schemas.FormulaItem.required.includes('formulaItemId'));
  assert(apis.formulation.components.schemas.FormulaTrace.properties.items.items.required.includes('formulaItemId'));
});
check('release operations exist and document the outbox event', () => {
  const s = apis.specification.paths['/api/specifications/versions/{specificationVersionId}/release'].post;
  const f = apis.formulation.paths['/api/formulations/formula-versions/{formulaVersionId}/release'].post;
  assert.match(s.description, /SpecificationPublished\.v1/);
  assert.match(f.description, /FormulaPublished\.v1/);
  assert(s.responses['409'] && f.responses['409'], 'immutable re-release returns 409');
});

console.log(`${count} M1 contract checks passed (envelope, SpecificationPublished.v1, FormulaPublished.v1, Specification and Formulation OpenAPI v1).`);
