# M2 UNRESOLVED example source alignment (STCN-29)

Responds to M1 PR8 owner feedback on the supplied `0fb42667d6d94fbab66ec29347d83e7b1a00d0e5` candidate. The old M2 example invented component/item IDs on the unrelated soy-lecithin versions. The example now pins the actual M1 spec_003_v2 and formula_003_v2, including both actual formula lines pointing to its one UNMAPPED component. The PLACEHOLDER ingredient ID, raw phrase and reason match the source exactly.

The supplied FormulaPublished has adopted that specification, so this fixture represents CONFIRMED rather than POTENTIAL. Its occurrence follows the actual source publication; the existing missing/declared allergen sets remain illustrative M2 test data, not a derived dataset or measured runtime result. Label_003_v1 remains an illustrative M2 label reference: no M4 provider example is fabricated.

The two M1 provider fixtures are exact pinned Git blobs with a source manifest. They are review inputs, not an assertion that PR8 was merged or that runtime integration exists. `npm run check:events` runs the original 24 schema/semantic cases plus 14 source traceability cases: two source hashes, exact two-line lookup/redelivery, and negative item/component/version/raw phrase/reason/ingredient/duplicate/phase/time checks. No schema shape, role, visibility, scanner, threshold or dependency version changes.

M4 review and ordered approved integration remain required. This follow-up does not mark STCN-2/29 Done or freeze contracts v1.
