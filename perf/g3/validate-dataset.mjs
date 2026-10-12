import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {validateRequest} from '../k6/fixture-validators.mjs';
import Ajv2020 from '../k6/node_modules/ajv/dist/2020.js';
import addFormats from '../k6/node_modules/ajv-formats/dist/index.js';
const directory=resolve(process.argv[2]);
const root=resolve(import.meta.dirname,'../..');
const ajv=new Ajv2020({strict:false,allErrors:true});addFormats(ajv);
ajv.addSchema(JSON.parse(readFileSync(resolve(root,'contracts/openapi/compliance-types.v1.schema.json'),'utf8')),
  'https://contracts.spectrace.invalid/openapi/compliance-types.v1.schema.json');
for (const file of ['event-envelope.v1.schema.json','specification-published-payload.v1.schema.json','formula-published-payload.v1.schema.json','specification-published.v1.schema.json','formula-published.v1.schema.json']) {
  const schema=JSON.parse(readFileSync(resolve(root,'contracts/events',file),'utf8'));
  ajv.addSchema(schema,'https://contracts.spectrace.invalid/events/'+file);
}
const specs=ajv.getSchema('https://contracts.spectrace.invalid/events/specification-published.v1.schema.json');
const formulas=ajv.getSchema('https://contracts.spectrace.invalid/events/formula-published.v1.schema.json');
for (const [file,validator,count] of [['specification-owner-import.jsonl',specs,40],['formula-owner-import.jsonl',formulas,5000]]) {
  const rows=readFileSync(resolve(directory,file),'utf8').trim().split('\n').map(JSON.parse);
  assert.equal(rows.length,count);
  rows.forEach(row=>assert(validator(row),JSON.stringify(validator.errors)));
}
const cases=JSON.parse(readFileSync(resolve(directory,'requests.json'),'utf8')).cases;
assert.equal(cases.length,20);
cases.forEach(c=>{assert(validateRequest(c.request),JSON.stringify(validateRequest.errors));assert.equal(c.expected.findings.length,250);});
console.log('PASS 40 Specification events, 5000 Formula events and 20 tenant preview requests against frozen schemas. Dataset is generated, not loaded.');
