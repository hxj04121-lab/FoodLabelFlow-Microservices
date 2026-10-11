# G1 M2 / M5 Compliance node-pool handoff — STCN-47

M2 draft interface, 5 October 2026; architecture authority: AWS v3 §8–10, ADR-08/09. M5 owns Terraform and cloud lifecycle. No Terraform implementation or actual placement evidence is available in main/candidates at inspection; this interface is pending M5 agreement.

| Requirement | Proposed exact interface for the chart |
|---|---|
| Region / lifetime | ap-southeast-1; on-demand evidence windows |
| EKS group / instance | compliance / c6i.large, On-Demand, minimum 1 maximum 4 |
| Required labels | eks.amazonaws.com/nodegroup=compliance; node.kubernetes.io/instance-type=c6i.large |
| Taint / M2 toleration | dedicated=compliance:NoSchedule / Equal, compliance, NoSchedule |
| Topology | eligible nodes across two AZs, topology.kubernetes.io/zone and kubernetes.io/hostname labels |
| Per replica | requests and limits both 1 CPU / 2 GiB |
| Must comparison | fixed 1 and 3 replicas, HPA disabled; 3 Ready pods on separate nodes before pod failover |
| Should | HPA CPU 60%, pods/nodes 1–4, after Must gates and execution authorization |

Before integration M5 confirms group name, taint, Kubernetes version >=1.30, subnet/AZ distribution, CNI NetworkPolicy enforcement, Cluster Autoscaler visibility and per-node allocatable resources. M2 supplies values and verifies actual pod/node labels at the approved evidence window. M5 supplies database/broker/IdP/metrics networking values; credentials are supplied separately by their owners.

Three general t3.medium nodes use 6 vCPU; four Compliance c6i.large nodes use 8; the separate c6i.large k6 host uses 2, totaling 16 nominal vCPU before rolling-update surge. M5 PR10 declares 16 required and 20 recommended quota, but live account quota/credit/access remain unverified. Its reusable budget workflow also needs an explicit fail-closed enforcement path: caller github.event_name is not workflow_call, so omitted credit can currently return success. Review is recorded in G0 M2 steward notes; M2 does not change M5's branch or infer apply authorization.

Acceptance remains pending actual M5 Terraform, approved gate prerequisites, runtime pod placement and M4 review. These static assets cannot satisfy G1's staging deployment criterion.
