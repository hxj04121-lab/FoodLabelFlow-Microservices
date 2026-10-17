import assert from 'node:assert/strict';
import {mkdirSync, readFileSync, writeFileSync} from 'node:fs';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {parse, parseAllDocuments} from 'yaml';

const root=dirname(fileURLToPath(import.meta.url));
const repo=resolve(root,'../..');
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
check('first Gateway release pins its verified image and only permits required in-cluster egress',()=>{
  const docs=render('gateway');
  const deployment=docs.find(d=>d.kind==='Deployment');
  const pod=deployment.spec.template.spec;
  assert.equal(pod.containers[0].image,'ghcr.io/hxj04121-lab/foodlabelflow-microservices/gateway@sha256:d4190ea6ee31b84e7b67d36ad56e6391612c8bdc4916b8bb14d28b9a17f6ab47');
  assert.equal(pod.nodeSelector['eks.amazonaws.com/nodegroup'],'general');
  assert.equal(pod.topologySpreadConstraints,undefined);
  const np=docs.find(d=>d.kind==='NetworkPolicy');
  assert.deepEqual(np.spec.ingress.flatMap(r=>r.from).map(s=>[s.namespaceSelector.matchLabels['kubernetes.io/metadata.name'],s.podSelector.matchLabels['app.kubernetes.io/name']]),
    [['observability','grafana-alloy']],'Only the Alloy metrics scraper may reach Gateway pods in-cluster');
  assert.equal(np.spec.egress.length,6,'DNS plus the five Gateway upstream services');
  assert.deepEqual(np.spec.egress[1].to[0].podSelector.matchLabels,{app:'keycloak'},'Keycloak egress follows the Operator pod label');
  for(const rule of np.spec.egress.slice(1)) assert.deepEqual(rule.ports,[{port:8080,protocol:'TCP'}]);
});
check('Gateway render preserves every required env in deploy workflow values order',()=>{
  const runtime=spawnSync('python3',[resolve(repo,'platform/gateway/deploy-runtime-values.py')],{
    cwd:repo,encoding:'utf8',env:{...process.env,GATEWAY_ISSUER:'https://id.example.test/auth/realms/spectrace'}
  });
  assert.equal(runtime.status,0,runtime.stderr);
  const runtimePath=resolve(evidence,'gateway-runtime-values.json');
  writeFileSync(runtimePath,runtime.stdout);
  const workflow=readFileSync(resolve(repo,'.github/workflows/deploy-gateway.yml'),'utf8');
  assert.match(workflow,/--values deploy\/helm\/values\/gateway\.yaml --values "\$RUNNER_TEMP\/gateway-runtime-values\.json"/);
  const rendered=run(['template','gateway',chart,'--namespace','gateway',
    '--values',resolve(root,'values/gateway.yaml'),'--values',runtimePath]);
  const docs=parseAllDocuments(rendered.stdout).map(d=>d.toJSON()).filter(Boolean);
  const deployment=docs.find(d=>d.kind==='Deployment'&&d.metadata.name==='gateway-gateway');
  const env=deployment.spec.template.spec.containers[0].env;
  const values=Object.fromEntries(env.map(row=>[row.name,row.value]));
  assert.equal(env.length,14,'each required Gateway variable appears once');
  assert.deepEqual(values,{
    GATEWAY_REALM:'spectrace',GATEWAY_AUDIENCE:'spectrace-api',
    GATEWAY_ALLOWED_ORIGINS:'https://hxj04121-lab.github.io,https://id.example.test',
    GATEWAY_SPECIFICATION_WRITE_ROLES:'ADMIN_DATA_MAINTENANCE',
    GATEWAY_FORMULATION_WRITE_ROLES:'ADMIN_DATA_MAINTENANCE',
    GATEWAY_COMPLIANCE_WRITE_ROLES:'CHANGE_MANAGER',
    GATEWAY_LABEL_WRITE_ROLES:'LABEL_OFFICER,APPROVER,PUBLISHER',
    GATEWAY_ISSUER:'https://id.example.test/auth/realms/spectrace',
    GATEWAY_JWK_SET_URI:'http://spectrace-keycloak-service.keycloak.svc.cluster.local:8080/auth/realms/spectrace/protocol/openid-connect/certs',
    GATEWAY_KEYCLOAK:'http://spectrace-keycloak-service.keycloak.svc.cluster.local:8080',
    GATEWAY_SPECIFICATION:'http://specification-specification.specification.svc.cluster.local:8080',
    GATEWAY_FORMULATION:'http://formulation-formulation.formulation.svc.cluster.local:8080',
    GATEWAY_COMPLIANCE:'http://compliance-compliance.compliance.svc.cluster.local:8080',
    GATEWAY_LABEL_WORKFLOW:'http://label-workflow-label-workflow.label-workflow.svc.cluster.local:8080'
  });
});
check('Gateway deploy workflow is manual, staging-protected, VPC-only, and OIDC-scoped',()=>{
  const workflow=parse(readFileSync(resolve(root,'../../.github/workflows/deploy-gateway.yml'),'utf8'));
  assert.deepEqual(Object.keys(workflow.on),['workflow_dispatch']);
  assert.deepEqual(workflow.jobs.deploy['runs-on'],['self-hosted','linux','staging-vpc']);
  assert.equal(workflow.jobs.deploy.environment,'staging');
  assert.deepEqual(workflow.jobs.deploy.permissions,{contents:'read','id-token':'write'});
  assert.equal(workflow.jobs.deploy.env.AWS_ACCOUNT_ID,'${{ vars.AWS_ACCOUNT_ID }}');
  assert.equal(workflow.jobs.deploy.env.AWS_REGION,'${{ vars.AWS_REGION }}');
  assert.equal(workflow.jobs.deploy.env.EKS_CLUSTER_NAME,'${{ vars.EKS_CLUSTER_NAME }}');
  assert.equal(workflow.jobs.deploy.env.AWS_ROLE_ARN,'${{ vars.AWS_DEPLOY_ROLE_ARN }}');
  const script=workflow.jobs.deploy.steps.map(step=>[step.name||'',step.run||''].join('\n')).join('\n');
  assert.match(script,/kubectl get namespace gateway/);
  assert.doesNotMatch(script,/create namespace|kubectl create ns|terraform apply/i);
  assert.doesNotMatch(script,/679857346122|spectrace-staging|ap-southeast-1/,'AWS target identifiers must come from the protected Environment');
  assert.match(script,/validate-deploy-target\.py/);
  assert.match(script,/helm install gateway/);
  assert.match(script,/anonymous GHCR pull/);
  assert.doesNotMatch(script,/well-known\/openid-configuration|kubectl get service "\$service"|for item in 'GATEWAY_KEYCLOAK/,'process readiness does not require Keycloak or business Services to be deployed');
});
check('invalid image digest is rejected',()=>run(['template','review',chart,'-f',resolve(root,'values/compliance.yaml'),'--set','image.digest=sha256:short'],false));

check('single-node staging overlay is used only for general-pool services',()=>{
  for(const service of ['specification','formulation','label-workflow']) {
    const docs=render(service,['-f',resolve(root,'values/staging-single-node.yaml')]);
    const zone=docs.find(d=>d.kind==='Deployment').spec.template.spec.topologySpreadConstraints.find(t=>t.topologyKey==='topology.kubernetes.io/zone');
    assert.equal(zone.minDomains,1,`${service} can use the single-zone general pool`);
  }
  const gateway=render('gateway',['-f',resolve(root,'values/staging-single-node.yaml')]).find(d=>d.kind==='Deployment').spec.template.spec;
  assert.equal(gateway.topologySpreadConstraints,undefined,'Gateway uses its protected one-node staging configuration');
  run(['template','review',chart,'-f',resolve(root,'values/compliance.yaml'),'-f',resolve(root,'values/staging-single-node.yaml')],false);
  const compliance=render('compliance',['-f',resolve(root,'values/staging-rds.yaml')]).find(d=>d.kind==='Deployment').spec.template.spec;
  assert.equal(compliance.topologySpreadConstraints.find(t=>t.topologyKey==='topology.kubernetes.io/zone').minDomains,2);
  assert.deepEqual(compliance.nodeSelector,{'eks.amazonaws.com/nodegroup':'compliance','node.kubernetes.io/instance-type':'c6i.large'});
});

check('kind-only Compliance overlay keeps EKS placement and resource requirements',()=>{
  const staged=render('compliance',['-f',resolve(root,'values/compliance-kind.yaml')]);
  const pod=staged.find(d=>d.kind==='Deployment').spec.template.spec;
  assert.equal(pod.topologySpreadConstraints.find(t=>t.topologyKey==='topology.kubernetes.io/zone').minDomains,1);
  assert.deepEqual(pod.nodeSelector,{'eks.amazonaws.com/nodegroup':'compliance','node.kubernetes.io/instance-type':'c6i.large'});
  assert.deepEqual(pod.tolerations,[{key:'dedicated',operator:'Equal',value:'compliance',effect:'NoSchedule'}]);
  assert.deepEqual(pod.containers[0].resources,{requests:{cpu:'1',memory:'2Gi'},limits:{cpu:'1',memory:'2Gi'}});
  const base=render('compliance').find(d=>d.kind==='Deployment').spec.template.spec;
  assert.equal(base.topologySpreadConstraints.find(t=>t.topologyKey==='topology.kubernetes.io/zone').minDomains,2);
  run(['template','review',chart,'-f',resolve(root,'values/compliance.yaml'),'-f',resolve(root,'values/compliance-kind.yaml'),'--set','kindTest=false'],false);
});

check('staging RDS egress is limited to observed private subnets and MySQL',()=>{
  for(const service of ['specification','formulation','compliance','label-workflow']) {
    const np=render(service,['-f',resolve(root,'values/staging-rds.yaml')]).find(d=>d.kind==='NetworkPolicy');
    const rules=np.spec.egress.filter(r=>r.ports?.some(p=>p.port===3306));
    assert.deepEqual(rules.flatMap(r=>r.to.map(t=>t.ipBlock?.cidr)),['10.40.64.0/20','10.40.80.0/20']);
    assert(rules.every(r=>r.ports.length===1));
    assert(!np.spec.egress.some(r=>r.to?.some(t=>t.ipBlock?.cidr==='0.0.0.0/0')));
  }
});

check('staging Gateway reaches backends across their service namespaces',()=>{
  const np=render('gateway',['-f',resolve(root,'values/staging-gateway.yaml')]).find(d=>d.kind==='NetworkPolicy');
  const peers=np.spec.egress.flatMap(r=>r.to.filter(t=>t.namespaceSelector).map(t=>[
    t.namespaceSelector.matchLabels['kubernetes.io/metadata.name'],t.podSelector?.matchLabels?.['app.kubernetes.io/name']
  ])).filter(([,name])=>['specification','formulation','compliance','label-workflow'].includes(name));
  assert.deepEqual(peers,[['specification','specification'],['formulation','formulation'],['compliance','compliance'],['label-workflow','label-workflow']]);
  assert.deepEqual(np.spec.ingress.flatMap(r=>r.from.map(f=>f.ipBlock?.cidr).filter(Boolean)),['10.40.0.0/20','10.40.16.0/20']);
  assert(np.spec.ingress.some(r=>r.from?.some(f=>f.namespaceSelector?.matchLabels?.['kubernetes.io/metadata.name']==='observability'&&f.podSelector?.matchLabels?.['app.kubernetes.io/name']==='grafana-alloy')),'staging ingress retains Alloy metrics access');
});
check('shared staging overlay permits Label Workflow to reach Compliance on 8080',()=>{
  const label=render('label-workflow',['-f',resolve(root,'values/staging-shared-label-workflow.yaml')]);
  const policy=label.find(d=>d.kind==='NetworkPolicy').spec;
  const compliance=policy.egress.flatMap(r=>r.to??[]).filter(p=>p.podSelector?.matchLabels?.['app.kubernetes.io/name']==='compliance');
  assert.deepEqual(compliance.map(p=>p.namespaceSelector?.matchLabels?.['kubernetes.io/metadata.name']),['staging']);
  assert(policy.egress.some(r=>r.to?.some(p=>p.namespaceSelector?.matchLabels?.['kubernetes.io/metadata.name']==='staging'&&p.podSelector?.matchLabels?.['app.kubernetes.io/name']==='compliance')&&r.ports?.some(p=>p.port===8080)));
  assert.equal(label.find(d=>d.kind==='Deployment').spec.template.spec.containers[0].env.find(e=>e.name==='SPECTRACE_LABEL_WORKFLOW_VALIDATION_BASE_URL')?.value,'http://compliance.staging.svc.cluster.local:8080');
  const complianceDocs=render('compliance',['-f',resolve(root,'values/staging-shared-compliance.yaml')]);
  const peers=complianceDocs.find(d=>d.kind==='NetworkPolicy').spec.ingress.flatMap(r=>r.from??[]).filter(p=>p.podSelector?.matchLabels?.['app.kubernetes.io/name']==='label-workflow');
  assert.deepEqual(peers.map(p=>p.namespaceSelector?.matchLabels?.['kubernetes.io/metadata.name']),['staging']);
  const separated=render('label-workflow').find(d=>d.kind==='NetworkPolicy').spec;
  assert(separated.egress.some(r=>r.to?.some(p=>p.namespaceSelector?.matchLabels?.['kubernetes.io/metadata.name']==='compliance')));
  assert(!separated.egress.some(r=>r.to?.some(p=>p.namespaceSelector?.matchLabels?.['kubernetes.io/metadata.name']==='staging')));
});

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

check('PR frontend builds are read-only and publishing consumes only the tested same-run artifact',()=>{
  const workflow=parse(readFileSync(resolve(repo,'.github/workflows/swe5001-m3.yml'),'utf8'));
  const build=workflow.jobs['frontend-container'];
  assert.deepEqual(build.permissions,{contents:'read'},'PR-controlled build steps must have no package write permission');
  assert(!build.steps.some(s=>/docker\s+(login|push)\b/.test(s.run||'')),'Build job must not publish');
  const publish=workflow.jobs['publish-frontend-container'];
  assert.equal(publish.needs,'frontend-container');
  assert.equal(publish.permissions.packages,'write');
  assert.equal(publish.if,"github.event_name == 'push' && (github.ref == 'refs/heads/XFY' || github.ref == 'refs/heads/main') && vars.VITE_OIDC_AUTHORITY != ''");
  const download=publish.steps.find(s=>s.uses==='actions/download-artifact@v4');
  assert.equal(download.with.name,'m3-frontend-container');
  assert(!download.with['run-id']&&!download.with.repository,'Do not consume another run or repository artifact');
  assert(!publish.steps.some(s=>(s.uses||'').startsWith('actions/checkout@')),'Privileged publisher must not execute checkout code');
  const commands=publish.steps.map(s=>s.run||'').join('\n');
  assert(commands.includes('docker load')&&commands.includes('org.opencontainers.image.revision')&&commands.includes('docker push'));
});
console.log(`${count} Helm checks passed. Rendered constraints are not live pod-placement evidence.`);
