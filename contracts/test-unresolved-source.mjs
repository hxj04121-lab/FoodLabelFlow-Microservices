import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
const root=dirname(fileURLToPath(import.meta.url));
const read=p=>readFileSync(resolve(root,p),'utf8');
const json=p=>JSON.parse(read(p));
const manifest=json('fixtures/m1-unresolved/source.json');
const sources=manifest.files.map(x=>json('fixtures/m1-unresolved/'+x.localFile));
const [spec,formula]=sources;
const finding=json('examples/compliance/impact-unresolved.json');
let count=0;
const check=(name,action)=>{action();count++;console.log('PASS '+name);};
for(const entry of manifest.files)check('exact M1 Git source '+entry.localFile,()=>{
  const content=Buffer.from(read('fixtures/m1-unresolved/'+entry.localFile).replaceAll('\r\n','\n'));
  const hash=createHash('sha1').update(Buffer.from(`blob ${content.length}\0`)).update(content).digest('hex');
  assert.equal(hash,entry.gitBlob);
});
function verify(event){
  const p=event.payload;
  assert.equal(p.kind,'CONFIRMED','the supplied FormulaPublished has adopted the candidate');
  assert.deepEqual(p.specificationChange,{previous:spec.payload.previousVersion,candidate:spec.payload.specificationVersion});
  assert.deepEqual(p.formulaVersion,formula.payload.formulaVersion);
  assert.equal(p.organisationId,formula.organisationId);
  assert.equal(event.correlationId,formula.correlationId);
  assert(new Date(event.occurredAt)>=new Date(formula.occurredAt));
  const ids=new Set();
  assert.equal(p.unresolvedAllergens.length,2);
  for(const evidence of p.unresolvedAllergens){
    const items=formula.payload.items.filter(x=>x.formulaItemId===evidence.formulaItemId);
    assert.equal(items.length,1,'one actual formula line');
    assert.deepEqual(items[0].specificationVersion,spec.payload.specificationVersion);
    assert.equal(evidence.specificationVersionId,spec.payload.specificationVersion.id);
    const components=spec.payload.components.filter(x=>x.specComponentId===evidence.specComponentId);
    assert.equal(components.length,1,'one actual component');
    assert.notEqual(components[0].matchStatus,'MATCHED');
    assert.equal(evidence.ingredientId,components[0].ingredientId);
    assert.equal(evidence.rawPhrase,components[0].rawPhrase);
    assert.equal(evidence.reason,components[0].matchStatus);
    const key=JSON.stringify([evidence.formulaItemId,evidence.specComponentId]);
    assert(!ids.has(key),'distinct formula/component pairs');ids.add(key);
  }
}
check('actual finding traces both formula lines to one exact unresolved component',()=>verify(finding));
check('source redelivery preserves traceability',()=>verify(structuredClone(finding)));
const bad=(name,mutate)=>check(name,()=>{const e=structuredClone(finding);mutate(e);assert.throws(()=>verify(e));});
bad('reject fabricated formula item',e=>e.payload.unresolvedAllergens[0].formulaItemId='item_002');
bad('reject fabricated component',e=>e.payload.unresolvedAllergens[0].specComponentId='component_003');
bad('reject wrong specification version',e=>e.payload.unresolvedAllergens[0].specificationVersionId='spec_001_v2');
bad('reject wrong raw phrase',e=>e.payload.unresolvedAllergens[0].rawPhrase='invented');
bad('reject wrong reason',e=>e.payload.unresolvedAllergens[0].reason='AMBIGUOUS');
bad('reject invented ingredient evidence',e=>e.payload.unresolvedAllergens[0].ingredientId=null);
bad('reject duplicate item/component evidence',e=>e.payload.unresolvedAllergens[1]=structuredClone(e.payload.unresolvedAllergens[0]));
bad('reject formula version mismatch',e=>e.payload.formulaVersion.id='formula_001_v1');
bad('reject unsupported potential phase after adoption',e=>e.payload.kind='POTENTIAL');
bad('reject finding before source publication',e=>e.occurredAt='2026-10-01T08:00:00Z');
console.log(`${count} M2 pinned UNRESOLVED source checks passed.`);
