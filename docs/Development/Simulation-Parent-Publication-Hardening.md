# COV-006/HARD-001: selected decision publication hardening

Decision publication now prepares a frozen record together with its actual chosen ability references, then prepares a complete action-registration batch before claiming publication. Runtime observation faults stay visible without replacing the AI's selected result. No new JSON fields, allocator IDs, trace profiles or legal-action claims are introduced.

## Preparation and ownership

Parent capture closes before selected-action projection. Both capture ledgers and legacy budget/interruption totals freeze at that first cutoff; later cleanup or timeout retries cannot refresh them. Engine getters and chosen-list copying run outside the parent monitor. Each preparation builds its own output map and child associations from captured data, so another preparation's optional search summary or selected-action joins cannot leak into a retry.

The prepared result pairs the event with an immutable list of the actual selected objects. Action aliases use that list; they cannot borrow another action's identity if the caller mutates its original list while a getter is blocked. Provenance snapshots detach owned origin lists under the parent monitor, then inspect engine target state outside it. Existing ordered-state/incarnation checks remain authoritative for whether a cause is known.

The action tracker captures a private selection scope before expensive projection. It prepares every action outside its monitor and commits the complete batch only while that scope remains current. Starting another selection or retiring the tracker prevents late registration. The private generation marker is object identity local to the tracker, not a serialized ID or historical registry. Uncommitted batches remain local to their active calls; normal execution and retirement retain their existing alias lifetimes.

## Publication and failure behavior

Publication permission is consumed once per parent, after heavy preparation. A timeout can publish while another preparation is blocked; the late loser cannot overwrite the winner, emit a duplicate or register aliases. Genuine deadlines during projection or provenance preparation leave the ordinary timeout retry available. They are not reported as tracing errors.

A projection failure that leaves no valid parent is reported while its parent/game capture remains active. If a valid parent exists but provenance preparation fails, the parent can still be emitted without a partially registered prefix. Obsolete registration preparation is discarded after retirement or supersession; this does not hide an active failure to construct the parent itself.

Emission returning normally is not collector acknowledgement. An ordinary failing trace subscriber is already handled by game dispatch, so healthy registrations remain available. If the emitter API itself throws, the publisher removes only its batch's unstarted aliases and reports the active runtime fault. It does not undo gameplay, synthesize execution results or discard an execution that a synchronous consumer already started.

Parent construction is also guarded. A runtime legacy/input observation fault cannot replace a real land/spell choice. Recording closure suppresses late construction faults and drops the late recorder. Skipped publication retires both input capture and publication permission, preventing recording re-enable from reviving an old parent or emitting orphan child messages. Constructor deadlines retain timeout control; disabled capture does not inspect construction inputs.

## Gap review and remaining ownership

The implementation follows the [publication contract](Simulation-Parent-Publication-Contract.md). Review corrected cutoff drift in legacy omission totals, detached mutable provenance lists and per-preparation output maps, and retired skipped parents. It also distinguished card identity from Java object identity in a real cast regression: the engine moves a card using a new object while retaining its card ID.

This increment covers parent construction, selected metadata/provenance preparation, registration and publication. It does not claim collector receipt, a whole-parent source denominator, complete legal alternatives or training readiness. Input closure, publication permission and action-registration lifetime remain separate ownership boundaries. No tracing repair changes engine targets, reruns policy or restores rules state.

After validation and the parent-inventory review, mode operation/selector inputs and remaining family arguments remain COV-006. COV-007 owns inner payment/X seams; COV-008 owns sustained availability. OPT-005 owns the updated full-path/peak costs of the expanded capture and these owned batches/scopes; no optimization or budget change is bundled here.

## Validation

The initial 14 regressions reproduce 12 failures in the old publication path. The final focused run passes 153 tests, including 25 publication cases and existing execution, target-provenance, parent-capture and choice units. The new cases exercise real spell resolution after a projection fault, live versus obsolete faults, a later action failing without a registered prefix, mutable chosen lists, parent construction failure/closure, deadlines at both preparation stages, timeout winners, duplicate suppression, subscriber versus emitter failures, started execution preservation, cutoff consistency and recording re-enable.

The full run passes 2,685 desktop tests (six existing skips), 23 game tests and 23 GUI tests with no failures/errors. Core and AI modules compile but have no tests. All 181 exporter tests pass. Three fresh simple, numeric and Doctor Who/Ur-Dragon games finish normally and validate. Full/filtered datasets, choices, examples and validation agree byte for byte; coverage agrees except source-record counts. Each game keeps two shuffles before `GAME_STARTED`. Simple/numeric normalized engine events match the preceding V4 baseline; Monte Carlo remains nondeterministic.

The parent-boundary review found no remaining reproduced fault within this inventory after the corrections above. Modes and remaining family inputs are the next COV-006 increment. Broader legal-domain/effect semantics, unknown whole-parent delivery, sustained availability and updated cost measurement remain with their named owners; this hardening does not certify learning examples.

Evidence: [tests and review corrections](assessments/COV-006-publication/tests.json), [fresh-game/export checks](assessments/COV-006-publication/validation.json), [commands](assessments/COV-006-publication/commands.json) and [source hashes](assessments/COV-006-publication/implementation-sources.json).
