import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import yaml from 'js-yaml';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
const root=new URL('./',import.meta.url);
const load=path=>yaml.safeLoad(readFileSync(new URL(path,root),'utf8'));
const api=load('openapi/label-workflow-v1.yaml');
const canonical=load('../docs/contracts/allergen-validation-api-v1.yaml').components.schemas.ApiError;
assert.equal(api.components.schemas.ApiError.$ref,'../../docs/contracts/allergen-validation-api-v1.yaml#/components/schemas/ApiError');
function rewrite(value){if(Array.isArray(value))return value.map(rewrite);if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,k==='$ref'?v.replace('#/components/schemas/','#/$defs/'):rewrite(v)]));return value;}
const defs=rewrite(api.components.schemas);defs.ApiError=canonical;
const ajv=new Ajv2020({strict:false,allErrors:true});addFormats(ajv);
const schema={$id:'https://contracts.spectrace.invalid/label-http.json',$schema:'https://json-schema.org/draft/2020-12/schema',$defs:defs};ajv.addSchema(schema);
const validator=name=>ajv.compile({$ref:schema.$id+'#/$defs/'+name});
let count=0;function check(name,run){run();count++;console.log('PASS '+name);}
const v=validator('LabelValidationRecord');
const blocked={resultCode:'DECLARED_ALLERGEN_MISSING',severity:'ERROR',passed:false,blocking:true,message:'A required declaration is missing.'};
const record={validationRunId:'validation_001',labelVersionId:'label_001_v1',formulaVersionId:'formula_001_v2',ruleSetVersionId:'rules_001_v1',jurisdictionCode:'SG',status:'PASSED',findings:[]};
function expect(name,value,wanted){check(name,()=>assert.equal(Boolean(v(value)),wanted,ajv.errorsText(v.errors)));}
expect('PASSED with no blocking findings',record,true);
expect('FAILED retains blocking ERROR',{...record,status:'FAILED',findings:[blocked]},true);
expect('PASSED rejects blocking ERROR',{...record,findings:[blocked]},false);
const warning={...blocked,severity:'WARNING',blocking:false};
expect('PASSED permits nonblocking warning',{...record,findings:[warning]},true);
expect('FAILED rejects missing evidence',{...record,status:'FAILED'},false);
expect('FAILED rejects empty evidence',{...record,status:'FAILED',findings:[]},false);
expect('FAILED rejects nonblocking warning only',{...record,status:'FAILED',findings:[warning]},false);
expect('blocking evidence cannot be WARNING',{...record,status:'FAILED',findings:[{...blocked,severity:'WARNING'}]},false);
expect('blocking ERROR cannot claim passed',{...record,status:'FAILED',findings:[{...blocked,passed:true}]},false);
expect('missing status rejected',Object.fromEntries(Object.entries(record).filter(([k])=>k!=='status')),false);
check('JWT and operation permission boundaries preserved',()=>{assert.deepEqual(api.security,[{bearerAuth:[]}]);assert.equal(api.paths['/api/labels/{labelVersionId}/validations'].post['x-required-permission'],'LABEL.VALIDATE');});
for(const [path,item] of Object.entries(api.paths))for(const [method,op]of Object.entries(item)){if(!['get','post','put','patch','delete'].includes(method))continue;
check(op.operationId+' preserves correlation and canonical errors',()=>{
const params=[...(item.parameters??[]),...(op.parameters??[])].map(p=>p.$ref?api.components.parameters[p.$ref.split('/').at(-1)]:p);assert(params.some(p=>p.in==='header'&&p.name==='X-Correlation-ID'&&p.required));
assert(op.responses['403']);for(const [status,response]of Object.entries(op.responses)){const r=response.$ref?api.components.responses[response.$ref.split('/').at(-1)]:response;assert(r.headers['X-Correlation-ID'].required);if(Number(status)>=400)assert.equal(r.content['application/json'].schema.$ref,'#/components/schemas/ApiError');}
});}
const e=validator('ApiError');
for(const [name,response]of Object.entries(api.components.responses))check(name+' retains canonical error example',()=>assert(e(response.content['application/json'].example),ajv.errorsText(e.errors)));
check('canonical errors retain all four fields',()=>{assert.deepEqual([...canonical.required].sort(),['code','evidenceId','message','traceId']);const error={code:'INVALID_REQUEST',message:'Invalid request.',traceId:null,evidenceId:null};assert(e(error));delete error.evidenceId;assert.equal(e(error),false);});
check('403 distinguishes private resource denial from absence',()=>{assert(api.components.responses.AuthorizationDenied.description.includes('another organisation'));assert(!api.components.responses.LabelNotFound.description.includes('not visible'));});
check('public DTO field sets remain unchanged',()=>{assert.deepEqual(api.components.schemas.LabelDraft.required,['labelVersionId','productId','formulaVersionId','ruleSetVersionId','jurisdictionCode','versionNumber','lifecycleStatus']);assert.deepEqual(api.components.schemas.LabelValidationRecord.required,['validationRunId','labelVersionId','formulaVersionId','ruleSetVersionId','jurisdictionCode','status','findings']);});
console.log(`${count} Label Workflow HTTP compatibility checks passed; private adapter/runtime and owner acceptance remain separate.`);