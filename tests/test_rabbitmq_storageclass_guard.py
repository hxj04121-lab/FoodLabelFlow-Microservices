import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/staging/verify_rabbitmq_storageclass.py"
spec = importlib.util.spec_from_file_location("verify_rabbitmq_storageclass", SCRIPT)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class RabbitMQStorageClassGuardTests(unittest.TestCase):
    def setUp(self):
        self.expected = yaml.safe_load((ROOT / "deploy/kubernetes/staging/ebs-gp3-storageclass.yaml.tmpl").read_text())

    def test_exact_reviewed_storageclass_is_accepted(self):
        actual = copy.deepcopy(self.expected)
        actual["metadata"].update({"uid": "synthetic", "resourceVersion": "1"})
        actual["status"] = {"phase": "synthetic"}
        guard.verify_storageclass(actual, self.expected)

    def test_cli_accepts_exact_reviewed_storageclass(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            actual_path = Path(temp_dir) / "actual.json"
            expected_path = Path(temp_dir) / "expected.yaml"
            actual_path.write_text(json.dumps(self.expected))
            expected_path.write_text((ROOT / "deploy/kubernetes/staging/ebs-gp3-storageclass.yaml.tmpl").read_text())
            self.assertEqual(guard.main([str(actual_path), str(expected_path)]), 0)

    def test_reserved_or_other_parameter_mismatch_is_rejected(self):
        for key, value in (
            ("tagSpecification_4", "kubernetes.io/cluster/test=owned"),
            ("encrypted", "false"),
            ("unreviewed", "value"),
        ):
            with self.subTest(key=key):
                actual = copy.deepcopy(self.expected)
                actual["parameters"][key] = value
                with self.assertRaisesRegex(ValueError, "stop for M1-approved protected recovery"):
                    guard.verify_storageclass(actual, self.expected)

    def test_reviewed_template_cannot_reintroduce_reserved_or_unrendered_tags(self):
        for value in ("kubernetes.io/cluster/test=owned", "\x24{cluster_name}=owned"):
            with self.subTest(value=value):
                expected = copy.deepcopy(self.expected)
                expected["parameters"]["tagSpecification_4"] = value
                with self.assertRaises(ValueError):
                    guard.verify_storageclass(expected, expected)

    def test_other_immutable_configuration_mismatch_is_rejected(self):
        actual = copy.deepcopy(self.expected)
        actual["reclaimPolicy"] = "Delete"
        with self.assertRaisesRegex(ValueError, "stop for M1-approved protected recovery"):
            guard.verify_storageclass(actual, self.expected)

    def test_wrong_identity_or_missing_configuration_is_rejected(self):
        for mutate in (
            lambda row: row["metadata"].update(name="other"),
            lambda row: row.pop("parameters"),
            lambda row: row.update(kind="List"),
        ):
            with self.subTest(mutate=mutate):
                actual = copy.deepcopy(self.expected)
                mutate(actual)
                with self.assertRaises(ValueError):
                    guard.verify_storageclass(actual, self.expected)

    def test_cli_fails_closed_on_unreadable_or_malformed_inputs_without_echoing_values(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            actual_path = Path(temp_dir) / "actual.json"
            expected_path = Path(temp_dir) / "expected.yaml"
            expected_path.write_text((ROOT / "deploy/kubernetes/staging/ebs-gp3-storageclass.yaml.tmpl").read_text())

            actual_path.write_text("{not-json: synthetic-secret}")
            with self.assertRaises(ValueError) as caught:
                guard.verify_storageclass(guard.read_document(actual_path, json.loads), self.expected)
            self.assertNotIn("synthetic-secret", str(caught.exception))

            actual_path.unlink()
            with self.assertRaises(ValueError) as caught:
                guard.read_document(actual_path, json.loads)
            self.assertNotIn(str(actual_path), str(caught.exception))

            self.assertEqual(guard.main([str(actual_path), str(expected_path)]), 1)


if __name__ == "__main__":
    unittest.main()
