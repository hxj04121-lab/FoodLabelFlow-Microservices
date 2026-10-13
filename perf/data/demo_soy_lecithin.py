#!/usr/bin/env python3
"""Soy-lecithin demo data and expected results (G3-M1.2, STCN-90).

The demo uses the baseline seed as it is loaded by Specification and Formulation (see
split_baseline_seed.py): two suppliers, two manufacturers and three fixture groups of 20 products.

Change: Supplier Beta (org-supplier-beta) releases Chocolate Base V2 = V1 components + soy lecithin.
  - NO_ACTION_BASELINE_SOY products also use Supplier Alpha's Soy Carrier, so soy is already declared:
    The required-allergen conclusion is unchanged, so Phase 1 emits no finding.
  - REVIEW_REQUIRED_BASELINE_NO_SOY products use Chocolate Base V1 without any soy: Phase 1 expects
    REVIEW_REQUIRED (POTENTIAL), and Phase 2 after adoption expects CONFIRMED + REVIEW_REQUIRED.
  - NEGATIVE_CONTROL_NO_CHOCOLATE products do not use Chocolate Base: no finding at all.

Writes docs/swe5001/data/demo-soy-lecithin-expected.json. Re-running produces byte-identical output.
Usage: python3 perf/data/demo_soy_lecithin.py   (from the repository root)
"""
import json
from pathlib import Path

import split_baseline_seed as seed

OUT = seed.ROOT / "docs/swe5001/data/demo-soy-lecithin-expected.json"
MATERIAL = "mat_chocolate_base"
PREVIOUS = "spec_chocolate_v1"
EXPECTED = {
    "NO_ACTION_BASELINE_SOY": None,
    "REVIEW_REQUIRED_BASELINE_NO_SOY": "REVIEW_REQUIRED",
}


def build_expected():
    sql = seed.BASELINE.read_text(encoding="utf-8")
    suppliers = seed.rows(sql, "supplier")
    materials = {m["supplier_material_id"]: m for m in seed.rows(sql, "supplier_material")}
    components = [c for c in seed.rows(sql, "spec_component") if c["specification_version_id"] == PREVIOUS]
    products = seed.rows(sql, "product")
    formulas = seed.rows(sql, "formula_version")
    items = seed.rows(sql, "formula_item")
    seed.assign_organisations(suppliers, products)
    supplier = next(s for s in suppliers if s["supplier_id"] == materials[MATERIAL]["supplier_id"])

    current = {f["product_id"]: f["formula_version_id"] for f in formulas if f["is_current_released"] == "Y"}
    uses = {}
    for item in items:
        if item["supplier_material_id"] == MATERIAL:
            uses.setdefault(item["formula_version_id"], item["specification_version_id"])

    affected, unaffected = [], []
    for product in sorted(products, key=lambda p: p["product_id"]):
        formula = current.get(product["product_id"])
        row = {"productId": product["product_id"], "organisationId": product["organisation_id"],
               "fixtureGroup": product["fixture_group"], "currentFormulaVersionId": formula}
        if formula in uses:
            assert uses[formula] == PREVIOUS, f"{formula} pins {uses[formula]}"
            affected.append({**row, "pinnedSpecificationVersionId": PREVIOUS,
                             "expectedPhase1Outcome": EXPECTED[product["fixture_group"]]})
        else:
            unaffected.append(row)

    def count(rows, org, outcome=None):
        return sum(1 for r in rows if r["organisationId"] == org
                   and r["expectedPhase1Outcome"] is not None
                   and (outcome is None or r["expectedPhase1Outcome"] == outcome))

    manufacturers = sorted({p["organisation_id"] for p in products})
    adopt = next(r for r in affected if r["organisationId"] == "org-manufacturer-north"
                 and r["expectedPhase1Outcome"] == "REVIEW_REQUIRED")
    result = {
        "scenario": "soy-lecithin V1 -> V2 (STCN-90)",
        "source": "baseline seed as loaded by Specification/Formulation V2 migrations; regenerate with perf/data/demo_soy_lecithin.py",
        "step1_specificationChange": {
            "supplierOrganisationId": supplier["organisation_id"], "supplierId": supplier["supplier_id"],
            "materialId": MATERIAL, "previousSpecificationVersionId": PREVIOUS,
            "candidateComponents": [{"ingredientId": c["ingredient_id"], "rawPhrase": c["raw_phrase"]}
                                    for c in sorted(components, key=lambda c: int(c["sequence_no"]))]
                                   + [{"ingredientId": "ing_soy_lecithin", "rawPhrase": "Soy lecithin (emulsifier)"}],
        },
        "step3_expectedPotentialFindings": {
            org: {"total": count(affected, org), "REVIEW_REQUIRED": count(affected, org, "REVIEW_REQUIRED"),
                  "NO_ACTION": count(affected, org, "NO_ACTION")} for org in manufacturers},
        "step4_adoption": {"organisationId": adopt["organisationId"], "productId": adopt["productId"],
                           "expectedCurrentFormulaVersionId": adopt["currentFormulaVersionId"],
                           "replace": {"materialId": MATERIAL, "from": PREVIOUS, "to": "<candidate from step 1>"},
                           "expectedPhase2": "CONFIRMED + REVIEW_REQUIRED -> ReviewTask (BR-04)"},
        "affectedProducts": affected,
        "unaffectedProducts": unaffected,
    }
    return result


def main():
    result = build_expected()
    OUT.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"candidate-referencing={len(result['affectedProducts'])} unrelated={len(result['unaffectedProducts'])} "
          + " ".join(f"{org}={v}" for org, v in result["step3_expectedPotentialFindings"].items()))


if __name__ == "__main__":
    main()
