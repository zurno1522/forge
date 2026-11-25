# COV-006: prior entity selections in the next selector's own view

The V6 outer operation is implemented and validated. This contract defines the next increment; it adds no runtime permission by itself. The source baseline is `f40645bb13c`. The controller's loop, not the presence of earlier trace events, determines which non-null values actually returned and were appended to `selecteds`.

## Scope and exact ownership

Start with the audited base `PlayerControllerAi` implementation, its controller-owned repeated loop and its ordinary single-entity selector. A subclass/override can return values without using the base single-recorder path, or can perform extra probes before its final return. Preserve that virtual dispatch and original result; do not grant an unaudited override a selector-history capability. Direct single requests and engine-owned zone-change loops remain history-unknown. No production subclass was found in the AI module during this audit; tests must still verify safe behavior when one is supplied.

The repeated-operation adapter needs the exact admission owner created for its own invocation. Add a narrow package-local observer hook that supplies that owner to the adapter's callback, or null when recording was unsupported, hidden, abandoned or failed before admission. Do not find an owner by scanning the currently active records: after an intermediate capture failure, the nearest active record may be an older operation with the same ability. The owner supplies an open check and live observation-failure reporting using already captured state. It must perform no fresh ability/root/controller getters while holding a parent publication monitor.

A private callback-scoped frame retains only this operation's primitive bounds/counts, receiver/request identity and a bounded receipt prefix. Install it around the original callback, restore the prior frame in `finally`, and retire it permanently on exit. No new sequence ID, lookup table, database or persistent registry is needed. The existing `parentChoiceId` provides diagnostic ownership; it does not itself grant history as a feature. A missing/closed admission owner cannot borrow another parent's scope.

Bracket each actual virtual selector dispatch with its original remaining-collection object and primitive local arguments. The audited base selector may claim that dispatch once at its visible entry. Match the actual chooser, request/ability object, offered collection and local arguments, rather than equal IDs. Nested or unrelated selector calls cannot claim the same dispatch a second time. A hidden selector cannot inspect/claim receipt metadata through this path.

After the virtual call returns non-null, keep the original removal and append order. Then observe the actual return and the authoritative `selecteds.size()` with a helper that cannot replace the callback result or exception. This remains necessary even when an override bypasses the single-recorder helper. Unsupported/missing selector provenance stays unknown; a sequence is never reconstructed from pool differences or event adjacency.

## Receipts and bounds

Retain at most 64 successful-return receipts per active operation, using the existing prompt-value bound. Each receipt owns an exact object reference and its detached diagnostic identity/incarnation at that return. Stop reading further object getters once this prefix is full. Do not cap the engine's result list, change its iteration limit, deduplicate returns or clamp values. An authoritative count beyond the prefix remains known while the history is explicitly incomplete.

Receipt getters and current-state projection run outside parent monitors. A blocked receipt getter cannot prevent parent timeout publication. If its return or Runtime failure arrives after that owner closes, discard it; do not append an obsolete receipt, revive a frame, emit an orphan or poison current health. A live failure is visible through that owner's source accounting and game observation health; future history remains unavailable. Fatal errors retain the existing policy. The frame does not bound engine callback/thread counts or preempt an already running getter.

A direct current association requires the exact receipt object and incarnation to agree with the new selector's frozen snapshot. Compare the stored incarnation directly with the captured snapshot incarnation, as well as current public eligibility. A matching live getter alone is insufficient. Private, stale, moved, LKI, foreign or otherwise unproved associations remain null. Player identity must match a current player object. Do not copy outer-view local references or infer hidden continuity from raw IDs.

## V7 permission and wire contract

Version the new permission as schema/profile V7 for all producer contexts, including hidden/unavailable cases. V1–V6 retain their meanings. Keep global history coverage `INCOMPLETE`: this is only the successful-return history of one controller operation, including planning work where declared by the selector's own state basis.

For an audited, visible repeated single-selector entry, extend diagnostic `options` with an independently versioned `selectionSequence` component:

```text
schemaVersion: 1
coverage: COMPLETE | PREFIX | UNAVAILABLE
minimumTotal, maximumTotal: exact received outer bounds
priorSelectionCount: authoritative successful-return count before this pick
priorSelections: bounded ordered diagnostic card/player references
reason: null | OUTSIDE_CAPTURED_PREFIX | CAPTURE_FAILED | UNSUPPORTED | INTERRUPTED
```

`COMPLETE` has a full receipt list of that count and null reason. `PREFIX` has a shorter bounded prefix and `OUTSIDE_CAPTURED_PREFIX`. Failed or unproved receipt provenance is `UNAVAILABLE`, preserving only any already proved prefix and the known count. Absence means no supported sequence capability, rather than an empty sequence. This component requires the new producer profile and the child's own entry component. Hidden choices retain the existing masked options and reveal none of it.

For complete receipt history, project a typed `ENTITY_PICK_SEQUENCE` in `policy.prompt`, with prompt coverage `RECEIVED_ENTITY_SEQUENCE_V1`:

```text
kind: ENTITY_PICK_SEQUENCE
minimumPicks, maximumPicks, optional, relatedPlayer: actual local single-pick request
minimumTotal, maximumTotal: received outer bounds
priorSelectionCount: successful returns before this pick
previousSelections: equally long ordered current local references, or null per unresolved receipt
```

Preserve duplicates/order. A complete receipt history can contain unresolved identities; those nulls do not become an empty history. A truncated, failed or unsupported history does not produce this prompt: mark it unavailable with an explicit omission. Do not fall back to pretending the missing history is complete. Scalar request bounds and safe current association do not authorize the current offered pool's membership/count/order or a legal domain.

A final typed carrier identifies this new planning permission before a hypothetical supplier is inspected. A carrier without an audited sequence does not grant planning permission to the older ordinary `SINGLE_ENTITY` request. Other unsupported hypothetical prompt suppliers remain uninspected. The current selector's own fresh snapshot determines all local references; this is not a borrowed outer baseline.

The decoder checks exact component/profile fields and scalar types, prefix/count declarations, own entry/timing, actual local optionality/role, and complete prompt/diagnostic agreement. It checks that `parentChoiceId` identifies an earlier, still-open recorded outer entity operation with the same chooser/request and matching bounds. It must not require that every earlier return has its own surviving message: receipts come from actual returns, and messages can be missing. Where a current local reference is supplied, validate its identity/incarnation against the raw receipt and the current public snapshot bindings. Reject private/stale links, invented counts/ownership, unknown fields and false completeness. Every example remains `trainingReady=false`.

## Validation gates and remaining work

Tests must cover real multiple iterations with fresh child-local history; actual virtual returns that bypass a serializer; unsupported override behavior; nested/delegated isolation; one-time dispatch claims; receipt duplicates; zero/empty cases; private/stale/LKI/foreign associations; the 64-receipt prefix; immutable events; hidden/off/copy/closed behavior; and blocked receipt getters with both late return and late Runtime failure. Live capture failures remain visible. Exact source accounting and original callback/exception behavior must reconcile.

Validate current full/filtered output, preserve the six V6 game samples and legacy report bytes, and retain shuffles before `GAME_STARTED`. A new source hook does not make uninstrumented invocations observable. The new frame/receipt/carrier costs belong to OPT-005; no raised budget or optimization is bundled here.

Review this increment before proceeding. Zone-change request/repetition and spell-ability choice families remain separate COV-006 work; engine-owned repetitions need their explicit engine/controller contract. COV-004 retains completeness requirements, COV-007 inner payment/X and COV-008 sustained availability.

Design/source hashes: [baseline evidence](assessments/COV-006-entity-history/design-sources.json). Documentation links and source ownership were checked; this design commit adds no runtime unit tests or V7 output. The reviewed risks above are implementation gates for this increment, not defects claimed in the completed V6 outer-operation scope.
