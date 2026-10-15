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
    assert(np.spec.egress.some(r=>r.to?.some(t=>t.namespaceSelector?.matchLabels?.['kubernetes.io/metadata.name']==='kube-system')),'DNS is allowed');
    assert(!np.spec.egress.some(r=>r.to?.some(t=>t.ipBlock?.cidr==='0.0.0.0/0')),'No unrestricted egress');
    assert(!docs.some(d=>d.kind==='HorizontalPodAutoscaler'),'Fixed comparison must disable HPA');
    if(service!=='gateway') assert.equal(pod.containers[0].envFrom?.[0]?.secretRef?.name,`${service}-runtime`);
    if(service!=='compliance') assert.equal(pod.nodeSelector['eks.amazonaws.com/nodegroup'],'general');
    const crossNamespace=np.spec.ingress.flatMap(r=>r.from??[]).filter(s=>s.namespaceSelector).map(s=>[s.namespaceSelector.matchLabels['kubernetes.io/metadata.name'],s.podSelector.matchLabels['app.kubernetes.io/name']]);
    const expectedPeers={gateway:[['observability','grafana-alloy']],specification:[['gateway','gateway'],['observability','grafana-alloy']],formulation:[['gateway','gateway'],['observability','grafana-alloy']],compliance:[['gateway','gateway'],['label-workflow','label-workflow'],['observability','grafana-alloy']],'label-workflow':[['gateway','gateway'],['observability','grafana-alloy']]}[service];
    assert.deepEqual(crossNamespace.sort((a,b)=>a.join('/').localeCompare(b.join('/'))),expectedPeers.sort((a,b)=>a.join('/').localeCompare(b.join('/'))));
    assert(np.spec.ingress.every(r=>(r.from??[]).every(s=>s.namespaceSelector&&s.podSelector)),'Every pod ingress peer is restricted by namespace and pod labels');
    if(service==='label-workflow') {
      assert(np.spec.egress.some(r=>r.to?.some(p=>p.namespaceSelector?.matchLabels?.['kubernetes.io/metadata.name']==='compliance'&&p.podSelector?.matchLabels?.['app.kubernetes.io/name']==='compliance')&&r.ports?.some(p=>p.port===8080)),'Compliance validation is allowed only to the Compliance workload on 8080');
      assert.equal(c.env.find(e=>e.name==='SPECTRACE_LABEL_WORKFLOW_VALIDATION_BASE_URL')?.value,'http://compliance.compliance.svc.cluster.local:8080');
    }
    if(service==='gateway') assert(np.spec.egress.some(r=>r.to?.some(p=>p.namespaceSelector?.matchLabels?.['kubernetes.io/metadata.name']==='specification'&&p.podSelector?.matchLabels?.['app.kubernetes.io/name']==='specification')&&r.ports?.some(p=>p.port===8080)));
    else assert(np.spec.egress.some(r=>r.to?.some(p=>p.namespaceSelector?.matchLabels?.['kubernetes.io/metadata.name']==='rabbitmq'&&p.podSelector?.matchLabels?.['app.kubernetes.io/name']==='rabbitmq')));
  });
}
check('fixed three-replica placement requires c6i.large and compliance group',()=>{
  const docs=render('compliance',['--set','replicaCount=3']);
  const dep=docs.find(d=>d.kind==='Deployment');
  assert.equal(dep.spec.replicas,3);
  const pod=dep.spec.template.spec;
  assert.deepEqual(pod.nodeSelector,{'eks.amazonaws.com/nodegroup':'compliance','node.kubernetes.io/instance-type':'c6i.large'});
  assert.deepEqual(pod.containers[0].resources,{requests:{cpu:'1',memory:'2Gi'},limits:{cpu:'1',memory:'2Gi'}});
  assert.deepEqual(pod.tolerations,[{key:'dedicated',operator:'Equal',value:'compliance',effect:'NoSchedule'}]);
  const zone=pod.topologySpreadConstraints.find(t=>t.topologyKey==='topology.kubernetes.io/zone');
  assert.equal(zone.minDomains,2);assert.equal(zone.maxSkew,1);assert.equal(zone.whenUnsatisfiable,'DoNotSchedule');
  const host=pod.topologySpreadConstraints.find(t=>t.topologyKey==='kubernetes.io/hostname');
  assert.equal(host.whenUnsatisfiable,'DoNotSchedule');
  const np=docs.find(d=>d.kind==='NetworkPolicy');
  assert.deepEqual(np.spec.ingress.flatMap(r=>r.from??[]).filter(s=>s.namespaceSelector).map(s=>[s.namespaceSelector.matchLabels['kubernetes.io/metadata.name'],s.podSelector.matchLabels['app.kubernetes.io/name']]).sort((a,b)=>a.join('/').localeCompare(b.join('/'))),[['gateway','gateway'],['label-workflow','label-workflow'],['observability','grafana-alloy']].sort((a,b)=>a.join('/').localeCompare(b.join('/'))));
  assert(np.spec.egress.some(r=>r.to?.some(t=>t.podSelector?.matchLabels?.['app.kubernetes.io/name']==='rabbitmq')&&r.ports[0].port===5672));
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
  ['obsolete workload taint key',['--set-string','tolerations[0].key=workload']],
  ['spread disabled',['--set','spread.enabled=false']],
  ['network policy disabled',['--set','networkPolicy.enabled=false']],
  ['autoscale range relaxed',['--set','autoscaling.maxReplicas=5']]
]) check(`reject ${name}`,()=>run(['template','review',chart,'-f',resolve(root,'values/compliance.yaml'),...args],false));
check('empty ingress/egress values render explicit deny rules',()=>{
  const docs=render('specification',['--set','networkPolicy.allowDNS=false','--set-json','networkPolicy.ingressFromPeers=[]','--set-json','networkPolicy.egressRules=[]']);
  const np=docs.find(d=>d.kind==='NetworkPolicy');
  assert.deepEqual(np.spec.ingress,[]);assert.deepEqual(np.spec.egress,[]);
});

check('database egress is restricted to configured private subnet CIDRs and MySQL port',()=>{
  const docs=render('specification',['--set-json','networkPolicy.databaseCidrs=["10.20.4.0/24","10.20.5.0/24"]']);
  const np=docs.find(d=>d.kind==='NetworkPolicy');
  for(const cidr of ['10.20.4.0/24','10.20.5.0/24']) assert(np.spec.egress.some(r=>r.to?.some(t=>t.ipBlock?.cidr===cidr)&&r.ports?.some(p=>p.port===3306)));
  assert(!np.spec.egress.some(r=>r.to?.some(t=>t.ipBlock?.cidr==='0.0.0.0/0')));
});
check('reject invalid RDS CIDR values',()=>run(['template','review',chart,'-f',resolve(root,'values/specification.yaml'),'--set-json','networkPolicy.databaseCidrs=["999.20.4.0/24"]'],false));
check('ALB ingress is accepted only from an explicit CIDR allow-list',()=>{
  const docs=render('gateway',['--set','networkPolicy.ingressFromCIDRs[0]=10.20.0.0/24']);
  const np=docs.find(d=>d.kind==='NetworkPolicy');
  assert.deepEqual(np.spec.ingress.filter(r=>r.from[0].ipBlock),[{from:[{ipBlock:{cidr:'10.20.0.0/24'}}],ports:[{port:'http',protocol:'TCP'}]}]);
});
check('reject invalid ALB CIDR values',()=>run(['template','review',chart,'-f',resolve(root,'values/gateway.yaml'),'--set','networkPolicy.ingressFromCIDRs[0]=999.20.0.0/24'],false));


check('immutable image digest renders by digest',()=>{
  const digest='sha256:'+'a'.repeat(64);
  const docs=render('compliance',['--set','image.digest='+digest]);
  const deployment=docs.find(d=>d.kind==='Deployment');
  assert(deployment.spec.template.spec.containers[0].image.endsWith('@'+digest));
});
check('invalid image digest is rejected',()=>run(['template','review',chart,'-f',resolve(root,'values/compliance.yaml'),'--set','image.digest=sha256:short'],false));

console.log(`${count} Helm checks passed. Rendered constraints are not live pod-placement evidence.`);
