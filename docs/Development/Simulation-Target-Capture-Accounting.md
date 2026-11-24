# COV-006: retained-parent target capture accounting

A recording parent now carries optional, independently versioned `targetCaptureAccounting`. It counts calls into the nested target capture method, before eligibility getters, and reconciles those calls with the child rows retained in that parent. This closes the nested capture-accounting follow-up without introducing a game/controller registry, new IDs or callback outcome claims.

## Ownership, lifecycle and wire

Each recording `AiDecisionRecorder` owns a fixed eight-long ledger. It retains no Game, parent, callback, engine object or scope collection. A constant-sized per-call scope lives only on the capture method's stack. Parents constructed with recording disabled or for simulation copies allocate no ledger. Calls made with recording disabled do not enter accounting. A parent constructed without that capability does not retrospectively acquire it.

The envelope has exactly `schemaVersion:1`, `scope:RECORDING_PARENT_TARGET_CAPTURE_CALLS`, `cutoff:PARENT_CAPTURE_CLOSED`, `closed:true`, `overflowed` and `counts`. It reuses the owning parent decision/run/game identity. Its counts are:

| Field | Meaning |
| --- | --- |
| `entered` | Capture-method calls admitted to the ledger while recording is enabled, before targeting/candidate-context eligibility is established. Includes selectors and precomputed application adapters; not unique gameplay decisions. |
| `captured` | Child rows actually admitted to this parent's list, including incomplete, declined/error and unavailable-view rows. Not the input profile's `CAPTURED` status. |
| `unsupported` | Calls that could not establish the required targeting/assessment context. Not an engine illegality or AI-error count. |
| `budget` | Calls omitted by the existing 128-child parent cap. |
| `interrupted` | Calls whose bounded traversal stopped before retaining a row. |
| `failed` | Capture-method failures before row admission. A recovered input failure with a retained unavailable view is counted as captured, not here. |
| `finished` | Capture-method bookkeeping exits observed before accounting closure. This is not original selector callback completion. |
| `inputUnavailable` | Retained rows whose present input context explicitly declares `UNAVAILABLE`. Absent capability and partial views are separate. |

The original callback runs after this capture method returns. A callback error can produce an `ERROR` child row while capture-method accounting remains successful. Later selection/provenance getter failures remain visible through existing observation health; they are not retroactively attributed as capture-method omissions.

Parent finalization closes and snapshots the ledger while its publication monitor prevents further child-row admission. Source methods can still be blocked or finishing at that cutoff. They are not awaited or cancelled; their late bookkeeping cannot change the snapshot. `entered-finished` counts pending capture-method exits at each parent cutoff, not workers necessarily running at run finish. A captured incomplete child can have a finished capture method and an unresolved selector callback. Missing whole-parent events cannot be reconstructed from the ledger inside a retained parent.

Counter locks contain only bounded scalar bookkeeping or freezing of their owned scalar snapshot. They do not call parent/getters, policy, publication or I/O. Parent finalization can acquire the ledger monitor, but the ledger never acquires the parent monitor. Counters saturate at signed-long maximum and set `overflowed`; they never wrap. Saturation suppresses exact aggregate arithmetic/fractions rather than changing gameplay.

## Validation and reporting

The exporter validates exact versions, scope/cutoff, flags, nonnegative signed-long bounds and counter fields. Captured rows must match the serialized child-list length, within the existing 128-row cap, and unavailable views must match the actual retained contexts. A completed selector result requires an exited capture method. Individual counts cannot exceed entries even with saturation; unsaturated outcome totals reconcile with entry/exit counts. Existing budget omissions agree with the source budget count. Legacy interruption metadata cannot exceed known interrupted calls plus pending capture-method exits; an eligibility getter can be pending before the older in-flight reservation exists.

Coverage adds `targetCaptureAccounting` only when at least one retained parent declares it. The report identifies present/absent parents, known counts, missing rows and pending work at those parent cutoffs. Its aggregate missing-row fraction is available only for an unsaturated retained-parent cohort with accounting on every parent and a nonzero denominator. Mixed legacy data leaves that fraction null. Legacy absence never declares zero loss.

These are capture-method statistics conditional on retained parents. `gameWideNestedInvocationCount`, `missingParentInvocationCount` and `callbackOutcomeCounts` remain null. They are not combined with standalone wrapper entries as a unique-decision denominator. There is no inferred per-turn loss clock for omitted calls. Raw counters, IDs and parent joins remain diagnostic, outside `inputContext.policy`; all examples remain `trainingReady=false`.

## Validation and gap review

The full run passes 2,610 desktop tests (six existing skips), plus 23 GUI and 23 game tests. Focused tests pass 87 cases. New integration assertions reconcile known captured/unsupported/failed calls, an unavailable view with a retained error row, the unchanged 128-row cap, pending eligibility/view work, already captured but unresolved selection, and disabled/copy construction. Dedicated ledger units test frozen cutoff/retry behavior and saturation. Callback result/exception identity and original callback counts remain asserted. Python: 172 tests pass, including versions, ranges, balances, retained-row/view matches, temporal contradictions, saturation and legacy absence; disk/materialized summaries agree.

All seven exports remain byte-identical for 46 saved streams, including nested-input messages without accounting. Three fresh simple, numeric and Doctor Who/Ur-Dragon games complete normally. Their 169 retained parents account for 51 capture-method calls and 51 child rows, with no omissions in this sample. The numeric game has zero calls; its fraction is unavailable, not a zero-denominator success rate. Full/filtered datasets, choices, examples and validation match byte for byte; coverage agrees except source-record counts. Each game retains two shuffles before `GAME_STARTED`. Simple/numeric normalized engine events match the V4 baseline; Monte Carlo supplies no deterministic control.

Review added source/result temporal validation and preserved monotonic bounds under saturation. No other current/prior-increment defect was reproduced. The next COV-006 increment is independently located planning input for final search operations, followed by the mode and remaining-family work. Game-wide callback/source delivery, family completeness and sustained availability retain their existing limits/owners; this conditional accounting does not close them by inference.

Evidence: [actual messages and capture-count excerpts](assessments/COV-006-accounting/actual-examples.json), [fresh-game/export checks](assessments/COV-006-accounting/validation.json), [legacy compatibility](assessments/COV-006-accounting/legacy-compatibility.json), [tests](assessments/COV-006-accounting/tests.json), [commands](assessments/COV-006-accounting/commands.json) and [implementation hashes](assessments/COV-006-accounting/implementation-sources.json).
