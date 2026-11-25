# COV-006/HARD-001: parent metadata capture hardening

Assessment setup/detail, evaluated-candidate snapshots, land enumeration and land search tagging now read engine metadata outside the parent publication monitor. The monitor protects owned bookkeeping and admission checks. A blocked getter or iterator can no longer hold up the evaluation timeout handler's `interrupted` call or parent closure.

## Capture and failure behavior

`beginAssessment` reads the root ID before taking the monitor and assigns an assessment only while open. `detail` and `evaluated` remember the originating assessment, capture metadata outside the monitor, then check both closure and assessment identity. Repeating an assessment for the same ability still creates a new owner: stale detail cannot attach to the replacement merely because its root ID is equal.

Land enumeration traverses its offered iterable once into an owned batch. Admission preserves order, duplicate entries, heuristic flags and existing candidate fields. A failed batch is not partially admitted. Land search tagging captures offered source/ability identities first, then updates owned candidate rows. It retains the previous last-matching-offer behavior, including duplicate candidate rows. No new limits or model-facing option permissions are introduced.

Active runtime observation failures are reported through existing game observation health and do not escape these metadata helpers into AI policy. Late runtime failures after parent closure are ignored. Genuine `AITimeoutTracker.Expired` remains AI deadline control flow and is rethrown without being relabeled as a trace failure. Fatal `Error` handling is unchanged. Error messages are not added to decision payloads.

These records describe observed, partial candidate metadata. Missing rows or unobserved search tagging do not prove legality or exclusion from all alternatives. This increment does not add a whole-parent invocation denominator or certify metadata completeness after a reported capture failure. Existing health reporting remains authoritative for capture faults; all training-readiness limits remain in force.

## Increment review and ownership

The capture increment closes the six reproduced getter/iterator boundaries identified in the [parent inventory](Simulation-Parent-Capture-Boundaries.md). Review added real deadline propagation, repeated-assessment isolation, one-pass land ordering/duplicate matching and partial-batch refusal to the original closure/failure regressions. No new wire fields, IDs, registry, sampling policy or traversal-budget changes are included.

Selected-action projection, provenance preparation, action-registration transactions and publication failure handling remain the next separate increment under COV-006/HARD-001. This change does not claim that `finish` or the entire action tracker is free of engine getters under monitors, or that a publication attempt acknowledges delivery. Those boundaries must be closed before modes. COV-007 retains inner payment/X work, COV-008 sustained availability, and OPT-005 updated full-path/peak measurement.

## Validation

The final focused run passes 125 tests. Eighteen initial regressions fail against the old code: twelve blocked closure cases and six live metadata failures that escape into policy. The final suite adds genuine deadline propagation at each boundary, replacement assessments with the same ability/root ID, one-pass ordering and duplicate-offer behavior, and refusal of a partially built land batch.

The final full run passes 2,660 desktop tests (six existing skips), 23 game tests and 23 GUI tests, with no failures/errors. Core and AI modules compile but have no tests. All 181 exporter tests pass. An earlier full run failed `FlatMonteCarloStrategyTest.drawsIntoTheCounterspellInResponseToAThreat`, returning null instead of Brainstorm with tracing disabled. The unchanged 25-test strategy class and subsequent full run pass. Timed-search variation is suspected, but the cause is not established; no policy, assertion or timing budget was changed.

Three fresh simple, numeric and Doctor Who/Ur-Dragon games finish normally and validate. Full/filtered datasets, choices, examples and validation agree byte for byte; coverage agrees except source-record counts. Each game retains two pregame shuffles. Simple/numeric normalized engine events match the preceding V4 baseline; Monte Carlo does not supply a deterministic comparison. No full-domain or training-readiness claim follows from these samples.

Evidence: [tests, including the earlier strategy failure](assessments/COV-006-parent-capture/tests.json), [fresh-game/export checks](assessments/COV-006-parent-capture/validation.json), [commands](assessments/COV-006-parent-capture/commands.json) and [source hashes](assessments/COV-006-parent-capture/implementation-sources.json).
