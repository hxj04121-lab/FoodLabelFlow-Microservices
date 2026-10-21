import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import yaml

from scripts.staging import check_operator_manifest_ownership as ownership
from scripts.staging import render_operator_install_manifest as renderer


class OperatorManifestOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.manifest_dir = Path(self.temp_dir.name)
        (self.manifest_dir / "cert-manager.yaml").write_text(
            """\
apiVersion: v1
kind: Namespace
metadata:
  name: cert-manager
---
apiVersion: apiextensions.k8s.io/v1
kind: CustomResourceDefinition
metadata:
  name: certificates.cert-manager.io
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: operator-role
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cert-manager
  namespace: cert-manager
---
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: webhook
  namespace: kube-system
"""
        )
        (self.manifest_dir / "rabbitmq-cluster-operator.yaml").write_text(
            """\
apiVersion: v1
kind: Namespace
metadata:
  name: rabbitmq-system
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: rabbitmq-role
  namespace: rabbitmq-system
---
apiVersion: admissionregistration.k8s.io/v1
kind: MutatingWebhookConfiguration
metadata:
  name: rabbitmq-webhook
"""
        )
        (self.manifest_dir / "messaging-topology-operator.yaml").write_text(
            """\
apiVersion: v1
kind: Namespace
metadata:
  name: rabbitmq-system
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: topology
  namespace: rabbitmq-system
"""
        )
        self.lock_rows = [
            {"file": "cert-manager.yaml"},
            {"file": "rabbitmq-cluster-operator.yaml"},
            {"file": "messaging-topology-operator.yaml"},
        ]
        self.load_patch = patch.object(ownership, "load_and_validate", return_value=self.lock_rows)
        self.load_patch.start()

    def tearDown(self):
        self.load_patch.stop()
        self.temp_dir.cleanup()

    def test_missing_targets_passes_and_only_metadata_gets_are_read(self):
        calls = []

        def run(command, **kwargs):
            calls.append(command)
            return subprocess.CompletedProcess(command, 0, "", "")

        counts = ownership.check_ownership(Path("unused"), self.manifest_dir, run=run)

        self.assertEqual(counts, {"Namespace": 2, "CustomResourceDefinition": 1, "Webhook": 1, "RBAC": 1, "Other": 0})
        self.assertTrue(calls)
        self.assertTrue(all(command[1] == "get" for command in calls))
        self.assertTrue(all(command[-2:] == ["-o", "name"] for command in calls))
        self.assertFalse(any("secrets" in " ".join(command) for command in calls))
        self.assertFalse(any(command[1] in {"create", "apply", "replace", "patch", "delete"} for command in calls))

    def test_existing_target_namespace_stops_before_any_other_resource_query(self):
        calls = []

        def run(command, **kwargs):
            calls.append(command)
            name = command[3]
            output = f"namespaces/{name}" if name == "cert-manager" else ""
            return subprocess.CompletedProcess(command, 0, output, "")

        with self.assertRaisesRegex(ValueError, "Pre-existing target Namespace cert-manager"):
            ownership.check_ownership(Path("unused"), self.manifest_dir, run=run)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][1:4], ["get", "namespaces", "cert-manager"])

    def test_existing_cluster_role_stops_before_install(self):
        def run(command, **kwargs):
            output = "clusterroles/operator-role" if command[2:4] == ["clusterroles", "operator-role"] else ""
            return subprocess.CompletedProcess(command, 0, output, "")

        with self.assertRaisesRegex(ValueError, "Pre-existing target ClusterRole/operator-role"):
            ownership.check_ownership(Path("unused"), self.manifest_dir, run=run)

    def test_forbidden_read_fails_closed(self):
        def run(command, **kwargs):
            return subprocess.CompletedProcess(command, 1, "", "forbidden")

        with self.assertRaisesRegex(ValueError, "Cannot verify ownership/absence"):
            ownership.check_ownership(Path("unused"), self.manifest_dir, run=run)

    def test_unknown_resource_kind_fails_closed(self):
        (self.manifest_dir / "messaging-topology-operator.yaml").write_text(
            "apiVersion: v1\nkind: Secret\nmetadata:\n  name: unexpected\n"
        )
        with self.assertRaisesRegex(ValueError, "Unsupported or incomplete target"):
            ownership.load_targets(Path("unused"), self.manifest_dir)

    def test_same_namespace_declared_by_two_locked_manifests_is_checked_once(self):
        targets = ownership.load_targets(Path("unused"), self.manifest_dir)
        self.assertEqual(sum(target["kind"] == "Namespace" and target["name"] == "rabbitmq-system" for target in targets), 1)

    def test_shared_rabbitmq_namespace_with_different_release_labels_is_checked_once(self):
        (self.manifest_dir / "messaging-topology-operator.yaml").write_text(
            """\
apiVersion: v1
kind: Namespace
metadata:
  name: rabbitmq-system
  labels: {owner: unexpected}
"""
        )
        targets = ownership.load_targets(Path("unused"), self.manifest_dir)
        self.assertEqual(sum(target["kind"] == "Namespace" and target["name"] == "rabbitmq-system" for target in targets), 1)

    def test_duplicate_cluster_resource_fails_closed(self):
        (self.manifest_dir / "messaging-topology-operator.yaml").write_text(
            """\
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: operator-role
"""
        )
        with self.assertRaisesRegex(ValueError, "Duplicate or conflicting target resource"):
            ownership.load_targets(Path("unused"), self.manifest_dir)

    def test_topology_renderer_removes_only_locked_shared_namespace_after_verification(self):
        path = self.manifest_dir / "messaging-topology-operator.yaml"
        path.write_text(
            """\
apiVersion: v1
kind: Namespace
metadata:
  name: rabbitmq-system
  labels: {owner: topology}
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: topology
  namespace: rabbitmq-system
"""
        )
        rows = [{"name": "messaging-topology-operator", "file": path.name}]
        with patch.object(renderer, "verify", return_value=rows) as verify:
            output = renderer.render_topology_manifest(Path("unused"), self.manifest_dir)
        verify.assert_called_once_with(Path("unused"), self.manifest_dir)
        rendered = [document for document in yaml.safe_load_all(output.read_text()) if document]
        self.assertEqual([(document["kind"], document["metadata"]["name"]) for document in rendered], [("ServiceAccount", "topology")])


if __name__ == "__main__":
    unittest.main()
