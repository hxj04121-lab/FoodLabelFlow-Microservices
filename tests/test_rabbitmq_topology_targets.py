import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts/staging"))

from rabbitmq_topology_targets import build_targets, ready_target_count  # noqa: E402


class RabbitMQTopologyTargetTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.directory = Path(self.tempdir.name)
        self.manifest = self.directory / "queues.yaml"
        self.targets = self.directory / "targets.json"
        self.actual = self.directory / "actual.json"

    def tearDown(self):
        self.tempdir.cleanup()

    def write_targets(self):
        self.manifest.write_text("""
apiVersion: rabbitmq.com/v1beta1
kind: Queue
metadata: {namespace: formulation, name: formulation.specification-published}
---
apiVersion: rabbitmq.com/v1beta1
kind: Queue
metadata: {namespace: rabbitmq, name: staging-smoke.smoke}
""")
        payload = build_targets(self.manifest, "Queue")
        self.targets.write_text(json.dumps(payload))
        return payload

    def test_targets_are_grouped_only_by_rendered_namespace(self):
        payload = self.write_targets()
        self.assertEqual(payload, {
            "kind": "Queue",
            "targets": {
                "formulation": ["formulation.specification-published"],
                "rabbitmq": ["staging-smoke.smoke"],
            },
        })
        self.assertEqual(sum(map(len, payload["targets"].values())), 2)

    def test_unready_or_missing_objects_fail_closed(self):
        self.write_targets()
        self.actual.write_text(json.dumps({"items": [{
            "metadata": {"name": "formulation.specification-published"},
            "status": {"conditions": [{"type": "Ready", "status": "True"}]},
        }]}))
        self.assertEqual(ready_target_count(self.targets, "formulation", self.actual), 1)
        self.actual.write_text(json.dumps({"items": [{
            "metadata": {"name": "formulation.specification-published"},
            "status": {"conditions": [{"type": "Ready", "status": "False"}]},
        }]}))
        with self.assertRaisesRegex(ValueError, "not Ready"):
            ready_target_count(self.targets, "formulation", self.actual)
        self.actual.write_text(json.dumps({"items": []}))
        with self.assertRaisesRegex(ValueError, "missing"):
            ready_target_count(self.targets, "formulation", self.actual)

    def test_unpopulated_or_unrendered_namespaces_cannot_be_queried(self):
        self.write_targets()
        self.actual.write_text(json.dumps({"items": []}))
        with self.assertRaisesRegex(ValueError, "no rendered targets"):
            ready_target_count(self.targets, "compliance", self.actual)

    def test_wrong_kind_and_duplicate_name_fail_closed(self):
        self.manifest.write_text("kind: Exchange\nmetadata: {namespace: rabbitmq, name: events}\n")
        with self.assertRaisesRegex(ValueError, "Expected only Queue"):
            build_targets(self.manifest, "Queue")
        self.manifest.write_text("""
kind: Queue
metadata: {namespace: rabbitmq, name: duplicate}
---
kind: Queue
metadata: {namespace: rabbitmq, name: duplicate}
""")
        with self.assertRaisesRegex(ValueError, "Duplicate Queue target"):
            build_targets(self.manifest, "Queue")


if __name__ == "__main__":
    unittest.main()
