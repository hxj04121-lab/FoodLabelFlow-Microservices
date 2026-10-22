import runpy
import unittest
from pathlib import Path

checks = runpy.run_path(str(Path(__file__).resolve().parents[1] / "platform/frontend/verify-alb.py"))


def health(*states):
    return {"TargetHealthDescriptions": [{"TargetHealth": {"State": s, "Reason": r}} for s, r in states]}


class RegisteredTargetsTest(unittest.TestCase):
    def test_unattached_group_targets_are_accepted_before_cutover(self):
        checks["verify_registered_targets"](health(("unused", "Target.NotInUse")), "frontend")
        checks["verify_registered_targets"](health(("healthy", None)), "frontend")

    def test_missing_or_failing_targets_are_rejected(self):
        for document in (health(), health(("unhealthy", "Target.ResponseCodeMismatch")), health(("unused", "Target.InvalidState"))):
            with self.assertRaises(ValueError):
                checks["verify_registered_targets"](document, "frontend")

    def test_cutover_still_requires_healthy(self):
        with self.assertRaises(ValueError):
            checks["verify_healthy_targets"](health(("unused", "Target.NotInUse")), "frontend")
