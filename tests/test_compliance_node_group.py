import copy
import unittest

from scripts.staging.verify_compliance_node_group import validate


def fixture():
    group = {
        "status": "ACTIVE",
        "instanceTypes": ["c6i.large"],
        "scalingConfig": {"minSize": 2, "desiredSize": 2},
        "health": {"issues": []},
    }
    nodes = []
    for name, zone in (("compliance-a", "ap-southeast-1a"), ("compliance-b", "ap-southeast-1b")):
        nodes.append({
            "metadata": {
                "name": name,
                "labels": {
                    "node.kubernetes.io/instance-type": "c6i.large",
                    "topology.kubernetes.io/zone": zone,
                },
            },
            "status": {
                "conditions": [{"type": "Ready", "status": "True"}],
                "allocatable": {"cpu": "1800m", "memory": "7Gi"},
            },
            "spec": {"taints": [{"key": "dedicated", "value": "compliance", "effect": "NoSchedule"}]},
        })
    return group, nodes


class ComplianceNodeGroupTests(unittest.TestCase):
    def test_accepts_two_ready_c6i_large_nodes_across_two_zones(self):
        group, nodes = fixture()
        result = validate(group, nodes)
        self.assertEqual(result["readyC6iLargeNodes"], 2)
        self.assertEqual(result["availabilityZones"], ["ap-southeast-1a", "ap-southeast-1b"])
        self.assertGreaterEqual(result["allocatableCpu"], 1)
        self.assertGreaterEqual(result["allocatableMemoryBytes"], 2 * 1024**3)

    def test_rejects_two_ready_nodes_in_one_zone(self):
        group, nodes = fixture()
        nodes[1]["metadata"]["labels"]["topology.kubernetes.io/zone"] = "ap-southeast-1a"
        with self.assertRaisesRegex(ValueError, "two Availability Zones"):
            validate(group, nodes)

    def test_rejects_only_one_ready_node(self):
        group, nodes = fixture()
        nodes.pop()
        with self.assertRaisesRegex(ValueError, "at least two Ready c6i.large"):
            validate(group, nodes)

    def test_rejects_two_ready_nodes_that_are_not_c6i_large(self):
        group, nodes = fixture()
        for node in nodes:
            node["metadata"]["labels"]["node.kubernetes.io/instance-type"] = "m6i.large"
        with self.assertRaisesRegex(ValueError, "at least two Ready c6i.large"):
            validate(group, nodes)

    def test_rejects_nodes_that_are_not_ready(self):
        group, nodes = fixture()
        for node in nodes:
            node["status"]["conditions"] = [{"type": "Ready", "status": "False"}]
        with self.assertRaisesRegex(ValueError, "at least two Ready c6i.large"):
            validate(group, nodes)

    def test_rejects_capacity_below_two_nodes(self):
        group, nodes = fixture()
        group["scalingConfig"]["minSize"] = 1
        with self.assertRaisesRegex(ValueError, "minSize and desiredSize of at least two"):
            validate(group, nodes)
        group, nodes = fixture()
        group["scalingConfig"]["desiredSize"] = 1
        with self.assertRaisesRegex(ValueError, "minSize and desiredSize of at least two"):
            validate(group, nodes)

    def test_rejects_insufficient_allocatable_cpu_or_memory(self):
        group, nodes = fixture()
        low_cpu = copy.deepcopy(nodes)
        for node in low_cpu:
            node["status"]["allocatable"]["cpu"] = "400m"
        with self.assertRaisesRegex(ValueError, "allocatable CPU and memory"):
            validate(group, low_cpu)
        low_memory = copy.deepcopy(nodes)
        for node in low_memory:
            node["status"]["allocatable"]["memory"] = "800Mi"
        with self.assertRaisesRegex(ValueError, "allocatable CPU and memory"):
            validate(group, low_memory)

    def test_preserves_nodegroup_health_instance_type_and_taint_checks(self):
        group, nodes = fixture()
        group["health"]["issues"] = [{"code": "NodeCreationFailure"}]
        with self.assertRaisesRegex(ValueError, "node group must be healthy"):
            validate(group, nodes)
        group, nodes = fixture()
        group["instanceTypes"] = ["m6i.large"]
        with self.assertRaisesRegex(ValueError, "node group must be healthy"):
            validate(group, nodes)
        group, nodes = fixture()
        nodes[0]["spec"]["taints"] = []
        with self.assertRaisesRegex(ValueError, "missing its dedicated taint"):
            validate(group, nodes)


if __name__ == "__main__":
    unittest.main()
