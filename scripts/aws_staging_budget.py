#!/usr/bin/env python3
"""Estimate and gate an AWS staging deployment window using public AWS prices."""
import argparse
import csv
import io
import json
import os
import sys
import urllib.request
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation, ROUND_CEILING
from pathlib import Path

REGION = "ap-southeast-1"
BULK = "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/{service}/current/{region}/index.csv"

# Pricing rows are selected from AWS's public regional Price List CSVs. No AWS credentials are used.
PRICE_SPECS = {
    "eks-standard-cluster-hour": ("AmazonEKS", "Hours", lambda r: r["usageType"] == "APS1-AmazonEKS-Hours:perCluster"),
    "ec2-t3-medium-linux-hour": ("AmazonEC2", "Hrs", lambda r: r["Product Family"] == "Compute Instance" and r["Instance Type"] == "t3.medium" and r["Operating System"] == "Linux" and r["Tenancy"] == "Shared" and r["Pre Installed S/W"] == "NA" and r["CapacityStatus"] == "Used" and "On Demand Linux t3.medium" in r["PriceDescription"]),
    "ec2-c6i-large-linux-hour": ("AmazonEC2", "Hrs", lambda r: r["Product Family"] == "Compute Instance" and r["Instance Type"] == "c6i.large" and r["Operating System"] == "Linux" and r["Tenancy"] == "Shared" and r["Pre Installed S/W"] == "NA" and r["CapacityStatus"] == "Used" and "On Demand Linux c6i.large" in r["PriceDescription"]),
    "ebs-gp3-gib-month": ("AmazonEC2", "GB-Mo", lambda r: r["Product Family"] == "Storage" and r["Volume API Name"] == "gp3" and r["Unit"] == "GB-Mo"),
    "rds-mysql-db-t3-small-multi-az-hour": ("AmazonRDS", "Hrs", lambda r: r["Product Family"] == "Database Instance" and r["Database Engine"] == "MySQL" and r["Instance Type"] == "db.t3.small" and r["Deployment Option"] == "Multi-AZ"),
    "rds-mysql-gp3-multi-az-gib-month": ("AmazonRDS", "GB-Mo", lambda r: r["Product Family"] == "Database Storage" and r["Database Engine"] == "MySQL" and r["Deployment Option"] == "Multi-AZ" and r["Volume Type"] == "General Purpose-GP3"),
    "alb-application-hour": ("AWSELB", "Hrs", lambda r: r["Product Family"] == "Load Balancer-Application" and r["usageType"] == "APS1-LoadBalancerUsage"),
    "alb-application-lcu-hour": ("AWSELB", "LCU-Hrs", lambda r: r["Product Family"] == "Load Balancer-Application" and r["usageType"] == "APS1-LCUUsage"),
    "nat-gateway-hour": ("AmazonEC2", "Hrs", lambda r: r["Product Family"] == "NAT Gateway" and r["usageType"] == "APS1-NatGateway-Hours"),
    "nat-data-gib": ("AmazonEC2", "GB", lambda r: r["Product Family"] == "NAT Gateway" and r["usageType"] == "APS1-NatGateway-Bytes"),
    "s3-standard-gib-month": ("AmazonS3", "GB-Mo", lambda r: r["Product Family"] == "Storage" and r["Storage Class"] == "General Purpose" and r["Volume Type"] == "Standard" and "first 50 TB" in r["PriceDescription"]),
    "public-ipv4-hour": ("AmazonVPC", "Hrs", lambda r: r["usageType"] == "APS1-PublicIPv4:InUseAddress"),
}


def decimal(value, label):
    if value is None or isinstance(value, bool):
        raise ValueError(f"missing or invalid {label}")
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError):
        raise ValueError(f"invalid {label}") from None
    if not result.is_finite():
        raise ValueError(f"invalid {label}")
    return result


def public_prices(service, specs):
    url = BULK.format(service=service, region=REGION)
    matches = {key: [] for key, _unit, _predicate in specs}
    with urllib.request.urlopen(url, timeout=120) as response:
        reader = csv.reader(io.TextIOWrapper(response, encoding="utf-8", newline=""))
        for _ in range(5):
            next(reader)
        header = next(reader)
        rows = []
        for cells in reader:
            if len(cells) != len(header):
                continue
            row = dict(zip(header, cells))
            if row.get("TermType") != "OnDemand" or row.get("Currency") != "USD":
                continue
            if row.get("PricePerUnit") in (None, "", "0", "0.0000000000"):
                continue
            for key, unit, predicate in specs:
                if row.get("Unit") == unit and predicate(row):
                    matches[key].append(row)
    retrieved = datetime.now(timezone.utc).isoformat(timespec="seconds")
    prices = {}
    for key, unit, _predicate in specs:
        rows = matches[key]
        if len(rows) != 1:
            raise ValueError(f"expected one {service} {key} price row, found {len(rows)}")
        row = rows[0]
        prices[key] = {
            "service": service,
            "product": row.get("PriceDescription") or row.get("Product Family"),
            "sku": row["SKU"],
            "region": REGION,
            "unit": unit,
            "price_usd": row["PricePerUnit"],
            "currency": "USD",
            "source_url": url,
            "retrieved_at": retrieved,
        }
    return prices


def refresh_prices(path):
    data = json.loads(Path(path).read_text())
    data["prices"] = {}
    for service in sorted({spec[0] for spec in PRICE_SPECS.values()}):
        specs = [(key, unit, predicate) for key, (source, unit, predicate) in PRICE_SPECS.items() if source == service]
        data["prices"].update(public_prices(service, specs))
    data["pricing_retrieved_at"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    Path(path).write_text(json.dumps(data, indent=2) + "\n")
    print(f"Updated {len(data['prices'])} public AWS Price List rows for {REGION}; no AWS credentials used.")


def load_config(path, now=None):
    data = json.loads(Path(path).read_text())
    if data.get("region") != REGION:
        raise ValueError(f"region must be {REGION}")
    resources = data["resources"]
    if resources.get("general_nodes") != 3 or resources.get("compliance_baseline_nodes") != 1 or resources.get("compliance_scaleout_max_nodes") != 3 or resources.get("k6_instance_type") != "c6i.large" or resources.get("rds_class") != "db.t3.small" or resources.get("rds_multi_az") is not True or resources.get("rds_gp3_gib") != 20:
        raise ValueError("resource configuration differs from the approved STCN-37 architecture")
    if data.get("required_standard_vcpu") != 16 or data.get("recommended_standard_vcpu") != 20:
        raise ValueError("Standard On-Demand quota values must be 16 required and 20 recommended vCPU")
    max_age = decimal(data.get("pricing_max_age_hours"), "pricing_max_age_hours")
    if max_age <= 0:
        raise ValueError("pricing_max_age_hours must be positive")
    for key, price in data.get("prices", {}).items():
        expected_service, expected_unit, _ = PRICE_SPECS.get(key, (None, None, None))
        expected_url = BULK.format(service=expected_service, region=REGION) if expected_service else None
        if decimal(price.get("price_usd"), f"{key} price") <= 0:
            raise ValueError(f"{key} price must be positive")
        if price.get("service") != expected_service or price.get("unit") != expected_unit or price.get("source_url") != expected_url or price.get("region") != REGION or price.get("currency") != "USD" or not price.get("sku") or not price.get("retrieved_at"):
            raise ValueError(f"incomplete AWS pricing metadata for {key}")
        retrieved = datetime.fromisoformat(price["retrieved_at"].replace("Z", "+00:00"))
        age = ((now or datetime.now(timezone.utc)) - retrieved.astimezone(timezone.utc)).total_seconds()
        if age < 0 or age > max_age * 3600:
            raise ValueError(f"stale or future AWS pricing row: {key}")
    assumptions = data["assumptions"]
    for key in ("alb_lcu_hours_per_window_hour", "nat_data_gib_per_hour", "s3_state_gib", "internet_egress_gib_per_hour", "internet_egress_usd_per_gib_assumption", "public_ipv4_address_count", "ebs_gib_per_node"):
        if decimal(assumptions.get(key), key) < 0:
            raise ValueError(f"{key} must be non-negative")
    missing = set(PRICE_SPECS) - set(data.get("prices", {}))
    if missing:
        raise ValueError("missing pricing: " + ", ".join(sorted(missing)))
    return data


def estimate(data, hours, experiment_hours, k6_hours):
    for name, value in (("hours", hours), ("experiment_hours", experiment_hours), ("k6_hours", k6_hours)):
        if value < 0 or (name == "hours" and value == 0):
            raise ValueError(f"{name} must be {'positive' if name == 'hours' else 'non-negative'}")
        if value > 744:
            raise ValueError(f"{name} must be at most 744")
    if experiment_hours > hours or k6_hours > hours:
        raise ValueError("experiment-hours and k6-hours cannot exceed the deployment window")

    p = {key: decimal(row["price_usd"], f"{key} price") for key, row in data["prices"].items()}
    r, a = data["resources"], data["assumptions"]
    hourly = [
        ("EKS standard control plane", "eks-standard-cluster-hour", 1, hours),
        ("3 x t3.medium baseline nodes", "ec2-t3-medium-linux-hour", 3, hours),
        ("Compliance baseline c6i.large", "ec2-c6i-large-linux-hour", 1, hours),
        ("Compliance experiment scale-out c6i.large", "ec2-c6i-large-linux-hour", r["compliance_scaleout_max_nodes"], experiment_hours),
        ("k6 c6i.large", "ec2-c6i-large-linux-hour", 1, k6_hours),
        ("RDS db.t3.small Multi-AZ", "rds-mysql-db-t3-small-multi-az-hour", 1, hours),
        ("Application Load Balancer", "alb-application-hour", 1, hours),
        ("NAT Gateway", "nat-gateway-hour", 1, hours),
        ("Public IPv4 addresses", "public-ipv4-hour", a["public_ipv4_address_count"], hours),
        ("ALB LCU assumption", "alb-application-lcu-hour", Decimal(str(a["alb_lcu_hours_per_window_hour"])) * Decimal(str(hours)), 1),
        ("NAT processed data assumption", "nat-data-gib", Decimal(str(a["nat_data_gib_per_hour"])) * Decimal(str(hours)), 1),
    ]
    rows = []
    raw = Decimal("0")
    for label, sku, quantity, duration in hourly:
        amount = p[sku] * Decimal(str(quantity)) * Decimal(str(duration))
        rows.append((label, amount))
        raw += amount

    month_fraction = Decimal(str(hours)) / Decimal("730")
    storage = [
        ("RDS Multi-AZ gp3 storage (20 GiB)", "rds-mysql-gp3-multi-az-gib-month", r["rds_gp3_gib"] * month_fraction),
        ("EBS gp3 root volumes assumption", "ebs-gp3-gib-month", a["ebs_gib_per_node"] * (3 + 1) * month_fraction),
        ("Compliance scale-out EBS assumption", "ebs-gp3-gib-month", r["compliance_scaleout_max_nodes"] * a["ebs_gib_per_node"] * Decimal(str(experiment_hours)) / Decimal("730")),
        ("k6 EBS assumption", "ebs-gp3-gib-month", a["ebs_gib_per_node"] * Decimal(str(k6_hours)) / Decimal("730")),
        ("S3 state storage assumption", "s3-standard-gib-month", a["s3_state_gib"] * month_fraction),
    ]
    for label, sku, quantity in storage:
        amount = p[sku] * Decimal(str(quantity))
        rows.append((label, amount))
        raw += amount
    egress = Decimal(str(a["internet_egress_gib_per_hour"])) * Decimal(str(hours)) * Decimal(str(a["internet_egress_usd_per_gib_assumption"]))
    rows.append(("Internet egress assumption", egress))
    raw += egress
    raw = raw.quantize(Decimal("0.01"), rounding=ROUND_CEILING)
    margin = (raw * Decimal("0.20")).quantize(Decimal("0.01"), rounding=ROUND_CEILING)
    return rows, raw, margin, raw + margin


def under_budget(final_cost, available_credit):
    cost = decimal(final_cost, "final estimated cost")
    credit = decimal(available_credit, "available credit")
    if cost < 0 or credit < 0:
        raise ValueError("cost and credit must be non-negative")
    return cost <= credit


def report(data, hours, experiment_hours, k6_hours, credit, require_gate=False):
    rows, raw, margin, final = estimate(data, hours, experiment_hours, k6_hours)
    print("AWS Staging Budget Check")
    print(f"Region: {REGION}")
    print(f"Deployment window: {hours} h")
    print(f"Experiment window: {experiment_hours} h")
    print(f"k6 window: {k6_hours} h")
    print("\nEstimate lines (USD)")
    for name, amount in rows:
        print(f"{name:<44} ${amount:.2f}")
    print(f"\nRaw estimated cost:        ${raw:.2f}")
    print(f"Safety margin (20%):       ${margin:.2f}")
    print(f"Final estimated cost:      ${final:.2f}")
    if credit is None:
        print("Available AWS credit:      UNVERIFIED")
        print("Gate result:               UNVERIFIED")
        return 2 if require_gate else 0
    amount = decimal(credit, "available credit")
    if amount < 0:
        raise ValueError("available credit must be non-negative")
    remaining = amount - final
    passed = under_budget(final, amount)
    print(f"Available AWS credit:      ${amount:.2f} (explicit input)")
    print(f"Remaining after window:    ${remaining:.2f}")
    print(f"Gate result:               {'PASS' if passed else 'FAIL'}")
    return 0 if passed else 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default="infra/aws-staging-budget.json")
    parser.add_argument("--hours", type=Decimal)
    parser.add_argument("--experiment-hours", type=Decimal)
    parser.add_argument("--k6-hours", type=Decimal)
    parser.add_argument("--available-credit-usd", type=Decimal, default=os.getenv("AVAILABLE_CREDIT_USD"))
    parser.add_argument("--require-gate", action="store_true", help="fail unless available credit is supplied and the cost fits")
    parser.add_argument("command", nargs="?", choices=("estimate", "quota", "refresh-pricing"), default="estimate")
    args = parser.parse_args()
    try:
        if args.command == "refresh-pricing":
            refresh_prices(args.config)
            return 0
        data = load_config(args.config)
        if args.command == "quota":
            print("Running On-Demand Standard instances: required 16 vCPU; recommended quota request 20 vCPU.")
            print("Applied account quota: UNVERIFIED (live query and screenshot are tracked in STCN-38).")
            return 0
        defaults = data["window_defaults"]
        hours = args.hours if args.hours is not None else Decimal(str(defaults["hours"]))
        experiment_hours = args.experiment_hours if args.experiment_hours is not None else Decimal(str(defaults["experiment_hours"]))
        k6_hours = args.k6_hours if args.k6_hours is not None else Decimal(str(defaults["k6_hours"]))
        return report(data, hours, experiment_hours, k6_hours, args.available_credit_usd, args.require_gate)
    except (OSError, KeyError, TypeError, ValueError, csv.Error) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
