# COV-006: nested target entry and input

Nested target-pool records now capture their own independently frozen entry location and V4 input context. They retain the existing parent decision, assessment/candidate association, choice ID and target-addition provenance. Parent baseline state is not substituted for child input. Final search operations and mode callbacks remain separate increments within COV-006.

## Capture boundary and ownership

`AiTargetChoiceRecorder.select` passes its existing pool timing into `beginTargetChoice` before capture. Actual selectors use `BEFORE_SELECTOR`; precomputed `selectedFrom/addSelected` results use `AFTER_SELECTION_BEFORE_APPLICATION` and keep `poolSnapshotTiming:AT_TARGET_ADDITION`. The post-capture timing mutation is removed. Real Sign in Blood branches test both ordinary and lethal precomputed applications.

Nested assessment input always declares `PLANNING_WORKING_STATE`. It describes the AI's current working state and does not claim a committed rules-state snapshot. Source visibility is established before the entry clock. Hidden-source records retain the legacy masked boundary and diagnostic reference semantics, while their new input is unavailable with `HIDDEN` and their entry contains no clock or kind. Current public diagnostic option bindings reuse the single existing bounded pool enumeration; no selectable-domain feature grant or extra option traversal is introduced.

Admission remembers the originating assessment, reads eligibility getters outside the parent monitor, then checks that the assessment is still the same before reserving a capture. Clock, prior-target/option traversal and input projection all run outside the parent publication monitor. The 128-record parent cap and game/player traversal allowances are unchanged. Unavailable views remain distinct from omitted child records.

The integration review also found existing target getters under the parent monitor during eligibility, selection completion and target-addition preparation. Those reference/state reads now occur before taking the monitor, with closed checks before and after. Target-addition metadata uses the captured ordered-state identity; subsequent engine mutation cannot reuse an old origin for a different state. No callback is rerun and original target additions retain their engine order.

Timeout publication does not wait for a blocked eligibility, pool, view or completion getter. A late completion cannot rewrite the frozen parent or attach to a new assessment. Capture-closure hardening from [the preceding increment](Simulation-Target-Entry-Context.md#capture-closure-hardening) suppresses late failures and retires abandoned standalone tokens; masked-entry consistency is also enforced by the shared decoder.

## Decoder and report

The existing nested choice protocol accepts optional `entryLocation` and `inputContext`. A present nested input requires its independently located boundary and explicit diagnostic-prefix declaration. Its timing must match the legacy pool-timing meaning and its state basis must be planning. Typed target prompts remain unsupported; this integration cannot certify effect-specific semantics or target cardinality from a heuristic pool. The validator applies existing current-identity/incarnation checks to the nested diagnostic prefix and rejects private/stale links, false rules-state input, contradictory application timing and hidden clocks.

Nested contexts contribute to context/component reporting only when the field is present. Legacy nested rows do not acquire new absence declarations in the older top-level input counters. The existing coverage audit continues to count absent nested contexts at its original observed-record denominator. Its V1 boundary inventory remains unchanged; V2 entry inventories describe only owners whose entry components actually occur in the report. Adding nested support therefore does not change the saved reports for earlier standalone-entry streams.

Captured child clocks group under `ENTRY_LOCATION`; unavailable entry clocks remain null under `ENTRY_LOCATION_UNAVAILABLE`. Legacy rows keep `PARENT_CONTAINER`. The chooser stays the actor; the entry's active turn player is not substituted for it. All examples remain `trainingReady=false`. Raw IDs, diagnostic offered pools and parent joins remain outside the permitted feature projection.

## Gap review and next work

This increment establishes nested boundary/current-state capture and preserves provenance. It does not infer a source invocation denominator from retained child rows. Existing parent budget/interruption declarations remain at their existing scope. The immediate COV-006 follow-up is bounded capture-call accounting within each retained parent: entered calls, admission/omission outcomes, in-flight capture and unavailable input. Missing whole-parent events and original callback outcomes outside that capture method remain unknown; per-parent accounting cannot claim a game-wide callback loss rate.

After that accounting follow-up, COV-006 continues final search-operation input, mode operation/selector distinctions and remaining audited family arguments. COV-008 owns sustained budget availability; adding views can exhaust the same fixed allowance earlier. No limit increase, immutable-copy optimization or writer change is bundled here.

## Validation and increment review

The full run passes 2,606 desktop tests (six existing skips), plus 23 GUI and 23 game tests, validating the final production source. A subsequent test-only addition exercises an actual blocked engine-view getter; the final focused run passes 93 tests. Those tests include blocked eligibility, input, completion and addition getters, late view failure after timeout, actual heuristic precomputed branches, frozen current state after parent/child mutations, one-pass pools and disabled/copy admission. Python: 167 tests pass, including new nested clock, source masking, input/binding and disk/materialized-report regressions.

All seven export files remain byte-identical for 40 saved streams, including earlier standalone-entry messages. Three fresh games complete normally, producing 838 contexts and 669 entry descriptors. Simple and Doctor Who/Ur-Dragon games exercise 51 nested records, including seven precomputed applications; the numeric game exercises no nested target pool. Full/filtered datasets, choices, examples and validation match byte for byte; coverage agrees except source-record counts. Two shuffles precede each `GAME_STARTED`. Simple/numeric normalized engine events match the preceding V4 fixtures; Monte Carlo differs and supplies no deterministic control.

The review fixed the getter/parent-monitor edges within this increment and prevented retroactive absence/inventory changes in legacy reports. The immediately preceding closure and hidden-entry fixes are committed separately. No other current/prior-increment defect was reproduced. The newly identified accounting follow-up remains within COV-006 and comes next, before search or modes; broader input/semantics and sustained-availability limits remain explicitly assigned.

Evidence: [actual nested messages and context excerpts](assessments/COV-006-nested/actual-examples.json), [fresh-game/export checks](assessments/COV-006-nested/validation.json), [legacy comparison](assessments/COV-006-nested/legacy-compatibility.json), [test scope](assessments/COV-006-nested/tests.json), [commands](assessments/COV-006-nested/commands.json) and [implementation hashes](assessments/COV-006-nested/implementation-sources.json).
