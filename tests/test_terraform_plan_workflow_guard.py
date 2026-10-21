import unittest
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/terraform-validate.yml"
ROOT_VARIABLES = Path(__file__).resolve().parents[1] / "infra/terraform/variables.tf"
ROOT_MAIN = Path(__file__).resolve().parents[1] / "infra/terraform/main.tf"
ADDON_VARIABLES = Path(__file__).resolve().parents[1] / "infra/terraform/modules/eks-addons/variables.tf"
ROOT_EKS_MAIN = Path(__file__).resolve().parents[1] / "infra/terraform/modules/eks/main.tf"


class TerraformPlanWorkflowGuardTest(unittest.TestCase):
    def test_new_environment_manages_lbc_by_default_and_root_passes_parameters(self):
        root_variables = ROOT_VARIABLES.read_text()
        addon_variables = ADDON_VARIABLES.read_text()
        root_main = ROOT_MAIN.read_text()
        self.assertRegex(root_variables, r'variable "manage_load_balancer_controller" \{[^}]*default\s*=\s*true')
        self.assertRegex(addon_variables, r'variable "manage_load_balancer_controller" \{[^}]*default\s*=\s*true')
        self.assertIn("manage_load_balancer_controller = var.manage_load_balancer_controller", root_main)
        self.assertRegex(root_main, r"existing_namespaces\s*=\s*var\.existing_namespaces")

    def test_staging_plan_uses_the_approved_single_node_compliance_baseline(self):
        workflow = WORKFLOW.read_text()
        root_variables = ROOT_VARIABLES.read_text()
        eks_main = ROOT_EKS_MAIN.read_text()

        self.assertIn(
            "-var=general_desired_size=3 -var=enable_compliance_node_group=true -var=compliance_desired_size=1",
            workflow,
        )
        compliance_variable = root_variables.split('variable "compliance_desired_size" {', 1)[1].split('variable "enable_compliance_node_group" {', 1)[0]
        self.assertIn("default     = 1", compliance_variable)
        self.assertIn("var.compliance_desired_size >= 1 && var.compliance_desired_size <= 4", compliance_variable)
        compliance_group = eks_main.split('resource "aws_eks_node_group" "compliance" {', 1)[1].split('\nresource "aws_autoscaling_group_tag"', 1)[0]
        self.assertIn("min_size     = 1", compliance_group)
        self.assertIn("max_size     = 4", compliance_group)

    def test_plan_is_main_only_protected_and_read_only(self):
        workflow = WORKFLOW.read_text()

        self.assertIn("github.ref == 'refs/heads/main' && inputs.run_plan", workflow)
        self.assertIn("environment: staging", workflow)
        self.assertIn("runs-on: [self-hosted, linux, staging-vpc]", workflow)
        self.assertIn("id-token: write", workflow)
        self.assertIn("terraform plan -input=false", workflow)
        self.assertIn("guard_staging_ownership.py", workflow)
        self.assertIn("name: Reject protected ALB, ACM, LBC, and namespace changes", workflow)
        self.assertIn("set -euo pipefail", workflow)
        self.assertIn("A protected resource action or invalid Plan JSON was detected", workflow)
        self.assertIn("get deployments -n kube-system --server-print=true --no-headers", workflow)
        self.assertIn("get networkpolicies --all-namespaces -o name", workflow)
        self.assertIn("get namespaces --server-print=true --no-headers", workflow)
        self.assertIn('get "$kind" "$name" -n "$namespace"', workflow)
        self.assertIn('platform-resources.txt', workflow)
        self.assertIn('managed-rbac-resources.txt', workflow)
        self.assertIn('describe-addon --cluster-name "$STAGING_CLUSTER_NAME" --addon-name aws-ebs-csi-driver', workflow)
        self.assertIn('get storageclass ebs-gp3 --ignore-not-found -o name', workflow)
        self.assertIn("--rbac-resources \"$rbac_inventory\"", workflow)
        self.assertLess(
            workflow.index('python3 "$GITHUB_WORKSPACE/scripts/staging/guard_staging_ownership.py"'),
            workflow.index("terraform plan -input=false"),
        )
        self.assertIn("get deployment aws-load-balancer-controller -n kube-system", workflow)
        self.assertIn("meta.helm.sh/release-name", workflow)
        self.assertNotIn("get secrets", workflow)
        self.assertNotIn("helm-metadata", workflow)
        self.assertIn("aws iam get-role --role-name", workflow)
        self.assertIn("aws iam get-policy --policy-arn", workflow)
        self.assertNotIn("AssumeRolePolicyDocument", workflow)
        self.assertNotIn("terraform apply", workflow)
        self.assertNotIn("terraform workspace new", workflow)
        self.assertNotIn("terraform import", workflow)

    def test_plan_rbac_manifest_has_no_secret_read_and_only_reads_the_named_lbc_deployment(self):
        manifest = (Path(__file__).resolve().parents[1] / "infra/terraform/manual/plan-readonly-rbac.yaml").read_text()
        self.assertNotIn("resources: [secrets]", manifest)
        self.assertIn("resources: [deployments]", manifest)
        self.assertIn("resourceNames: [aws-load-balancer-controller]", manifest)
        self.assertEqual(manifest.count("namespace: kube-system"), 2)
        self.assertIn("verbs: [get]", manifest)

    def test_plan_pins_state_version_workspace_and_existing_ownership(self):
        workflow = WORKFLOW.read_text()

        self.assertIn("expected_state_version_id:", workflow)
        self.assertIn('test "$actual_version_id" = "$EXPECTED_STATE_VERSION_ID"', workflow)
        self.assertIn('test "$after_version_id" = "$EXPECTED_STATE_VERSION_ID"', workflow)
        self.assertIn("workspace_key_prefix=spectrace", workflow)
        self.assertIn('terraform workspace select "$STAGING_TF_WORKSPACE"', workflow)
        self.assertIn('test "$(terraform workspace show)" = "$STAGING_TF_WORKSPACE"', workflow)
        self.assertIn("manage_load_balancer_controller=false", workflow)
        self.assertIn('existing_namespaces=["gateway","keycloak","keycloak-system","rabbitmq-system"]', workflow)
        self.assertIn("staging-ownership-allowlist.json", workflow)
        self.assertIn("guard_staging_plan_actions.py", workflow)
        self.assertNotIn("tIRsj1wl1CWHRWeAa2C1G.1QujMl.0dk", workflow)
        self.assertGreater(
            workflow.index('python3 "$GITHUB_WORKSPACE/scripts/staging/guard_staging_plan_actions.py"'),
            workflow.index("terraform plan -input=false"),
        )
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
            "module.eks_addons[0].kubernetes_manifest.keycloak_resource_role",
            "module.eks_addons[0].kubernetes_manifest.keycloak_resource_rolebinding",
            "module.eks_addons[0].helm_release.load_balancer_controller[0]",
            "module.eks_addons[0].aws_iam_policy.load_balancer_controller[0]",
            "module.eks_addons[0].aws_iam_role_policy_attachment.load_balancer_controller[0]",
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
            "kubeconfig",
            "kube-system-deployments.txt",
            "network-policy-names.txt",
            "namespace-names.txt",
            "managed-rbac-resources.txt",
            "iam-resources.txt",
            "iam-read-error.txt",
            "iam-role-name.txt",
            "iam-policy-name.txt",
            "iam-attachment-name.txt",
            "platform-resources.txt",
            "platform-read-error.txt",
            "lbc-helm-owner.txt",
            "ebs-addon-name.txt",
        ):
            self.assertIn(f'$RUNNER_TEMP/$M5_TF_TEMP_SUFFIX/{filename}', workflow)
        self.assertNotIn('$RUNNER_TEMP/staging.tfvars.json', workflow)
        self.assertNotIn('$RUNNER_TEMP/staging.tfplan', workflow)


if __name__ == "__main__":
    unittest.main()
