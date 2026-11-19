# Remaining trace hardening: design priorities

Status: design draft; no runtime or wire-format changes. Baseline: `ad925214474` on `goldfish-trace`. This follows the [HARD-001 assessment and implemented fixes](Simulation-Trace-Coupling-Assessment.md#implementation-follow-up-2026-11-18).

## Priority and scope

| Order | Work | Reason | Design deliverable |
| --- | --- | --- | --- |
| 1 | HARD-006: capture before deferred delivery | A reproduced reentrant cast loses its link even though emitted rows have the expected order. More snapshot instrumentation would inherit this timing assumption. | A narrow capture/lifecycle contract, prototype boundaries and behavior-based acceptance tests. |
| 2 | HARD-003: retire links during a game | Terminal cleanup is fixed, but unclassified removals retain history until execution ends. Reliable removal facts are needed before eviction can be safe. | A removal call-site inventory, explicit classification and a retention invariant. |
| 3 | HARD-005: spool exporter state | The immediate quadratic/output-string problems are fixed. Parsed graphs and dataset rows still scale with corpus size. | Disk-backed validation/materialization with equivalent output and bounded working memory. |

This is design order, not a claim that ordinary CLI output is currently failing. HARD-006 is reproduced through a deliberately reentrant subscriber; no ordinary CLI occurrence has been identified. HARD-003's 1,000-removal control retains about 192 KB in the scalar map, not an established cross-game leak. HARD-005 can proceed independently and move ahead in implementation if corpus memory becomes the practical limit. HARD-003 does not require implementing an asynchronous writer first.

## HARD-006: capture facts while execution context exists

The current collector and tracker both inspect the live stack from EventBus subscribers. `Game.fireEvent()` delegates directly to Guava. Nested posts wait for the enclosing subscriber to return; by delivery time, the selected action can have retired and its stack item can have changed. Freezing only the correlation ID would leave target, mode, X, payment and envelope snapshots vulnerable to the same delay.

The recommended direction is an optional, synchronous observation hook at the existing event-emission boundary, before deferred subscriber delivery. Audit the relevant producers to confirm that the event is emitted after the represented mutation and while its required facts are still available. `Game.fireEvent()` is the candidate seam, not proof that every event producer already satisfies that condition.

| Boundary | Owner | Contract |
| --- | --- | --- |
| Selection and explicit transformation aliases | `forge-ai` | Keep current attribution rules. A nested unrelated play, copied effect or shared grant ancestor must not inherit the selected action. |
| Committed event emission | `forge-game` | Invoke a generic observer interface without importing AI classes, JSON contracts or output sinks. Preserve existing engine event posts and their relative order. |
| Execution observation | `forge-ai` | Observe the actual stack insertion synchronously, before the execution helper returns, so the terminal result has its actual IDs even under nested dispatch. |
| Trace capture | Desktop trace adapter | Freeze envelope, identities and implemented snapshots synchronously. Return detached values; retain no `Game`, `Player`, ability, card or mutable view in queued records. |
| Serialization/projection | Desktop trace output | Consume the frozen record once. Full and filtered output derive from the same capture. Preserve nullable/unavailable facts and V1 fields. |

The tracker and collector each need the emission boundary. Moving only the collector leaves the tracker's delayed `stacked()` subscriber unable to populate a successful terminal result. Their captures must be independent of registration order: the collector's reference lookup uses active aliases, not a side effect of the tracker's stack callback.

An immutable copy of today's event object is insufficient if its fields still reference mutable views. Freeze the values actually used by the trace. Generic engine events remain available to UI/log subscribers; migrating trace capture must also remove the corresponding old trace subscriptions to avoid duplicate rows.

### Ordering, failure and lifetime contract

- For serialized game-worker emission, observation order follows existing event emission/enqueue order. Pregame shuffles remain before `GAME_STARTED`; removal remains before the later counter announcement. A nested decision, STARTED, addition and terminal preserve their existing relative order.
- Capture admission, record indexing and publication need one explicit per-game ordering contract, including administrative outcome events from cancellation. Do not assume that separate Guava subscriber locks or a synchronized writer establish that contract.
- Do not hold a collector/coordinator lock while calling into a tracker. Tracker methods already emit while holding their own monitor. The implementation must document lock acquisition order before adding synchronization.
- Capture callbacks observe facts and update observational state only. They must not invoke AI choices, mutate rules state or post further engine events. Preserve capture/sink failures for the run owner to report; do not turn a failed observation into a successful trace or a rules decision.
- Register only for recorded real games. Simulation copies do not inherit observers or action context. Registration has an explicit detach/close lifetime, covering setup failure and exceptional execution as well as success.
- Authoritative finish stops admission, finishes previously admitted capture, emits pending final records, attempts flush and retires state. `GAME_OUTCOME` and `GAME_FINISHED` are too early. Late evaluation-worker output cannot reopen a retired session.

The first implementation supports same-thread reentrant emission while retaining synchronous output. An asynchronous writer and concurrent mutation of the same Game are separate capabilities. The cancellation-event ordering and close/admission protocol must be settled before implementation; a generic hook alone does not establish thread safety.

### Options and implementation gates

Keeping the current dispatch restriction is the smallest option, but prevents reliable expansion into reentrant instrumentation. Extending individual events with neutral immutable facts can be appropriate where the emission seam is too late. The preferred starting point is the generic hook because it covers both AI events and existing engine events without adding learning fields to every engine DTO. Confirm this choice in a small prototype before migrating the full collector.

1. Audit cast, land, resolution, removal, bulk clear, AI selection/terminal and cancellation emission sites. Specify what is already committed and what can change after posting.
2. Prototype emission-time stack observation and detached capture. The existing nested Archivist reproduction must become linked `SUCCEEDED/STACKED`, including when the stack item is removed or resolved before deferred subscribers see it. No duplicate addition or terminal is allowed.
3. Migrate the remaining trace adapters, index/publication ownership and terminal lifecycle under the same contract. Keep the existing wire format where facts and semantics are unchanged; explicitly review any newly exposed capability.

Acceptance tests exercise the real boundary: ordinary and Ooze activations, unrelated copies/grants, nested mutation after capture, deliberate subscriber-order changes, simulation-copy suppression, full/filtered parity, pregame shuffle order, delayed counter order, late pre-finish choices, concurrent administrative cancellation, capture/sink failure and post-close intake. Assert emitted values and tracker outcomes rather than testing hook registration alone. Benchmark disabled and enabled recording to check the added seam's cost.

## HARD-003: classify removal before releasing history

Current `MagicStack.remove()` emits an ability view without a stack ID or reason. `CounterEffect` removes an entry, performs zone/trigger/log work, then emits `GameEventStackCountered`. Eviction on removal or after a fixed number of intervening events loses a valid counter link.

Design an engine-neutral removal disposition and actual stack identity at the mutation boundary. A pending counter is different from a removal that will have no later diagnostic. These facts must come from the responsible operation; the collector must not infer them from card names, destination zones or elapsed time. They can be internal event facts without changing the V1 JSON projection.

Audit at least `MagicStack` resolution, `remove(Card)`, player elimination and clear; `CounterEffect`; stack-exile costs; `GameAction` zone changes; `AirbendEffect`; and the single/all-card change-zone effects. Overloads and indirect removal matter as well as direct `remove(si)` calls. Confirm bulk-clear membership, frozen-stack handling and any operation that can throw between removal and its later announcement.

The target invariant is correlation state proportional to live linked entries plus explicitly pending terminal observations, rather than all historical removals. Release a definitive non-counter removal after its snapshot is captured; retain a pending counter until its announcement or an explicit operation-finalization boundary. A bulk clear must not discard an already removed entry whose counter diagnostic is still pending.

Legacy/unknown removal remains conservative during migration. While any supported path can remain unknown indefinitely, do not claim a hard within-game memory bound. Complete classification or an explicit incomplete-trace policy is required before making that claim; silent capped eviction is unacceptable.

Acceptance: real counter effects with intervening events keep their link; repeated non-counter removals stop growing history; bulk clears and exceptions retire the right entries; full/filtered parity and removal-before-counter order remain intact. Test pending operations as well as final map cardinality. Separate this implementation commit from capture changes.

## HARD-005: validate into a private disk spool

Use Python's standard-library disk-backed facilities, with SQLite as the initial candidate for indexed action/decision validation. Keep input/run/game identities, lifecycle state and decision payloads on disk, processing one input record at a time. Simply processing one complete run at a time bounds memory to the largest run and is only an intermediate improvement.

Separate validation from publication:

1. Read exact source bytes, hash inputs, validate records and store private spool state. Detect duplicate IDs/run IDs and contradictory relationships across both arrival orders. Preserve legacy and opaque identities.
2. Finalize game/run status and verify all inputs. An eventual `ABORTED` run censors earlier games, so labels cannot become public at game completion. Retain exceptional partial games under the established contract.
3. Iterate validated rows in the current input/game/decision order, compute labels and coverage, and write the existing six output files. Preserve example selection order, including distinct-group preference and fill behavior. Stream the manifest too; otherwise memory growth merely moves to another output.

The spool is private and disposable on invalid input, interruption, disk exhaustion or output failure. Publication begins only after complete validation. Whether all six files publish atomically is a separate explicit contract; retaining today's publication behavior does not justify calling it atomic. Source files must not change unnoticed between validation and materialization; materialize from the validated spool rather than rereading them for content.

Memory acceptance must include a single huge game, many games/runs, large action histories and coverage/example diversity. All growing indexes and aggregates need either disk storage or a documented bound; retaining a Python set of every action would defeat the goal. Working memory still has a floor set by the largest record and configured caches. Report RSS, spool bytes and time separately.

Require equivalent dataset, examples, manifest, coverage and validation output on the successful/timeout/exception/legacy corpus. Add malformed late-input and disk-failure checks proving that invalid input never publishes a valid-looking dataset. Benchmark the existing repeated-run fixtures and longer single-game fixtures. Keep this change independent of Java capture and removal classification.

## Next deliverable

Begin with the HARD-006 producer/ordering audit and small nested-dispatch prototype. Resolve administrative cancellation and observer-close ordering before choosing the final API. The design is ready for that bounded investigation; it does not yet declare reentrant or asynchronous support implemented.
