import unittest

from scripts.staging.validate_helm_release import validate


def obj(kind, name, uid, *, labels=None, annotations=None, owners=None, namespace="frontend"):
    return {"kind": kind, "metadata": {
        "name": name, "namespace": namespace, "uid": uid,
        "labels": labels or {}, "annotations": annotations or {}, "ownerReferences": owners or [],
    }}


def installed_inventory():
    annotations = {"meta.helm.sh/release-name": "frontend", "meta.helm.sh/release-namespace": "frontend"}
    labels = {"app.kubernetes.io/instance": "frontend"}
    deployment = obj("Deployment", "frontend-frontend", "deployment-uid", labels=labels, annotations=annotations)
    replica = obj("ReplicaSet", "frontend-frontend-abc", "replicaset-uid", labels=labels,
                  owners=[{"uid": "deployment-uid"}])
    pod = obj("Pod", "frontend-frontend-abc-xyz", "pod-uid", labels=labels,
              owners=[{"uid": "replicaset-uid"}])
    service = obj("Service", "frontend-frontend", "service-uid", labels=labels, annotations=annotations)
    return [deployment, replica, pod, service]


class HelmReleaseInventoryTests(unittest.TestCase):
    def test_precreated_namespace_with_only_platform_baseline_is_installable(self):
        validate([], {"items": [
            obj("ServiceAccount", "default", "default-sa"),
            obj("NetworkPolicy", "stcn-default-deny-frontend", "default-deny"),
        ]}, "frontend", "frontend", "frontend-frontend")

    def test_business_namespace_accepts_only_the_two_terraform_platform_policies(self):
        validate([], {"items": [
            obj("ServiceAccount", "default", "default-sa", namespace="specification"),
            obj("NetworkPolicy", "stcn-default-deny-specification", "default-deny", namespace="specification"),
            obj("NetworkPolicy", "stcn-specification-flows", "service-flows", namespace="specification"),
        ]}, "specification", "specification", "specification-specification")

    def test_rejects_orphan_pods_and_unmanaged_jobs_on_first_install(self):
        for item in (obj("Pod", "leftover", "pod"), obj("ReplicaSet", "leftover", "replicaset"),
                     obj("Job", "migration", "job"), obj("CronJob", "migration", "cronjob"),
                     obj("DaemonSet", "agent", "daemonset"), obj("ReplicationController", "legacy", "rc")):
            with self.subTest(kind=item["kind"]), self.assertRaisesRegex(ValueError, "unknown pre-existing"):
                validate([], {"items": [item]}, "frontend", "frontend", "frontend-frontend")

    def test_rejects_failed_and_other_releases(self):
        for release in ([{"name": "frontend", "status": "failed"}],
                        [{"name": "unexpected", "status": "deployed"}]):
            with self.subTest(release=release), self.assertRaisesRegex(ValueError, "unexpected or failed"):
                validate(release, {"items": []}, "frontend", "frontend", "frontend-frontend")

    def test_upgrade_accepts_release_owned_deployment_descendants_only(self):
        validate([{"name": "frontend", "status": "deployed"}],
                 {"items": installed_inventory()}, "frontend", "frontend", "frontend-frontend")
        with self.assertRaisesRegex(ValueError, "unknown pre-existing"):
            validate([{"name": "frontend", "status": "deployed"}],
                     {"items": installed_inventory() + [obj("Job", "unknown", "job")]},
                     "frontend", "frontend", "frontend-frontend")

    def test_rejects_release_annotation_with_wrong_namespace_or_instance(self):
        bad = obj("Deployment", "frontend-frontend", "deployment", annotations={
            "meta.helm.sh/release-name": "frontend", "meta.helm.sh/release-namespace": "other",
        })
        with self.assertRaisesRegex(ValueError, "unknown pre-existing"):
            validate([], {"items": [bad]}, "frontend", "frontend", "frontend-frontend")


if __name__ == "__main__":
    unittest.main()
