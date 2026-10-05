import assert from 'node:assert/strict';
import {mkdirSync,readFileSync,writeFileSync} from 'node:fs';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {createPreviewStub,fixture} from './stub.mjs';
import {validateRequest,validateResponse,validateError} from './fixture-validators.mjs';

const root=dirname(fileURLToPath(import.meta.url));
const evidence=resolve(process.env.EVIDENCE_DIR||resolve(root,'.evidence'));
mkdirSync(evidence,{recursive:true});
assert(validateRequest(fixture.cases[0].request),JSON.stringify(validateRequest.errors));
assert(validateResponse(fixture.previewResponse),JSON.stringify(validateResponse.errors));
const partial=structuredClone(fixture.cases[0].request);delete partial.ruleSetVersionId;
assert.equal(validateRequest(partial),false);
const confirmed=structuredClone(fixture.previewResponse);confirmed.findings[0].kind='CONFIRMED';
assert.equal(validateResponse(confirmed),false);
console.log('PASS actual OpenAPI request/response fixture schemas and negative cases');

const server=createPreviewStub();server.listen(0,'127.0.0.1');await once(server,'listening');
const url=`http://127.0.0.1:${server.address().port}/api/compliance/impact-previews`;
try {
  for(const [name,headers,body,status] of [
    ['missing synthetic token',{},fixture.cases[0].request,401],
    ['cross-organisation',{Authorization:'Bearer stcn-local-stub'},{...fixture.cases[0].request,organisationId:'manufacturer_other'},403],
    ['partial snapshot',{Authorization:'Bearer stcn-local-stub'},partial,400],
  ]) {
    const r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json','X-Correlation-ID':'stub-negative',...headers},body:JSON.stringify(body)});
    assert.equal(r.status,status);assert.equal(r.headers.get('x-correlation-id'),'stub-negative');
    assert(validateError(await r.json()));console.log('PASS stub '+name+' with canonical error');
  }
} finally {await new Promise((resolve,reject)=>server.close(e=>e?reject(e):resolve()));}

async function runDriver(mode,shouldPass) {
  const stub=createPreviewStub(mode);stub.listen(0,'127.0.0.1');await once(stub,'listening');
  const resultFile=resolve(evidence,`${mode}-summary.json`);
  let output='';
  try {
    const child=spawn(process.env.K6_BIN||'k6',['run','--env',`BASE_URL=http://127.0.0.1:${stub.address().port}`,'--env','DRY_RUN=true','--env',`RESULT_FILE=${resultFile}`,resolve(root,'impact-preview.js')],{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe']});
    child.stdout.on('data',c=>{output+=c.toString();});child.stderr.on('data',c=>{output+=c.toString();});
    const timeout=setTimeout(()=>child.kill(),30000);
    let code;
    try {[code]=await once(child,'close');} finally {clearTimeout(timeout);}
    writeFileSync(resolve(evidence,`${mode}-k6.txt`),output);
    assert.equal(code===0,shouldPass,`k6 ${mode}: ${output}`);
    const summary=JSON.parse(readFileSync(resultFile,'utf8'));
    const errors=summary.metrics['contract_errors{phase:hold}'];
    assert.equal(errors.thresholds['rate<0.01'].ok,shouldPass);
    assert(summary.metrics.iterations.values.count>=8,'Enough open arrivals must execute');
    if(shouldPass) {
      assert.equal(errors.values.rate,0);
      assert.equal(summary.metrics['validated_response_duration{phase:hold}'].thresholds['p(95)<=2000'].ok,true);
      assert.equal(summary.metrics.dropped_iterations.thresholds['count==0'].ok,true);
    }
    console.log(`PASS actual k6 ${mode}: ${shouldPass?'success':'invalid preview rejected'}`);
  } finally {await new Promise((resolve,reject)=>stub.close(e=>e?reject(e):resolve()));}
}
for(const [mode,shouldPass] of [['ok',true],['wrong-tenant',false],['wrong-kind',false],['missing-version',false],['bad-body',false],['http-503',false]]) await runDriver(mode,shouldPass);
console.log('10 local fixture/protocol/k6 checks passed. Local stub is not a JWT, capacity or failover implementation.');
