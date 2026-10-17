import unittest

from scripts.staging.resolve_rds_values import resolve


def documents():
    return (
        {"DBInstances": [{
            "DBInstanceStatus": "available",
            "Engine": "mysql",
            "PubliclyAccessible": False,
            "Endpoint": {"Address": "spectrace.abc.ap-southeast-1.rds.amazonaws.com"},
            "DBSubnetGroup": {"VpcId": "vpc-123", "Subnets": [
                {"SubnetIdentifier": "subnet-a"}, {"SubnetIdentifier": "subnet-b"}
            ]},
        }]},
        {"Subnets": [
            {"SubnetId": "subnet-a", "VpcId": "vpc-123", "State": "available", "CidrBlock": "10.0.48.0/24"},
            {"SubnetId": "subnet-b", "VpcId": "vpc-123", "State": "available", "CidrBlock": "10.0.49.0/24"},
        ]},
    )


class StagingRdsValuesTests(unittest.TestCase):
    def test_emits_actual_private_endpoint_and_subnet_cidrs(self):
        self.assertEqual(resolve(*documents(), "vpc-123"), {
            "mysqlHost": "spectrace.abc.ap-southeast-1.rds.amazonaws.com",
            "networkPolicy": {"databaseCidrs": ["10.0.48.0/24", "10.0.49.0/24"]},
        })

    def test_rejects_public_or_unavailable_database(self):
        database, subnets = documents()
        database["DBInstances"][0]["PubliclyAccessible"] = True
        with self.assertRaisesRegex(ValueError, "private MySQL"):
            resolve(database, subnets, "vpc-123")
        database, subnets = documents()
        database["DBInstances"][0]["DBInstanceStatus"] = "creating"
        with self.assertRaisesRegex(ValueError, "not available"):
            resolve(database, subnets, "vpc-123")

    def test_rejects_wrong_vpc_and_unmatched_subnets(self):
        database, subnets = documents()
        with self.assertRaisesRegex(ValueError, "outside the EKS VPC"):
            resolve(database, subnets, "vpc-other")
        database, subnets = documents()
        subnets["Subnets"].pop()
        with self.assertRaisesRegex(ValueError, "do not match"):
            resolve(database, subnets, "vpc-123")

    def test_rejects_non_aws_endpoint_and_invalid_cidr(self):
        database, subnets = documents()
        database["DBInstances"][0]["Endpoint"]["Address"] = "db.example.invalid"
        with self.assertRaisesRegex(ValueError, "regional AWS endpoint"):
            resolve(database, subnets, "vpc-123")
        database, subnets = documents()
        subnets["Subnets"][0]["CidrBlock"] = "999.0.0.0/24"
        with self.assertRaises(ValueError):
            resolve(database, subnets, "vpc-123")


if __name__ == "__main__":
    unittest.main()
