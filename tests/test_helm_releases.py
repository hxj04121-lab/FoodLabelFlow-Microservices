import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("helm_releases", Path(__file__).resolve().parents[1] / "scripts/staging/helm_releases.py")
helm_releases = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helm_releases)


def secret(name, version, status, owner="helm"):
    return {"metadata": {"labels": {"owner": owner, "name": name, "version": str(version), "status": status}}}


class HelmReleasesTest(unittest.TestCase):
    def test_latest_revision_status_per_release_including_failed(self):
        rows = helm_releases.releases({"items": [
            secret("frontend", 1, "superseded"), secret("frontend", 2, "failed"),
            secret("gateway", 1, "deployed"), secret("other", 1, "deployed", owner="someone"),
        ]})
        self.assertEqual(rows, [{"name": "frontend", "status": "failed", "revision": "2"},
                                {"name": "gateway", "status": "deployed", "revision": "1"}])

    def test_empty_namespace_has_no_releases(self):
        self.assertEqual(helm_releases.releases({"items": []}), [])
