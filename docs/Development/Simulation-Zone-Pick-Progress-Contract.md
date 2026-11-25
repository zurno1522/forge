# COV-006: ordinary zone-selector progress and prior returns

The [V9 outer operation](Simulation-Zone-Operation-Contract.md) is implemented and validated. This contract defines its next increment; it emits no new runtime field or permission. Implement child progress/history separately from the remaining engine branches and spell-ability families.

## Ownership and actual facts

Use the existing `ZoneChangeSelectionObserver` inside the exact ordinary-operation admission supplied by `AiChoiceRecorder`'s owned-callback hook. Never scan active records to find a plausible parent. The original engine callback runs once with the owned observer, or a no-op observer when unsupported. Start with the audited base `PlayerControllerAi`, matching the installed receiver by identity. A subclass or changed receiver cannot acquire history capability merely by returning cards.

A private callback-scoped frame restores its predecessor in `finally`; even a failed, hidden or unsupported nested operation masks the older zone frame. Retire permanently on exit, parent closure, interruption or loss of receiver identity, clearing raw references. No new sequence ID, database, lookup table or engine event is required. The existing `parentChoiceId` supplies diagnostic ownership.

`beforeSelector` announces one actual impending virtual dispatch. Save the offered object's identity, optionality, engine loop position and retained count. A visible base selector can claim this dispatch once, matching the actual chooser, ability, destination, origin-list object, offered object and decider. Equal IDs, equal list values or adjacent events are insufficient. An unrelated/nested probe cannot consume it twice. Hidden selectors never inspect the new metadata.

The observer's `returned` fact follows the original append for non-null results, or precedes the original cancellation/retry path for null. Keep three separate facts:

| Field | Meaning |
| --- | --- |
| `attemptIndex` | Zero-based ordinal of actual selector dispatches in this operation, including declined/retried attempts. |
| `priorNonNullReturnCount` | Non-null callback returns before this attempt, including duplicate returns. |
| `retainedCount` | The engine's current deduplicated collection size at this entry. |

The loop position can repeat after a declined retry. Retained count need not grow after a non-null duplicate, and the final pile can be cleared. Do not derive one count from another, reconstruct it from earlier trace rows, or equate it with moved cards. Automatic selections have no invented callback attempt/return; retained count remains the separately supplied engine fact if automatic and selector paths coexist.

## Counters, receipts and closure

Use nonnegative signed longs for the attempt ordinal and prior non-null-return count, independently of the engine's int loop position/retained count. Ordinal `Long.MAX_VALUE` can be represented once. Before any subsequent increment would wrap, make the affected progress unavailable with `COUNTER_OVERFLOW` and emit null for unknown counters; never repeat a saturated ordinal as an exact new position. Preserve unrelated exact engine facts and the original callback. Consumers must preserve integer precision, as with existing long-valued fields.

Retain at most 64 ordered non-null-return receipts: exact object plus detached Card ID/incarnation at the observed return. Preserve duplicates; null returns advance the attempt ordinal without appending a receipt. Stop receipt getters after the prefix fills while continuing exact primitive counts. Missing selector provenance makes receipt history unsupported rather than complete. Missing emitted messages alone do not: an admitted capture may have established provenance before publication failed.

Run getters outside parent publication monitors. A blocked getter cannot hold timeout publication. Discard a late value or Runtime failure after owner closure/retirement; do not append, revive a frame, emit an orphan or change live health. A live receipt fault is visible through that exact owner's accounting/observation health and leaves later receipt history unavailable. Preserve original result/exception; fatal errors follow the existing policy. The observer does not cap engine attempts, preempt a getter or alter retry behavior.

Bind each earlier receipt only against the new child's own frozen view, using exact object identity and the stored incarnation compared directly with the captured incarnation. Reuse the current public-association rules: stale/moved/LKI/foreign/private/unrepresented cards yield null. Do not copy outer local references or copy private names from diagnostics. Complete receipt capture can therefore produce an ordered vector of null identities, especially during a library search before its selected cards move. Richer private historical knowledge remains a separate knowledge/completeness design.

## Proposed V10 component and permission

Advance all producer envelopes consistently to schema/profile V10 when implemented, inheriting V1–V9 unchanged. At supported visible `CONTROLLER_ZONE_CHANGE_CARD` entries only, extend options with independently versioned `zoneSelectionProgress`:

```text
schemaVersion: 1
progressCoverage: COMPLETE | UNAVAILABLE
progressReason: null | UNSUPPORTED | COUNTER_OVERFLOW
attemptIndex: long | null
loopPosition: received int
priorNonNullReturnCount: long | null
retainedCount: received nonnegative int
requestedIterations: received outer int
receiptCoverage: COMPLETE | PREFIX | UNAVAILABLE
receiptReason: null | OUTSIDE_CAPTURED_PREFIX | CAPTURE_FAILED | UNSUPPORTED | COUNTER_OVERFLOW
priorReturns: ordered Card diagnostic references, at most 64
```

Complete progress requires exact non-null counters, null reason, nonnegative loop position and `priorNonNullReturnCount <= attemptIndex`. Do not impose a retained-count inequality against callback returns: retained cards are a distinct engine fact. Unknown counter fields require unavailable progress and an explicit reason. Complete receipts require complete progress, a receipt for every prior non-null return and null reason; prefix receipts require a shorter bounded vector and `OUTSIDE_CAPTURED_PREFIX`. Failed/unproved provenance is unavailable, retaining only any proved prefix. Counter overflow prevents a completeness claim. Absence means unsupported/unknown capability, never an empty sequence.

When progress and receipts are complete, permit a typed `ZONE_PICK_PROGRESS` prompt with coverage `RECEIVED_ZONE_PROGRESS_V1`:

```text
kind: ZONE_PICK_PROGRESS
minimumPicks, maximumPicks, optional: actual local single-pick request
requestedIterations, destination, origins: received ordinary-operation/request fields
sourcePlayer, decider: current child-local roles
attemptIndex, loopPosition, priorNonNullReturnCount, retainedCount: exact entry progress
previousReturns: equally long ordered child-current public references or null per receipt
```

These fields are invocation/progress knowledge, not a complete legal domain, committed outcome or whole-game history. Preserve received origin order/duplicates. A foreign/uncaptured non-null role makes the prompt unavailable. Prefix/failed/unknown history makes this prompt unavailable with a PROMPT omission; do not fall back to a falsely complete empty history. No offered-pool membership/count/order permission is added.

A final typed carrier identifies the audited permission before hypothetical suppliers are inspected. With no audited progress claim, retain the older V8 received zone-request permission, including its planning support. This differs from the ordinary entity-single planning fallback: copying V7's gate blindly would remove an existing V8 permission. With an explicit incomplete progress claim, emit the declared unavailable prompt rather than the older request as a silent fallback. Unrelated hypothetical suppliers remain uninspected. Hidden views remain masked and expose no progress component.

The V9 outer request/result and accounting schema stay unchanged. This increment adds no source boundary or inventory entry: the existing single-selector boundary already has invocation accounting and its own entry. Global history remains incomplete; every example remains `trainingReady=false`.

## Decoder and coverage gates

Require the new component's exact fields/types/profile and the child's own selector entry. Its parent must be an earlier still-open ordinary zone operation, with matching chooser, ability/source and received request fields. Resolve source/decider roles against the child's bindings, not the parent's local references. The component must not be accepted at entity selectors, direct callbacks without an owner, other engine branches or older profiles.

For each recorded parent, track only bounded scalar validation state in the existing choice spool: the last observed exact attempt ordinal and return count/outcome. Exact ordinals must strictly increase, allowing gaps for missing rows. The prior non-null-return count cannot decrease or increase by more than the attempt gap. Where consecutive attempts and a surviving terminal prove the prior callback completed/declined, require the corresponding one/zero count increment. An absent terminal does not prove no return. An observed selector ERROR prevents a later complete progress claim under that same audited operation. Never construct model history from these validations.

Missing earlier selector rows may be tolerated; a missing outer start cannot establish ownership. Do not require every earlier selector message to survive and do not apply V7's final-retained-count check. A valid outer retained count can be zero after successful returns. Check each non-null projected Card reference against the raw receipt and current public binding/incarnation; unresolved entries remain null. Reject invented ownership, stale/private links, false completeness, repeated/reversed exact ordinals, inconsistent received arguments, unknown fields and false readiness.

Add a conditional progress report only when components are present. Count observed components, exact/unavailable progress, receipt coverage and overlapping receipt/projection entries. Missing operation counts and unobserved attempts remain unknown. Do not count repeated vectors as unique choices/returns or keep an unbounded in-memory set of parent IDs. Legacy reports remain byte-identical.

## Implementation tests and remaining ownership

Exercise actual retries with repeated loop positions; declines followed by retry or exit; duplicate returns with unchanged retained count; discarded final piles; nested/failed intermediate admission; delegated/changed controllers; one-time claims; publication loss without reconstructing history; live/late receipt faults and blocked-getter closure; hidden/off/copy/abandoned bypass; fresh current associations; the 64-receipt prefix; and counter boundaries seeded near `Long.MAX_VALUE` without running enormous loops. Preserve original callbacks, results, exceptions, engine event order and source/collector accounting. Test that a nested unsequenced planning request keeps V8 permission, and that incomplete supported history does not masquerade as that fallback.

Compare full/filtered exports, saved legacy bytes and fresh search-heavy games. Describe private/unresolved identity samples honestly; public, overflow and closure cases may need focused engine/unit fixtures. Review current/prior defects before proceeding. Named/EACH, Heist and Draft still require their own operation semantics. Spell-ability choices follow. COV-004 owns completeness requirements; richer private historical knowledge remains a separate later knowledge design; COV-007 payment/X; COV-008 availability; OPT-005 expanded observer/receipt cost and retention. No cap increase, optimization, reward or learning policy is bundled here.

Design/source hashes: [baseline evidence](assessments/COV-006-zone-progress/design-sources.json). The source review confirms that both hooks already exist, the V9 adapter currently passes a no-op observer, and V7 receipt/count assumptions cannot simply be copied. This design adds no runtime test or V10 output. Its implementation gates above are not defects claimed in the completed V9 outer operation.
