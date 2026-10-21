import json
import tempfile
import unittest
from pathlib import Path

from scripts.staging.guard_staging_ownership import check_ownership


REPO = Path(__file__).resolve().parents[1]
ALLOWLIST = REPO / "scripts/staging/staging-ownership-allowlist.json"
ROLE_ARN = "arn:aws:iam::679857346122:role/spectrace-staging-aws_load_balancer_controller"
POLICY_ARN = "arn:aws:iam::679857346122:policy/spectrace-staging-aws-load-balancer-controller"
LBC_SECRET = "sh.helm.release.v1.aws-load-balancer-controller.v4 Opaque 1 1d\n"


class StagingOwnershipGuardTest(unittest.TestCase):
    def run_guard(
        self,
        *,
        state="",
        deployments="aws-load-balancer-controller 1/1 1 1 1d\n",
        policies="",
        namespaces="gateway Active 1d\nkeycloak Active 1d\nkeycloak-system Active 1d\n",
        iam=None,
        rbac="",
        platform="",
        helm=LBC_SECRET,
        account="679857346122",
        cluster="spectrace-staging",
        manage_lbc=False,
        allowlist=None,
    ):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = {name: root / name for name in ("state", "deployments", "policies", "namespaces", "iam", "rbac", "platform", "helm", "allowlist")}
            values = {
                "state": state,
                "deployments": deployments,
                "policies": policies,
                "namespaces": namespaces,
                "iam": iam if iam is not None else (
                    f"role\t{ROLE_ARN}\n"
                    f"policy\t{POLICY_ARN}\n"
                    f"attachment\t{ROLE_ARN}\t{POLICY_ARN}\n"
                ),
                "rbac": rbac,
                "platform": platform,
                "helm": helm,
                "allowlist": json.dumps(allowlist) if allowlist is not None else ALLOWLIST.read_text(),
            }
            for name, value in values.items():
                files[name].write_text(value)
            return check_ownership(
                files["state"],
                files["deployments"],
                files["policies"],
                files["namespaces"],
                files["iam"],
                files["rbac"],
                files["platform"],
                files["helm"],
                files["allowlist"],
                account,
                cluster,
                manage_lbc,
            )

    def test_approved_independent_management_passes_with_exact_live_evidence(self):
        self.assertEqual(self.run_guard(), [])

    def test_unexpected_live_addon_without_state_ownership_fails_closed(self):
        issues = self.run_guard(deployments="aws-load-balancer-controller 1/1 1 1 1d\ncluster-autoscaler 1/1 1 1 1d\n")
        self.assertTrue(any("cluster-autoscaler" in issue and "Terraform State lacks" in issue for issue in issues))

    def test_unexpected_iam_object_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "outside the reviewed LBC allowlist"):
            self.run_guard(iam=f"role\t{ROLE_ARN}\npolicy\t{POLICY_ARN}\nrole\tarn:aws:iam::679857346122:role/unapproved\n")

    def test_incomplete_lbc_iam_or_unhealthy_deployment_is_blocked(self):
        issues = self.run_guard(iam=f"role\t{ROLE_ARN}\npolicy\t{POLICY_ARN}\n")
        self.assertTrue(any("inventory is incomplete" in issue for issue in issues))
        issues = self.run_guard(deployments="aws-load-balancer-controller 0/1 1 0 1d\n")
        self.assertTrue(any("not at the reviewed healthy replica count" in issue for issue in issues))

    def test_exact_existing_namespaces_are_allowed_but_missing_or_extra_objects_block(self):
        self.assertEqual(self.run_guard(namespaces="gateway Active 1d\nkeycloak Active 1d\n"), [
            "namespace keycloak-system: reviewed out-of-band namespace is missing from the live cluster."
        ])
        issues = self.run_guard(namespaces="gateway Active 1d\nkeycloak Active 1d\nkeycloak-system Active 1d\nrabbitmq Active 1d\n")
        self.assertTrue(any('workload_namespace["rabbitmq"]' in issue for issue in issues))

    def test_state_ownership_of_independently_managed_objects_is_a_conflict(self):
        for address in (
            "module.eks_addons[0].helm_release.load_balancer_controller",
            "module.eks_addons[0].helm_release.load_balancer_controller[0]",
            "module.eks_addons[0].aws_iam_policy.load_balancer_controller[0]",
            "module.eks_addons[0].aws_iam_role_policy_attachment.load_balancer_controller[0]",
        ):
            with self.subTest(address=address):
                issues = self.run_guard(state=address + "\n")
                self.assertTrue(any("despite the approved independent-management mode" in issue for issue in issues))
        issues = self.run_guard(state='module.eks_addons[0].kubernetes_manifest.workload_namespace["gateway"]\n')
        self.assertTrue(any("conflicts with the reviewed independent owner" in issue for issue in issues))

    def test_tgb_role_and_rolebinding_outside_state_are_blocked(self):
        issues = self.run_guard(rbac="Role\tspectrace-staging-deployer-custom-resources\tgateway\n")
        self.assertEqual(len(issues), 1)
        self.assertIn("Terraform State lacks", issues[0])

    def test_tgb_role_and_rolebinding_owned_by_state_pass(self):
        state = (
            'module.eks_addons[0].kubernetes_manifest.deployer_custom_resource_role["gateway"]\n'
            'module.eks_addons[0].kubernetes_manifest.deployer_custom_resource_rolebinding["frontend"]\n'
        )
        rbac = (
            "Role\tspectrace-staging-deployer-custom-resources\tgateway\n"
            "RoleBinding\tspectrace-staging-deployer-custom-resources\tfrontend\n"
        )
        self.assertEqual(self.run_guard(state=state, rbac=rbac), [])

    def test_unexpected_rbac_object_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "unexpected resource entry"):
            self.run_guard(rbac="Role\tcluster-admin\tdefault\n")

    def test_other_declared_platform_objects_must_be_state_owned(self):
        issues = self.run_guard(platform="eks-addon\taws-ebs-csi-driver\n")
        self.assertTrue(any("Terraform State lacks module.eks_addons[0].aws_eks_addon.ebs_csi" in issue for issue in issues))
        issues = self.run_guard(platform="storageclass\tebs-gp3\n")
        self.assertTrue(any("Terraform State lacks module.eks_addons[0].kubernetes_manifest.ebs_gp3_storage_class" in issue for issue in issues))

    def test_rabbitmq_and_cluster_node_reader_rbac_outside_state_is_blocked(self):
        issues = self.run_guard(rbac="Role\tspectrace-staging-deployer-rabbitmq\tgateway\n")
        self.assertTrue(any('rabbitmq_service_deployer_role["gateway"]' in issue for issue in issues))
        issues = self.run_guard(rbac="ClusterRole\tspectrace-staging-node-reader\tcluster\n")
        self.assertTrue(any("staging_deployer_node_reader" in issue for issue in issues))

    def test_missing_or_unexpected_helm_metadata_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "Secret is missing"):
            self.run_guard(helm="")
        with self.assertRaisesRegex(ValueError, "outside the reviewed release name pattern"):
            self.run_guard(helm="sh.helm.release.v1.other-release.v1 Opaque 1 1d\n")

    def test_account_cluster_and_management_mode_are_pinned(self):
        with self.assertRaisesRegex(ValueError, "AWS identity"):
            self.run_guard(account="123456789012")
        with self.assertRaisesRegex(ValueError, "Kubernetes target"):
            self.run_guard(cluster="other-cluster")
        with self.assertRaisesRegex(ValueError, "management mode"):
            self.run_guard(manage_lbc=True)

    def test_allowlist_rejects_unreviewed_fields_or_expired_owner_window(self):
        allowlist = json.loads(ALLOWLIST.read_text())
        allowlist["unreviewed_resource"] = "anything"
        with self.assertRaisesRegex(ValueError, "unreviewed fields"):
            self.run_guard(allowlist=allowlist)

        allowlist = json.loads(ALLOWLIST.read_text())
        allowlist["window_end_utc"] = "2026-10-24T00:00:00Z"
        with self.assertRaisesRegex(ValueError, "window_end_utc"):
            self.run_guard(allowlist=allowlist)


if __name__ == "__main__":
    unittest.main()
