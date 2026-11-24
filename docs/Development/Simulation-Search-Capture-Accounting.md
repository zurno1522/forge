# COV-006: retained-parent search capture accounting

Recording parents now carry independently versioned `searchCaptureAccounting`. This counts the observation capture phase of `searchTargets` before host eligibility getters and before the original search callback. It reconciles source attempts with search-operation rows retained in that parent, including failed and unsupported captures. It does not count callbacks, unique gameplay choices or missing whole-parent events.

## Ownership and scope

The existing fixed eight-long `AiCaptureAccounting` implementation now has two typed families, target and search. Each recording parent owns one ledger per family. Neither ledger retains engine objects, callbacks, scope collections or invocation IDs. Constant-sized call scopes are referenced only by active capture calls; this is a retention bound, not a claim of JVM stack allocation. Parents constructed with recording disabled or for simulation copies allocate neither ledger; calls on disabled or simulation-copy chooser games and closed parents do not enter search accounting. Enabling recording later does not retrospectively allocate a capability.

The search envelope has `schemaVersion:1`, `scope:RECORDING_PARENT_SEARCH_CAPTURE_CALLS`, `cutoff:PARENT_CAPTURE_CLOSED`, `closed:true`, `overflowed` and `counts`. The [target ledger's eight fields](Simulation-Target-Capture-Accounting.md#ownership-lifecycle-and-wire) keep the same scalar meanings, applied to the search capture phase:

- `entered` includes attempts before host eligibility; it is not limited to calls that produced a search row.
- `unsupported` includes null or foreign-game hosts. A host getter failure counts as `failed` and remains visible through observation health, while the original callback still runs.
- `captured` counts actual row admission, including masked, incomplete, declined/error and unavailable-view rows. `inputUnavailable` counts only retained rows with an explicitly unavailable input.
- `budget` and `interrupted` count pre-row cap and stopped-traversal omissions. Recovered view failure with a retained row is captured/unavailable, not failed capture.
- `finished` is capture-phase exit before the original callback starts. Callback errors and result-snapshot failures cannot retroactively turn a retained row into a missing capture.

Parent finalization closes both ledgers while preventing further row admission. It does not await in-flight getters or callbacks. Late scope updates cannot change the frozen snapshot. `entered-finished` describes pending capture exits at each parent cutoff, not necessarily workers still running at run finish. Saturating signed-long counters and overflow flags preserve the existing no-wrap contract. Ledger monitors perform only bounded owned bookkeeping; they never call parent/getters, publication or I/O. The original callback runs outside them.

## Interruption reconciliation

Legacy search interruption metadata includes both capture work omitted before a row and result-stage interruption on an already retained row. These are different populations. The validator allows a retained `CAPTURE_INTERRUPTED` row to explain one such result interruption without adding a capture omission. Budget counts still match exactly; remaining legacy interruption metadata cannot exceed known pre-row interruptions plus pending capture exits.

Review identified an older race: the pre-row interruption count was incremented before its in-flight reservation was removed, under separate lock acquisitions. A timeout between them could serialize the same capture twice. The cleanup now removes the reservation and adds the completed interruption count together under the parent monitor. A cutoff sees one pending or one interrupted capture. Source bookkeeping can be pending independently, which remains explicit in the ledger.

## Decoder, reporting and compatibility

The shared decoder validates each named family independently: exact envelope fields/version/scope, signed-long ranges, row/view reconciliation, temporal exit constraints, unsaturated balances and legacy omission bounds. Search and target ledgers cannot borrow each other's scope or row counts. Rows may be absent because every attempted host was unsupported; a search row declaration is not fabricated for that case.

Coverage adds `searchCaptureAccounting` only when at least one retained parent carries it. The conditional cohort, present/absent parents, known missing rows, pending exits and fraction rules match target accounting. Missing/legacy parents, saturation or a zero denominator prevent an exact aggregate fraction. `gameWideNestedInvocationCount`, `missingParentInvocationCount` and `callbackOutcomeCounts` remain null. No per-turn clock is invented for omitted calls. Target wire and legacy exports remain compatible; no search summary, limitations or Markdown section is added to older streams lacking this component. All examples remain `trainingReady=false`.

## Gap ownership

This closes the search capture-source follow-up within COV-006. Current-parent accounting does not establish delivery for a whole missing parent or game-wide callbacks. Mode entry/current-input integration and remaining audited family arguments still belong to COV-006. Before mode integration, review the existing assessment-context helper's getter/parent-monitor edge and fix a reproduced timeout hazard within this same item. COV-007 owns inner payment/X work; COV-008 owns sustained availability. OPT-005 retains updated end-to-end/peak measurement for expanded family capture and its bounded scalar/snapshot costs; the older workload numbers do not measure this increment. No cap, policy sampling or writer/copy optimization changes are included.

## Validation and increment review

The final full run passes 2,622 desktop tests (six existing skips), plus 23 game and 23 GUI tests. Core and AI modules compile but contain no tests. The final production source includes source-count, view/callback separation, pending capture/result, disabled/copy, eligibility, cap, saturation and closure regressions. The earlier focused run passes 61 cases. The subsequently added race regression first fails against the old cleanup with `expected [1] but found [2]`, then passes in the final full run. Its barriers force a parent-cutoff interleaving rather than relying on a probabilistic stress loop. Python passes 181 tests, including independent scopes, malformed counters, pending/unavailable rows, mixed/zero/overflow cohorts and retained result interruption. Disk/materialized summaries agree.

All seven exports remain byte-identical for 58 saved streams, including independently located search inputs. Three fresh simple, numeric and Doctor Who/Ur-Dragon games complete normally. Their 166 retained parents account for six search capture attempts and six retained rows, with no search omissions in these samples. All six are heuristic revalidations with partial planning views; the four precomputed application variants retain actual-controller unit coverage, not fresh-game coverage in this sample. Simple/numeric games have zero search entries and null denominator fractions. Full/filtered datasets, choices, examples and validation match byte for byte; coverage agrees except source-record counts. Every game preserves two shuffles before `GAME_STARTED`. Simple/numeric normalized engine events match the V4 baseline; Monte Carlo remains nondeterministic.

Review fixed the interruption cleanup race in its own commit, `9397fbe2e8a`, before the accounting feature commit. The full suite and fresh games validate their combined source. The assessment-context getter/monitor edge remains an immediate COV-006 follow-up before modes. Whole-parent delivery, game-wide callbacks and performance/availability limits retain their named owners. Packaging review also corrected earlier module labels against retained Maven logs: the totals were right, but the 23-case GUI suite had been called core.

Evidence: [actual messages](assessments/COV-006-search-accounting/actual-examples.json), [fresh-game/export checks](assessments/COV-006-search-accounting/validation.json), [legacy compatibility](assessments/COV-006-search-accounting/legacy-compatibility.json), [tests and expected failing regression](assessments/COV-006-search-accounting/tests.json), [module-label audit](assessments/COV-006-search-accounting/module-label-audit.json), [commands](assessments/COV-006-search-accounting/commands.json) and [implementation hashes](assessments/COV-006-search-accounting/implementation-sources.json).
