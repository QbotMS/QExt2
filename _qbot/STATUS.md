# QBot Audit Status — 2026-05-23

## Execution Summary

| Field | Value |
|---|---|
| Task | NEXT from `_qbot/TASKS.md` |
| Type | Audit (no code modification) |
| Production code modified | NO |
| Files generated | `_qbot/AUDIT.md`, `_qbot/STATUS.md` |
| Build result | PASS |
| Test result | PASS |

## Rule Compliance Check

| Rule | Status |
|---|---|
| 1. Read TASKS.md before changes | DONE |
| 2. Execute only NEXT task | DONE |
| 3. No unrelated architecture rewrite | DONE (read-only) |
| 4. No DataType renames | DONE |
| 5. DYN static-only | N/A (no DYN in current codebase) |
| 6. DYNMSG/DYNMSGv2 separate from DYN | N/A |
| 7. LIVE kept as compact 3x2 | N/A |
| 8. No hardcoded secrets | **VIOLATION** — token in `local.properties` (see AUDIT.md §9) |
| 9. Update STATUS.md after task | DONE |
| 10. Inspect and report instead of guessing | DONE |

## Audit Deliverables

- [x] All Kotlin/Java files catalogued (18 main + 2 test)
- [x] All XML layout/resource files catalogued (4 layouts + 3 config + 1 strings + 9 drawables)
- [x] Registered DataTypes identified (3: qext2-primary, qext2-active, qext2-stats)
- [x] Action receivers identified (1: StatsActionReceiver with 3 actions)
- [x] Gate open implementation documented (GateOpenClient + KarooSdkHttpCaller)
- [x] Rate limit implementations documented (4 levels: PRIMARY render, GATE debounce, CARB debounce, CARB idempotence)
- [x] Font verification complete: **CORRUPTED** (HTML, not TTF)
- [x] Build/test results captured

## Key Findings

1. **Font files are corrupted HTML** — both roboto_condensed_*.ttf files contain HTML documents, not font data. They are not referenced anywhere. Safe to delete.
2. **Hardcoded token** in `local.properties:4` (`QEXT_GATE_TOKEN=0987654321...`). This violates rule #8.
3. **GATE button** is now wired to CARB UNDO in `StatsDataType.bind()` (requestCode 302 uses `ACTION_CARB_UNDO`), while the `ACTION_GATE_TAP` handler in `StatsActionReceiver` is still defined but unreachable via the GATE button.
4. **`field_primary_fallback.xml`** is an unused layout not referenced by any DataType.
5. **`demoSnapshot()`** and **`bindUnit()`** methods in `StatsDataType.kt` are defined but never called.

## Next Task

Ready for next task from `_qbot/TASKS.md`.
