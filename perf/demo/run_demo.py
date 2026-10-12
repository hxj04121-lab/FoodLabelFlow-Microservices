#!/usr/bin/env python3
"""Drive the soy-lecithin demo, steps 1-7, through the Gateway and record evidence (G3-M1.2/3, STCN-90/91).

Usage:
  python3 perf/demo/run_demo.py --base https://<gateway-host> --tokens tokens.json [--out evidence.json]

tokens.json maps realm usernames to access tokens obtained by PKCE sign-in (never commit it):
  {"supplier.beta.maker": "...", "manufacturer.north.maker": "...", "manufacturer.north.checker": "...",
   "label.maker": "...", "label.checker": "...", "label.publisher": "..."}
The label.* entries default to the manufacturer.north.* tokens when absent.

Expected results come from docs/swe5001/data/demo-soy-lecithin-expected.json (perf/data/demo_soy_lecithin.py).
Each step ends PASS, FAIL or BLOCKED (a dependency answered 404/501/503 or a contract is not yet public).
The evidence file holds statuses, IDs, error codes and X-Correlation-ID values, never tokens. Exit codes:
0 = all seven steps PASS; 1 = any FAIL; 2 = BLOCKED or incomplete. Only standard-library Python is used.
"""
import argparse
import datetime as dt
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXPECTED = ROOT / "docs/swe5001/data/demo-soy-lecithin-expected.json"
PROVENANCE = "prov_project_seed"
UNAVAILABLE = {404, 501, 502, 503}


class Demo:
    def __init__(self, base, tokens, timeout):
        self.base = base.rstrip("/")
        self.tokens = tokens
        self.timeout = timeout
        self.steps = []

    def call(self, user, method, path, body=None):
        request = urllib.request.Request(self.base + path, method=method,
                                         data=None if body is None else json.dumps(body).encode())
        request.add_header("Authorization", "Bearer " + self.tokens[user])
        request.add_header("Accept", "application/json")
        if body is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None, response.headers.get("X-Correlation-ID")
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                payload = json.loads(raw) if raw else None
            except ValueError:
                payload = {"raw": raw[:200].decode(errors="replace")}
            return error.code, payload, error.headers.get("X-Correlation-ID")

    def poll(self, check, what):
        deadline = time.monotonic() + self.timeout
        last = None
        while time.monotonic() < deadline:
            done, last = check()
            if done:
                return True, last
            time.sleep(2)
        return False, f"timed out after {self.timeout}s waiting for {what}; last: {last}"

    def record(self, number, title, status, **details):
        self.steps.append({"step": number, "title": title, "status": status,
                           "at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"), **details})
        print(f"[{status:7}] step {number}: {title}" + (f" - {details.get('reason')}" if details.get("reason") else ""))
        return status == "PASS"


def outcome_of(status, payload, expected=(200, 201)):
    if status in expected:
        return "PASS"
    return "BLOCKED" if status in UNAVAILABLE else "FAIL"


def error_code(payload):
    return payload.get("code") if isinstance(payload, dict) else None


def items(payload):
    return payload.get("items", []) if isinstance(payload, dict) else []


def exit_code(steps):
    if any(step["status"] == "FAIL" for step in steps):
        return 1
    return 0 if len(steps) == 7 and {s["step"] for s in steps} == set(range(1, 8)) \
        and all(s["status"] == "PASS" for s in steps) else 2


def run(demo, expected):
    change = expected["step1_specificationChange"]
    adopt = expected["step4_adoption"]
    supplier, maker, checker = "supplier.beta.maker", "manufacturer.north.maker", "manufacturer.north.checker"
    north = expected["step3_expectedPotentialFindings"][adopt["organisationId"]]

    # 1. Supplier Beta creates and releases Chocolate Base V2 with soy lecithin.
    body = {"materialId": change["materialId"], "effectiveDate": dt.date.today().isoformat(), "provenanceId": PROVENANCE,
            "components": [{**c, "matchRule": "supplier statement, demo V2"} for c in change["candidateComponents"]]}
    status, draft, correlation = demo.call(supplier, "POST", "/api/specifications/versions", body)
    if status != 201:
        return demo.record(1, "Supplier Beta releases Chocolate Base V2", outcome_of(status, draft), http=status,
                           code=error_code(draft), correlationId=correlation,
                           reason="create draft refused" + (" (supplier role lacks ADMIN_DATA_MAINTENANCE?)" if status == 403 else ""))
    candidate = draft["specificationVersionId"]
    status, released, correlation = demo.call(supplier, "POST", f"/api/specifications/versions/{candidate}/release")
    if not demo.record(1, "Supplier Beta releases Chocolate Base V2", outcome_of(status, released), http=status,
                       code=error_code(released), candidateSpecificationVersionId=candidate, correlationId=correlation):
        return False

    # 2. SpecificationPublished reaches Formulation's projection (outbox -> RabbitMQ -> consumer).
    path = "/api/formulations/released-specifications?" + urllib.parse.urlencode({"materialId": change["materialId"], "limit": 100})
    ok, last = demo.poll(lambda: (lambda r: (r[0] == 200 and any(
        i.get("specificationVersion", {}).get("id") == candidate for i in items(r[1])), r[0]))(demo.call(maker, "GET", path)),
        "the candidate in Formulation's released-specification projection")
    demo.record(2, "SpecificationPublished propagates to Formulation", "PASS" if ok else "FAIL",
                reason=None if ok else last, correlationId=correlation)

    # 3. Compliance Phase 1: POTENTIAL findings for the north manufacturer's products pinning V1.
    def potential():
        status, page, _ = demo.call(maker, "GET", "/api/compliance/impact-findings?kind=POTENTIAL&limit=100")
        if status in UNAVAILABLE:
            return True, ("BLOCKED", status)
        mine = [f for f in items(page) if f.get("specificationChange", {}).get("candidate", {}).get("id") == candidate]
        return len(mine) >= north["total"], ("FOUND", status, len(mine), mine)
    ok, last = demo.poll(potential, "POTENTIAL findings")
    if ok and last[0] == "BLOCKED":
        demo.record(3, "POTENTIAL findings in the impact inbox", "BLOCKED", http=last[1], reason="Compliance API not deployed")
    else:
        found = last[3] if ok else []
        outcomes = {}
        for finding in found:
            outcome = finding.get("outcome") or finding.get("payload", {}).get("outcome")
            outcomes[outcome] = outcomes.get(outcome, 0) + 1
        good = ok and len(found) == north["total"] \
            and outcomes.get("REVIEW_REQUIRED", 0) == north["REVIEW_REQUIRED"] \
            and outcomes.get("NO_ACTION", 0) == north["NO_ACTION"]
        demo.record(3, "POTENTIAL findings in the impact inbox", "PASS" if good else "FAIL",
                    expected=north, observed=outcomes, reason=None if good else (last if not ok else "outcome counts differ"))

    # 4. The manufacturer adopts V2 in one product's formula and releases it.
    status, product, _ = demo.call(maker, "GET", f"/api/formulations/products/{adopt['productId']}")
    current = (product or {}).get("currentFormulaVersion", {}).get("id") if status == 200 else None
    status, formula, _ = demo.call(maker, "GET", f"/api/formulations/formula-versions/{current}") if current else (status, product, None)
    if status != 200:
        return demo.record(4, "Manufacturer adopts V2 and releases a formula", outcome_of(status, formula), http=status,
                           code=error_code(formula))
    new_items = [{"materialId": i["materialId"],
                  "specificationVersionId": candidate if i["materialId"] == change["materialId"] else i["specificationVersion"]["id"],
                  "quantity": i.get("quantity"), "unit": i.get("unit")} for i in formula["items"]]
    status, draft, _ = demo.call(maker, "POST", "/api/formulations/formula-versions",
                                 {"productId": adopt["productId"], "provenanceId": PROVENANCE, "items": new_items})
    adopted = (draft or {}).get("formulaVersionId")
    if status == 201:
        status, released, correlation = demo.call(maker, "POST", f"/api/formulations/formula-versions/{adopted}/release",
                                                  {"expectedCurrentFormulaVersionId": current})
    if not demo.record(4, "Manufacturer adopts V2 and releases a formula", outcome_of(status, released if status in (200, 201) else draft),
                       http=status, previousFormulaVersionId=current, adoptedFormulaVersionId=adopted, correlationId=correlation):
        return False

    # 5. Phase 2: CONFIRMED + REVIEW_REQUIRED for the adopted formula, which opens a ReviewTask in Label Workflow.
    def confirmed():
        status, page, _ = demo.call(maker, "GET", "/api/compliance/impact-findings?kind=CONFIRMED&limit=100")
        if status in UNAVAILABLE:
            return True, ("BLOCKED", status)
        mine = [f for f in items(page) if f.get("formulaVersion", {}).get("id") == adopted
                and f.get("specificationChange", {}).get("candidate", {}).get("id") == candidate]
        return bool(mine), ("FOUND", status, mine)
    ok, last = demo.poll(confirmed, "a CONFIRMED finding")
    if ok and last[0] == "BLOCKED":
        demo.record(5, "CONFIRMED finding and review task", "BLOCKED", http=last[1], reason="Compliance API not deployed")
    else:
        outcome = (last[2][0].get("outcome") if ok else None)
        finding_ok = outcome == "REVIEW_REQUIRED"
        demo.record(5, "CONFIRMED finding and review task", "BLOCKED" if finding_ok else "FAIL",
                    observedOutcome=outcome, findingStatus="PASS" if finding_ok else "FAIL",
                    findingId=last[2][0].get("findingId") if ok else None, reviewTaskStatus="NOT_OBSERVED",
                    reason="CONFIRMED finding observed, but no public unique ReviewTask read contract yet (G0 C-05)"
                    if finding_ok else (last if not ok else "expected REVIEW_REQUIRED"))

    # 6. Label draft, validation, submit, maker-checker decision and publication.
    lm, lc, lp = (u if u in demo.tokens else d for u, d in
                  (("label.maker", maker), ("label.checker", checker), ("label.publisher", checker)))
    status, label, correlation = demo.call(lm, "POST", "/api/labels/drafts", {"productId": adopt["productId"], "jurisdictionCode": "US"})
    trail = [("draft", status)]
    if status == 201:
        label_id = label["labelVersionId"]
        for user, suffix, body in ((lm, "validations", None), (lm, "submit", None),
                                   (lc, "decisions", {"decision": "APPROVE", "comments": "demo"}), (lp, "publish", None)):
            status, response, correlation = demo.call(user, "POST", f"/api/labels/{label_id}/{suffix}", body)
            trail.append((suffix, status))
            if status not in (200, 201):
                break
    demo.record(6, "Label draft, validate, submit, approve, publish", outcome_of(status, None), trail=trail,
                correlationId=correlation, reason=None if status in (200, 201) else f"stopped at {trail[-1][0]}")

    # 7. LabelPublished flows back to Compliance; history is kept, re-evaluation is observed separately.
    demo.record(7, "LabelPublished updates the Compliance projection", "BLOCKED",
                publicationHttpStatus=trail[-1][1], projectionStatus="NOT_OBSERVED",
                reason="Label publication HTTP success does not prove event delivery or projection application; "
                       "a correlated, version-exact observer is required",
                correlationId=correlation)
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--base", required=True, help="Gateway base URL, e.g. https://staging.example")
    parser.add_argument("--tokens", required=True, type=Path)
    parser.add_argument("--out", type=Path, default=Path("demo-evidence.json"))
    parser.add_argument("--timeout", type=int, default=120, help="seconds to wait for each asynchronous step")
    args = parser.parse_args()
    tokens = json.loads(args.tokens.read_text())
    missing = [u for u in ("supplier.beta.maker", "manufacturer.north.maker", "manufacturer.north.checker") if u not in tokens]
    if missing:
        parser.error("tokens file lacks " + ", ".join(missing))
    demo = Demo(args.base, tokens, args.timeout)
    started = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")
    run(demo, json.loads(EXPECTED.read_text()))
    args.out.write_text(json.dumps({"scenario": "soy-lecithin V1 -> V2", "base": args.base, "startedAt": started,
                                    "steps": demo.steps}, indent=2) + "\n")
    print(f"evidence written to {args.out}")
    return exit_code(demo.steps)


if __name__ == "__main__":
    raise SystemExit(main())
