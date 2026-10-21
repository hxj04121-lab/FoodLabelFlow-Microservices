import copy
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
import subprocess
from subprocess import CompletedProcess
from unittest.mock import patch

import yaml

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/staging/apply_rabbitmq_owned.py"
ROOT = Path(__file__).resolve().parents[1]
CLUSTER_MANIFEST = ROOT / "deploy/operators/rabbitmq/rabbitmq-cluster.yaml"
spec = importlib.util.spec_from_file_location("apply_rabbitmq_owned", SCRIPT)
safe_apply = importlib.util.module_from_spec(spec)
spec.loader.exec_module(safe_apply)


def protected_env():
    return {
        "GITHUB_ACTIONS": "true",
        "GITHUB_REF": "refs/heads/main",
        "GITHUB_WORKFLOW": safe_apply.WORKFLOW,
        "STAGING_WINDOW_VALIDATED": "true",
    }


class FakeKubectl:
    def __init__(
        self,
        existing=(),
        diff_code=0,
        fail_on_create=None,
        fail_all_creates=False,
        lose_create_response=False,
        workload_returncode=0,
        workload_stdout="",
        workload_stderr="",
        workload_timeout=False,
        on_workload_list=None,
        resource_get_returncode=0,
        resource_get_stdout=None,
        resource_get_stdout_sequence=None,
        resource_get_stderr="",
        resource_get_timeout=False,
    ):
        self.existing = {safe_apply.object_key(item): item for item in existing}
        self.diff_code = diff_code
        self.fail_on_create = fail_on_create
        self.fail_all_creates = fail_all_creates
        self.lose_create_response = lose_create_response
        self.workload_returncode = workload_returncode
        self.workload_stdout = workload_stdout
        self.workload_stderr = workload_stderr
        self.workload_timeout = workload_timeout
        self.on_workload_list = on_workload_list
        self.resource_get_returncode = resource_get_returncode
        self.resource_get_stdout = resource_get_stdout
        self.resource_get_stdout_sequence = resource_get_stdout_sequence
        self.resource_get_stderr = resource_get_stderr
        self.resource_get_timeout = resource_get_timeout
        self.resource_get_calls = 0
        self.create_calls = 0
        self.created = []
        self.calls = []
        self.argv = []
        self.payloads = []

    def __call__(self, args, input, text, capture_output, timeout=None):
        self.calls.append(args[1])
        self.argv.append(args)
        self.payloads.append(input)
        if args[1] == "get" and len(args) > 2 and args[2] == "statefulsets,pods,persistentvolumeclaims":
            if self.on_workload_list:
                self.on_workload_list(self)
            if self.workload_timeout:
                raise subprocess.TimeoutExpired(args, timeout)
            return CompletedProcess(
                args,
                self.workload_returncode,
                self.workload_stdout,
                self.workload_stderr,
            )
        if args[1] == "get" and len(args) > 2 and args[2] == "--ignore-not-found=true":
            self.resource_get_calls += 1
            if self.resource_get_timeout:
                raise subprocess.TimeoutExpired(args, timeout)
            if self.resource_get_returncode:
                return CompletedProcess(
                    args,
                    self.resource_get_returncode,
                    "",
                    self.resource_get_stderr,
                )
            if self.resource_get_stdout_sequence is not None and self.resource_get_calls <= len(self.resource_get_stdout_sequence):
                return CompletedProcess(
                    args,
                    0,
                    self.resource_get_stdout_sequence[self.resource_get_calls - 1],
                    self.resource_get_stderr,
                )
            if self.resource_get_stdout is not None:
                return CompletedProcess(
                    args,
                    0,
                    self.resource_get_stdout,
                    self.resource_get_stderr,
                )
        resources = [item for item in yaml.safe_load_all(input) if item]
        if args[1] == "get":
            wanted = {safe_apply.object_key(item) for item in resources}
            found = [item for key, item in self.existing.items() if key in wanted]
            return CompletedProcess(args, 0, json.dumps({"kind": "List", "items": found}), "")
        if args[1] == "diff":
            return CompletedProcess(args, self.diff_code, "", "")
        if args[1] == "create":
            self.create_calls += 1
            if any(safe_apply.object_key(resource) in self.existing for resource in resources):
                return CompletedProcess(args, 1, "", "AlreadyExists")
            if self.fail_all_creates or self.create_calls == self.fail_on_create:
                return CompletedProcess(args, 1, "", "create failed")
            self.created.extend(resources)
            for resource in resources:
                self.existing[safe_apply.object_key(resource)] = resource
            if self.lose_create_response:
                self.lose_create_response = False
                return CompletedProcess(args, 124, "", "kubectl timed out")
            return CompletedProcess(args, 0, "", "")
        raise AssertionError(args)


class RabbitMQSafeApplyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.manifest = Path(self.tmp.name) / "resources.yaml"
        self.manifest.write_text("""apiVersion: rabbitmq.com/v1beta1
kind: User
metadata:
  name: staging-smoke
  namespace: rabbitmq
spec:
  tags: [management]
""")

    def use_cluster_manifest(self):
        self.manifest.write_text(CLUSTER_MANIFEST.read_text())
        return yaml.safe_load(self.manifest.read_text())

    def owned_cluster(self):
        cluster = copy.deepcopy(yaml.safe_load(CLUSTER_MANIFEST.read_text()))
        cluster["metadata"]["annotations"] = {safe_apply.OWNER_ANNOTATION: safe_apply.OWNER}
        return cluster

    def assert_no_delete(self, kubectl):
        self.assertNotIn("delete", kubectl.calls)
        self.assertFalse(any(args[1] == "delete" for args in kubectl.argv))

    def test_creates_missing_resources_with_owner_marker(self):
        kubectl = FakeKubectl()
        created, unchanged = safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())
        self.assertEqual((created, unchanged), (1, 0))
        self.assertEqual(kubectl.created[0]["metadata"]["annotations"][safe_apply.OWNER_ANNOTATION], safe_apply.OWNER)

    def test_resumes_only_owned_unchanged_resources(self):
        existing = list(yaml.safe_load_all(self.manifest.read_text()))[0]
        existing["metadata"]["annotations"] = {safe_apply.OWNER_ANNOTATION: safe_apply.OWNER}
        kubectl = FakeKubectl([existing])
        self.assertEqual(safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env()), (0, 1))
        self.assertEqual(kubectl.created, [])
        self.assertIn("diff", kubectl.calls)
        self.assertNotIn("--force-conflicts", kubectl.argv[-1])

    def test_refuses_unknown_ownership_before_any_create(self):
        existing = list(yaml.safe_load_all(self.manifest.read_text()))[0]
        existing["metadata"]["annotations"] = {safe_apply.OWNER_ANNOTATION: "someone-else"}
        kubectl = FakeKubectl([existing])
        with self.assertRaisesRegex(safe_apply.SafeApplyError, "unknown ownership"):
            safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())
        self.assertEqual(kubectl.created, [])

    def test_refuses_to_adopt_an_exact_unowned_resource(self):
        existing = list(yaml.safe_load_all(self.manifest.read_text()))[0]
        kubectl = FakeKubectl([existing])
        with self.assertRaisesRegex(safe_apply.SafeApplyError, "unknown ownership"):
            safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())
        self.assertEqual(kubectl.created, [])

    def test_refuses_drift_in_owned_resources_without_replacing_them(self):
        existing = list(yaml.safe_load_all(self.manifest.read_text()))[0]
        existing["metadata"]["annotations"] = {safe_apply.OWNER_ANNOTATION: safe_apply.OWNER}
        kubectl = FakeKubectl([existing], diff_code=1)
        with self.assertRaisesRegex(safe_apply.SafeApplyError, "refusing to update"):
            safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())
        self.assertEqual(kubectl.created, [])

    def test_refuses_outside_protected_window_before_kubectl(self):
        kubectl = FakeKubectl()
        with self.assertRaisesRegex(safe_apply.SafeApplyError, "protected main staging window"):
            safe_apply.apply_owned_manifest(self.manifest, kubectl, {})
        self.assertEqual(kubectl.calls, [])

    def test_retries_a_partial_create_without_replacing_existing_resources(self):
        first = yaml.safe_load(self.manifest.read_text())
        second = {**first, "metadata": {**first["metadata"], "name": "specification-rabbitmq", "namespace": "specification"}}
        self.manifest.write_text(yaml.safe_dump_all([first, second], sort_keys=False))
        kubectl = FakeKubectl(fail_on_create=2)
        with patch.object(safe_apply.time, "sleep"):
            self.assertEqual(safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env()), (2, 0))
        self.assertEqual(len(kubectl.created), 2)
        self.assertEqual(kubectl.create_calls, 3)
        self.assertTrue(all("--field-manager" in args and safe_apply.FIELD_MANAGER in args for args in kubectl.argv if args[1] in {"diff", "create"}))

    def test_recovers_a_create_accepted_before_kubectl_timed_out(self):
        kubectl = FakeKubectl(lose_create_response=True)
        with patch.object(safe_apply.time, "sleep"):
            self.assertEqual(safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env()), (1, 0))
        self.assertEqual(len(kubectl.created), 1)
        self.assertEqual(kubectl.create_calls, 1)

    def test_stops_after_bounded_retries_without_overwriting_resources(self):
        kubectl = FakeKubectl(fail_all_creates=True)
        with patch.object(safe_apply.time, "sleep"):
            with self.assertRaisesRegex(safe_apply.SafeApplyError, "no existing resource was overwritten"):
                safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())
        self.assertEqual(kubectl.create_calls, safe_apply.CREATE_ATTEMPTS)
        self.assertEqual(kubectl.created, [])

    def test_rejects_cluster_scoped_or_unreviewed_resources_before_kubectl(self):
        kubectl = FakeKubectl()
        self.manifest.write_text("""apiVersion: v1
kind: Namespace
metadata:
  name: rabbitmq-system
""")
        with self.assertRaisesRegex(safe_apply.SafeApplyError, "outside the reviewed RabbitMQ namespace"):
            safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())
        self.assertEqual(kubectl.calls, [])
        self.manifest.write_text("""apiVersion: rabbitmq.com/v1beta1
kind: User
metadata:
  name: unexpected-user
  namespace: rabbitmq
""")
        with self.assertRaisesRegex(safe_apply.SafeApplyError, "outside the reviewed RabbitMQ namespace"):
            safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())
        self.assertEqual(kubectl.calls, [])

    def test_allowlist_covers_chart_rendered_topology_only(self):
        allowed = [
            ("rabbitmq.com/v1beta1", "Exchange", "rabbitmq", "spectrace-events"),
            ("rabbitmq.com/v1beta1", "Queue", "compliance", "compliance-label-published-dlq"),
            ("rabbitmq.com/v1beta1", "Binding", "compliance", "compliance-label-published-event-1"),
            ("rabbitmq.com/v1beta1", "Policy", "label-workflow", "label-workflow-impact-finding-delivery-policy"),
        ]
        denied = [
            ("rabbitmq.com/v1beta1", "Exchange", "default", "spectrace-events"),
            ("rabbitmq.com/v1beta1", "Queue", "compliance", "other-queue"),
            ("apiextensions.k8s.io/v1", "CustomResourceDefinition", "", "users.rabbitmq.com"),
        ]
        for api_version, kind, namespace, name in allowed:
            resource = {"apiVersion": api_version, "kind": kind, "metadata": {"namespace": namespace, "name": name}}
            self.assertTrue(safe_apply.is_allowed_resource(resource), (kind, namespace, name))
        for api_version, kind, namespace, name in denied:
            resource = {"apiVersion": api_version, "kind": kind, "metadata": {"namespace": namespace, "name": name}}
            self.assertFalse(safe_apply.is_allowed_resource(resource), (kind, namespace, name))

    def test_empty_workload_output_with_nonzero_status_fails_closed(self):
        self.use_cluster_manifest()
        kubectl = FakeKubectl(workload_returncode=7, workload_stdout="", workload_stderr="synthetic failure")

        with self.assertRaisesRegex(safe_apply.SafeApplyError, "protected recovery workflow"):
            safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())

        self.assertEqual(kubectl.create_calls, 0)
        self.assertEqual(kubectl.created, [])
        self.assert_no_delete(kubectl)

    def test_workload_inventory_timeout_fails_closed(self):
        self.use_cluster_manifest()
        kubectl = FakeKubectl(workload_timeout=True)

        with self.assertRaisesRegex(safe_apply.SafeApplyError, "Could not verify RabbitMQ"):
            safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())

        self.assertEqual(kubectl.create_calls, 0)
        self.assertEqual(kubectl.created, [])
        self.assert_no_delete(kubectl)

    def test_forbidden_workload_inventory_fails_closed(self):
        self.use_cluster_manifest()
        kubectl = FakeKubectl(
            workload_returncode=1,
            workload_stdout="",
            workload_stderr="Error from server (Forbidden)",
        )

        with self.assertRaisesRegex(safe_apply.SafeApplyError, "Could not verify RabbitMQ"):
            safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())

        self.assertEqual(kubectl.create_calls, 0)
        self.assertEqual(kubectl.created, [])
        self.assert_no_delete(kubectl)

    def test_existing_workload_or_pvc_without_cluster_stops_without_mutation(self):
        self.use_cluster_manifest()
        kubectl = FakeKubectl(workload_stdout="persistentvolumeclaim/rabbitmq-server-0\n")

        with self.assertRaisesRegex(safe_apply.SafeApplyError, "exist without the expected Cluster"):
            safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())

        self.assertEqual(kubectl.create_calls, 0)
        self.assertEqual(kubectl.created, [])
        self.assert_no_delete(kubectl)

    def test_cluster_appearing_between_reads_is_never_deleted_or_overwritten(self):
        self.use_cluster_manifest()
        concurrently_created = yaml.safe_load(CLUSTER_MANIFEST.read_text())
        concurrently_created["metadata"]["annotations"] = {safe_apply.OWNER_ANNOTATION: "other-owner"}

        def create_between_reads(fake):
            key = safe_apply.object_key(concurrently_created)
            fake.existing[key] = concurrently_created

        kubectl = FakeKubectl(on_workload_list=create_between_reads)

        with self.assertRaisesRegex(safe_apply.SafeApplyError, "protected recovery workflow"):
            safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())

        self.assertEqual(kubectl.created, [])
        self.assertEqual(kubectl.create_calls, 1)
        self.assert_no_delete(kubectl)

    def test_existing_mismatched_cluster_stops_and_requests_protected_recovery(self):
        cluster = self.owned_cluster()
        cluster["spec"]["replicas"] = 2
        self.manifest.write_text(CLUSTER_MANIFEST.read_text())
        kubectl = FakeKubectl(existing=[cluster], diff_code=1)

        with self.assertRaisesRegex(safe_apply.SafeApplyError, "protected recovery workflow"):
            safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())

        self.assertEqual(kubectl.create_calls, 0)
        self.assertEqual(kubectl.created, [])
        self.assert_no_delete(kubectl)

    def test_existing_owned_unchanged_cluster_is_a_no_write_path(self):
        self.manifest.write_text(CLUSTER_MANIFEST.read_text())
        kubectl = FakeKubectl(existing=[self.owned_cluster()])

        self.assertEqual(safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env()), (0, 1))

        self.assertEqual(kubectl.create_calls, 0)
        self.assertEqual(kubectl.created, [])
        self.assert_no_delete(kubectl)

    def test_missing_cluster_with_verified_empty_inventory_creates_normally(self):
        self.use_cluster_manifest()
        kubectl = FakeKubectl()

        self.assertEqual(safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env()), (1, 0))

        self.assertEqual(kubectl.create_calls, 1)
        self.assertEqual(len(kubectl.created), 1)
        self.assert_no_delete(kubectl)

    def test_cluster_read_nonzero_with_empty_output_fails_closed(self):
        self.use_cluster_manifest()
        kubectl = FakeKubectl(resource_get_returncode=1, resource_get_stderr="Forbidden")

        with self.assertRaisesRegex(safe_apply.SafeApplyError, "Could not safely inspect"):
            safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())

        self.assertEqual(kubectl.create_calls, 0)
        self.assertEqual(kubectl.created, [])
        self.assert_no_delete(kubectl)

    def test_cluster_read_timeout_fails_closed(self):
        self.use_cluster_manifest()
        kubectl = FakeKubectl(resource_get_timeout=True)

        with self.assertRaisesRegex(safe_apply.SafeApplyError, "Could not safely inspect"):
            safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())

        self.assertEqual(kubectl.create_calls, 0)
        self.assertEqual(kubectl.created, [])
        self.assert_no_delete(kubectl)

    def test_unparseable_cluster_response_fails_closed(self):
        self.use_cluster_manifest()
        kubectl = FakeKubectl(resource_get_stdout="synthetic malformed response")

        with self.assertRaisesRegex(safe_apply.SafeApplyError, "Could not safely decode"):
            safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())

        self.assertEqual(kubectl.create_calls, 0)
        self.assertEqual(kubectl.created, [])
        self.assert_no_delete(kubectl)

    def test_list_response_without_list_items_fails_closed_on_initial_read(self):
        malformed_responses = (
            '{"kind":"List"}',
            '{"kind":"List","items":null}',
            '{"kind":"List","items":{}}',
        )
        for response in malformed_responses:
            with self.subTest(response=response):
                kubectl = FakeKubectl(resource_get_stdout=response)

                with self.assertRaisesRegex(safe_apply.SafeApplyError, "Could not safely decode"):
                    safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())

                self.assertEqual(kubectl.create_calls, 0)
                self.assertEqual(kubectl.created, [])
                self.assert_no_delete(kubectl)

    def test_list_response_without_list_items_fails_closed_during_create_recovery(self):
        malformed_responses = (
            '{"kind":"List"}',
            '{"kind":"List","items":null}',
            '{"kind":"List","items":{}}',
        )
        for response in malformed_responses:
            with self.subTest(response=response):
                kubectl = FakeKubectl(
                    lose_create_response=True,
                    resource_get_stdout_sequence=["", response],
                )

                with patch.object(safe_apply.time, "sleep"):
                    with self.assertRaisesRegex(safe_apply.SafeApplyError, "Could not safely decode"):
                        safe_apply.apply_owned_manifest(self.manifest, kubectl, protected_env())

                self.assertEqual(kubectl.create_calls, 1)
                self.assertEqual(len(kubectl.created), 1)
                self.assert_no_delete(kubectl)


if __name__ == "__main__":
    unittest.main()
