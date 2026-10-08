# STCN-37 — AWS staging deployment-window budget gate

**Region:** `ap-southeast-1` (Singapore)
**Pricing retrieved:** 2026-10-04 13:08 UTC from AWS public regional Price List CSV files
**Quota model:** Running On-Demand Standard instances, 16 vCPU required; 20 vCPU recommended. The applied account quota remains unverified and is tracked under STCN-38.

## What the gate checks

`python3 scripts/aws_staging_budget.py` estimates a planned staging window without AWS credentials. Supply `--hours`, `--experiment-hours`, and `--k6-hours` to match the deployment plan. The baseline includes 3 `t3.medium` nodes and one `c6i.large` compliance node for the full window. Up to three additional compliance nodes are billed only for experiment hours; the one `c6i.large` k6 generator is billed only for k6 hours. RDS is `db.t3.small`, MySQL, Multi-AZ, with 20 GiB gp3 storage.

The script refreshes market rates from anonymously downloadable AWS Price List Bulk CSV files. Refresh with:

```bash
python3 scripts/aws_staging_budget.py refresh-pricing
```

Refresh is a public HTTPS download and does not use AWS CLI credentials. The snapshot stores service, product description, AWS SKU, region, unit, price, source URL, and retrieval timestamp. Rows older than the configured seven-day freshness window fail closed.
The raw estimate and 20% margin are each rounded up to cents before the gate comparison, keeping the displayed components additive and conservative.

## Pricing snapshot

All rates are USD On-Demand rates in `ap-southeast-1`. EC2 rows are Linux, shared tenancy, and used capacity; the RDS row is MySQL Multi-AZ. The snapshot was retrieved on 4 October 2026.

| Resource | Price | Unit | AWS SKU | Source |
|---|---:|---|---|---|
| EKS standard cluster | $0.10 | cluster-hour | `FCCKZFRYQFAGPWQN` | [AmazonEKS regional bulk CSV](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEKS/current/ap-southeast-1/index.csv) |
| `t3.medium` Linux | $0.0528 | instance-hour | `USWP83ASNCY4BSQF` | [AmazonEC2 regional bulk CSV](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/ap-southeast-1/index.csv) |
| `c6i.large` Linux | $0.098 | instance-hour | `UPYXX6A6MGP4W9X7` | [AmazonEC2 regional bulk CSV](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/ap-southeast-1/index.csv) |
| RDS `db.t3.small` MySQL Multi-AZ | $0.104 | DB instance-hour | `2WYUG2VKMU9X3N37` | [AmazonRDS regional bulk CSV](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonRDS/current/ap-southeast-1/index.csv) |
| RDS MySQL gp3 Multi-AZ storage | $0.276 | GiB-month | `W5CHCRSKW24CVYPR` | [AmazonRDS regional bulk CSV](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonRDS/current/ap-southeast-1/index.csv) |
| EBS gp3 | $0.096 | GiB-month | `X8SY8CJFS8WV7VQN` | [AmazonEC2 regional bulk CSV](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/ap-southeast-1/index.csv) |
| Application Load Balancer | $0.0252 | load-balancer-hour | `UXD3JNG6UAM79CW6` | [AWSELB regional bulk CSV](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AWSELB/current/ap-southeast-1/index.csv) |
| Application Load Balancer LCU | $0.008 | LCU-hour | `45KY3J5YX2QM6KS7` | [AWSELB regional bulk CSV](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AWSELB/current/ap-southeast-1/index.csv) |
| NAT Gateway | $0.059 | gateway-hour | `98TA3WPUP3A4AH4Y` | [AmazonEC2 regional bulk CSV](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/ap-southeast-1/index.csv) |
| NAT processed data | $0.059 | GiB | `E9U985ZGMWKXKT47` | [AmazonEC2 regional bulk CSV](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/ap-southeast-1/index.csv) |
| S3 Standard storage | $0.025 | GiB-month | `M6MTARCQFUQBQ2V3` | [AmazonS3 regional bulk CSV](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonS3/current/ap-southeast-1/index.csv) |
| In-use public IPv4 | $0.005 | address-hour | `C8D8VMQRDHMB9KRU` | [AmazonVPC regional bulk CSV](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonVPC/current/ap-southeast-1/index.csv) |

AWS documents the public bulk download workflow in [Getting price list files manually](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/using-the-aws-price-list-bulk-api-fetching-price-list-files-manually.html).

## Conservative planning assumptions

These workload quantities are explicit inputs in `infra/aws-staging-budget.json`, not account-metered usage. They can be calibrated from Cost Explorer after STCN-40.

| Item | Default assumption |
|---|---|
| ALB LCU | 1 LCU-hour per deployment hour, priced from the Singapore AWS bulk row |
| NAT data processing | 0.5 GiB per deployment hour, priced from the Singapore AWS bulk row |
| S3 Terraform state | 1 GiB Standard storage for the window; request charges excluded |
| Internet egress | 0.5 GiB per deployment hour at an explicit conservative USD 0.12/GiB planning rate; confirm applicable tier and calibrate |
| Public IPv4 count | 3 addresses for the window |
| EC2 root volumes | 20 GiB gp3 per node, including experiment and k6 nodes |

## Example 24-hour run

```bash
python3 scripts/aws_staging_budget.py --hours 24 --experiment-hours 4 --k6-hours 2
```

With the 4 October snapshot, the example produces:

```text
Raw estimated cost:        $17.62
Safety margin (20%):       $3.53
Final estimated cost:      $21.15
Available AWS credit:      UNVERIFIED
Gate result:               UNVERIFIED
```

To enforce the gate with a balance supplied explicitly:

```bash
python3 scripts/aws_staging_budget.py --require-gate --hours 24 --experiment-hours 4 --k6-hours 2 --available-credit-usd 200
```

The USD 200 value above is an example input only; no remaining account credit is asserted.

Pre-apply callers use `.github/workflows/aws-staging-budget-gate.yml`. This reusable workflow always passes `--require-gate`, regardless of the caller event. Missing/empty or invalid credit exits 2; insufficient credit exits 1; only a finite non-negative balance covering the full estimate exits 0. It has no estimate-only override. The former combined `aws-staging-budget.yml` is now estimate-only; no repository workflow called that old reusable entry point when this correction was prepared. External callers must migrate to the gate path; an unmigrated call to the old estimate-only workflow is rejected by GitHub because it has no `workflow_call` trigger.

```yaml
jobs:
  budget:
    uses: ./.github/workflows/aws-staging-budget-gate.yml
    with:
      window_hours: '24'
      experiment_hours: '4'
      k6_hours: '2'
      available_credit_usd: ${{ vars.STAGING_AVAILABLE_CREDIT_USD }}
  # A later apply job must depend on budget with needs: budget.
```

This is caller wiring guidance, not a deployment. The owner must supply a currently verified balance and planned hours. Push, PR and manual estimates remain read-only and may report UNVERIFIED. The regression workflow exercises the real reusable caller on a PR/push and asserts rejected inputs through the CLI. GitHub documents that the [reusable workflow context belongs to the caller](https://docs.github.com/en/actions/reference/workflows-and-actions/reusing-workflow-configurations#github-context), so event-name equality cannot identify a reusable invocation.

## Scope

No AWS credentials, account quota values, credits, or budgets are recorded. No AWS resource was created, modified, or deleted, and no Terraform apply was run. The reusable budget workflow supports a pre-apply call, but this repository currently has no Terraform deployment workflow to integrate. Live quota evidence and budget alerts belong to STCN-38; the real AWS service spike belongs to STCN-40.
