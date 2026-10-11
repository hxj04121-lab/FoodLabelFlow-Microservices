# AWS staging cost model

As of 2026-10-11, `ap-southeast-1`. These are planning estimates, not a Terraform Plan, quote, account charge, or budget gate. The owner selected on-demand staging windows: use local Compose between windows and destroy AWS staging after each approved window. Eight-hour and 24-hour rows are per window; 30-day 24×7 is a comparison only, not the expected operating pattern.

## Model and rates

The EKS model reflects three `t3.medium` general nodes with 30 GiB gp3 roots, an EKS standard-support control plane, private Multi-AZ `db.t3.small` MySQL with 20 GiB gp3, one ALB at one average LCU, one NAT Gateway processing 10 GiB/day, three public IPv4 addresses, 1 GiB S3 state, and three 8 GiB RabbitMQ volumes from the target architecture. Compliance is off in the baseline. RDS error/general/slow-query logs use Terraform-managed 14-day retention; the cost rows include a conservative 1 GiB/month of CloudWatch ingestion plus 1 GiB-month of storage. EKS 1.36 uses the AWS-owned KMS v2 key for default API-data envelope encryption, and state uses the AWS-managed S3 KMS key; these keys have no monthly key-storage fee, but KMS API usage may be billed. Internet egress, cross-AZ traffic, backups/snapshots, taxes, KMS requests, and actual request-driven LCU usage are also excluded.

| Component | Rate used |
| --- | ---: |
| EKS standard support | $0.10 / cluster-hour |
| EC2 `t3.medium` | $0.0528 / instance-hour |
| RDS `db.t3.small` Multi-AZ | $0.104 / deployment-hour (already includes standby) |
| RDS gp3 / EC2 gp3 | $0.276 / GiB-month / $0.096 / GiB-month |
| ALB / 1 LCU | $0.0252 / hour / $0.008 / LCU-hour |
| NAT Gateway / processing | $0.059 / hour / $0.059 / GiB |
| Public IPv4 | $0.005 / address-hour |
| S3 state bucket | $0.025 / GiB-month |
| CloudWatch Logs Standard ingestion / storage | $0.70 / GiB / $0.03 / GiB-month |

Rates came from the Singapore price-list snapshots recorded in the account readiness report on 2026-10-11; the CloudWatch rate came from a read-only Price List API query on 2026-10-11. Refresh prices and resource quantities before each apply. Official pricing references: [EKS](https://aws.amazon.com/eks/pricing/), [EC2](https://aws.amazon.com/ec2/pricing/on-demand/), [RDS MySQL](https://aws.amazon.com/rds/mysql/pricing/), [VPC/NAT and public IPv4](https://aws.amazon.com/vpc/pricing/), [CloudWatch](https://aws.amazon.com/cloudwatch/pricing/), and [KMS](https://aws.amazon.com/kms/pricing/).

## Cost by operating option

The EKS window values include the $0.025 monthly 1-GiB state-bucket allowance and $0.73 monthly CloudWatch allowance (1 GiB ingested plus 1 GiB-month stored). They do not include the G3-only Compliance scale-out or separate k6 EC2 runner. The Compose option is one `t3.medium` EC2 with a 30 GiB gp3 root and one public IPv4; MySQL runs on that host, so there is no EKS, NAT, ALB, managed RDS, or CloudWatch log-group charge.

| Scenario | Compose | Compose + 20% | EKS + RDS + ALB | EKS + 20% |
| --- | ---: | ---: | ---: | ---: |
| One 4-hour spike (EKS uses one general node; Compliance off) | $0.25 | $0.30 | $2.36 | $2.83 |
| One 8-hour demo window | $0.49 | $0.59 | $4.89 | $5.87 |
| One 24-hour demo window | $1.48 | $1.78 | $13.16 | $15.79 |
| 30 days at 24×7 (720 hours) | $44.46 | $53.35 | $372.81 | $447.37 |

Both options carry a 20% planning reserve in the adjacent columns. EKS is about 8.4× the modeled hourly cost of the single-instance Compose demo before experiment nodes and unknown logs/traffic. Compose is the quicker and cheaper fallback, but it is the current monolith and does not satisfy STCN-39/40's Kubernetes architecture evidence.

## G3 experiment add-ons

Enable the Terraform Compliance node group only for an approved experiment. The four-node case uses the maximum group size; it is not part of the normal window. The k6 host is a separate `t3.medium` EC2 with a 20 GiB gp3 volume and one public IPv4, billed only for its G3 hours.

| Optional G3 resource | 8 hours | 24 hours |
| --- | ---: | ---: |
| One Compliance `c6i.large` (30 GiB gp3 root) | $0.82 | $2.45 |
| Four Compliance `c6i.large` nodes (30 GiB gp3 each) | $3.26 | $9.79 |
| Separate k6 `t3.medium` runner (20 GiB gp3 + public IPv4) | $0.48 | $1.45 |

The architecture's roughly seven cumulative staging days (168 hours) model to $87.57 before experiments. If four Compliance nodes and k6 each run 24 hours during G3, the modeled total is $98.80, or $118.56 with a 20% reserve. Account credit balances and product eligibility change over time, so verify them in Billing before each window and keep account-specific balances out of this public repository. The monthly actual-cost budget has 50%, 80%, and 100% alert subscriptions; alerts do not cap charges. Include existing account resources, usage outside this project, snapshots, logs, traffic, taxes, and remaining credit eligibility in the live STCN-37 gate before approval.

Before the G3 run, supply the actual `k6_hours` and Compliance node-hours to the STCN-37 fail-closed gate, confirm credits/budget and audience CIDRs, and use a non-Root deploy role. The current STCN-39 branch does not provision a k6 runner; it only records the separate, G3-only cost input. No AWS resource has been created.
