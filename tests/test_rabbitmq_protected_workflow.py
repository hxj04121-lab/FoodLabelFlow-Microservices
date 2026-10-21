import unittest
from pathlib import Path
import subprocess

import yaml


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW_PATH = ROOT / ".github/workflows/deploy-rabbitmq.yml"
RUNBOOK_PATH = ROOT / "docs/aws/runbooks/install-rabbitmq-operators-cloudshell.md"


class RabbitMQProtectedWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow_text = WORKFLOW_PATH.read_text()
        cls.workflow = yaml.load(cls.workflow_text, Loader=yaml.BaseLoader)
        cls.job = cls.workflow["jobs"]["deploy"]
        cls.steps = cls.job["steps"]

    def test_only_main_staging_vpc_job_can_assume_limited_deploy_role(self):
        self.assertEqual(self.job["if"], "github.ref == 'refs/heads/main'")
        self.assertEqual(self.job["runs-on"], ["self-hosted", "linux", "staging-vpc"])
        self.assertEqual(self.job["environment"], "staging")
        self.assertEqual(self.job["permissions"]["id-token"], "write")
        credentials = [step for step in self.steps if step.get("uses", "").startswith("aws-actions/configure-aws-credentials@")]
        self.assertEqual(len(credentials), 1)
        self.assertEqual(credentials[0]["with"]["role-to-assume"], "${{ env.AWS_DEPLOY_ROLE_ARN }}")
        self.assertNotIn("AWS_OPERATOR_INSTALL_ROLE_ARN", self.workflow_text)
        self.assertNotIn("sts:AssumeRole", self.workflow_text)
        self.assertNotIn("--clusterrole=cluster-admin", self.workflow_text)
        self.assertNotIn("role: cluster-admin", self.workflow_text)

    def test_kubeconfig_path_is_initialized_in_a_step_and_cleaned_always(self):
        self.assertNotIn("runner.temp", self.workflow_text)
        self.assertNotIn("KUBECONFIG", self.job.get("env", {}))
        initialize = next(step for step in self.steps
                          if step.get("name") == "Initialize per-run temporary Kubernetes config path")
        self.assertIn("$RUNNER_TEMP/m5-rabbitmq-kubeconfig-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT", initialize["run"])
        self.assertIn('>> "$GITHUB_ENV"', initialize["run"])
        cleanup = next(step for step in self.steps if step.get("name", "").startswith("Remove temporary kubeconfig"))
        self.assertEqual(cleanup["if"], "always()")
        self.assertIn('rm -f "$KUBECONFIG"', cleanup["run"])

    def test_workflow_shell_blocks_parse_as_bash(self):
        for index, step in enumerate(self.steps):
            if step.get("run"):
                subprocess.run(
                    ["bash", "-n"], input=step["run"], text=True, check=True,
                    capture_output=True, timeout=15,
                )

    def test_operators_and_crds_are_only_readiness_checked(self):
        preflight = next(step for step in self.steps if step.get("name", "").startswith("Read-only account, operator"))
        self.assertIn("kubectl auth can-i", preflight["run"])
        for crd in (
            "certificates.cert-manager.io", "rabbitmqclusters.rabbitmq.com", "users.rabbitmq.com",
            "permissions.rabbitmq.com", "exchanges.rabbitmq.com", "queues.rabbitmq.com",
            "bindings.rabbitmq.com", "policies.rabbitmq.com",
        ):
            self.assertIn(crd, preflight["run"])
        for deployment in (
            "cert-manager-webhook", "cert-manager-cainjector", "rabbitmq-cluster-operator",
            "messaging-topology-operator",
        ):
            self.assertIn(deployment, preflight["run"])
        self.assertNotIn("kubectl apply", self.workflow_text)
        self.assertNotIn("kubectl replace", self.workflow_text)
        # The only delete is a never-reconciled RabbitmqCluster with no StatefulSet, Pod or PVC (no data).
        self.assertEqual(self.workflow_text.count("kubectl delete"), 1)
        delete_at = self.workflow_text.index("kubectl delete rabbitmqclusters.rabbitmq.com spectrace --namespace rabbitmq")
        guard_at = self.workflow_text.rindex('kubectl get statefulsets,pods,persistentvolumeclaims --namespace rabbitmq -o name)" ]]', 0, delete_at)
        self.assertLess(delete_at - guard_at, 400)
        for release_url in (
            "cert-manager.yaml", "cluster-operator.yml", "messaging-topology-operator-with-certmanager.yaml",
        ):
            self.assertNotIn(release_url, self.workflow_text)

    def test_kubectl_authorization_preflight_uses_v136_compatible_resource_forms(self):
        preflight = next(step for step in self.steps if step.get("name", "").startswith("Read-only account, operator"))
        self.assertIn('resource="${resource}/${resource_name}"', preflight["run"])
        self.assertNotIn("--resource-name", preflight["run"])
        self.assertIn('"secrets/$secret"', preflight["run"])
        self.assertIn("create pods --subresource=portforward", preflight["run"])
        self.assertNotIn("create pods/portforward", preflight["run"])
        self.assertIn("secrets/staging-smoke-user-credentials", preflight["run"])

    def test_topology_readiness_only_queries_populated_namespaces_and_keeps_dependency_order(self):
        step = next(step for step in self.steps
                    if step.get("name") == "Apply topology in dependency order and wait for reconciliation")
        run = step["run"]
        self.assertIn("for kind in 00-exchanges 10-queues 20-bindings 30-policies", run)
        self.assertIn("rabbitmq_topology_targets.py build", run)
        self.assertIn("rabbitmq_topology_targets.py namespaces", run)
        self.assertIn("rabbitmq_topology_targets.py check", run)
        self.assertNotIn("namespaces=(rabbitmq specification formulation compliance label-workflow)", run)
        self.assertIn('kubectl get "$resource" --namespace "$namespace" -o json', run)
        self.assertIn("deadline=$((SECONDS + 300))", run)
        self.assertIn('test "$SECONDS" -lt "$deadline"', run)

    def test_workflow_mutations_are_guarded_by_exact_namespace_and_name_allowlist(self):
        apply_helper = self.workflow_text.count("scripts/staging/apply_rabbitmq_owned.py")
        self.assertGreaterEqual(apply_helper, 5)
        self.assertIn("rabbitmq", self.workflow_text)
        self.assertIn("specification", self.workflow_text)
        self.assertIn("formulation", self.workflow_text)
        self.assertIn("compliance", self.workflow_text)
        self.assertIn("label-workflow", self.workflow_text)
        self.assertNotIn("--force-conflicts", self.workflow_text)

    def test_business_users_have_no_management_tag_and_only_smoke_user_has_it(self):
        users = list(yaml.safe_load_all((ROOT / "deploy/operators/rabbitmq/users.yaml").read_text()))
        tags = {(row["metadata"]["namespace"], row["metadata"]["name"]): row["spec"]["tags"] for row in users}
        for namespace, name in (
            ("specification", "specification-rabbitmq"),
            ("formulation", "formulation-rabbitmq"),
            ("compliance", "compliance-rabbitmq"),
            ("label-workflow", "label-workflow-rabbitmq"),
        ):
            self.assertEqual(tags[(namespace, name)], [])
        self.assertEqual(tags[("rabbitmq", "staging-smoke")], ["management"])

    def test_each_permission_references_its_same_namespace_user_cr(self):
        rendered = subprocess.run(
            ["kubectl", "kustomize", str(ROOT / "deploy/operators/rabbitmq"),
             "--load-restrictor", "LoadRestrictionsNone"],
            text=True, capture_output=True, check=True, timeout=30,
        ).stdout
        resources = [row for row in yaml.safe_load_all(rendered) if row]
        users = [row for row in resources if row["kind"] == "User"]
        permissions = [row for row in resources if row["kind"] == "Permission"]
        users_by_key = {(row["metadata"]["namespace"], row["metadata"]["name"]): row for row in users}
        self.assertEqual(len(permissions), 5)
        for permission in permissions:
            key = (permission["metadata"]["namespace"], permission["metadata"]["name"])
            self.assertEqual(permission["spec"].get("userReference"), {"name": key[1]})
            self.assertNotIn("user", permission["spec"])
            self.assertIn(key, users_by_key)
            self.assertNotIn("importCredentialsSecret", users_by_key[key]["spec"])
        self.assertEqual(
            {row["metadata"]["namespace"]: row["spec"]["permissions"] for row in permissions},
            {
                "specification": {"configure": "^$", "write": "^spectrace\\.events$", "read": "^$"},
                "formulation": {"configure": "^$", "write": "^spectrace\\.events$", "read": "^formulation\\.specification-published$"},
                "compliance": {"configure": "^$", "write": "^spectrace\\.events$", "read": "^compliance\\.(specification-published|formula-published|label-published)$"},
                "label-workflow": {"configure": "^$", "write": "^spectrace\\.events$", "read": "^label-workflow\\.(formula-published|impact-finding)$"},
                "rabbitmq": {"configure": "^$", "write": "^spectrace\\.events$", "read": "^staging-smoke\\.smoke$"},
            },
        )
    def test_cleanup_always_removes_sensitive_and_temporary_diagnostics(self):
        cleanup = next(step for step in self.steps if step.get("name", "").startswith("Remove temporary kubeconfig"))
        self.assertEqual(cleanup["if"], "always()")
        for path in (
            '"$KUBECONFIG"', "rabbitmq-window.json", "rabbitmq-budget-gate.log", "cluster.json",
            "ebs-gp3.json", "ebs-csi-node.json", "ebs-csi-controller.json", "crd.json",
            "operator-deployment.json", "general-nodes.json", "general-nodegroup.json",
            "rabbitmq-pods.json", "rabbitmq-pvcs.json", "all-nodes.json",
            "00-exchanges*.yaml", "10-queues*.yaml", "20-bindings*.yaml", "30-policies*.yaml",
            "rabbitmq-port-forward.log",
            "*-targets.json",
        ):
            self.assertIn(path, cleanup["run"])
        self.assertFalse(any(step.get("uses", "").startswith("actions/upload-artifact@") for step in self.steps))
        self.assertNotIn("kubectl get secrets -A", self.workflow_text)
        self.assertNotIn("kubectl get secrets --all-namespaces", self.workflow_text)
        self.assertNotIn('curl --user "$rabbit_user:$rabbit_password"', self.workflow_text)

    def test_cloudshell_runbook_fails_closed_and_records_all_operator_target_states(self):
        runbook = RUNBOOK_PATH.read_text()
        before_state = runbook.split("## 2. Inspect existing installation state", 1)[1].split("## 3.", 1)[0]
        self.assertNotIn("|| true", before_state)
        self.assertIn("--ignore-not-found", before_state)
        self.assertIn("if ! \"${command[@]}\"", before_state)
        self.assertIn("This is not evidence of absence", before_state)
        for name in (
            "cert-manager", "cert-manager-webhook", "cert-manager-cainjector",
            "rabbitmq-cluster-operator", "messaging-topology-operator",
            "certificates.cert-manager.io", "rabbitmqclusters.rabbitmq.com", "users.rabbitmq.com",
            "permissions.rabbitmq.com", "exchanges.rabbitmq.com", "queues.rabbitmq.com",
            "bindings.rabbitmq.com", "policies.rabbitmq.com",
        ):
            self.assertIn(name, before_state)
        for webhook in (
            "cluster-operator-mutating-webhook-configuration", "cluster-operator-validating-webhook-configuration",
            "messaging-topology-mutating-webhook-configuration", "messaging-topology-validating-webhook-configuration",
            "cert-manager-webhook",
        ):
            self.assertIn(webhook, before_state)
        self.assertIn("mutatingwebhookconfigurations.admissionregistration.k8s.io", before_state)
        self.assertIn("validatingwebhookconfigurations.admissionregistration.k8s.io", before_state)
        after_state = runbook.split("## 5. Verify availability", 1)[1]
        for service in ("cert-manager-webhook", "cluster-operator-webhook-service", "messaging-topology-webhook-service"):
            self.assertIn(service, after_state)
        self.assertIn("caBundlePresent", after_state)
        self.assertIn("readyAddressCount", after_state)


if __name__ == "__main__":
    unittest.main()
