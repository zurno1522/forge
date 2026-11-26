# COV-006: controller-owned spell-choice progress

V14 records the received single-selector role. This increment records progress and a bounded prefix of prior non-null returns within the actual base `chooseSpellAbilitiesForEffect` operation. It does not group engine-level VillainousChoice repetitions or AssignGroup's per-player selectors. Those are separate engine operations; adjacency and equal IDs establish no history owner.

## Exact operation ownership

Use the outer recorder's own admitted, visible `Capture`, never ambient admission. A private thread-local frame is installed only for the exact base controller currently installed on the chooser. Even an unsupported, hidden, failed or unrecorded nested operation masks an older frame. Restore the prior frame in `finally`; retire and release receipts/announced arguments on exit, interruption, controller change or owner closure. No game API, persistent registry or cross-operation receipt storage is needed.

Immediately before the existing virtual single call, announce the exact remaining-list and parameter-map identities and the current retained-list size. Immediately after the original remove/append, report the returned value and actual retained size; report null before exiting. Preserve the original copy/do-while/removal/append/stop order, including nonpositive requests, duplicate/out-of-pool returns and original errors. A single visible base selector may claim that announced attempt once, with matching chooser, ability, receiver, list and map identities. Unwitnessed calls cannot become proved returns. Never replay policy or inspect results on an unadmitted path.

Keep zero-based attempt and non-null-return counters as longs with explicit overflow refusal; retained size and received requestedSelections remain ints. Counts describe callbacks, not legality or effect application. Retain at most 64 detached SPELL_ABILITY reference receipts, in actual return order with duplicates. Read receipt identity only after return and before another selection. Do not retain returned engine objects, infer an ability's source/definition from raw IDs, or read the 65th return's identity. Live getter faults remain visible observation failures; late values/faults after closure are discarded without blocking closure.

## V15 wire and permission

Use V15 globally; source slots, entry inventories through V6 and accounting schema remain unchanged. A claimed single-selector entry may add `spellSelectionProgress` to diagnostic options:

```text
schemaVersion: 1
progressCoverage: COMPLETE | UNAVAILABLE
progressReason: null | UNSUPPORTED | COUNTER_OVERFLOW
attemptIndex: long | null
priorNonNullReturnCount: long | null
retainedCount: nonnegative int
requestedSelections: int
receiptCoverage: COMPLETE | PREFIX | UNAVAILABLE
receiptReason: null | OUTSIDE_CAPTURED_PREFIX | CAPTURE_FAILED | UNSUPPORTED | COUNTER_OVERFLOW
priorReturns: ordered SPELL_ABILITY references, at most 64
```

Complete base-loop progress has attemptIndex = priorNonNullReturnCount = retainedCount. A complete receipt prefix contains exactly that many receipts. Prefix truncation and unsupported/unwitnessed/capture-failed history remain explicit; they must never become complete again within the frame. Incomplete history does not mean no earlier returns.

When both the V14 role receipt and progress/history are complete, permit `RECEIVED_SPELL_PROGRESS_V1`:

```text
kind: SPELL_ABILITY_PICK_PROGRESS
affectedPlayer: this view's Player reference | null
requestedSelections: int
attemptIndex: nonnegative long
priorNonNullReturnCount: nonnegative long
retainedCount: nonnegative int
previousReturns: ordered null entries, one per proved earlier return (at most 64)
```

This profile deliberately supplies no ability identity in the feature projection. Generated modes are not necessarily stack objects, and Card/Player incarnation proof cannot prove ability identity. The null vector preserves return occurrence/order while diagnostic receipts support audits. A future independently audited static-definition/current-stack association belongs to COV-004; raw IDs, option indices, offered membership/order/count, scripts and names remain excluded. Do not silently turn a diagnostic ability ID into a local feature reference.

Incomplete claimed progress/history makes the prompt unavailable with its actual reason; it must not fall back to a known empty history or V14 prompt. A direct/unclaimed selector keeps V14's current-role request, with history unknown. Unsupported role receipts remain unsupported even when progress is known. The final typed carrier permits planning arguments but keeps `PLANNING_WORKING_STATE`; unrelated hypothetical suppliers remain uninspected. Charge one prompt unit plus one per projected receipt.

The decoder requires V15 and the own single-selector entry, exact fields/types/bounds and the recorded outer owner with matching actor/ability/source/requestedSelections. Validate monotonic attempt/count progression, complete count equality, persistent incompleteness and observed prior terminal constraints without requiring missing selector rows to exist. Validate the bounded receipt prefix against preceding captured returns where available; do not invent missing rows or infer source ownership from event proximity. Disk-backed and materialized validation must agree. Old profiles/inventories/exports remain unchanged. All examples remain `trainingReady=false`.

## Gates and gap ownership

Test real base-controller iteration, duplicate/nonmember returns, declines/empty-list errors, exact virtual dispatch, original return/error identity, direct fallback and nested masking/restoration, wrong receiver/list/map refusal, failed admission, hidden/off/copy paths, bounded receipts without a 65th getter, long overflow, and live/blocked getter closure. Validate actual emitted messages, full/filtered games, legacy bytes, accounting and shuffles before GAME_STARTED. Review current/prior defects before continuing.

COV-006 owns this controller-local progress and the remaining direct argument/mode semantics audit. COV-004 owns ability associations, full option/domain knowledge and longer history; COV-007 payment/X; COV-008 availability; OPT-005 scoped frames, detached bounded receipts and progress projection costs. No cap increase or runtime optimization belongs in this increment.

## Implementation, validation and gap review

V15 implements the bounded controller-owned frame, before/after hooks, diagnostic progress component and typed planning carrier. A visible selector consumes its one-time dispatch claim before option/request getters; a failed metadata capture cannot leave that attempt available to a nested probe. Entity and zone selectors already consume their claims in that order. The counter guard refuses complete base-loop progress after an impossible continuation following decline. Independent later stack membership is not required to match an earlier receipt's stack-instance snapshot; stable raw ability IDs provide diagnostic consistency only, never a feature association.

Final focused validation passes 132 Java cases, including twenty-one new history cases. They exercise actual base-loop order and duplicates, unchanged nonmember returns, direct fallback, nested restore/one-time probes, exact receiver/list/map matching, failed operation and failed selector admission, unwitnessed history, impossible post-decline continuation, 64-receipt bounds without later identity reads, live faults, four blocked-getter closure combinations, overflow, changed/subclass receivers, missing collected rows, disabled nested masking, three bypasses and per-receipt planning work. Python passes 242 tests, including nine new methods for positive roles/progress, duplicate receipts, missing selector rows, prefix/overflow/partial semantics, immutable earlier receipts despite later stack association, disk/materialized parity and strict capability/owner/count/feature rejection.

The initial full reactor ran 2,887 desktop tests (six skips) with one failure, plus 23 passing game and 23 passing GUI tests. The failure was the existing Monte Carlo `scavengeOntoACreature` scenario choosing no action. A controlled harness reproduces it with both initial V15 and four pre-V15 AI classes over the same other classes, with tracing disabled: each fresh JVM fails seed 1 and passes seeds 2–12. This establishes pre-existing scenario instability and is consistent with cold/deadline sensitivity; it does not prove a unique timing cause or engine/card rules bug. The full suite is not reported green. The final claim-order/count-guard changes and two additional regressions are covered by the passing focused build, not falsely attributed to that earlier full run. Source manifests distinguish those builds. Search/scenario reliability is logged separately for investigation; OPT-005 retains calibration and deadline-sensitive measurement.

All seven export artifacts remain byte-identical for 190 saved V1–V14 streams. Three final-build games (Sieges, ordinary search, Doctor Who/Ur-Dragon with Monte Carlo) finish normally. Full/filtered datasets, choices, examples and validation match; coverage agrees except source-record totals. Accounting reconciles with zero observation failures and each game retains two shuffles before `GAME_STARTED`. Ordinary and Siege engine sequences match V14. The Monte Carlo sample makes no deterministic or universal deadline-neutrality claim. Actual engine/controller excerpts for GenericChoice, VillainousChoice, zero request and decline validate eighteen lifecycle events/nine operations and selectors. These excerpts are not complete-game traces.

The current increment's demonstrated gaps are fixed. Review also identifies an earlier COV-006 entity-wrapper fast path that may skip masking a disabled nested operation. Reproduce and fix that separate ownership defect before proceeding to the next design item; it does not belong to a future performance task. The remaining COV-006 direct numeric/entity parameter and mode-input audit follows. Static ability definitions/associations, full domains and engine-level history outside this controller loop remain COV-004; payment/X COV-007; availability COV-008; scoped frames, bounded receipts and projection/export-state costs OPT-005. All examples remain unready.

Evidence: [tests and full-suite limitation](assessments/COV-006-spell-progress/tests.json), [final games](assessments/COV-006-spell-progress/validation.json), [actual game messages](assessments/COV-006-spell-progress/actual-game-examples.json), [engine/controller messages](assessments/COV-006-spell-progress/engine-resolution-examples.json), [excerpt validation](assessments/COV-006-spell-progress/engine-resolution-validation.json), [legacy exports](assessments/COV-006-spell-progress/legacy-compatibility.json), [final sources](assessments/COV-006-spell-progress/implementation-sources.json), [initial full-run sources](assessments/COV-006-spell-progress/initial-reactor-sources.json), [scavenge triage](assessments/COV-006-spell-progress/scavenge-triage/summary.json) and [commands](assessments/COV-006-spell-progress/commands.json).
