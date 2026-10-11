import assert from 'node:assert/strict';
import {mkdirSync, writeFileSync} from 'node:fs';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {parseAllDocuments} from 'yaml';

const root=dirname(fileURLToPath(import.meta.url));
const helm=process.env.HELM_BIN || 'helm';
const chart=resolve(root,'spectrace-service');
const evidence=resolve(process.env.EVIDENCE_DIR || resolve(root,'.evidence'));
mkdirSync(evidence,{recursive:true});
let count=0;
const run=(args,pass=true)=>{
  const r=spawnSync(helm,args,{encoding:'utf8'});
  if(r.error) throw r.error;
  if(pass) assert.equal(r.status,0,r.stderr+'\n'+r.stdout);
  else assert.notEqual(r.status,0,'Invalid placement/resources must fail values validation');
  return r;
};
const check=(name,fn)=>{fn();count++;console.log('PASS '+name);};
const render=(service,extra=[])=>{
  const r=run(['template','review',chart,'-f',resolve(root,`values/${service}.yaml`),...extra]);
  const docs=parseAllDocuments(r.stdout);
  for(const doc of docs) assert.equal(doc.errors.length,0,doc.errors.join('\n'));
  return docs.map(d=>d.toJSON()).filter(Boolean);
};
check('strict lint for every service values file',()=>{
  const outputs=[];
  for(const service of ['compliance','specification','formulation','label-workflow','gateway']) {
    outputs.push(run(['lint',chart,'--strict','-f',resolve(root,`values/${service}.yaml`)]).stdout);
  }
  writeFileSync(resolve(evidence,'helm-lint.txt'),outputs.join('\n'));
});
for(const service of ['compliance','specification','formulation','label-workflow','gateway']) {
  check(`${service} uses the same chart, probes and ClusterIP policy`,()=>{
    const docs=render(service);
    const dep=docs.find(d=>d.kind==='Deployment');
    const pod=dep.spec.template.spec;
    assert.deepEqual(dep.spec.selector.matchLabels,dep.spec.template.metadata.labels);
    const c=pod.containers[0];
    assert.equal(c.name,service);
    assert.equal(c.startupProbe.httpGet.port,'http');
    assert.equal(c.readinessProbe.httpGet.path,'/actuator/health/readiness');
    assert.equal(c.livenessProbe.httpGet.path,'/actuator/health/liveness');
    assert.equal(pod.automountServiceAccountToken,false);
    assert.equal(c.securityContext.allowPrivilegeEscalation,false);
    assert.equal(c.securityContext.readOnlyRootFilesystem,true);
    assert.equal(docs.find(d=>d.kind==='Service').spec.type,'ClusterIP');
    const np=docs.find(d=>d.kind==='NetworkPolicy');
    assert.deepEqual(np.spec.policyTypes,['Ingress','Egress']);
    assert.equal(np.spec.egress.length,1,'Default egress is DNS only');
    assert.deepEqual(np.spec.egress[0].ports.map(p=>p.protocol),['UDP','TCP']);
    assert(!docs.some(d=>d.kind==='HorizontalPodAutoscaler'),'Fixed comparison must disable HPA');
    if(service!=='compliance') assert.equal(pod.nodeSelector['eks.amazonaws.com/nodegroup'],'general');
  });
}
check('fixed three-replica placement requires c6i.large and compliance group',()=>{
  const docs=render('compliance',['--set','replicaCount=3']);
  const dep=docs.find(d=>d.kind==='Deployment');
  assert.equal(dep.spec.replicas,3);
  const pod=dep.spec.template.spec;
  assert.deepEqual(pod.nodeSelector,{'eks.amazonaws.com/nodegroup':'compliance','node.kubernetes.io/instance-type':'c6i.large'});
  assert.deepEqual(pod.containers[0].resources,{requests:{cpu:'1',memory:'2Gi'},limits:{cpu:'1',memory:'2Gi'}});
  assert(pod.tolerations.some(t=>t.key==='dedicated'&&t.value==='compliance'&&t.effect==='NoSchedule'));
  const zone=pod.topologySpreadConstraints.find(t=>t.topologyKey==='topology.kubernetes.io/zone');
  assert.equal(zone.minDomains,2);assert.equal(zone.maxSkew,1);assert.equal(zone.whenUnsatisfiable,'DoNotSchedule');
  const host=pod.topologySpreadConstraints.find(t=>t.topologyKey==='kubernetes.io/hostname');
  assert.equal(host.whenUnsatisfiable,'DoNotSchedule');
  const np=docs.find(d=>d.kind==='NetworkPolicy');
  assert.deepEqual(np.spec.ingress.map(r=>r.from[0].podSelector.matchLabels['app.kubernetes.io/name']),['gateway','label-workflow']);
  writeFileSync(resolve(evidence,'compliance-three-replicas.json'),JSON.stringify(docs,null,2)+'\n');
});
check('optional Should HPA preserves 1–4 range and fixed per-pod limits',()=>{
  const docs=render('compliance',['--set','autoscaling.enabled=true']);
  const hpa=docs.find(d=>d.kind==='HorizontalPodAutoscaler');
  assert.equal(hpa.apiVersion,'autoscaling/v2');
  assert.equal(hpa.spec.minReplicas,1);assert.equal(hpa.spec.maxReplicas,4);
  assert.equal(hpa.spec.metrics[0].resource.target.averageUtilization,60);
  assert.equal(docs.find(d=>d.kind==='Deployment').spec.replicas,undefined);
});
for(const [name,args] of [
  ['CPU limit changed',['--set-string','resources.limits.cpu=2']],
  ['memory request changed',['--set-string','resources.requests.memory=1Gi']],
  ['general node group selected',['--set-string','nodeSelector.eks\\.amazonaws\\.com/nodegroup=general']],
  ['wrong instance type',['--set-string','nodeSelector.node\\.kubernetes\\.io/instance-type=t3.medium']],
  ['spread disabled',['--set','spread.enabled=false']],
  ['network policy disabled',['--set','networkPolicy.enabled=false']],
  ['autoscale range relaxed',['--set','autoscaling.maxReplicas=5']]
]) check(`reject ${name}`,()=>run(['template','review',chart,'-f',resolve(root,'values/compliance.yaml'),...args],false));
check('empty ingress/egress values render explicit deny rules',()=>{
  const docs=render('specification',['--set','networkPolicy.allowDNS=false','--set-json','networkPolicy.ingressFromServices=[]']);
  const np=docs.find(d=>d.kind==='NetworkPolicy');
  assert.deepEqual(np.spec.ingress,[]);assert.deepEqual(np.spec.egress,[]);
});
console.log(`${count} Helm checks passed. Rendered constraints are not live pod-placement evidence.`);
