import {createServer} from 'node:http';
import {readFileSync} from 'node:fs';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {validateRequest} from './fixture-validators.mjs';

const here=dirname(fileURLToPath(import.meta.url));
export const fixture=JSON.parse(readFileSync(resolve(here,'fixtures/local-preview.json'),'utf8'));
export function createPreviewStub(mode='ok') {
  return createServer(async (request,response)=>{
    const correlation=request.headers['x-correlation-id'] || 'stub-correlation';
    const send=(status,body)=>{response.writeHead(status,{'Content-Type':'application/json','X-Correlation-ID':correlation});response.end(JSON.stringify(body));};
    const error=(status,code)=>send(status,{code,message:'Synthetic local stub response',traceId:correlation,evidenceId:'stub-evidence'});
    if(request.url!=='/api/compliance/impact-previews'||request.method!=='POST') return error(404,'NOT_FOUND');
    if(request.headers.authorization!=='Bearer stcn-local-stub') return error(401,'UNAUTHORIZED');
    const chunks=[];let size=0;
    for await(const chunk of request) {size+=chunk.length;if(size>65536) return error(413,'REQUEST_TOO_LARGE');chunks.push(chunk);}
    let body;
    try {body=JSON.parse(Buffer.concat(chunks).toString('utf8'));} catch {return error(400,'INVALID_REQUEST');}
    if(!validateRequest(body)) return error(400,'INVALID_REQUEST');
    if(body.organisationId!==fixture.cases[0].request.organisationId) return error(403,'FORBIDDEN');
    if(JSON.stringify(body)!==JSON.stringify(fixture.cases[0].request)) return error(422,'PROJECTION_UNAVAILABLE');
    if(mode==='http-503') return error(503,'SERVICE_UNAVAILABLE');
    const preview=structuredClone(fixture.previewResponse);preview.correlationId=correlation;
    if(mode==='wrong-tenant') preview.organisationId='manufacturer_other';
    if(mode==='wrong-kind') preview.findings[0].kind='CONFIRMED';
    if(mode==='missing-version') delete preview.findings[0].formulaVersion;
    if(mode==='bad-body') {response.writeHead(200,{'Content-Type':'application/json','X-Correlation-ID':correlation});response.end('not-json');return;}
    send(200,preview);
  });
}
