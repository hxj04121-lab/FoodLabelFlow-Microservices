import tempfile
import unittest
from pathlib import Path

from scripts.staging.guard_staging_ownership import check_ownership


class HelmReleaseOwnershipGuardTest(unittest.TestCase):
    def run_guard(
        self, state: str, deployments: str, policies: str, namespaces: str = "", iam: str = "", rbac: str = ""
    ):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = {
                "state": root / "state.txt",
                "deployments": root / "deployments.txt",
                "policies": root / "policies.txt",
                "namespaces": root / "namespaces.txt",
                "iam": root / "iam.txt",
                "rbac": root / "rbac.txt",
            }
            for name, content in (
                ("state", state),
                ("deployments", deployments),
                ("policies", policies),
                ("namespaces", namespaces),
                ("iam", iam),
                ("rbac", rbac),
            ):
                files[name].write_text(content)
            return check_ownership(
                files["state"],
                files["deployments"],
                files["policies"],
                files["namespaces"],
                files["iam"],
                files["rbac"],
            )

    def test_live_controller_without_state_ownership_fails_closed(self):
        issues = self.run_guard("", "deployment.apps/aws-load-balancer-controller\n", "")
        self.assertEqual(len(issues), 1)
        self.assertIn("Terraform State lacks", issues[0])

    def test_live_controller_owned_by_state_passes(self):
        issues = self.run_guard(
            "module.eks_addons[0].helm_release.load_balancer_controller\n",
            "deployment.apps/aws-load-balancer-controller\n",
            "",
        )
        self.assertEqual(issues, [])

    def test_any_live_platform_policy_without_state_ownership_fails_closed(self):
        issues = self.run_guard("", "", "networkpolicy.networking.k8s.io/stcn-default-deny-compliance\n")
        self.assertEqual(len(issues), 1)
        self.assertIn("spectrace-network-policies", issues[0])

    def test_absent_platform_resources_do_not_block(self):
        self.assertEqual(
            self.run_guard(
                "",
                "deployment.apps/gateway-gateway\n",
                "networkpolicy.networking.k8s.io/gateway-gateway\n",
            ),
            [],
        )

    def test_live_namespace_without_state_ownership_fails_closed(self):
        issues = self.run_guard("", "", "", "namespace/gateway\n")
        self.assertEqual(len(issues), 1)
        self.assertIn('workload_namespace["gateway"]', issues[0])

    def test_live_namespace_owned_by_state_passes(self):
        issues = self.run_guard(
            'module.eks_addons[0].kubernetes_manifest.workload_namespace["gateway"]\n',
            "",
            "",
            "namespace/gateway\n",
        )
        self.assertEqual(issues, [])

    def test_server_table_inventory_lines_are_parsed_by_resource_name(self):
        issues = self.run_guard(
            "",
            "aws-load-balancer-controller 1/1 1 1 3d\n",
            "",
            "gateway Active 3d\n",
        )
        self.assertEqual(len(issues), 2)
        self.assertTrue(any("aws-load-balancer-controller" in issue for issue in issues))
        self.assertTrue(any("namespace gateway" in issue for issue in issues))

    def test_live_controller_iam_without_state_ownership_fails_closed(self):
        issues = self.run_guard(
            "",
            "",
            "",
            iam=(
                "role\tspectrace-staging-aws_load_balancer_controller\n"
                "policy\tspectrace-staging-aws-load-balancer-controller\n"
                "attachment\tspectrace-staging-aws-load-balancer-controller\n"
            ),
        )
        self.assertEqual(len(issues), 3)
        self.assertTrue(all("Terraform State lacks" in issue for issue in issues))

    def test_live_controller_iam_owned_by_state_passes(self):
        issues = self.run_guard(
            (
                'module.eks_addons[0].aws_iam_role.addons["aws_load_balancer_controller"]\n'
                "module.eks_addons[0].aws_iam_policy.load_balancer_controller\n"
                "module.eks_addons[0].aws_iam_role_policy_attachment.load_balancer_controller\n"
            ),
            "",
            "",
            iam=(
                "role\tspectrace-staging-aws_load_balancer_controller\n"
                "policy\tspectrace-staging-aws-load-balancer-controller\n"
                "attachment\tspectrace-staging-aws-load-balancer-controller\n"
            ),
        )
        self.assertEqual(issues, [])

    def test_live_tgb_role_without_state_ownership_fails_closed(self):
        issues = self.run_guard(
            "",
            "",
            "",
            rbac="Role\tgateway\nRoleBinding\tfrontend\n",
        )
        self.assertEqual(len(issues), 2)
        self.assertTrue(all("Terraform State lacks" in issue for issue in issues))

    def test_live_tgb_rbac_owned_by_state_passes(self):
        issues = self.run_guard(
            (
                'module.eks_addons[0].kubernetes_manifest.deployer_custom_resource_role["gateway"]\n'
                'module.eks_addons[0].kubernetes_manifest.deployer_custom_resource_rolebinding["frontend"]\n'
            ),
            "",
            "",
            rbac="Role\tgateway\nRoleBinding\tfrontend\n",
        )
        self.assertEqual(issues, [])


if __name__ == "__main__":
    unittest.main()
