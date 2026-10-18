import runpy
import unittest
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

build_window = runpy.run_path(str(Path(__file__).parents[1] / "platform/staging/validate_window.py"))["build_window"]


class StagingWindowTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 10, 17, 15, 0, tzinfo=ZoneInfo("Asia/Singapore"))

    def test_accepts_user_confirmed_future_window(self):
        result = build_window("2", "2026-10-17 17:00", self.now)
        self.assertEqual(result["approvedHours"], 2)
        self.assertEqual(result["plannedEndSgt"], "2026-10-17T17:00:00+08:00")

    def test_rejects_invalid_or_overlong_duration(self):
        for hours in ("0", "25", "nan", "inf"):
            with self.subTest(hours=hours), self.assertRaises(ValueError):
                build_window(hours, "2026-10-18 17:00", self.now)

    def test_rejects_past_or_too_early_shutdown(self):
        for end in ("2026-10-17 14:59", "2026-10-17 16:59"):
            with self.subTest(end=end), self.assertRaises(ValueError):
                build_window("2", end, self.now)

    def test_rejects_non_singapore_datetime_format(self):
        with self.assertRaises(ValueError):
            build_window("1", "2026-10-17T17:00:00+08:00", self.now)

    def test_rejects_a_shutdown_after_the_stcn17_authorization_expires(self):
        self.assertEqual(build_window("2", "2026-10-23 08:00", self.now)["plannedEndSgt"], "2026-10-23T08:00:00+08:00")
        with self.assertRaisesRegex(ValueError, "expires at 2026-10-23 00:00 UTC"):
            build_window("2", "2026-10-23 08:01", self.now)


if __name__ == "__main__":
    unittest.main()
