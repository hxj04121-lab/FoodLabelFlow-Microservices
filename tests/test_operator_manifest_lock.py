import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/staging/verify_operator_manifest_lock.py"
spec = importlib.util.spec_from_file_location("verify_operator_manifest_lock", SCRIPT)
lock_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lock_module)
SCHEMA_SCRIPT = Path(__file__).resolve().parents[1] / "scripts/staging/verify_mto_permission_schema.py"
schema_spec = importlib.util.spec_from_file_location("verify_mto_permission_schema", SCHEMA_SCRIPT)
schema_module = importlib.util.module_from_spec(schema_spec)
schema_spec.loader.exec_module(schema_module)
LOCK = Path(__file__).resolve().parents[1] / "deploy/operators/rabbitmq/operator-manifests.lock"


class OperatorManifestLockTests(unittest.TestCase):
    def test_exact_operator_versions_urls_and_digest_metadata_are_present(self):
        rows = lock_module.load_and_validate(LOCK)
        self.assertEqual(
            [(row["name"], row["version"], row["file"]) for row in rows],
            [
                ("cert-manager", "v1.21.2", "cert-manager.yaml"),
                ("rabbitmq-cluster-operator", "v2.22.3", "rabbitmq-cluster-operator.yaml"),
                ("messaging-topology-operator", "v1.20.2", "messaging-topology-operator.yaml"),
            ],
        )
        self.assertTrue(all("/releases/download/" in row["url"] for row in rows))

    def test_verifier_accepts_locked_bytes_and_rejects_changed_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payloads = {}
            manifests = []
            for row in lock_module.load_and_validate(LOCK):
                content = f"apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: {row['name']}\n".encode()
                payloads[row["file"]] = content
                (root / row["file"]).write_bytes(content)
                manifests.append({**row, "sha256": hashlib.sha256(content).hexdigest(), "bytes": len(content)})
            test_lock = root / "test.lock"
            test_lock.write_text(json.dumps({"schemaVersion": 1, "manifests": manifests}))
            self.assertEqual(len(lock_module.verify(test_lock, root)), 3)
            (root / manifests[0]["file"]).write_bytes(payloads[manifests[0]["file"]] + b"tamper")
            with self.assertRaisesRegex(ValueError, "Byte-size mismatch"):
                lock_module.verify(test_lock, root)

    def test_unversioned_or_latest_release_urls_fail_closed(self):
        document = json.loads(LOCK.read_text())
        document["manifests"][0]["url"] = "https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml"
        with tempfile.TemporaryDirectory() as directory:
            lock_path = Path(directory) / "invalid.lock"
            lock_path.write_text(json.dumps(document))
            with self.assertRaisesRegex(ValueError, "exact reviewed HTTPS"):
                lock_module.load_and_validate(lock_path)

    def test_manifest_url_must_match_the_reviewed_repository_and_asset(self):
        document = json.loads(LOCK.read_text())
        document["manifests"][0]["url"] = (
            "https://github.com/untrusted/cert-manager/releases/download/v1.21.2/cert-manager.yaml"
        )
        with tempfile.TemporaryDirectory() as directory:
            lock_path = Path(directory) / "invalid.lock"
            lock_path.write_text(json.dumps(document))
            with self.assertRaisesRegex(ValueError, "exact reviewed HTTPS"):
                lock_module.load_and_validate(lock_path)

    def test_topology_operator_permission_crd_supports_user_reference(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "messaging-topology-operator.yaml"
            path.write_text("""
apiVersion: apiextensions.k8s.io/v1
kind: CustomResourceDefinition
metadata: {name: permissions.rabbitmq.com}
spec:
  names: {kind: Permission}
  versions:
  - name: v1beta1
    schema:
      openAPIV3Schema:
        properties:
          spec:
            properties:
              userReference:
                type: object
                properties:
                  name: {type: string}
""")
            schema_module.validate_permission_schema(path)
            path.write_text(path.read_text().replace("name: {type: string}", "name: {type: integer}"))
            with self.assertRaisesRegex(ValueError, "string name"):
                schema_module.validate_permission_schema(path)

    def test_platform_ci_validates_the_exact_locked_permission_schema(self):
        workflow = Path(__file__).resolve().parents[1] / ".github/workflows/m5-platform-validation.yml"
        self.assertIn("verify_mto_permission_schema.py", workflow.read_text())


if __name__ == "__main__":
    unittest.main()
