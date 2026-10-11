import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const root = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
if (args.length && (args.length !== 2 || args[0] !== '--schema')) {
  console.error('Usage: node test-envelope-interface.mjs [--schema <M1 schema path inside contracts>]');
  process.exit(1);
}
const canonicalPath = resolve(root, args[1] ?? 'events/event-envelope.v1.schema.json');
const canonicalRelative = relative(root, canonicalPath).replaceAll('\\', '/');
if (canonicalRelative.startsWith('../') || isAbsolute(canonicalRelative)) {
  console.error('Canonical schema must be an identified file under contracts/.');
  process.exit(1);
}
if (!existsSync(canonicalPath)) {
  console.error(`BLOCKED: M1 canonical event-envelope artifact is absent: ${canonicalRelative}`);
  console.error('M2 EnvelopeInterface is validated by npm run check; no M1 contract or substitute fixture is fabricated.');
  process.exit(2);
}
if (canonicalRelative.startsWith('events/impact-finding')) {
  console.error('BLOCKED: the M2 event/profile cannot stand in for the M1 canonical artifact.');
  process.exit(2);
}
const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);
const base = 'https://contracts.spectrace.invalid/';
const schemas = [];
for (const directory of ['openapi', 'events', 'common', 'schemas']) {
  const path = resolve(root, directory);
  if (!existsSync(path)) continue;
  function collect(folder) {
    for (const item of readdirSync(folder, { withFileTypes: true })) {
      const file = resolve(folder, item.name);
      if (item.isDirectory()) collect(file);
      else if (item.name.endsWith('.schema.json')) schemas.push(file);
    }
  }
  collect(path);
}
for (const file of schemas) {
  const schema = JSON.parse(readFileSync(file, 'utf8'));
  assert(ajv.validateSchema(schema), `${file}: ${ajv.errorsText()}`);
  ajv.addSchema(schema, base + relative(root, file).replaceAll('\\', '/'));
}
const validate = ajv.getSchema(base + canonicalRelative);
assert(validate, 'Canonical artifact must be a .schema.json file in a contract schema directory');
const examples = readdirSync(resolve(root, 'examples/compliance')).filter((name) => name.startsWith('impact-'));
for (const name of examples) {
  const event = JSON.parse(readFileSync(resolve(root, 'examples/compliance', name), 'utf8'));
  assert(validate(event), `${name}: ${ajv.errorsText(validate.errors)}`);
  console.log(`PASS canonical envelope accepts ${name}`);
}
const source = JSON.parse(readFileSync(resolve(root, 'examples/compliance/impact-potential-missing.json'), 'utf8'));
for (const field of ['eventId', 'eventType', 'schemaVersion', 'occurredAt', 'producer', 'organisationId', 'correlationId', 'aggregateId', 'aggregateVersion', 'payload']) {
  const invalid = structuredClone(source);
  delete invalid[field];
  assert.equal(validate(invalid), false, `Canonical artifact must require ${field}`);
}
for (const [field, value] of [['eventId', 'not-a-uuid'], ['schemaVersion', 2], ['aggregateVersion', 0], ['occurredAt', '2026-10-01T16:00:00+08:00']]) {
  const invalid = structuredClone(source);
  invalid[field] = value;
  assert.equal(validate(invalid), false, `Canonical artifact must enforce ${field}`);
}
console.log('M1 envelope interface conformance passed against the supplied artifact; owner/peer acceptance remains separate.');
