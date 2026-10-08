import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const root=new URL('./',import.meta.url);
const read=path=>readFileSync(new URL(path,root));
const json=path=>JSON.parse(read(path));
function gitTextBlob(bytes){
  // Git's text checkout may use CRLF on Windows; hash its canonical LF form.
  const canonical=Buffer.from(bytes.toString('utf8').replace(/\r\n/g,'\n'),'utf8');
  return createHash('sha1').update(`blob ${canonical.length}\0`).update(canonical).digest('hex');
}
const manifest=json('label-envelope-sources.v1.json');
for(const source of manifest.sources){
  const path=source.path.replace(/^contracts\//,'');
  const bytes=read(path);
  const blob=gitTextBlob(bytes);
  assert.equal(blob,source.blob,`Pinned ${source.owner} source changed: ${path}`);
  const windowsCheckout=Buffer.from(bytes.toString('utf8').replace(/\r?\n/g,'\r\n'),'utf8');
  assert.equal(gitTextBlob(windowsCheckout),source.blob,`Windows checkout must preserve ${source.owner} Git identity`);
}
const ajv=new Ajv2020({strict:false,allErrors:true});addFormats(ajv);
// Use each real file's URI as its resolution base without editing provider bytes.
for(const path of ['openapi/compliance-types.v1.schema.json','events/event-envelope.v1.schema.json','events/label-published-v1.schema.json']){
  const schema=json(path);assert(ajv.validateSchema(schema),ajv.errorsText());
  ajv.addSchema({...schema,$id:new URL(path,root).href});
}
const profile=ajv.getSchema(new URL('events/label-published-v1.schema.json',root).href);
const canonical=ajv.getSchema(new URL('events/event-envelope.v1.schema.json',root).href);
const sample=json('events/examples/label-published-v1.json');
let checks=0;
function expect(name,validator,value,wanted){assert.equal(Boolean(validator(value)),wanted,`${name}: ${ajv.errorsText(validator.errors)}`);checks++;console.log(`PASS ${name}`);}
const copy=()=>structuredClone(sample);
expect('published sample satisfies actual M1 Envelope',canonical,sample,true);
expect('published sample satisfies LabelPublished profile',profile,sample,true);
expect('old M4 producer rejected by canonical',canonical,{...sample,producer:'label-workflow'},false);
expect('old M4 producer rejected by profile',profile,{...sample,producer:'label-workflow'},false);
expect('other valid service producer is canonical-valid',canonical,{...sample,producer:'compliance-service'},true);
expect('LabelPublished pins its own producer',profile,{...sample,producer:'compliance-service'},false);
for(const field of ['eventId','eventType','schemaVersion','occurredAt','producer','organisationId','correlationId','aggregateId','aggregateVersion','payload']){
  const value=copy();delete value[field];expect(`missing envelope ${field}`,profile,value,false);
}
for(const [name,change] of [
  ['invalid UUID',{eventId:'not-a-uuid'}],
  ['wrong event type',{eventType:'SpecificationPublished.v1'}],
  ['new payload version',{eventType:'LabelPublished.v2'}],
  ['wrong envelope version',{schemaVersion:2}],
  ['invalid timestamp',{occurredAt:'not-a-date'}],
  ['non-UTC timestamp',{occurredAt:'2026-10-08T10:00:00+08:00'}],
  ['unknown producer',{producer:'unknown-service'}],
  ['whitespace organisation ID',{organisationId:' '}],
  ['overlength correlation ID',{correlationId:'c'.repeat(129)}],
  ['overlength aggregate ID',{aggregateId:'a'.repeat(129)}],
  ['zero aggregate version',{aggregateVersion:0}],
  ['undeclared envelope field',{unexpected:true}],
]) expect(name,profile,{...sample,...change},false);
for(const field of ['productId','labelVersionId','formulaVersionId','ruleSetVersionId','jurisdictionCode','declarations']){
  const value=copy();delete value.payload[field];expect(`missing payload ${field}`,profile,value,false);
}
const extraPayload=copy();extraPayload.payload.unexpected=true;expect('closed payload preserved',profile,extraPayload,false);
assert(sample.payload.declarations.length>0,'Sample must exercise declaration fields');
for(const field of ['allergenId','declarationType','declarationSource','displayText']){
  const value=copy();delete value.payload.declarations[0][field];expect(`missing declaration ${field}`,profile,value,false);
}
for(const [name,change]of [['wrong declaration type',{declarationType:'MAY_CONTAIN'}],['unknown provenance',{declarationSource:'UNKNOWN'}],['extra declaration field',{unexpected:true}]]){
  const value=copy();Object.assign(value.payload.declarations[0],change);expect(name,profile,value,false);
}
const empty=copy();empty.payload.declarations=[];expect('existing empty declarations remain valid',profile,empty,true);
const nullable=copy();nullable.payload.declarations[0].displayText=null;expect('existing nullable display text remains valid',profile,nullable,true);
console.log(`${checks} LabelPublished canonical/profile checks passed; pinned provider bytes verified. No runtime or reviewer acceptance is implied.`);
