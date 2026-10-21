# G1 M2 / M5 Compliance node-pool handoff — STCN-47

> M1 staging-window decision (21 Oct 2026): the short demo starts with one Compliance node. This is a capacity baseline, not a revision to G1's two-AZ acceptance target. The Compliance chart still requires two eligible zones for its three-replica experiment; G1 remains NOT RUN until an approved run records actual scale-out and pod placement.

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

## October 11 actual M5 Terraform alignment

PR #27 at 540e99f7d11701b3173609a8be5ba61b50d382c4 defines the node group `compliance`, c6i.large, min 1/max 4 over the two private subnets, and taint **workload=compliance:NoSchedule**. The earlier `dedicated` proposal above is superseded for this staging implementation. The G3 experiment branch updates Helm's enforced toleration and tests to `workload`; M5 source is preserved. Immutable `image.digest` rendering is now supported for controlled trials. This is a static interface comparison; no real scheduling or Terraform apply was run. M5/M4 review and approved window remain required.

### October 11 22:40 review correction — current contract

The paragraph immediately above records the earlier 540e99f snapshot only. M5 #27 now uses **dedicated=compliance:NoSchedule** at 6fb4060438aa5f67fd2e1eb3c1e6560f0560874f (`infra/terraform/modules/eks/main.tf`), matching main's chart and M1's review on #33. The current values/schema/exact-toleration assertion retain `dedicated`; an obsolete `workload` override is rejected. Digest rendering remains the functional chart addition. Native Helm verification now has 19 checks. Live placement and AWS authorization remain unverified; no Terraform or cluster action occurred.
