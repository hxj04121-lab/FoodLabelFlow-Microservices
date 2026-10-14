import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT = REPO_ROOT / "scripts/staging/record-deployment-evidence.py"
SERVICE = "specification"
COMMIT = "a" * 40
SERVICES = ("gateway", "specification", "formulation", "compliance", "label-workflow")


def image_for(service):
    return f"ghcr.io/hxj04121-lab/foodlabelflow-microservices/{service}@sha256:" + "b" * 64


def deployment(service=SERVICE, image=None, ready=2, revision="7"):
    image = image or image_for(service)
    return {
        "metadata": {
            "name": f"{service}-{service}",
            "namespace": service,
            "generation": 4,
            "labels": {"app.kubernetes.io/name": service},
            "annotations": {"deployment.kubernetes.io/revision": revision},
        },
        "spec": {
            "replicas": 2,
            "template": {"spec": {"containers": [{"name": service, "image": image}]}},
        },
        "status": {
            "observedGeneration": 4,
            "updatedReplicas": ready,
            "readyReplicas": ready,
            "availableReplicas": ready,
        },
    }


class DeploymentRecordTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.runner_temp = self.root / "runner-temp"
        self.runner_temp.mkdir()
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        kubectl = self.bin_dir / "kubectl"
        kubectl.write_text(
            "#!/usr/bin/env python3\n"
            "import os, sys\n"
            "with open(os.environ['KUBECTL_LOG'], 'a', encoding='utf-8') as log:\n"
            "    log.write(' '.join(sys.argv[1:]) + '\\n')\n"
            "if sys.argv[1:3] == ['rollout', 'status']:\n"
            "    raise SystemExit(int(os.environ.get('KUBECTL_ROLLOUT_EXIT', '0')))\n"
            "if sys.argv[1:3] == ['get', 'deployment']:\n"
            "    print(os.environ['KUBECTL_DEPLOYMENT_JSON'])\n"
            "    raise SystemExit(0)\n"
            "raise SystemExit(2)\n",
            encoding="utf-8",
        )
        kubectl.chmod(0o755)
        self.env = os.environ.copy()
        self.env.update({
            "PATH": f"{self.bin_dir}:{self.env['PATH']}",
            "RUNNER_TEMP": str(self.runner_temp),
            "GITHUB_ACTIONS": "true",
            "GITHUB_REPOSITORY": "hxj04121-lab/FoodLabelFlow-Microservices",
            "GITHUB_REF": "refs/heads/main",
            "GITHUB_EVENT_NAME": "push",
            "GITHUB_SHA": COMMIT,
            "GITHUB_RUN_ID": "38500000123",
            "GITHUB_SERVER_URL": "https://github.com",
            "KUBECTL_DEPLOYMENT_JSON": json.dumps(deployment()),
            "KUBECTL_LOG": str(self.root / "kubectl.log"),
        })
        self.output = self.runner_temp / "stcn-15" / f"{SERVICE}.json"

    def tearDown(self):
        self.temp.cleanup()

    def run_recorder(self, service=SERVICE, image=None, output=None, env=None):
        image = image or image_for(service)
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--service", service, "--image", image,
             "--output", str(output or self.output)],
            capture_output=True,
            text=True,
            env=env or self.env,
            check=False,
        )

    def test_records_workflow_commit_digest_and_ready_deployment_revision(self):
        for service in SERVICES:
            with self.subTest(service=service):
                self.env["KUBECTL_DEPLOYMENT_JSON"] = json.dumps(deployment(service))
                output = self.runner_temp / "stcn-15" / f"{service}.json"
                result = self.run_recorder(service=service, output=output)
                self.assertEqual(result.returncode, 0, result.stderr)
                record = json.loads(output.read_text(encoding="utf-8"))
                self.assertEqual(record, {
                    "service": service,
                    "namespace": service,
                    "commit_sha": COMMIT,
                    "workflow_run_id": 38500000123,
                    "workflow_run_url": "https://github.com/hxj04121-lab/FoodLabelFlow-Microservices/actions/runs/38500000123",
                    "conclusion": "success",
                    "image": image_for(service),
                    "deployment_revision": 7,
                })
        self.assertEqual((self.root / "kubectl.log").read_text(encoding="utf-8").splitlines(), [
            line
            for service in SERVICES
            for line in (
                f"rollout status deployment/{service}-{service} --namespace {service} --timeout=180s",
                f"get deployment {service}-{service} --namespace {service} --output=json",
            )
        ])

    def test_rejects_mutable_or_foreign_image(self):
        valid_image = image_for(SERVICE)
        for image in (valid_image.split("@", 1)[0] + ":latest", "ghcr.io/example/app@sha256:" + "b" * 64):
            with self.subTest(image=image):
                result = self.run_recorder(image=image)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(self.output.exists())

    def test_rejects_unready_or_wrong_revision_deployment(self):
        self.env["KUBECTL_DEPLOYMENT_JSON"] = json.dumps(deployment(ready=1))
        result = self.run_recorder()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.output.exists())

        self.env["KUBECTL_DEPLOYMENT_JSON"] = json.dumps(deployment(revision="0"))
        result = self.run_recorder()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.output.exists())

    def test_rejects_unprotected_context_and_output_outside_runner_temp(self):
        self.env["GITHUB_REF"] = "refs/pull/52/merge"
        result = self.run_recorder()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.output.exists())

        self.env["GITHUB_REF"] = "refs/heads/main"
        outside = self.root / "record.json"
        result = self.run_recorder(output=outside)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(outside.exists())

    def test_rejects_failed_rollout(self):
        self.env["KUBECTL_ROLLOUT_EXIT"] = "1"
        result = self.run_recorder()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
