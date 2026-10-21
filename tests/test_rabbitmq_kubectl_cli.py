import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class KubectlAuthorizationSyntaxTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.kubectl = shutil.which("kubectl")
        if not cls.kubectl:
            raise unittest.SkipTest("kubectl is required; CI pins v1.36.1 before this test.")
        version = subprocess.run(
            [cls.kubectl, "version", "--client", "-o", "json"],
            text=True, capture_output=True, check=True, timeout=15,
        )
        cls.version = json.loads(version.stdout)["clientVersion"]["gitVersion"]
        if os.environ.get("REQUIRE_KUBECTL_V136") == "1":
            if cls.version != "v1.36.1":
                raise AssertionError(f"Expected kubectl v1.36.1, found {cls.version}.")

    def test_type_name_and_portforward_forms_reach_only_local_unavailable_api(self):
        with tempfile.TemporaryDirectory() as directory:
            kubeconfig = Path(directory) / "isolated-kubeconfig.yaml"
            kubeconfig.write_text("""
apiVersion: v1
kind: Config
clusters:
- name: parser-probe
  cluster:
    server: http://127.0.0.1:9
contexts:
- name: parser-probe
  context: {cluster: parser-probe, user: parser-probe}
current-context: parser-probe
users:
- name: parser-probe
  user: {}
""")
            commands = [
                ["get", "namespaces/gateway"],
                ["get", "csidrivers.storage.k8s.io/ebs.csi.aws.com"],
                ["get", "storageclasses.storage.k8s.io/ebs-gp3"],
                ["get", "daemonsets.apps/ebs-csi-node", "--namespace", "kube-system"],
                ["get", "deployments.apps/ebs-csi-controller", "--namespace", "kube-system"],
                ["get", "customresourcedefinitions.apiextensions.k8s.io/queues.rabbitmq.com"],
                ["get", "secrets/staging-smoke-user-credentials", "--namespace", "rabbitmq"],
                ["create", "pods", "--subresource=portforward", "--namespace", "rabbitmq"],
            ]
            for arguments in commands:
                with self.subTest(arguments=arguments):
                    result = subprocess.run(
                        [self.kubectl, "auth", "can-i", *arguments, "--kubeconfig", str(kubeconfig),
                         "--request-timeout=1s"],
                        text=True, capture_output=True, timeout=5,
                    )
                    combined = result.stdout + result.stderr
                    self.assertNotIn("unknown flag", combined.lower())
                    self.assertTrue(
                        "connection refused" in combined.lower() or "timed out" in combined.lower(),
                        f"Expected only the isolated local API connection failure for kubectl {arguments}; got {combined!r}",
                    )

    def test_removed_resource_name_flag_is_rejected_by_this_cli(self):
        result = subprocess.run(
            [self.kubectl, "auth", "can-i", "get", "secrets", "--resource-name=example", "--kubeconfig=/dev/null"],
            text=True, capture_output=True, timeout=5,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unknown flag: --resource-name", (result.stdout + result.stderr).lower())


if __name__ == "__main__":
    unittest.main()
