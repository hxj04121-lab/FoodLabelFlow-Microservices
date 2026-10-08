import contextlib
import copy
import io
import json
import unittest
from decimal import Decimal
from pathlib import Path
from datetime import datetime, timedelta, timezone
from tempfile import TemporaryDirectory

from scripts.aws_staging_budget import estimate, load_config, report, under_budget

CONFIG = Path(__file__).resolve().parents[1] / "infra/aws-staging-budget.json"


def fixture():
    return load_config(CONFIG)


class AwsStagingBudgetTests(unittest.TestCase):
    def test_window_cost_scales_for_24_48_and_168_hours(self):
        data = fixture()
        totals = [sum(amount for _, amount in estimate(data, h, h / 6, h / 12)[0]) for h in (24, 48, 168)]
        self.assertEqual(totals[1].quantize(Decimal("0.01")), (totals[0] * 2).quantize(Decimal("0.01")))
        self.assertEqual(totals[2].quantize(Decimal("0.01")), (totals[0] * 7).quantize(Decimal("0.01")))

    def test_baseline_experiment_and_k6_hours_are_separate(self):
        data = fixture()
        rows, _, _, _ = estimate(data, Decimal("24"), Decimal("4"), Decimal("2"))
        lines = dict(rows)
        p = {key: Decimal(row["price_usd"]) for key, row in data["prices"].items()}
        self.assertEqual(lines["3 x t3.medium baseline nodes"], 3 * 24 * p["ec2-t3-medium-linux-hour"])
        self.assertEqual(lines["Compliance baseline c6i.large"], 24 * p["ec2-c6i-large-linux-hour"])
        self.assertEqual(lines["Compliance experiment scale-out c6i.large"], 3 * 4 * p["ec2-c6i-large-linux-hour"])
        self.assertEqual(lines["k6 c6i.large"], 2 * p["ec2-c6i-large-linux-hour"])
        self.assertEqual(lines["RDS db.t3.small Multi-AZ"], 24 * p["rds-mysql-db-t3-small-multi-az-hour"])

    def test_safety_margin_is_20_percent(self):
        data = fixture()
        _, raw, margin, final = estimate(data, 24, 4, 2)
        self.assertGreaterEqual(margin, raw * Decimal("0.20"))
        self.assertLess(margin, raw * Decimal("0.20") + Decimal("0.01"))
        self.assertEqual(final, raw + margin)
        self.assertEqual(Decimal("100") * Decimal("0.20"), Decimal("20"))
        self.assertEqual(Decimal("100") + Decimal("20"), Decimal("120"))

    def test_credit_boundaries_compare_final_estimate(self):
        self.assertTrue(under_budget("99.99", "100"))
        self.assertTrue(under_budget("100", "100"))
        self.assertFalse(under_budget("100.01", "100"))

    def test_missing_credit_is_unverified_but_estimate_succeeds(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = report(fixture(), 24, 4, 2, None)
        self.assertEqual(code, 0)
        self.assertIn("Available AWS credit:      UNVERIFIED", out.getvalue())
        self.assertIn("Gate result:               UNVERIFIED", out.getvalue())
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(report(fixture(), 24, 4, 2, None, require_gate=True), 2)

    def test_explicit_credit_returns_pass_or_fail(self):
        data = fixture()
        _, _, _, final = estimate(data, 24, 4, 2)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(report(data, 24, 4, 2, final), 0)
            self.assertEqual(report(data, 24, 4, 2, final - Decimal("0.01")), 1)

    def test_invalid_hours_credit_assumptions_and_prices_fail(self):
        for h, exp, k6 in ((-1, 0, 0), (0, 0, 0), (24, -1, 0), (24, 25, 0), (24, 0, 25)):
            with self.subTest(hours=h, experiment=exp, k6=k6):
                with self.assertRaises(ValueError):
                    estimate(fixture(), h, exp, k6)
        with self.assertRaisesRegex(ValueError, "non-negative"):
            under_budget("1", "-1")
        for mutate, message in (
            (lambda d: d["resources"].update(general_nodes=-1), "architecture"),
            (lambda d: d["assumptions"].update(nat_data_gib_per_hour=-1), "non-negative"),
            (lambda d: d["prices"]["ec2-t3-medium-linux-hour"].update(price_usd="-0.01"), "positive"),
            (lambda d: d["prices"]["ec2-t3-medium-linux-hour"].update(unit="GiB"), "incomplete AWS pricing metadata"),
            (lambda d: d["prices"]["ec2-t3-medium-linux-hour"].update(source_url="https://example.com/price.csv"), "incomplete AWS pricing metadata"),
            (lambda d: d["prices"].pop("rds-mysql-db-t3-small-multi-az-hour"), "missing pricing"),
        ):
            data = json.loads(CONFIG.read_text())
            mutate(data)
            path = CONFIG.with_suffix(".invalid-test.json")
            try:
                path.write_text(json.dumps(data))
                with self.assertRaisesRegex(ValueError, message):
                    load_config(path)
            finally:
                path.unlink(missing_ok=True)

    def test_stale_public_price_snapshot_fails_closed(self):
        data = json.loads(CONFIG.read_text())
        old = (datetime.now(timezone.utc) - timedelta(hours=169)).isoformat()
        data["prices"]["ec2-t3-medium-linux-hour"]["retrieved_at"] = old
        with TemporaryDirectory() as directory:
            path = Path(directory) / "stale.json"
            path.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, "stale"):
                load_config(path)


if __name__ == "__main__":
    unittest.main()
