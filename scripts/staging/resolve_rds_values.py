#!/usr/bin/env python3
"""Validate private staging RDS metadata and emit Helm/runtime values."""

import argparse
import ipaddress
import json
import re
from pathlib import Path


def resolve(database_document, subnets_document, expected_vpc):
    instances = database_document.get("DBInstances", [])
    if len(instances) != 1:
        raise ValueError("expected exactly one staging RDS instance")
    database = instances[0]
    endpoint = database.get("Endpoint", {}).get("Address", "")
    if database.get("DBInstanceStatus") != "available":
        raise ValueError("staging RDS instance is not available")
    if database.get("Engine") != "mysql" or database.get("PubliclyAccessible") is not False:
        raise ValueError("staging database must be private MySQL")
    if not re.fullmatch(r"[a-zA-Z0-9.-]+\.ap-southeast-1\.rds\.amazonaws\.com", endpoint):
        raise ValueError("staging RDS endpoint is not a regional AWS endpoint")

    subnet_group = database.get("DBSubnetGroup", {})
    if subnet_group.get("VpcId") != expected_vpc:
        raise ValueError("staging RDS subnet group is outside the EKS VPC")
    subnet_ids = {row.get("SubnetIdentifier") for row in subnet_group.get("Subnets", [])}
    if len(subnet_ids) < 2 or None in subnet_ids:
        raise ValueError("staging RDS requires at least two configured private subnets")

    subnets = subnets_document.get("Subnets", [])
    if {row.get("SubnetId") for row in subnets} != subnet_ids:
        raise ValueError("described subnets do not match the RDS subnet group")
    if any(row.get("VpcId") != expected_vpc or row.get("State") != "available" for row in subnets):
        raise ValueError("RDS subnet is not available in the EKS VPC")
    cidrs = sorted({str(ipaddress.ip_network(row["CidrBlock"], strict=True)) for row in subnets})
    return {"mysqlHost": endpoint, "networkPolicy": {"databaseCidrs": cidrs}}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("database_json", type=Path)
    parser.add_argument("subnets_json", type=Path)
    parser.add_argument("--vpc-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = resolve(json.loads(args.database_json.read_text()), json.loads(args.subnets_json.read_text()), args.vpc_id)
    except (OSError, json.JSONDecodeError, KeyError, ValueError) as error:
        parser.error(str(error))
    args.output.write_text(json.dumps(result, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
