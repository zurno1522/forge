# COV-001: observed input coverage reporting

Implemented as an additive `inputCoverageAudit` section in `coverage.json` and an observed-input audit in `coverage.md`. This changes reports, not capture, policy, budgets or dataset schemas. The reporting section has `schemaVersion:1` and applies even to legacy input with no contexts.

## Definitions and denominators

| Field | Unit and meaning |
| --- | --- |
| `totals.records` | Observed parent decisions, standalone choice starts, nested target records and final search operations. They overlap and are not unique gameplay decisions. Standalone terminal events are not counted again. Incomplete, censored and post-outcome observations are included. |
| `observedOwners` | Breaks that same denominator into the four record-owner kinds. |
| `contextStatuses` | One value per observed record: `ABSENT`, `CAPTURED`, `PARTIAL` or `UNAVAILABLE`. `ABSENT` is missing capability, not an empty view. `CAPTURED` applies to the named partial profile. |
| `inputBoundaries` | Whether the current version-1 inventory establishes an input boundary for this owner. A known boundary does not prove a legacy producer captured it or that every branch made a fresh selection. `knownInputBoundaryInventoryV1` lists the inventory and timing. |
| `timings`, `stateBases` | Declarations per present context; absent contexts supply none. |
| `components` | Coverage declarations per present context, including unsupported stack and incomplete history. Captured contexts can still have unsupported components outside the profile's promises. |
| `omissionReasons` | Records carrying each explicit reason, deduplicated within a record. Different reasons can overlap. `omissionComponents` counts distinct component/reason pairs per record. |
| `optionBindings` | Version 1 offers diagnostic bindings only; model-facing selectable-option bindings are unsupported. This is a capability classification, not a count of successfully matched options. |
| `captureOmissionMetadata` | Parent declarations of nested-target/search capture omissions, distinguishing declared metadata from unavailable metadata. |
| `knownUnrecordedCaptures` | Explicitly reported parent capture omissions, separate from the observed-record denominator. They are capture attempts, not necessarily distinct gameplay choices. Missing declarations mean unknown, not zero. |
| `unobservedDecisionCount`, `completeLearningExampleCount` | Both remain null. Neither can be established from the current trace. |

`byBoundary` groups by owner and boundary. A missing group means no record was observed; it does not establish that the chooser was never invoked. `byTurn` groups by run, game, turn, player and timing basis, so repeated turn/player IDs in different games or runs remain distinct.

Parent views use the parent baseline turn. Standalone choices use the STARTED event turn; an absent event turn stays null, without borrowing it from another record or a context. Nested targets and search operations use the parent container turn with `PARENT_CONTAINER`, not an independently established selector time. Known lost captures are attributed only to the reporting parent, because their actual boundaries/times are unknown.

The CLI aggregates high-cardinality turn groups in its existing private disk spool and streams the report. The materialized library return remains available as before. A separate `coverage_audit.py` module must accompany the exporter.

## Validation and compatibility

The Python suite passes 121 tests. New regressions cover exact denominators, legacy absence, overlapping omissions, unsupported components despite captured profile status, unavailable budgets, nested/search timing, unknown turns, separate runs, disk/in-memory parity, parent-reported lost captures, incomplete timeout choices and producer-compatible announcement/masked-source contexts.

The boundary audit exposed two existing exporter mismatches, corrected here: `CONTROLLER_ANNOUNCEMENT` is accepted as `BEFORE_OPERATION` while `BEFORE_SELECTOR` is rejected; a masked-source context can be unavailable for `HIDDEN` without revealing the original boundary. Other unaudited contexts still require explicit unavailable coverage.

Eight saved real streams (simple/complex, old/new capability, full/filtered) validate. Dataset, choice, example, manifest and validation outputs remain byte-identical to the prior exporter. Existing coverage JSON fields remain equal; only the new audit is added. Full/filtered audit JSON and Markdown sections agree. Complete coverage Markdown still has source-specific reproducibility metadata, so full-file byte equality is not claimed. See [checks and counts](assessments/COV-001/validation.json).

No Java producer or capture implementation changed, so no new gameplay run or Java test result is claimed by this item. The saved streams exercise real output through the changed exporter; this is not a performance benchmark.

For the saved new Doctor Who/Ur-Dragon game, the denominator is 431 observed records: 56 parent decisions, 40 nested target records, six search operations and 329 standalone starts. Context statuses are 70 captured, 270 partial and 91 absent. This differs deliberately from the earlier count of 340 present contexts: the audit makes absent inputs and overlapping record types visible.

## Gap review and pause

COV-001's reporting scope is complete. Its deliberate limits are:

- Choices that never emit records cannot be counted. Explicit capture omissions establish only known lost capture attempts, not all unobserved choices.
- Version 1 has no model-facing option bindings or certified complete learning examples. COV-002 through COV-004 address missing information, not this report's counts.
- Nested/search timing and lost-capture attribution remain at the parent-container level where the wire schema supplies no independent time/boundary.
- Unsupported components are reported as declared; the exporter validates structure and consistency, not every engine permission or the total information used by a heuristic.
- More granular invocation accounting would require new producer instrumentation and a separate coverage design. It is not inferred here.

Pause here for review before OPT-001 (cost attribution). No allocation, copying, serialization, compression, sampling or budget optimization is included.
