"""SWE5001 M2 fixed-protocol tooling. Offline by default; no provisioning or credentials in evidence."""
from __future__ import annotations
import argparse
import copy
import base64
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import random
import statistics
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / "docs/swe5001/test-plan-G0-M2-targets.v1.json"

def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))

def write(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()

def timestamp(value):
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()

def require(condition, message):
    if not condition:
        raise ValueError(message)

def finding_id(org, candidate, formula, label):
    return str(uuid.UUID(bytes=hashlib.md5(f"{org}|{candidate}|{formula}|{label}".encode(), usedforsecurity=False).digest(), version=3))

def dataset(destination, source_commit):
    """Generate synthetic owner-import inputs, never insert into another service's database."""
    targets = read(TARGET)
    destination = Path(destination)
    require(not destination.exists() or not any(destination.iterdir()), "Dataset destination must be empty")
    destination.mkdir(parents=True, exist_ok=True)
    rng = random.Random(targets["dataset"]["seed"])
    specifications, formulas, labels, cases = [], [], [], []
    spec_template = read(ROOT / "contracts/examples/specification/specification-published-soy-lecithin-v2.json")
    formula_template = read(ROOT / "contracts/examples/formulation/formula-published-adopts-v2.json")
    suppliers = {
        "candidate": ("org-supplier-beta", "sup_chocolate_demo", "CHOCO_SUP", "Demo Chocolate Supplier"),
        "background": ("org-supplier-alpha", "sup_base_demo", "BASE_SUP", "Demo Base Ingredients Supplier"),
    }
    cocoa = ("ing_cocoa", "Cocoa", "cocoa mass", "MATCHED")
    wheat = ("ing_wheat_flour", "Wheat Flour", "wheat flour", "MATCHED")
    soy = ("ing_soy_lecithin", "Soy Lecithin", "emulsifier (soy lecithin)", "MATCHED")
    unknown = ("ing_neutral_base", "Neutral Base", "natural flavouring (carrier undisclosed)", "UNMAPPED")

    def specification(material, version, components, supplier_kind):
        source = copy.deepcopy(spec_template)
        org, supplier_id, code, name = suppliers[supplier_kind]
        version_id = f"{material}_v{version}"
        released = f"2026-10-01T0{5 + version}:55:00Z"
        source.update(eventId=str(uuid.uuid5(uuid.NAMESPACE_URL, f"5001:spec:{version_id}")),
                      organisationId=org, aggregateId=material, aggregateVersion=version, occurredAt=released,
                      correlationId=f"dataset-{version_id}")
        source["payload"].update(organisationId=org,
            supplier={"supplierId": supplier_id, "supplierCode": code, "supplierName": name},
            material={"materialId": material, "materialCode": material.upper(), "materialName": material.replace("_", " ")},
            specificationVersion={"id": version_id, "versionNumber": version},
            previousVersion=None if version == 1 else {"id": f"{material}_v1", "versionNumber": 1}, releasedAt=released,
            components=[{"specComponentId": f"{version_id}_c{index:02}", "sequenceNo": index,
                         "ingredientId": ingredient, "ingredientName": ingredient_name, "rawPhrase": phrase, "matchStatus": status}
                        for index, (ingredient, ingredient_name, phrase, status) in enumerate(components, 1)])
        specifications.append(source)
        return version_id

    material = "perf_material_candidate"
    previous = specification(material, 1, [cocoa, wheat], "candidate")
    candidate = specification(material, 2, [cocoa, wheat, soy], "candidate")
    backgrounds = {}
    for name, component in (("wheat", wheat), ("soy", soy), ("unresolved", unknown)):
        item_material = f"perf_background_{name}"
        backgrounds[name] = (item_material, specification(item_material, 1, [component], "background"))
    safe = []
    for index in range(1, 12):
        item_material = f"perf_background_safe_{index:02}"
        safe.append((item_material, specification(item_material, 1, [cocoa], "background")))

    for tenant in range(1, 21):
        org = f"perf_manufacturer_{tenant:02}"
        expected = []
        for index in range(250):
            product = f"perf_product_{tenant:02}_{index:03}"
            formula, label = product + "_formula_v1", product + "_label_v1"
            source = copy.deepcopy(formula_template)
            source.update(eventId=str(uuid.uuid5(uuid.NAMESPACE_URL, f"5001:formula:{tenant}:{index}")), organisationId=org, aggregateId=product, aggregateVersion=1)
            payload = source["payload"]
            payload.update(organisationId=org, formulaVersion={"id": formula, "versionNumber": 1}, previousFormulaVersion=None)
            payload["product"]["productId"] = product
            formula_index = (tenant - 1) * 250 + index
            group, references_candidate = formula_index % 4, formula_index % 5 == 0
            references = [backgrounds["wheat"]]
            if references_candidate:
                references.append((material, previous))
            if group in (2, 3):
                references.append(backgrounds["soy" if group == 2 else "unresolved"])
            references.extend(rng.sample(safe, rng.randint(4, 12) - len(references)))
            payload["items"] = [{"formulaItemId": f"{formula}_i{sequence:02}", "sequenceNo": sequence,
                                 "materialId": item_material, "specificationVersion": {"id": spec_id, "versionNumber": 1},
                                 "quantity": 1, "unit": "kg"}
                                for sequence, (item_material, spec_id) in enumerate(references, 1)]
            formulas.append(source)
            declared = ["all_wheat"] + (["all_soy"] if group in (1, 2) else [])
            # This is an explicit synthetic business version, to be created/approved by M4's importer.
            labels.append({"organisationId": org, "productId": product, "labelVersionId": label, "versionNumber": 1,
                           "formulaVersionId": formula, "ruleSetVersionId": "ruleset_us_falcpa_demo_v1", "jurisdiction": "US",
                           "declaredAllergens": declared, "fixtureGroup": ["MISSING_AFTER_SUBSTITUTION", "FULLY_DECLARED", "UNCHANGED_CONCLUSION", "UNRESOLVED_EXPLICIT"][group],
                           "fixtureKind": "owner-import-plan-not-a-published-event"})
            # The 80% controls do not reference the candidate; group 2 already contains soy in another item.
            if references_candidate and group != 2:
                expected.append({"findingId": finding_id(org, candidate, formula, label),
                                 "outcome": "NO_ACTION" if group == 1 else "REVIEW_REQUIRED"})
        cases.append({"request": {"organisationId": org, "specificationChange": {"previous": {"id": previous, "versionNumber": 1},
                        "candidate": {"id": candidate, "versionNumber": 2}}, "ruleSetVersionId": "ruleset_us_falcpa_demo_v1", "jurisdiction": "US"},
                      "expected": {"findings": expected}})
    for name, rows in (("specification-owner-import.jsonl", specifications), ("formula-owner-import.jsonl", formulas), ("label-owner-import.jsonl", labels)):
        (destination / name).write_text("".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows), encoding="utf-8")
    write(destination / "requests.json", {"datasetFormatVersion": 2, "fixtureKind": "synthetic-owner-import-plan-not-loaded", "cases": cases})
    manifest = {"status": "GENERATED_NOT_LOADED", "datasetFormatVersion": 2, "seed": 5001,
                "releasedSpecificationVersions": len(specifications), "releasedFormulaVersions": len(formulas), "manufacturers": 20,
                "formulaVersionsPerManufacturer": 250, "sourceCommit": source_commit, "targetsSha256": sha(TARGET),
                "workload": {"formulaItemMinimum": 4, "formulaItemMaximum": 12, "candidateMaterialId": material,
                             "candidateFormulaFraction": .2, "candidateFormulaCount": 1000, "candidateFormulasPerManufacturer": 50,
                             "expectedFindingCount": sum(len(c["expected"]["findings"]) for c in cases),
                             "supplierOrganisations": ["org-supplier-alpha", "org-supplier-beta"], "groupPartition": "formulaIndex % 4"},
                "files": {p.name: sha(p) for p in sorted(destination.iterdir()) if p.is_file()},
                "loading": "M1/M4 must import through owned APIs, return exact released snapshots and approve expectations before measurement."}
    write(destination / "manifest.json", manifest)
    return manifest

def ladder_plan():
    targets = read(TARGET)
    counts = {1: 0, 3: 0}
    plan = []
    for replicas in targets["trialOrder"]:
        counts[replicas] += 1
        plan.append({"replicas": replicas, "trial": counts[replicas], "rates": targets["arrivalRatesPerSecond"],
                     "secondsPerRate": sum(targets[x] for x in ("warmupSeconds", "rateRampSeconds", "settleSeconds", "measuredHoldSeconds"))})
    return {"status": "PLAN_ONLY", "targetsSha256": sha(TARGET), "ladders": plan, "stop": "two consecutive valid SLO misses per ladder; retain all invalid/missed/censored/non-monotonic trials"}

def raw_metrics(path, phase=None):
    errors, durations, dropped = [], [], 0
    with Path(path).open(encoding="utf-8") as stream:
        for line in stream:
            record = json.loads(line)
            if record.get("type") != "Point":
                continue
            data, metric = record["data"], record["metric"]
            if metric == "dropped_iterations":
                dropped += data["value"]
            if phase and data.get("tags", {}).get("phase") != phase:
                continue
            point = (timestamp(data["time"]), float(data["value"]))
            if metric == "contract_errors": errors.append(point)
            if metric == "validated_response_duration": durations.append(point)
    require(errors and len(errors) == len(durations), "Missing or incomplete request/latency telemetry")
    require(all(math.isfinite(value) and value >= 0 for _, value in durations), "Invalid latency samples")
    require(all(value in (0, 1) for _, value in errors), "Invalid error samples")
    return sorted(errors), sorted(durations), dropped

def percentile(values, fraction=.95):
    values = sorted(values)
    require(bool(values), "No percentile samples")
    return values[max(0, math.ceil(fraction * len(values)) - 1)]

def rate_result(raw, rate):
    errors, durations, dropped = raw_metrics(raw, "hold")
    require(len(errors) >= rate * 299, "Hold shorter than declared 300 seconds or telemetry lost")
    p95, error = percentile([x[1] for x in durations]), statistics.mean(x[1] for x in errors)
    slo = read(TARGET)["slo"]
    passed = p95 <= slo["p95MillisecondsMaximum"] and error < slo["errorFractionExclusiveMaximum"] and dropped == 0
    return {"rate": rate, "passed": passed, "requests": len(errors), "p95Milliseconds": p95, "errorFraction": error,
            "dropped": dropped, "rawSha256": sha(raw)}

def aggregate(directory):
    directory, target = Path(directory), read(TARGET)
    results = read(directory / "runs.json")
    expected = [(x["replicas"], x["trial"]) for x in ladder_plan()["ladders"]]
    require([(x["replicas"], x["trial"]) for x in results] == expected, "Missing, reordered or repeated ladder")
    capacities, censored, non_monotonic = {1: [], 3: []}, False, False
    for ladder in results:
        runs = ladder["runs"]
        require(bool(runs), "Empty ladder")
        require([x["rate"] for x in runs] == target["arrivalRatesPerSecond"][:len(runs)], "Rate ladder changed")
        consecutive_miss = next((index for index in range(1, len(runs)) if not runs[index - 1]["passed"] and not runs[index]["passed"]), None)
        require(consecutive_miss is None or consecutive_miss == len(runs) - 1, "Ladder continued after two consecutive misses")
        require(len(runs) == len(target["arrivalRatesPerSecond"]) or consecutive_miss is not None, "Ladder stopped before two consecutive misses")
        values = []
        seen_miss = False
        for run in runs:
            measured = rate_result(directory / run["raw"], run["rate"])
            require(measured["passed"] == run["passed"], "Summary conflicts with raw samples")
            require(measured.get("dropped", 0) == 0, "Dropped arrivals invalidate capacity")
            if measured["passed"]:
                non_monotonic |= seen_miss
                if not seen_miss: values.append(run["rate"])
            else: seen_miss = True
        require(values, "No sustainable tested rate")
        censored |= runs[-1]["passed"]
        capacities[ladder["replicas"]].append(max(values))
    one, three = statistics.median(capacities[1]), statistics.median(capacities[3])
    evidence = read(directory / "environment.json")
    require(evidence["targetsSha256"] == sha(TARGET), "Target manifest changed")
    require(all(x == evidence["environmentFingerprint"] for x in (l["environmentFingerprint"] for l in results)), "Environment changed between ladders")
    telemetry = evidence.get("grafanaArtifacts", {})
    required_telemetry = {"grafana-compliance.json", "grafana-mysql.json", "grafana-gateway.json", "grafana-load-generator.json", "grafana-broker.json", "utc-clock.txt"}
    telemetry_complete = bool(evidence.get("telemetryReviewedBy")) and required_telemetry <= telemetry.keys() and all(
        (directory / name).is_file() and sha(directory / name) == digest for name, digest in telemetry.items())
    status = "INCONCLUSIVE" if censored or non_monotonic or not telemetry_complete else "PASS" if three / one >= 2 else "FAIL"
    result = {"status": status, "capacity1": one, "capacity3": three, "ratio": three / one, "scalingEfficiency": (three / one) / 3,
              "discreteRates": target["arrivalRatesPerSecond"], "rightCensored": censored, "nonMonotonic": non_monotonic, "grafanaComplete": telemetry_complete,
              "perTrial": capacities, "targetsSha256": sha(TARGET), "environmentFingerprint": evidence["environmentFingerprint"]}
    write(directory / "capacity.json", result)
    return result

def failover_result(raw, timeline):
    errors, durations, dropped = raw_metrics(raw)
    kill = timestamp(timeline["deleteRequestedAt"])
    ready = timestamp(timeline["replacementReadyAt"]) if timeline.get("replacementReadyAt") else math.inf
    start, end = kill - 120, kill + 300
    require(errors[0][0] <= start + 1 and errors[-1][0] >= end - 1, "Incomplete 120/300 second failover observation")
    errors = [(t, v) for t, v in errors if start <= t <= end]
    durations = [(t, v) for t, v in durations if start <= t <= end]
    require(not dropped, "Dropped arrivals invalidate failover")
    buckets = {}
    for t, value in errors: buckets.setdefault(math.floor(t - start), []).append(value)
    require(all(i in buckets for i in range(420)), "Missing one-second arrival/telemetry bucket")
    longest = current = 0
    for bucket in range(420):
        current = current + 1 if any(buckets[bucket]) else 0
        longest = max(longest, current)
    recovery_windows = []
    for second in range(30, 301):
        values = [v for t, v in durations if kill + second - 30 < t <= kill + second]
        require(values, "Missing rolling latency window")
        recovery_windows.append((second, percentile(values)))
    recovered = next((second for second, _ in recovery_windows if all(p <= 2000 for t, p in recovery_windows if t >= second)), None)
    error = statistics.mean(v for _, v in errors)
    passed = error < .01 and longest <= 10 and recovered is not None and recovered <= 60 and ready - kill < 180
    return {"status": "PASS" if passed else "FAIL", "errorFraction": error, "longestErrorBucketsSeconds": longest,
            "sustainedRecoverySeconds": recovered, "replacementReadySeconds": ready - kill if math.isfinite(ready) else None,
            "requests": len(errors), "rawSha256": sha(raw), "scope": "one pod only; no node/AZ/region availability claim"}

def authorize(config, duration=0, delete=False):
    require(config.get("approval", {}).get("owner") and config["approval"].get("budgetApproved") is True
            and config["approval"].get("accessConfirmed") is True, "Confirmed owner/window/budget/access approval required")
    require(timestamp(config["windowStartUtc"]) <= time.time() and time.time() + duration < timestamp(config["windowEndUtc"]), "Outside approved window or insufficient remaining time")
    require(not delete or config["approval"].get("podDeleteApproved") is True, "One-pod deletion needs explicit window approval")
    require(config["targetsSha256"] == sha(TARGET), "Frozen target manifest mismatch")
    require(config.get("fixtureApprovedBy") and config.get("loadedDatasetEvidence"), "M1/M4 approved loaded dataset evidence required")
    require(config["baseUrl"].startswith("https://") and "@" not in config["baseUrl"] and "?" not in config["baseUrl"], "Use approved HTTPS ingress without credentials/query")
    require(config.get("region") == "ap-southeast-1", "Use the reviewed AWS region")
    require(len(config.get("commit", "")) == 40, "Record full tested commit SHA")

def environment_scope(config):
    return {k: config[k] for k in ("awsAccount", "region", "windowStartUtc", "windowEndUtc", "commit", "imageDigest", "requestSha256", "loadedDatasetEvidence", "targetsSha256")}

def fingerprint(environment):
    return hashlib.sha256(json.dumps(environment, sort_keys=True).encode()).hexdigest()

def transient_tokens(config, requests, minimum_seconds):
    fixtures = read(requests)
    require(fixtures.get("datasetFormatVersion") == 2, "Regenerate superseded performance dataset before load")
    tokens = read(config["tokenFile"])
    require(set(tokens) == {c["request"]["organisationId"] for c in fixtures["cases"]}, "Tenant token coverage mismatch")
    # Expiry is only a preflight freshness check. Only the service's signature/issuer/audience validation authorizes a JWT.
    try:
        for token in tokens.values():
            payload = token.split(".")[1]
            claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
            require(claims["exp"] > time.time() + minimum_seconds, "Fresh owner-approved JWTs must cover the full trial")
    except (KeyError, IndexError, TypeError, ValueError) as error:
        raise ValueError("Supply fresh real JWTs through the private token file; no token is copied to evidence") from None
    return tokens

def kubectl(config, *args):
    return subprocess.check_output([config.get("kubectl", "kubectl"), "--context", config["context"], "-n", config["namespace"], *args], text=True)

def placement(config, replicas):
    deploy = json.loads(kubectl(config, "get", "deployment", config["deployment"], "-o", "json"))
    container = next(c for c in deploy["spec"]["template"]["spec"]["containers"] if c["name"] == "compliance")
    require(container["resources"] == {"requests": {"cpu": "1", "memory": "2Gi"}, "limits": {"cpu": "1", "memory": "2Gi"}}, "Fixed resource envelope changed")
    require(container["image"] == config["imageDigest"] and "@sha256:" in container["image"], "Pinned image digest changed")
    selector = dict(part.split("=", 1) for part in config["podSelector"].split(","))
    require(selector == deploy["spec"]["selector"]["matchLabels"], "Pod selector must exactly identify the reviewed Deployment")
    hpas = json.loads(kubectl(config, "get", "hpa", "-o", "json"))["items"]
    require(not any(h["spec"]["scaleTargetRef"]["name"] == config["deployment"] for h in hpas), "Disable HPA for fixed-replica comparisons")
    pods = json.loads(kubectl(config, "get", "pods", "-l", config["podSelector"], "-o", "json"))["items"]
    pods = [p for p in pods if not p["metadata"].get("deletionTimestamp")]
    require(len(pods) == replicas, "Unexpected active replica count")
    result, zones = [], set()
    for pod in pods:
        require(any(c["type"] == "Ready" and c["status"] == "True" for c in pod["status"].get("conditions", [])), "Pod not Ready")
        owner = next(o for o in pod["metadata"]["ownerReferences"] if o.get("controller"))
        replica_set = json.loads(kubectl(config, "get", "replicaset", owner["name"], "-o", "json"))
        require(any(o["uid"] == deploy["metadata"]["uid"] and o.get("controller") for o in replica_set["metadata"]["ownerReferences"]), "Pod is not owned by the approved Deployment")
        node = json.loads(kubectl(config, "get", "node", pod["spec"]["nodeName"], "-o", "json"))
        require(any(c.get("type") == "Ready" and c.get("status") == "True"
                    for c in node.get("status", {}).get("conditions", [])), "Node not Ready")
        labels = node["metadata"]["labels"]
        require(labels.get("eks.amazonaws.com/nodegroup") == "compliance" and labels.get("node.kubernetes.io/instance-type") == "c6i.large", "Wrong node placement")
        require(any(t["key"] == "dedicated" and t.get("value") == "compliance" and t["effect"] == "NoSchedule" for t in node["spec"].get("taints", [])), "Missing dedicated node taint")
        require(all(c["imageID"].endswith(config["imageDigest"].split("@", 1)[1]) for c in pod["status"]["containerStatuses"] if c["name"] == "compliance"), "Running image digest differs")
        zone = labels["topology.kubernetes.io/zone"]; zones.add(zone)
        result.append({"name": pod["metadata"]["name"], "uid": pod["metadata"]["uid"], "node": node["metadata"]["name"], "zone": zone,
                       "imageIds": [c["imageID"] for c in pod["status"]["containerStatuses"]]})
    require(replicas != 3 or len(zones) >= 2, "Three replicas must span two AZs")
    return result

def run_ladders(config_path, destination):
    config, destination = read(config_path), Path(destination)
    authorize(config)
    require(not destination.exists(), "Do not overwrite previous evidence")
    destination.mkdir(parents=True)
    request_file = Path(config["requestFile"]).resolve()
    require(sha(request_file) == config["requestSha256"], "Reviewed request fixture changed")
    environment = environment_scope(config)
    environment["environmentFingerprint"] = fingerprint(environment)
    environment["grafanaArtifacts"] = {}
    write(destination / "environment.json", environment)
    results = []
    for ladder in ladder_plan()["ladders"]:
        authorize(config, 600)
        kubectl(config, "scale", "deployment", config["deployment"], "--replicas=" + str(ladder["replicas"]))
        kubectl(config, "rollout", "status", "deployment/" + config["deployment"], "--timeout=180s")
        row = {"replicas": ladder["replicas"], "trial": ladder["trial"], "environmentFingerprint": environment["environmentFingerprint"], "runs": []}
        results.append(row)
        consecutive_misses = 0
        for rate in ladder["rates"]:
            authorize(config, 600)
            tokens = transient_tokens(config, request_file, 600)
            name = f"r{ladder['replicas']}-t{ladder['trial']}-rate{rate}"
            write(destination / (name + "-placement.json"), placement(config, ladder["replicas"]))
            env = dict(os.environ, DRY_RUN="false", BASE_URL=config["baseUrl"], REQUEST_FILE=str(request_file), AUTH_TOKENS_JSON=json.dumps(tokens),
                       RATE=str(rate), REPLICAS=str(ladder["replicas"]), TRIAL_ID=name, RESULT_FILE=str((destination / (name + "-summary.json")).resolve()))
            raw = destination / (name + "-raw.jsonl")
            with (destination / (name + ".log")).open("w", encoding="utf-8") as log:
                result = subprocess.run([config.get("k6", "k6"), "run", "--out", "json=" + str(raw.resolve()), "impact-preview.js"], cwd=ROOT / "perf/k6", env=env, stdout=log, stderr=subprocess.STDOUT)
            require(result.returncode in (0, 99), "k6/generator execution invalid; preserve evidence and investigate")
            measured = rate_result(raw, rate)
            measured.update(raw=raw.name, exitCode=result.returncode, completedAt=utc())
            row["runs"].append(measured); write(destination / "runs.json", results)
            require(measured["dropped"] == 0, "Dropped arrivals invalidate the generator run")
            require(placement(config, ladder["replicas"]), "Placement changed")
            consecutive_misses = 0 if measured["passed"] else consecutive_misses + 1
            if consecutive_misses >= 2: break
    return aggregate(destination)  # INCONCLUSIVE until reviewed Grafana artifacts are attached and hashed.

def run_failover(config_path, capacity_path, destination):
    config, capacity, destination = read(config_path), read(capacity_path), Path(destination)
    authorize(config, 460, delete=True)
    require(capacity["status"] == "PASS" and capacity["targetsSha256"] == sha(TARGET), "Verified uncensored capacity result required")
    require(capacity["environmentFingerprint"] == fingerprint(environment_scope(config)), "Failover must use the same measured environment/window")
    require(not destination.exists(), "Do not overwrite previous evidence")
    destination.mkdir(parents=True)
    before = placement(config, 3)
    write(destination / "placement-before.json", before)
    requests = Path(config["requestFile"]).resolve()
    tokens = transient_tokens(config, requests, 460)
    require(sha(requests) == config["requestSha256"], "Reviewed requests changed")
    raw = (destination / "raw.jsonl").resolve()
    env = dict(os.environ, DRY_RUN="false", EXPERIMENT="failover", RATE=str(capacity["capacity3"] / 2), CAPACITY_3=str(capacity["capacity3"]),
               BASE_URL=config["baseUrl"], AUTH_TOKENS_JSON=json.dumps(tokens), REQUEST_FILE=str(requests), REPLICAS="3", TRIAL_ID="pod-failover",
               RESULT_FILE=str((destination / "summary.json").resolve()))
    timeline = {"startedAt": utc(), "capacitySha256": sha(capacity_path), "targetsSha256": sha(TARGET), "deletedPod": before[0],
                "replacementReadyAt": None, "utcClockEvidence": config.get("utcClockEvidence")}
    with (destination / "k6.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen([config.get("k6", "k6"), "run", "--out", "json=" + str(raw), "impact-preview.js"], cwd=ROOT / "perf/k6", env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            # Wait for actual first completed arrival, then retain the declared 120-second baseline.
            first = None
            deadline = time.monotonic() + 15
            while first is None:
                require(process.poll() is None and time.monotonic() < deadline, "k6 did not start producing telemetry")
                if raw.exists():
                    for line in raw.read_text(encoding="utf-8").splitlines():
                        try: point = json.loads(line)
                        except json.JSONDecodeError: continue  # only the currently incomplete last line
                        if point.get("metric") == "contract_errors" and point.get("type") == "Point":
                            first = timestamp(point["data"]["time"]); break
                if first is None: time.sleep(.25)
            while time.time() < first + 120:
                require(process.poll() is None, "Generator stopped before deletion; no pod was deleted")
                time.sleep(.25)
            authorize(config, 310, delete=True)
            current = placement(config, 3)
            require({p["uid"] for p in current} == {p["uid"] for p in before}, "Pod set changed before deletion")
            timeline["deleteRequestedAt"] = utc()
            kubectl(config, "delete", "pod", before[0]["name"], "--wait=false")
            timeline["deleteAcceptedAt"] = utc(); write(destination / "timeline.json", timeline)
            ready_deadline = time.monotonic() + 180
            while time.monotonic() < ready_deadline and process.poll() is None:
                pods = json.loads(kubectl(config, "get", "pods", "-l", config["podSelector"], "-o", "json"))["items"]
                for pod in pods:
                    ready = next((c for c in pod["status"].get("conditions", []) if c["type"] == "Ready" and c["status"] == "True"), None)
                    if pod["metadata"]["uid"] not in {p["uid"] for p in before} and ready and not pod["metadata"].get("deletionTimestamp"):
                        timeline.update(replacementReadyAt=ready["lastTransitionTime"], replacement={"name": pod["metadata"]["name"], "uid": pod["metadata"]["uid"]})
                        break
                if timeline["replacementReadyAt"]: break
                time.sleep(1)
            write(destination / "timeline.json", timeline)
            code = process.wait(timeout=320)
            require(code in (0, 99), "Generator failed; retain evidence as invalid")
        finally:
            if process.poll() is None: process.terminate(); process.wait(timeout=15)
    write(destination / "placement-after.json", placement(config, 3))
    result = failover_result(raw, timeline)
    # Clock synchronization and Grafana exports must be reviewed before accepting the cloud claim.
    result["rawDecision"] = result["status"]
    result["status"] = "INCONCLUSIVE_PENDING_TELEMETRY_REVIEW"
    write(destination / "failover.json", result)
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("dataset"); p.add_argument("destination"); p.add_argument("--commit", required=True)
    sub.add_parser("plan")
    p = sub.add_parser("run"); p.add_argument("config"); p.add_argument("destination")
    p = sub.add_parser("aggregate"); p.add_argument("directory")
    p = sub.add_parser("failover-run"); p.add_argument("config"); p.add_argument("capacity"); p.add_argument("destination")
    p = sub.add_parser("failover-analyze"); p.add_argument("raw"); p.add_argument("timeline")
    args = parser.parse_args()
    if args.command == "dataset":
        import re
        require(bool(re.fullmatch("[a-f0-9]{40}", args.commit)), "Use a full source commit SHA")
        subprocess.run(["git", "-C", str(ROOT), "cat-file", "-e", args.commit + "^{commit}"], check=True)
        result = dataset(args.destination, args.commit)
    elif args.command == "plan": result = ladder_plan()
    elif args.command == "run": result = run_ladders(args.config, args.destination)
    elif args.command == "aggregate": result = aggregate(args.directory)
    elif args.command == "failover-run": result = run_failover(args.config, args.capacity, args.destination)
    else: result = failover_result(args.raw, read(args.timeline))
    print(json.dumps(result, indent=2))

if __name__ == "__main__":
    main()
