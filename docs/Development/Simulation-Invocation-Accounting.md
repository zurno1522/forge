# COV-001A: bounded standalone invocation accounting

New simulation runs declare `observationAccountingSchemaVersion:1` in `RUN_STARTED` and include optional, independently versioned `observationAccounting` in `RUN_FINISHED`. Existing event order, schemas, choice IDs, input profiles, callback results/exceptions and capture budgets are unchanged. There is no new event stream, per-invocation ID allocator, database or retained invocation registry.

## Scope and bounded ownership

Version 1 counts entries into the standalone `AiChoiceRecorder.observe` adapter while recording is enabled, for original AI participant controllers explicitly configured by the collector. Eligibility is that tracing/controller scope, before record-context validity checks. Null/unsupported abilities can therefore produce an entered invocation and an omitted record while the original callback still runs. Calls bypassing this adapter, parent decisions, nested target records and final search operations do not gain invocation coverage by inference.

The collector retains at most 16 original participant controller ledgers. Each has a fixed named-boundary table plus `UNCLASSIFIED`; unknown names do not grow the table. The collector also retains a fixed receipt ledger. A run aggregate merges closed game snapshots into the same fixed table; it does not retain a list of games or tokens. Each invocation's constant-sized bookkeeping lives only in the existing callback scope, not in a global collection. These bounds do not bound the engine's own callback stack or thread count.

Before source visibility is established, entries remain `UNCLASSIFIED`. Hidden sources use `MASKED_CHOICE`; raw hidden boundary names are not added to metadata. Unsupported contexts rejected before visibility stay unclassified. Replacement/control changes, unsupported team perspectives, participant truncation, unknown names or unmatched collector observations make scope completeness false. A routine game-end controller reset that leaves the original AI controller in place is not a scope change; the collector checks actual controller identity outside its publication monitor. Such scopes still preserve known counts but cannot supply an exact missing-start fraction. Disabled/copy callbacks do not enter or initialize accounting through policy.

## Distinct transitions and denominators

| Counter | Meaning |
| --- | --- |
| `entered` | Eligible wrapped invocations, not unique gameplay decisions or legal alternatives. Retries, recommendations and applications can be separate invocations. |
| `prepared` | Base start-record map completed before publication. It may still be suppressed, fail event freezing/publication, or miss the collector. |
| `omitted` | Invocation exited without a prepared base record. `omittedUnsupported`, `omittedClosed` and `omittedFailure` reconcile this count. |
| `callbackStarted`, `callbackReturned`, `callbackThrew` | Tracked original callback transitions. A callback error is not a capture omission; an optional decline is still a return. |
| `inputUnavailable` | Present input context declared unavailable. The containing start record can remain retained; this is not a lost-record count. Absent input capability is separate. |
| `startAttempts`, `terminalAttempts` | Attempts to freeze/publish the corresponding immutable event. |
| `startReturned`, `terminalReturned` | Publication calls returned normally. Event subscribers may still have failed; these are not delivery acknowledgements. |
| `finished` | Wrapped invocations whose bookkeeping exited before cutoff. |
| `observationFailures` | Caught failures in observation work, independent of callback failures. Original observation-health reporting remains authoritative. |
| `collectorStarts`, `collectorTerminals` | Standalone records whose collector write returned for the configured output path. These are checked against records actually read by the exporter for fully accounted games. They do not promise physical durability or successful future writer close. |

For an unsaturated cell, `prepared + omitted <= entered`, `finished <= entered`, and `callbackReturned + callbackThrew <= callbackStarted <= entered`. Completed invocations reconcile their preparation and callback transitions. Publication returns cannot exceed attempts; terminal attempts require a returned start publication. A prepared-but-uncollected record is distinct from an invocation that never prepared a record.

All counters are nonnegative signed-long values and saturate with an explicit `overflowed` flag. Overflow disables exact reconciliation/fractions rather than wrapping or changing gameplay. Unknown boundary strings are not retained, and name processing under the counter lock is bounded. Counter locks contain only fixed bookkeeping; no engine getter, callback, event emission, collector traversal or I/O runs under them. The merge API consumes only the accounting implementation's detached closed snapshots.

## Cutoff, delivery and failures

The cutoff is game capture closure, not `GAME_OUTCOME` or `GAME_FINISHED`. The collector first finishes its existing execution output and closes admission, then disables recording/closes observations and closes original ledgers outside its publication monitor. In-flight callbacks are not awaited or cancelled by accounting. A late callback may run or finish later; it cannot rewrite the frozen cutoff snapshot or attach to another game.

Counts aggregate across each game's cutoff. Thus `entered-finished` and callback-start/exit differences describe transitions still pending **at those cutoffs**, not workers necessarily running when the whole run later ends. Missing entry/terminal records cannot be repaired using parent metadata. COV-005's independent entry descriptors locate surviving observations; they cannot locate an omitted invocation in time.

After successful game finalization, the run merges those bounded snapshots. If a game was not accounted, the run marks the scope incomplete. Existing timeout/exception aggregate status remains based on game execution; accounting does not turn a timed-out game into a normal run. Subscriber/capture failures remain visible through the existing health channel, and writer failures remain CLI failures. A failing sink cannot be required to persist its own failure summary. Missing/partial final data is unknown, never a zero-loss declaration.

`RUN_FINISHED` carries the source and collector dimensions in the existing outputs. Full and filtered decision streams contain the same standalone events and summary. The exporter validates versions, fields, nonnegative bounds, cell balances, omission reasons and collector counts. Legacy absence remains unavailable capability. It does not infer subscriber delivery from an attempted emission or callback success.

## Interpretation and remaining integration

For complete, unsaturated accounting scopes, `startsMissingAtCutoffs = entered - collectorStarts`. Its fraction uses entered **wrapped invocations** as denominator. This includes unsupported/closed-context omissions and pending/prepared-but-uncollected starts; it is an availability measure at cutoff, not an AI-error rate or unique-decision capture rate. A zero denominator has no fraction. Partial scopes, legacy runs and overflow leave the exact missing count/fraction null.

Reports keep source counts, collector receipts, unavailable views, callback errors and pending-at-cutoff work separate, with per-boundary counts for classified invocations. Unknown/masked entries cannot be assigned back to private boundary names. There is no per-turn loss claim: absent invocations have no independent time record. Parent/nested and uninstrumented invocation counts remain null, and all datasets remain `trainingReady=false`.

COV-006/007 must reuse and extend this framework explicitly when their new nested/search/inner-payment seams are integrated. A new record does not automatically establish a new invocation-counting scope. The current scope is complete for the declared original-controller standalone adapter; broader controller perspectives and durable historical knowledge remain separate designs. OPT-005 measures counter overhead and the larger V4 profile before broad expansion.

## Regression requirements

Known-call fixtures must reconcile healthy completion, decline/error, unavailable input with a retained base record, unsupported context with an original callback, pre-record capture failure, cutoff-pending capture/callback work, subscriber failure with normal publication return, actor/control scope loss, saturation and disabled/copy paths. Integration must preserve original return/exception identity, callback-once behavior, immutable cutoff snapshots, source failure visibility, full/filtered parity and shuffles before `GAME_STARTED`. Structural export checks do not prove every engine permission or every uninstrumented path.

## Validation and item review

Final focused tests: 59 passed. The preceding full run passed 2,589 desktop tests (six existing skips), plus 23 core and 23 game tests. Fresh games then exposed an overly conservative scope flag on Forge's routine game-end controller reset. The final focused suite includes both unchanged-controller cleanup and real controller changes; those tests and three reviewed games validate the refinement. The full suite was not repeated after that small collector change. Python: 152 tests passed. All seven export artifacts remained byte-identical for 26 saved absent/V1–V4 streams.

Three fresh simple, numeric and Doctor Who/Ur-Dragon games completed normally, with 723 contexts and 628 entered standalone invocations. Each invocation produced a start and terminal collector receipt; no starts were omitted. These are sampled scoped results, not proof of universal lossless coverage. Full/filtered datasets, choices, examples and validation match byte for byte; coverage matches except source-record counts. Each game retains two shuffles before `GAME_STARTED`. Simple and numeric normalized engine events match the preceding V4 fixtures; the Monte Carlo game differs and is not a deterministic control.

The review corrected the no-op cleanup scope flag within this item. No other current/prior-item functional defect was reproduced. Parent/nested and inner-selector integration belongs to COV-006/007, with new counting scopes defined explicitly rather than inferred from retained parent rows. OPT-005 owns measured accounting/V4 costs and populated-public-proof workloads. Control/team permission expansion and durable delivery remain separate later designs. Proceed to that performance checkpoint without a user-review gate.

Evidence: [actual run summaries/examples](assessments/COV-001A/actual-examples.json), [game/export checks and source hashes](assessments/COV-001A/validation.json), [legacy comparison](assessments/COV-001A/legacy-compatibility.json), [tests and exact validation scope](assessments/COV-001A/tests.json), [commands](assessments/COV-001A/commands.json), [implementation hashes](assessments/COV-001A/implementation-sources.json).
