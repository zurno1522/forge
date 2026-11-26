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
