# COV-006: assessment-context attribution and admission hardening

Assessment-context lookup no longer holds the parent publication monitor while reading ability roots or candidate IDs. It snapshots the originating assessment, performs those getters outside the monitor, then checks both parent closure and assessment identity before assigning or returning an association. A late result cannot borrow a replacement assessment's ID. Normal decision/assessment IDs, candidate indices and wire fields are unchanged.

A closed assessment remains the admission owner even when it can no longer return a context map. Otherwise a closure race could turn the old child capture into an unrelated standalone choice. The standalone constructor retires such admission before reading source visibility/options/input. It emits neither an orphan start nor a terminal. Original callbacks still run once and keep their result/exception behavior.

Eligibility/metadata failures use the already resolved owner references to distinguish authoritative closure from an active observation failure. Error handling does not call new source getters to rediscover ownership. A late failure after closure cannot poison game observation health; a failure during active capture remains visible. No new registry, ID, profile, budget or public protocol field is introduced.

## Validation

Five initial regressions reproduce the old monitor contention by timing out parent closure or assessment replacement while root lookup is blocked. The final focused run passes 88 tests with no failures or skips. Expanded cases block root and ID getters, return or throw after closure, block later metadata lookup, change assessment while lookup is pending, preserve callback counts and reject orphan events. An active metadata failure remains visible with its original exception identity while preserving the original callback. Existing decision, mode, target, input and source-accounting units also pass.

Two fresh simple/numeric games complete normally. Their full/filtered datasets, choices, examples and validation match byte for byte; coverage agrees except source-record counts. Normalized engine events match the preceding V4 baseline, and each game preserves two shuffles before `GAME_STARTED`. Export code and message schemas are unchanged. The full run of 2,622 desktop plus 23 game and 23 GUI tests predates this small hardening increment; this source claims the focused run and fresh-game/export checks, not a new full-suite run.

## Review and immediate ownership

The reproduced attribution/admission hazard is closed by this increment. The review does not certify that the entire parent recorder is free of engine getters under its monitor. Existing assessment/detail bookkeeping, candidate snapshots and land enumeration/search tagging still need a bounded closure review before modes. That is an immediate COV-006/HARD-001 follow-up, not an optimization or permission to defer a reproduced timeout defect. Final selected-action/provenance publication also needs its ownership contract distinguished from pre-publication capture before changing its locking; no duplicate-finish or missing-parent delivery guarantee is invented here.

After that review/fix, mode operation/selector integration and remaining family arguments remain COV-006. Inner payment/X belongs to COV-007; sustained availability belongs to COV-008. OPT-005 retains broader overhead/peak measurement. No AI policy is rerun or restored, and no new training-readiness claim is made.

Evidence: [tests and initial failing regressions](assessments/COV-006-assessment-hardening/tests.json), [fresh-game/export checks](assessments/COV-006-assessment-hardening/validation.json), [commands](assessments/COV-006-assessment-hardening/commands.json) and [source hashes](assessments/COV-006-assessment-hardening/implementation-sources.json).
