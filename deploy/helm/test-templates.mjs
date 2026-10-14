import assert from 'node:assert/strict';
import {mkdirSync, readFileSync, writeFileSync} from 'node:fs';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {parse, parseAllDocuments} from 'yaml';

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
    assert.equal(np.spec.egress.length,service==='gateway'?6:1,service==='gateway'?'Gateway egress is DNS plus five scoped upstreams':'Default egress is DNS only');
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
  assert.deepEqual(pod.tolerations,[{key:'dedicated',operator:'Equal',value:'compliance',effect:'NoSchedule'}]);
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
  ['obsolete workload taint key',['--set-string','tolerations[0].key=workload']],
  ['spread disabled',['--set','spread.enabled=false']],
  ['network policy disabled',['--set','networkPolicy.enabled=false']],
  ['autoscale range relaxed',['--set','autoscaling.maxReplicas=5']]
]) check(`reject ${name}`,()=>run(['template','review',chart,'-f',resolve(root,'values/compliance.yaml'),...args],false));
check('empty ingress/egress values render explicit deny rules',()=>{
  const docs=render('specification',['--set','networkPolicy.allowDNS=false','--set-json','networkPolicy.ingressFromServices=[]']);
  const np=docs.find(d=>d.kind==='NetworkPolicy');
  assert.deepEqual(np.spec.ingress,[]);assert.deepEqual(np.spec.egress,[]);
});


check('immutable image digest renders by digest',()=>{
  const digest='sha256:'+'a'.repeat(64);
  const docs=render('compliance',['--set','image.digest='+digest]);
  const deployment=docs.find(d=>d.kind==='Deployment');
  assert(deployment.spec.template.spec.containers[0].image.endsWith('@'+digest));
});
check('first Gateway release pins its verified image and only permits required in-cluster egress',()=>{
  const docs=render('gateway');
  const deployment=docs.find(d=>d.kind==='Deployment');
  const pod=deployment.spec.template.spec;
  assert.equal(pod.containers[0].image,'ghcr.io/hxj04121-lab/foodlabelflow-microservices/gateway@sha256:d4190ea6ee31b84e7b67d36ad56e6391612c8bdc4916b8bb14d28b9a17f6ab47');
  assert.equal(pod.nodeSelector['eks.amazonaws.com/nodegroup'],'general');
  assert.equal(pod.topologySpreadConstraints,undefined);
  const np=docs.find(d=>d.kind==='NetworkPolicy');
  assert.equal(np.spec.ingress.length,0);
  assert.equal(np.spec.egress.length,6,'DNS plus the five Gateway upstream services');
  assert.deepEqual(np.spec.egress[1].to[0].podSelector.matchLabels,{app:'keycloak'},'Keycloak egress follows the Operator pod label');
  for(const rule of np.spec.egress.slice(1)) assert.deepEqual(rule.ports,[{port:8080,protocol:'TCP'}]);
  const env=pod.containers[0].env;
  assert.equal(env.find(row=>row.name==='GATEWAY_REALM').value,'spectrace');
  assert.equal(env.find(row=>row.name==='GATEWAY_AUDIENCE').value,'spectrace-api');
  assert.equal(env.find(row=>row.name==='GATEWAY_ALLOWED_ORIGINS').value,'https://hxj04121-lab.github.io');
});
check('Gateway deploy workflow is manual, staging-protected, VPC-only, and OIDC-scoped',()=>{
  const workflow=parse(readFileSync(resolve(root,'../../.github/workflows/deploy-gateway.yml'),'utf8'));
  assert.deepEqual(Object.keys(workflow.on),['workflow_dispatch']);
  assert.deepEqual(workflow.jobs.deploy['runs-on'],['self-hosted','linux','staging-vpc']);
  assert.equal(workflow.jobs.deploy.environment,'staging');
  assert.deepEqual(workflow.jobs.deploy.permissions,{contents:'read','id-token':'write'});
  const script=workflow.jobs.deploy.steps.map(step=>[step.name||'',step.run||''].join('\n')).join('\n');
  assert.match(script,/kubectl get namespace gateway/);
  assert.doesNotMatch(script,/create namespace|kubectl create ns|terraform apply/i);
  assert.match(script,/helm install gateway/);
  assert.match(script,/anonymous GHCR pull/);
  assert.doesNotMatch(script,/well-known\/openid-configuration|kubectl get service "\$service"|for item in 'GATEWAY_KEYCLOAK/,'process readiness does not require Keycloak or business Services to be deployed');
});
check('invalid image digest is rejected',()=>run(['template','review',chart,'-f',resolve(root,'values/compliance.yaml'),'--set','image.digest=sha256:short'],false));

const targetGroup='arn:aws:elasticloadbalancing:ap-southeast-1:000000000000:targetgroup/test-gateway/0123456789abcdef';
check('Gateway binds its existing target group without creating an Ingress/ALB',()=>{
  const docs=render('gateway',['--set-string','targetGroupArn='+targetGroup]);
  const binding=docs.find(d=>d.kind==='TargetGroupBinding');
  const service=docs.find(d=>d.kind==='Service');
  assert.equal(binding.spec.targetGroupARN,targetGroup);
  assert.equal(binding.spec.targetType,'ip');
  assert.equal(binding.spec.serviceRef.name,service.metadata.name);
  assert.equal(binding.spec.serviceRef.port,service.spec.ports[0].port);
  assert(!docs.some(d=>d.kind==='Ingress'));
});
check('target group binding is disabled by default',()=>assert(!render('gateway').some(d=>d.kind==='TargetGroupBinding')));
check('reject target binding on a business service',()=>run(['template','review',chart,'-f',resolve(root,'values/compliance.yaml'),'--set-string','targetGroupArn='+targetGroup],false));
check('reject malformed target group ARN',()=>run(['template','review',chart,'-f',resolve(root,'values/gateway.yaml'),'--set-string','targetGroupArn=not-an-arn'],false));

check('RabbitMQ policies match the actual spectrace Operator labels',()=>{
  const output=run(['template','review',resolve(root,'platform-network-policies')]).stdout;
  const docs=parseAllDocuments(output).map(d=>d.toJSON()).filter(Boolean);
  const policy=docs.find(d=>d.metadata.name==='stcn-rabbitmq-flows');
  assert.equal(policy.spec.podSelector.matchLabels['app.kubernetes.io/name'],'spectrace');
  for(const doc of docs) {
    const json=JSON.stringify(doc);
    assert(!json.includes('"app.kubernetes.io/name":"rabbitmq"'),'Operator uses cluster name, not the generic service name');
  }
  for(const service of ['specification','formulation','compliance','label-workflow']) {
    const policy=docs.find(d=>d.metadata.name===`stcn-${service}-flows`);
    assert(policy,`missing ${service} flows`);
    assert(policy.spec.egress.some(rule=>rule.ports?.some(p=>p.port===5672) && rule.to?.some(peer=>peer.podSelector?.matchLabels?.['app.kubernetes.io/name']==='spectrace')));
  }
});
check('staged RabbitMQ bundle renders placement patch and quorum PDB',()=>{
  const r=spawnSync(process.env.KUBECTL_BIN || 'kubectl',['kustomize',resolve(root,'../kubernetes/staging/rabbitmq')],{encoding:'utf8',timeout:60000});
  if(r.error) throw r.error;
  assert.equal(r.status,0,r.stderr+'\n'+r.stdout);
  const docs=parseAllDocuments(r.stdout).map(d=>d.toJSON()).filter(Boolean);
  const cluster=docs.find(d=>d.apiVersion==='rabbitmq.com/v1beta1' && d.kind==='RabbitmqCluster' && d.metadata.name==='spectrace');
  assert(cluster,'pinned RabbitMQ Cluster must be in the rendered bundle');
  const affinity=cluster.spec.affinity;
  assert.deepEqual(affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms[0].matchExpressions,
    [{key:'eks.amazonaws.com/nodegroup',operator:'In',values:['general']}]);
  const term=affinity.podAntiAffinity.requiredDuringSchedulingIgnoredDuringExecution[0];
  assert.equal(term.topologyKey,'kubernetes.io/hostname');
  assert.deepEqual(term.labelSelector.matchLabels,{'app.kubernetes.io/name':'spectrace','app.kubernetes.io/component':'rabbitmq'});
  const pdb=docs.find(d=>d.apiVersion==='policy/v1' && d.kind==='PodDisruptionBudget' && d.metadata.name==='spectrace-rabbitmq-quorum');
  assert(pdb,'quorum PDB must be in the rendered bundle');
  assert.equal(pdb.spec.maxUnavailable,1);
  assert.deepEqual(pdb.spec.selector,term.labelSelector);
});

console.log(`${count} Helm checks passed. Rendered constraints are not live pod-placement evidence.`);
