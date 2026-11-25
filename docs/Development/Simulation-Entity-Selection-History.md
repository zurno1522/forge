# COV-006: prior entity selections in the next selector's own view

The V6 outer operation is implemented and validated. The V7 implementation adds the bounded selector-history permission defined here; current producers use V8 while retaining that permission. The design source baseline is `f40645bb13c`. The controller's loop, not the presence of earlier trace events, determines which non-null values actually returned and were appended to `selecteds`.

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

The conditional `entitySelectionHistory` coverage report counts only present sequence components: receipt coverage, overlapping prior-return/receipt totals, projected prompts and resolved/unresolved projected entries. Absent components and unobserved sequence counts stay unknown. These vectors overlap across successive picks and are not unique gameplay decisions.

The decoder checks exact component/profile fields and scalar types, prefix/count declarations, own entry/timing, actual local optionality/role, and complete prompt/diagnostic agreement. It checks that `parentChoiceId` identifies an earlier, still-open recorded outer entity operation with the same chooser/request and matching bounds. It must not require that every earlier return has its own surviving message: receipts come from actual returns, and messages can be missing. Where a current local reference is supplied, validate its identity/incarnation against the raw receipt and the current public snapshot bindings. Reject private/stale links, invented counts/ownership, unknown fields and false completeness. Every example remains `trainingReady=false`.

## Validation gates and remaining work

Tests must cover real multiple iterations with fresh child-local history; actual virtual returns that bypass a serializer; unsupported override behavior; nested/delegated isolation; one-time dispatch claims; receipt duplicates; zero/empty cases; private/stale/LKI/foreign associations; the 64-receipt prefix; immutable events; hidden/off/copy/closed behavior; and blocked receipt getters with both late return and late Runtime failure. Live capture failures remain visible. Exact source accounting and original callback/exception behavior must reconcile.

Validate current full/filtered output, preserve the six V6 game samples and legacy report bytes, and retain shuffles before `GAME_STARTED`. A new source hook does not make uninstrumented invocations observable. The new frame/receipt/carrier costs belong to OPT-005; no raised budget or optimization is bundled here.

Review this increment before proceeding. Zone-change request/repetition and spell-ability choice families remain separate COV-006 work; engine-owned repetitions need their explicit engine/controller contract. COV-004 retains completeness requirements, COV-007 inner payment/X and COV-008 sustained availability.

Design/source hashes: [baseline evidence](assessments/COV-006-entity-history/design-sources.json). The reviewed risks above are validation gates for this increment, not defects claimed in the completed V6 outer-operation scope.

## Implementation validation and gap review

The initial six controller regressions fail in five cases for the missing sequence prompt; the unsupported override case already preserves behavior. Final focused validation passes 143 Java cases. The full reactor passes 2,738 desktop tests (six existing skips), 23 game tests and 23 GUI tests; core/AI compile with no tests. Nineteen new cases exercise actual repeated controller choices, fresh second-selector state and local references, immutable earlier messages, private/stale receipt refusal, zero/empty/direct cleanup, nested/delegated isolation, failed intermediate admission, one-time dispatch claims, the bounded prefix beyond 64 without capping actual returns, and hidden/off/copy bypass. Four blocked-receipt cases verify parent closure or recording-off with a late value or Runtime failure; live receipt failure remains visible and the original returns survive. Existing shared bindings/prompt/lifecycle tests cover the delegated projection helpers and original callback exceptions.

The Python suite passes 202 tests. It checks public and unresolved history, planning permission, actual outer ownership/role/bounds, complete versus prefix/unavailable declarations, valid missing earlier selector rows, sequence-position progression, current receipt identity/incarnation and private/stale-link rejection. Conditional coverage counts preserve absence as unknown, including in the disk-backed exporter.

All seven exported artifacts are byte-identical across 104 saved V1–V6 streams. Both published V4 workload summaries remain unchanged. A real workload protocol smoke check confirms the current profile for one numeric callback and two lifecycle messages; it is not a new CPU/allocation/peak assessment.

The integration review found no further demonstrated current/prior runtime defect. Histories are explicitly partial and bounded: an incomplete receipt prefix makes the prompt unavailable, and complete receipt capture can still contain null current identities. Direct or unaudited callbacks remain history-unknown. No new ID registry, offered-pool permission, raised budget or learning-readiness claim is introduced. The next COV-006 item is the separately audited zone-change request, followed by explicit engine-owned repetition and spell-ability families. COV-004/007/008/OPT-005 retain the ownership above.

Six fresh games complete normally: simple, numeric, modal Azorius, Doctor Who/Ur-Dragon, time-counter and counter-focused fixtures. The time-counter operation has one initial selector and returns no entities. The counter-focused game records four operations returning one, one, two and two entities: six complete selector histories, including two later picks with one resolved earlier return each. Actual message excerpts preserve the child's own view and owner. Private/stale identities, prefix exhaustion and receipt faults are exercised by unit cases rather than claimed as observed game samples.

Full/filtered datasets, choices, examples and validation match byte for byte; coverage agrees except source-record totals. Every game preserves two shuffles before `GAME_STARTED`. All five non-Monte-Carlo engine streams match V6 after normalizing diagnostic metadata; simple/numeric/modal also match their earlier baselines. Monte Carlo differs and provides no deterministic or universal deadline-neutrality claim.

Evidence: [actual messages](assessments/COV-006-entity-history/actual-examples.json), [game/export validation](assessments/COV-006-entity-history/validation.json), [tests](assessments/COV-006-entity-history/tests.json), [legacy compatibility](assessments/COV-006-entity-history/legacy-compatibility.json), [implementation hashes](assessments/COV-006-entity-history/implementation-sources.json), [fixtures](assessments/COV-006-entity-history/fixture-sources.json), [game commands](assessments/COV-006-entity-history/commands.json), [workload profile smoke](assessments/COV-006-entity-history/profile-smoke.json) and [historical workload parity](assessments/COV-006-entity-history/benchmark-legacy.json).
