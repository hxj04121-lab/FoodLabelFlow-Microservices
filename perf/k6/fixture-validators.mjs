import {readFileSync} from 'node:fs';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import {parse} from 'yaml';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const base='https://contracts.spectrace.invalid/';
const ajv=new Ajv2020({strict:false,allErrors:true});
addFormats(ajv);
for(const path of ['openapi/compliance-types.v1.schema.json','events/impact-finding-payload.v1.schema.json']) {
  ajv.addSchema(JSON.parse(readFileSync(resolve(root,'contracts',path),'utf8')),base+path);
}
const api=parse(readFileSync(resolve(root,'contracts/openapi/compliance.v1.yaml'),'utf8'));
const definitions=JSON.parse(JSON.stringify(api.components.schemas).replaceAll('#/components/schemas/','#/$defs/'));
const id=base+'openapi/preview-fixture.json';
ajv.addSchema({$id:id,$schema:'https://json-schema.org/draft/2020-12/schema',$defs:definitions});
export const validateRequest=ajv.compile({$ref:id+'#/$defs/ImpactPreviewRequest'});
export const validateResponse=ajv.compile({$ref:id+'#/$defs/ImpactPreview'});
export const validateError=ajv.compile({$ref:base+'openapi/compliance-types.v1.schema.json#/$defs/ApiError'});
