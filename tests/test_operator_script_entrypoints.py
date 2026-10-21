import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class OperatorScriptEntrypointTest(unittest.TestCase):
    def test_scripts_run_as_files_like_the_workflow(self):
        # The workflow runs `python3 scripts/staging/<file>.py`, which does not put the repo root on sys.path.
        for script in ("check_operator_manifest_ownership.py", "render_operator_install_manifest.py"):
            result = subprocess.run(
                [sys.executable, str(ROOT / "scripts/staging" / script), "--help"],
                cwd=ROOT / "scripts", capture_output=True, text=True, timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
