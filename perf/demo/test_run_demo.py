"""Exercises run_demo.py against an in-process fake Gateway: the happy path and the BLOCKED/403 paths."""
import json
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import run_demo  # noqa: E402

EXPECTED = json.loads(run_demo.EXPECTED.read_text())
ADOPT = EXPECTED["step4_adoption"]
NORTH = EXPECTED["step3_expectedPotentialFindings"][ADOPT["organisationId"]]


class FakeGateway(BaseHTTPRequestHandler):
    mode = "ok"
    calls = []

    def log_message(self, *args):
        pass

    def reply(self, status, body=None):
        raw = b"" if body is None else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-Correlation-ID", "corr-" + str(len(FakeGateway.calls)))
        self.end_headers()
        self.wfile.write(raw)

    def handle_any(self, method):
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length)) if length else None
        FakeGateway.calls.append((method, self.path, body, self.headers.get("Authorization")))
        path, mode = self.path, FakeGateway.mode
        if method == "POST" and path == "/api/specifications/versions":
            if mode == "forbidden":
                return self.reply(403, {"code": "AUTHORIZATION_DENIED"})
            return self.reply(201, {"specificationVersionId": "spec_demo_v2"})
        if path.endswith("/release") and "specifications" in path:
            return self.reply(200, {"specificationVersionId": "spec_demo_v2", "lifecycleStatus": "RELEASED"})
        if path.startswith("/api/formulations/released-specifications"):
            return self.reply(200, {"items": [{"specificationVersion": {"id": "spec_demo_v2", "versionNumber": 2}}]})
        if path.startswith("/api/compliance/"):
            if mode == "blocked":
                return self.reply(404, {"code": "NOT_FOUND"})
            if "POTENTIAL" in path:
                found = [{"outcome": "REVIEW_REQUIRED", "candidate": "spec_demo_v2"}] * NORTH["REVIEW_REQUIRED"] \
                    + [{"outcome": "NO_ACTION", "candidate": "spec_demo_v2"}] * NORTH["NO_ACTION"]
                return self.reply(200, {"items": found})
            return self.reply(200, {"items": [{"outcome": "REVIEW_REQUIRED", "formula": "formula_demo_v2"}]})
        if path == f"/api/formulations/products/{ADOPT['productId']}":
            return self.reply(200, {"currentFormulaVersion": {"id": ADOPT["expectedCurrentFormulaVersionId"], "versionNumber": 1}})
        if path.startswith("/api/formulations/formula-versions/") and method == "GET":
            return self.reply(200, {"items": [
                {"materialId": "mat_chocolate_base", "specificationVersion": {"id": "spec_chocolate_v1"}, "quantity": 2, "unit": "kg"},
                {"materialId": "mat_wheat_flour", "specificationVersion": {"id": "spec_wheat_flour_v1"}, "quantity": None, "unit": None}]})
        if path == "/api/formulations/formula-versions":
            return self.reply(201, {"formulaVersionId": "formula_demo_v2"})
        if path.endswith("/release"):
            return self.reply(200, {"formulaVersionId": "formula_demo_v2"})
        if path.startswith("/api/labels"):
            if mode == "blocked":
                return self.reply(404, {"code": "NOT_FOUND"})
            return self.reply(201, {"labelVersionId": "label_demo"})
        return self.reply(500, {"code": "UNEXPECTED", "path": path})

    def do_GET(self):
        self.handle_any("GET")

    def do_POST(self):
        self.handle_any("POST")


class RunDemoTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeGateway)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        FakeGateway.calls = []
        tokens = {u: "token-" + u for u in ("supplier.beta.maker", "manufacturer.north.maker", "manufacturer.north.checker")}
        self.demo = run_demo.Demo(f"http://127.0.0.1:{self.server.server_port}", tokens, timeout=4)

    def tearDown(self):
        self.server.shutdown()

    def statuses(self):
        return {step["step"]: step["status"] for step in self.demo.steps}

    def test_happy_path_passes_every_step_and_adopts_v2_keeping_other_items(self):
        FakeGateway.mode = "ok"
        run_demo.run(self.demo, EXPECTED)
        self.assertEqual(self.statuses(), {n: "PASS" for n in range(1, 8)})
        create = next(c for c in FakeGateway.calls if c[1] == "/api/formulations/formula-versions")
        self.assertEqual([i["specificationVersionId"] for i in create[2]["items"]], ["spec_demo_v2", "spec_wheat_flour_v1"])
        release = next(c for c in FakeGateway.calls if c[1] == "/api/formulations/formula-versions/formula_demo_v2/release")
        self.assertEqual(release[2], {"expectedCurrentFormulaVersionId": ADOPT["expectedCurrentFormulaVersionId"]})
        draft = next(c for c in FakeGateway.calls if c[1] == "/api/specifications/versions")
        self.assertEqual(draft[2]["components"][-1]["ingredientId"], "ing_soy_lecithin")
        self.assertEqual(draft[3], "Bearer token-supplier.beta.maker")
        self.assertNotIn("token-", json.dumps(self.demo.steps))

    def test_missing_services_are_blocked_not_failed(self):
        FakeGateway.mode = "blocked"
        run_demo.run(self.demo, EXPECTED)
        self.assertEqual(self.statuses(), {1: "PASS", 2: "PASS", 3: "BLOCKED", 4: "PASS", 5: "BLOCKED", 6: "BLOCKED", 7: "BLOCKED"})

    def test_a_supplier_without_write_permission_stops_at_step_one(self):
        FakeGateway.mode = "forbidden"
        run_demo.run(self.demo, EXPECTED)
        self.assertEqual(self.statuses(), {1: "FAIL"})
        self.assertIn("ADMIN_DATA_MAINTENANCE", self.demo.steps[0]["reason"])


if __name__ == "__main__":
    with tempfile.TemporaryDirectory():
        unittest.main()
