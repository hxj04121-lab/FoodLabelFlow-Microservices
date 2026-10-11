"""Offline protocol tests use explicitly synthetic samples; they are not AWS evidence."""
import datetime as dt
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import experiment as e

def samples(path, seconds, rate=1, failures=(), slow=(), phase="hold"):
    start = 1800000000
    with Path(path).open("w", encoding="utf-8") as output:
        for second in range(seconds):
            for arrival in range(rate):
                time = dt.datetime.fromtimestamp(start + second + (arrival + .5) / rate, dt.timezone.utc).isoformat()
                for name, value in (("contract_errors", int(second in failures)), ("validated_response_duration", 2500 if second in slow else 100)):
                    output.write(json.dumps({"type": "Point", "metric": name, "data": {"time": time, "value": value, "tags": {"phase": phase}}}) + "\n")
    return start

class ProtocolTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = Path(self.temp.name)
    def tearDown(self): self.temp.cleanup()
    def test_dataset_is_repeatable_complete_and_matches_java_uuid(self):
        first, second = self.root / "a", self.root / "b"
        before = e.sha(e.TARGET)
        a, b = e.dataset(first, "a" * 40), e.dataset(second, "a" * 40)
        self.assertEqual(a, b); self.assertEqual(before, e.sha(e.TARGET))
        self.assertEqual(a["releasedFormulaVersions"], 5000)
        cases = e.read(first / "requests.json")["cases"]
        self.assertEqual(len(cases), 20)
        self.assertTrue(all(len(case["expected"]["findings"]) == 250 for case in cases))
        self.assertEqual(len({x["findingId"] for c in cases for x in c["expected"]["findings"]}), 5000)
        self.assertEqual(e.finding_id("org", "spec", "formula", "label"), "19bd4dc0-1fe7-3a8f-bfb8-7083f923a8c1")
        with self.assertRaises(ValueError): e.dataset(first, "a" * 40)
    def test_interleaved_full_ladder_and_fixed_durations(self):
        plan = e.ladder_plan()["ladders"]
        self.assertEqual([x["replicas"] for x in plan], [1, 3, 3, 1, 1, 3])
        self.assertEqual([x["trial"] for x in plan], [1, 1, 2, 2, 3, 3])
        self.assertTrue(all(x["rates"] == [1, 2, 4, 8, 16, 32, 64, 128, 256] and x["secondsPerRate"] == 540 for x in plan))
    def test_unconfirmed_window_never_runs(self):
        with self.assertRaisesRegex(ValueError, "approval"): e.authorize({})
    def test_expired_token_file_stops_before_load_and_freshness_does_not_authorize_identity(self):
        request = self.root / "request.json"; token_file = self.root / "private.json"
        e.write(request, {"cases": [{"request": {"organisationId": "org"}}]})
        payload = base64.urlsafe_b64encode(json.dumps({"exp": 0}).encode()).decode().rstrip("=")
        e.write(token_file, {"org": "synthetic." + payload + ".unsigned-test-only"})
        with self.assertRaisesRegex(ValueError, "fresh real JWTs"):
            e.transient_tokens({"tokenFile": str(token_file)}, request, 600)
    def test_capacity_raw_miss_and_incomplete_telemetry(self):
        raw = self.root / "raw.jsonl"; samples(raw, 300)
        self.assertTrue(e.rate_result(raw, 1)["passed"])
        samples(raw, 300, slow=range(200, 300))
        self.assertFalse(e.rate_result(raw, 1)["passed"])
        samples(raw, 300, failures=range(3))  # Exactly 1% is a failure, not rounded down.
        self.assertFalse(e.rate_result(raw, 1)["passed"])
        samples(raw, 100)
        with self.assertRaises(ValueError): e.rate_result(raw, 1)
    def timeline(self, start, ready=170):
        iso = lambda second: dt.datetime.fromtimestamp(start + second, dt.timezone.utc).isoformat()
        return {"deleteRequestedAt": iso(120), "replacementReadyAt": iso(120 + ready)}
    def test_failover_healthy_samples_and_strict_ready_deadline(self):
        raw = self.root / "raw.jsonl"; start = samples(raw, 421)
        self.assertEqual(e.failover_result(raw, self.timeline(start))["status"], "PASS")
        self.assertEqual(e.failover_result(raw, self.timeline(start, 180))["status"], "FAIL")
    def test_late_latency_regression_cannot_be_hidden_by_early_recovery(self):
        raw = self.root / "raw.jsonl"; start = samples(raw, 421, slow=range(300, 350))
        self.assertEqual(e.failover_result(raw, self.timeline(start))["status"], "FAIL")
    def test_continuous_error_buckets_and_missing_telemetry(self):
        raw = self.root / "raw.jsonl"; start = samples(raw, 421, rate=100)
        # One failure per second keeps total error below 1%, but 11 adjacent error buckets must fail.
        lines = raw.read_text().splitlines()
        for second in range(120, 131):
            value = json.loads(lines[second * 200]); value["data"]["value"] = 1; lines[second * 200] = json.dumps(value)
        raw.write_text("\n".join(lines) + "\n")
        self.assertEqual(e.failover_result(raw, self.timeline(start))["longestErrorBucketsSeconds"], 11)
        self.assertEqual(e.failover_result(raw, self.timeline(start))["status"], "FAIL")
        samples(raw, 400)
        with self.assertRaises(ValueError): e.failover_result(raw, self.timeline(start))
    def test_capacity_requires_every_trial_in_declared_order(self):
        e.write(self.root / "runs.json", [{"replicas": 1, "trial": 1, "runs": []}])
        with self.assertRaises(ValueError): e.aggregate(self.root)
    def test_censored_ladders_cannot_claim_capacity(self):
        rows = []
        for ladder in e.ladder_plan()["ladders"]:
            rows.append({"replicas": ladder["replicas"], "trial": ladder["trial"], "environmentFingerprint": "same",
                         "runs": [{"rate": r, "passed": True, "raw": "synthetic"} for r in e.read(e.TARGET)["arrivalRatesPerSecond"]]})
        e.write(self.root / "runs.json", rows)
        e.write(self.root / "environment.json", {"targetsSha256": e.sha(e.TARGET), "environmentFingerprint": "same", "grafanaArtifacts": {}})
        with patch.object(e, "rate_result", side_effect=lambda raw, rate: {"passed": True}):
            self.assertEqual(e.aggregate(self.root)["status"], "INCONCLUSIVE")
    def test_two_misses_are_required_and_non_monotonic_holds_are_inconclusive(self):
        rows = [{"replicas": x["replicas"], "trial": x["trial"], "environmentFingerprint": "same", "runs": [
                {"rate": rate, "passed": passed, "raw": "synthetic"} for rate, passed in [(1, True), (2, False), (4, True), (8, False), (16, False)]]}
                for x in e.ladder_plan()["ladders"]]
        e.write(self.root / "runs.json", rows)
        e.write(self.root / "environment.json", {"targetsSha256": e.sha(e.TARGET), "environmentFingerprint": "same", "grafanaArtifacts": {}})
        with patch.object(e, "rate_result", side_effect=lambda raw, rate: {"passed": rate in (1, 4), "dropped": 0}):
            result = e.aggregate(self.root)
            self.assertTrue(result["nonMonotonic"])
            self.assertEqual(result["status"], "INCONCLUSIVE")
        rows[0]["runs"] = rows[0]["runs"][:2]
        e.write(self.root / "runs.json", rows)
        with self.assertRaisesRegex(ValueError, "two consecutive"): e.aggregate(self.root)
    def test_native_k6_parses_failover_half_capacity_without_network_calls(self):
        binary = os.environ.get("K6_BIN") or shutil.which("k6")
        self.assertTrue(binary, "Install the pinned k6 binary to execute this test")
        env = dict(os.environ, DRY_RUN="false", EXPERIMENT="failover", RATE="16", CAPACITY_3="32", REPLICAS="3", TRIAL_ID="offline-inspect",
                   AUTH_TOKENS_JSON='{"manufacturer_01":"synthetic-local-inspection-only"}')
        result = subprocess.run([binary, "inspect", "--include-system-env-vars", "--execution-requirements", "impact-preview.js"], cwd=e.ROOT / "perf/k6", env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        options = json.loads(result.stdout)["scenarios"]["failover"]
        self.assertEqual(options["executor"], "constant-arrival-rate")
        self.assertEqual(options["duration"], "7m10s")
        self.assertEqual(options["rate"], 32)
        self.assertEqual(options["timeUnit"], "2s")
        env["CAPACITY_3"] = "40"
        invalid = subprocess.run([binary, "inspect", "--include-system-env-vars", "impact-preview.js"], cwd=e.ROOT / "perf/k6", env=env, capture_output=True, text=True)
        self.assertNotEqual(invalid.returncode, 0)

if __name__ == "__main__": unittest.main()
