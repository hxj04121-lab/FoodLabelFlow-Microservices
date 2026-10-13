import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';
import {validateRequest} from '../k6/fixture-validators.mjs';
import Ajv2020 from '../k6/node_modules/ajv/dist/2020.js';
import addFormats from '../k6/node_modules/ajv-formats/dist/index.js';
const directory=resolve(process.argv[2]), root=resolve(import.meta.dirname,'../..');
const read=name=>JSON.parse(readFileSync(resolve(directory,name),'utf8'));
const rows=name=>readFileSync(resolve(directory,name),'utf8').trim().split('\n').map(JSON.parse);
const manifest=read('manifest.json');
assert.equal(manifest.datasetFormatVersion,2,'Superseded one-item/all-candidate datasets must not be loaded');
assert.equal(manifest.status,'GENERATED_NOT_LOADED');
const digest=file=>createHash('sha256').update(readFileSync(file)).digest('hex');
assert.equal(manifest.targetsSha256,digest(resolve(root,'docs/swe5001/test-plan-G0-M2-targets.v1.json')));
for(const [name,hash] of Object.entries(manifest.files)) assert.equal(digest(resolve(directory,name)),hash);
const ajv=new Ajv2020({strict:false,allErrors:true});addFormats(ajv);
ajv.addSchema(JSON.parse(readFileSync(resolve(root,'contracts/openapi/compliance-types.v1.schema.json'),'utf8')),
  'https://contracts.spectrace.invalid/openapi/compliance-types.v1.schema.json');
for(const file of ['event-envelope.v1.schema.json','specification-published-payload.v1.schema.json','formula-published-payload.v1.schema.json','specification-published.v1.schema.json','formula-published.v1.schema.json']) {
  ajv.addSchema(JSON.parse(readFileSync(resolve(root,'contracts/events',file),'utf8')),'https://contracts.spectrace.invalid/events/'+file);
}
const specs=rows('specification-owner-import.jsonl'), formulas=rows('formula-owner-import.jsonl'), labels=rows('label-owner-import.jsonl');
for(const [data,validator,count] of [[specs,ajv.getSchema('https://contracts.spectrace.invalid/events/specification-published.v1.schema.json'),manifest.releasedSpecificationVersions],
  [formulas,ajv.getSchema('https://contracts.spectrace.invalid/events/formula-published.v1.schema.json'),5000]]) {
  assert.equal(data.length,count);data.forEach(row=>assert(validator(row),JSON.stringify(validator.errors)));
}
assert.equal(labels.length,5000);
const unique=values=>assert.equal(new Set(values).size,values.length,'Source-owner primary keys must be globally unique');
unique(specs.map(s=>s.payload.specificationVersion.id));
unique(specs.flatMap(s=>s.payload.components.map(c=>c.specComponentId)));
unique(formulas.map(f=>f.payload.formulaVersion.id));
unique(formulas.flatMap(f=>f.payload.items.map(i=>i.formulaItemId)));
const bySpec=new Map(specs.map(s=>[s.payload.specificationVersion.id,s.payload]));
const byFormula=new Map(formulas.map(f=>[f.payload.formulaVersion.id,f.payload]));
const seed=readFileSync(resolve(root,'services/specification/src/main/resources/db/migration/V2__baseline_seed.sql'),'utf8');
const vocabulary=new Map([...seed.matchAll(/\('(ing_[^']+)', '([^']+)', '(CANONICAL|COMPOUND|PLACEHOLDER)'/g)].map(m=>[m[1],{name:m[2],kind:m[3]}]));
for(const event of specs) {
  const p=event.payload;
  assert.equal(event.organisationId,p.organisationId);
  assert(seed.includes("('"+p.supplier.supplierId+"', '"+p.organisationId+"',"),'Supplier ownership must match the M1 seed');
  p.components.forEach((c,index)=>{
    const ingredient=vocabulary.get(c.ingredientId);assert(ingredient,'Ingredient must exist in M1 vocabulary');
    assert.equal(c.ingredientName,ingredient.name);assert.equal(c.sequenceNo,index+1);
    assert.equal(c.matchStatus,ingredient.kind==='PLACEHOLDER'?'UNMAPPED':'MATCHED');
  });
}
const cases=read('requests.json').cases;
assert.equal(read('requests.json').datasetFormatVersion,2);
assert.equal(cases.length,20);unique(cases.map(c=>c.request.organisationId));
assert.equal(new Set(cases.map(c=>c.request.specificationChange.candidate.id)).size,1);
const groups=new Map(), findingIds=[];
for(const c of cases) {
  assert(validateRequest(c.request),JSON.stringify(validateRequest.errors));
  const own=formulas.filter(f=>f.organisationId===c.request.organisationId);
  assert.equal(own.length,250);
  assert.equal(own.filter(f=>f.payload.items.some(i=>i.specificationVersion.id===c.request.specificationChange.previous.id)).length,50);
  own.forEach(f=>{
    assert.equal(f.payload.organisationId,f.organisationId);
    assert(f.payload.items.length>=4&&f.payload.items.length<=12,'Frozen recipe size 4–12');
    f.payload.items.forEach((i,index)=>{
      const spec=bySpec.get(i.specificationVersion.id);assert(spec,'Released specification reference must exist');
      assert.equal(i.materialId,spec.material.materialId);assert.deepEqual(i.specificationVersion,spec.specificationVersion);assert.equal(i.sequenceNo,index+1);
    });
  });
  assert([37,38].includes(c.expected.findings.length));
  c.expected.findings.forEach(f=>findingIds.push(f.findingId));
}
for(const label of labels) {
  const formula=byFormula.get(label.formulaVersionId);assert(formula);
  assert.equal(formula.organisationId,label.organisationId);assert.equal(formula.product.productId,label.productId);
  groups.set(label.fixtureGroup,(groups.get(label.fixtureGroup)||0)+1);
}
assert.deepEqual([...groups.values()],[1250,1250,1250,1250]);
unique(findingIds);assert.equal(findingIds.length,750);
console.log('PASS '+specs.length+' Specification events, 5000 Formula events, 4–12 items, 20% candidate reuse, four equal groups and 20 tenant requests. Dataset is generated, not loaded.');
