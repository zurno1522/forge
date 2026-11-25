# COV-006: ordinary engine-owned zone selection operation

The [boundary inventory](Simulation-Engine-Zone-Sequence-Boundaries.md) is complete. Implement this in separate increments: a neutral game/controller callback seam, then an observed outer operation, then child progress/history. Each increment preserves its own validated scope. The neutral engine seam and V9 outer operation are implemented; child progress/history remains the next increment.

## Game/controller boundary

`PlayerController.withZoneChangeSelections(request, callback)` invokes the callback once with a no-op `ZoneChangeSelectionObserver` by default. Its request contains the already received ability, destination, ordered origins, source player, decider and requested iteration count. Construction retains references without reading collections. The game module imports no AI, serializer, wire IDs or learning types.

Only the ordinary one-at-a-time branch of `ChangeZoneEffect.changeHiddenOriginResolve` calls the wrapper. Extract that branch into a private helper with one private context of already computed values, including the original chosen collection. Keep amount calculation, original controller lookups/virtual calls, reveal delivery, filtering, removal/append and later application order unchanged. The helper returns after the existing type-total clearing and before pile shuffle, reordering, destination changes or movement.

The observer receives `beforeSelector(actualOffer, optional, loopPosition, retainedCount)` immediately before each actual virtual selector and `returned(actualCardOrNull, retainedCount)` after the original append for non-null returns, or before confirmation/retry for null. Auto/random/predefined paths emit no selector facts. Zero iterations still represent an operation with no selector calls. Observers do not transform the original result or repair policy. Default/off/copy capture does not inspect new inputs. Original callback exceptions propagate unchanged.

## Outer wire and V9 permission

The second increment introduces `ZONE_CHANGE_SELECTION_OPERATION` at `CONTROLLER_ZONE_CHANGE_SELECTIONS`, with its own `OPERATION` entry at `BEFORE_OPERATION`. Capture before the selection loop and its delayed reveal. Do not open a disclosure prompt around the whole operation or extend child disclosure lifetimes. Existing child `parentChoiceId` supplies diagnostic ownership; no new ID or registry is needed.

All producer envelopes advance consistently to schema/profile V9, inheriting V1–V8 unchanged. Outer diagnostic options are exactly:

```text
coverage: RECEIVED_ZONE_OPERATION_REQUEST
branch: ORDINARY_HIDDEN_ORIGIN
requestedIterations: exact received int, including zero/negative values
destination: exact ZoneType name
origins: ordered received names
sourcePlayerId, deciderPlayerId: received roles, nullable
```

No outer offered-card pool is captured or permitted. The new prompt permission `RECEIVED_ZONE_OPERATION_V1` is restricted to this boundary, using a final typed carrier identifiable before hypothetical suppliers are inspected:

```text
kind: ZONE_CHANGE_OPERATION_REQUEST
requestedIterations, destination, origins: received values
sourcePlayer, decider: current local player references, or null for null roles
```

A foreign/uncaptured non-null role makes the prompt unavailable. Preserve origin order/duplicates and the existing finite diagnostic origin bound; consume existing work per origin. This is an iteration request, not a legal minimum/maximum, callback-attempt count, successful-return count or guarantee of movement. Other hypothetical families retain their existing permission gates. Hidden contexts reveal none of these fields and retain unestablished timing/basis.

A completed terminal selection is exactly `{coverage,items,selectedCount,truncated}`, using the existing 256-reference returned-prefix convention. `OBSERVED_RETAINED_CARD_PREFIX` means cards retained after type-total clearing, before subsequent postselection changes/application. `UNAVAILABLE` keeps the observed count and an empty truncated prefix. Preserve the returned collection and its engine deduplication; never label it a list of callback returns or moved cards. A null callback result has the ordinary declined lifecycle. Error/closure keeps the existing censoring rules.

Add one fixed source-accounting boundary (35 to 36 named cells); empty cells remain absent from wire. Preserve accounting schema V1 and old reports. Add a conditional `knownInputBoundaryInventoryExtensionV5` for this new outer entry; freeze V1/V2 and existing V3/V4 extensions. The decoder checks exact type/boundary/profile/entry/timing, received arguments/roles, retained-prefix declarations and lack of offered-pool features. Outer and inner rows overlap and are not unique gameplay decisions. All examples remain `trainingReady=false`.

## Child progress/history is the next increment

Until separately implemented, inner selectors retain V8 request permission and no sequence component. Parent links alone grant no history. The later component must distinguish a monotonic long attempt ordinal, prior non-null-return count and current retained count; repeated loop positions and deduplication make them unequal. Use actual returned-object/incarnation receipts, a bounded prefix of 64, fresh child-local public associations and null for unresolved identities. Do not reuse V7's final-list cardinality check: a final retained pile can be cleared. Specify overflow, pending/error/closure and missing-message semantics before shipping that component.

## Validation and ownership

The engine seam must test actual resolution, retry/cancel, duplicate returns with deduplicated retention, type-total clearing, zero iterations, random/predefined bypass, first-only reveal timing, virtual dispatch and original exceptions. The outer adapter additionally tests exact fresh entry/roles/bounds, nested ownership, exhausted input, hidden/off/copy/closed bypass, source/collector reconciliation and decoder negatives. Validate fresh search-heavy games and full/filtered parity, preserve legacy export bytes and shuffles before `GAME_STARTED`.

Review each increment for current/prior defects before proceeding. Named/EACH, Heist and Draft remain separate COV-006 scopes. Spell-ability choices follow. COV-004 retains completeness; COV-007 payment/X; COV-008 availability; OPT-005 the added private-context/wrapper/carrier work and retention. No raised budget, optimization or learning-readiness claim is bundled here.


## Engine seam increment

The neutral callback seam and ordinary-loop extraction are implemented. Focused validation passes 31 cases, including ten actual-engine cases for dispatch/append order, first-only/retry reveal arguments, retry and confirmed cancellation, duplicate returns with deduplicated retention, type-total clearing, zero/negative iteration counts, random/predefined bypass, named-search exclusion, default behavior and original exception identity. Existing zone-request and delegated Intuition cases also pass. A source comparison confirms the original loop body is identical after removing whitespace and the added observer statements. No new trace event or producer profile is emitted by this increment. The full combined reactor/game gate follows with the outer adapter; child history remains separately scoped.

The review found no further demonstrated current/prior defect in this seam. Observer implementations must contain observation faults; the default observer reads nothing. Other branches stay outside the wrapper. Evidence: [engine sources](assessments/COV-006-zone-operation/engine-sources.json), [focused checks](assessments/COV-006-zone-operation/engine-tests.json) and [body equivalence](assessments/COV-006-zone-operation/engine-body-equivalence.json).


## V9 outer-operation validation and gap review

The outer adapter passes 92 focused Java cases, including twelve new cases for actual engine ownership, fresh pre-callback state, exact signed iteration values and distinct roles, nested restoration, empty/null/error outcomes, foreign/null roles, hidden/off/copy request/result bypass, visible diagnostic failure, existing-budget use and exhaustion, bounded retained prefixes without clamping and exact source accounting. Actual engine resolution verifies the outer view precedes delayed reveal, while each child's own view sees its delivered facts. The existing request and entity-history permissions remain distinct. Shared closure and late-failure regressions remain green in the full suite.

The combined full reactor passes 2,770 desktop tests (six existing skips), 23 game tests and 23 GUI tests. Core/AI compile with no tests. The Python suite passes 210 tests, covering the new type, received fields and role binding, diagnostic-only links, operation entry/profile, retained-card prefixes, unavailable references, conditional inventory V5 and rejected invented history/feature fields. Java hashes were frozen before the full reactor and Python hashes before the final Python suite; both match the packaged sources.

All seven exported artifacts remain byte-identical across 130 saved V1–V8 streams. Both published V4 workload summaries remain unchanged. A real workload protocol smoke check emits V9 for one numeric callback and two lifecycle events; this is not a new CPU/allocation/peak assessment.

Seven fresh games complete normally. The search-heavy fixture records ten ordinary outer operations and fifteen child selectors, all with their received request prompts. The retained counts are 1, 2, 2, 2, 1, 1, 2, 1, 1 and 2. The other six scenarios exercise the retained general, target/search, mode and entity-history paths without a new zone operation. Retry/cancel, duplicate return versus retention, cleared piles, zero iterations and random/predefined bypass are engine-unit cases rather than claimed game samples. The environment interrupted search-fixture startup after the other six games completed; that fixture alone was rerun after recovery, with identical validated sources.

Full/filtered datasets, choices, examples and validation match byte for byte; coverage agrees except source-record totals. Every game retains two shuffles before `GAME_STARTED`. All six games without Monte Carlo match V8 engine output after diagnostic normalization; simple/numeric/modal also match their earlier baselines. Monte Carlo differs and gives no deterministic or universal deadline-neutrality claim. Source/collector counts reconcile for the expanded configured scope; uninstrumented operation totals remain unknown.

The review found no further demonstrated current/prior defect in this increment. This is a tracing/infrastructure enhancement, not a newly established engine/card rules bug. Child attempt/return/retained progress and prior-return history remain the immediate next COV-006 increment, requiring their own wire/overflow/closure semantics. Parent links supply diagnostic ownership only. Named/EACH, Heist and Draft keep their separate scopes, followed by spell-ability choices. COV-004/007/008/OPT-005 retain completeness, payment/X, availability and cost/retention ownership. All examples remain `trainingReady=false`.

Evidence: [actual messages](assessments/COV-006-zone-operation/actual-examples.json), [seven-game validation](assessments/COV-006-zone-operation/validation.json), [tests](assessments/COV-006-zone-operation/tests.json), [legacy compatibility](assessments/COV-006-zone-operation/legacy-compatibility.json), [implementation hashes](assessments/COV-006-zone-operation/implementation-sources.json), [fixtures](assessments/COV-006-zone-operation/fixture-sources.json), [commands](assessments/COV-006-zone-operation/commands.json), [profile smoke](assessments/COV-006-zone-operation/profile-smoke.json) and [historical workload parity](assessments/COV-006-zone-operation/benchmark-legacy.json).
