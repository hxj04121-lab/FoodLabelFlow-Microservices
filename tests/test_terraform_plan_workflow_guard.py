import unittest
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/terraform-validate.yml"


class TerraformPlanWorkflowGuardTest(unittest.TestCase):
    def test_plan_is_main_only_protected_and_read_only(self):
        workflow = WORKFLOW.read_text()

        self.assertIn("github.ref == 'refs/heads/main' && inputs.run_plan", workflow)
        self.assertIn("environment: staging", workflow)
        self.assertIn("runs-on: [self-hosted, linux, staging-vpc]", workflow)
        self.assertIn("id-token: write", workflow)
        self.assertIn("terraform plan -input=false", workflow)
        self.assertIn("guard_staging_ownership.py", workflow)
        self.assertIn("get deployments -n kube-system --server-print=true --no-headers", workflow)
        self.assertIn("get networkpolicies --all-namespaces -o name", workflow)
        self.assertIn("get namespaces --server-print=true --no-headers", workflow)
        self.assertIn('get "$kind" spectrace-staging-deployer-custom-resources -n "$namespace"', workflow)
        self.assertIn("--rbac-resources \"$rbac_inventory\"", workflow)
        self.assertLess(
            workflow.index('python3 "$GITHUB_WORKSPACE/scripts/staging/guard_staging_ownership.py"'),
            workflow.index("terraform plan -input=false"),
        )
        self.assertNotIn("kubectl get secrets", workflow)
        self.assertIn("aws iam get-role --role-name", workflow)
        self.assertIn("aws iam get-policy --policy-arn", workflow)
        self.assertNotIn("AssumeRolePolicyDocument", workflow)
        self.assertNotIn("terraform apply", workflow)
        self.assertNotIn("terraform workspace new", workflow)
        self.assertNotIn("terraform import", workflow)

    def test_plan_pins_state_version_workspace_and_existing_ownership(self):
        workflow = WORKFLOW.read_text()

        self.assertIn("expected_state_version_id:", workflow)
        self.assertIn('test "$actual_version_id" = "$EXPECTED_STATE_VERSION_ID"', workflow)
        self.assertIn('test "$after_version_id" = "$EXPECTED_STATE_VERSION_ID"', workflow)
        self.assertIn("workspace_key_prefix=spectrace", workflow)
        self.assertIn('terraform workspace select "$STAGING_TF_WORKSPACE"', workflow)
        self.assertIn('test "$(terraform workspace show)" = "$STAGING_TF_WORKSPACE"', workflow)
        for address in (
            "module.eks[0].aws_eks_cluster.this",
            "module.network.aws_vpc.this",
            "module.eks[0].aws_eks_node_group.general",
            "module.rds[0].aws_db_instance.this",
            "module.edge[0].aws_lb.this",
            "module.edge[0].aws_lb_target_group.gateway",
            'module.eks_addons[0].kubernetes_manifest.deployer_custom_resource_role["gateway"]',
            'module.eks_addons[0].kubernetes_manifest.deployer_custom_resource_role["keycloak"]',
            'module.eks_addons[0].kubernetes_manifest.deployer_custom_resource_role["frontend"]',
            'module.eks_addons[0].kubernetes_manifest.deployer_custom_resource_rolebinding["gateway"]',
            'module.eks_addons[0].kubernetes_manifest.deployer_custom_resource_rolebinding["keycloak"]',
            'module.eks_addons[0].kubernetes_manifest.deployer_custom_resource_rolebinding["frontend"]',
            'module.eks_addons[0].kubernetes_manifest.workload_namespace["gateway"]',
            'module.eks_addons[0].kubernetes_manifest.workload_namespace["keycloak"]',
            'module.eks_addons[0].kubernetes_manifest.workload_namespace["rabbitmq"]',
            'module.eks_addons[0].kubernetes_manifest.workload_namespace["observability"]',
            'module.eks_addons[0].kubernetes_manifest.workload_namespace["specification"]',
            'module.eks_addons[0].kubernetes_manifest.workload_namespace["formulation"]',
            'module.eks_addons[0].kubernetes_manifest.workload_namespace["compliance"]',
            'module.eks_addons[0].kubernetes_manifest.workload_namespace["label-workflow"]',
            'module.eks_addons[0].kubernetes_manifest.workload_namespace["frontend"]',
            'module.eks_addons[0].kubernetes_manifest.workload_namespace["rabbitmq-system"]',
            'module.eks_addons[0].kubernetes_manifest.workload_namespace["keycloak-system"]',
        ):
            self.assertIn(address, workflow)
        self.assertIn("does not prove there is no AWS drift", workflow)

    def test_private_plan_artifacts_are_removed_after_success_or_failure(self):
        workflow = WORKFLOW.read_text()

        self.assertIn('M5_TF_TEMP_SUFFIX: foodlabelflow-m5-plan-${{ github.run_id }}-${{ github.run_attempt }}', workflow)
        self.assertIn('if: ${{ always() }}', workflow)
        self.assertIn('name: Remove private Terraform plan artifacts', workflow)
        self.assertIn('shutil.rmtree(plan_dir)', workflow)
        self.assertGreater(
            workflow.index('name: Remove private Terraform plan artifacts'),
            workflow.index('sha256sum "$RUNNER_TEMP/$M5_TF_TEMP_SUFFIX/staging.tfplan"'),
        )
        for filename in (
            "staging-window.json",
            "staging.tfvars.json",
            "staging.tfplan",
            "terraform-state-addresses.txt",
            "terraform-init.log",
            "terraform-workspace.log",
            "terraform-plan.log",
            "staging-state-head.json",
            "staging-state-head-after.json",
        ):
            self.assertIn(f'$RUNNER_TEMP/$M5_TF_TEMP_SUFFIX/{filename}', workflow)
        self.assertNotIn('$RUNNER_TEMP/staging.tfvars.json', workflow)
        self.assertNotIn('$RUNNER_TEMP/staging.tfplan', workflow)


if __name__ == "__main__":
    unittest.main()
