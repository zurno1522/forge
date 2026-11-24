# COV-006: final search-operation entry and input

Final main-phase search target operations now retain independently frozen `entryLocation` and V4 `inputContext` components. They keep their existing parent, choice ID, requested target, before/after plans, result and exact target-origin links. The parent baseline is not substituted for current operation input.

## Boundary and permission

`HEURISTIC_REVALIDATION` is a composite operation, captured at `BEFORE_OPERATION` with entry kind `OPERATION`. `RESTORE_APPROVED_TARGET`, `ROLLOUT_TARGET_OVERRIDE`, `DISCOVERED_TARGET` and `GUARD_REAIM` apply choices generated earlier; they use `AFTER_SELECTION_BEFORE_APPLICATION` and entry kind `APPLICATION`. No selector or rollout is rerun to reconstruct an earlier input. All public search input declares `PLANNING_WORKING_STATE`: search may already have disturbed the live plan.

Source visibility is checked before the public entry clock. Clock capture precedes before-plan traversal and input projection, all before the original callback. These are successive synchronous reads, not an atomic engine snapshot; the decoder does not require clock values in the entry and view to be equal. The current source/card permission, bounded projection, diagnostic joins and omission rules remain V4. Requested targets and before/after plans stay diagnostic. They are not an offered pool; `optionBindings` and typed prompts remain unsupported. This increment does not certify legal alternatives, effect semantics, rewards or complete learning examples.

A masked operation retains its legacy masked operation/result and null target plans. Its new input is unavailable with `HIDDEN`; input timing/basis and entry kind/timing/basis are `UNESTABLISHED`, with no entry clock. This prevents the new component from revealing whether the private operation was a revalidation or an application.

The 128-operation parent cap and fixed game/player traversal allowance are unchanged. An unavailable input view does not omit an otherwise retained application or change its original callback result. Input capture failure is visible through observation health and retains an unavailable context when row admission remains possible.

## Closure and existing getter hardening

Eligibility, visibility, clock, input and target-plan reads run outside the parent publication monitor. The integration review found pre-existing engine getters under that monitor in result serialization and provenance preparation. Ability IDs are now captured with the target-plan snapshot, and changed-group actor checks and origin construction happen outside the monitor. Publication installs the complete result and origin batch only after checking that the parent is still open. A failure in a later ability cannot publish a partial origin batch.

The outer eligibility getter is also inside the observation failure boundary, so its failure remains visible while the original callback still runs once. Closed parents do not start new captures or result getter work. Timeout publication does not wait for blocked input/result getters; late work cannot rewrite the frozen event or report an obsolete snapshot failure as a live game failure. This does not introduce an engine-wide lock or change gameplay/event ordering.

## Decoder and report compatibility

The existing search-application protocol accepts the two independently optional components. A present input requires its own entry. Validation checks chooser and known turn-player identities, the operation-specific timing, planning basis, hidden-source consistency and unsupported prompt/pool claims. The entry component can appear without a view; it cannot borrow a parent clock.

Only present search inputs contribute to the older top-level context counters. Legacy search records keep their previous absent-context audit and `PARENT_CONTAINER` turn attribution. New captured entries use `ENTRY_LOCATION`; unavailable entries have null clocks and `ENTRY_LOCATION_UNAVAILABLE`. Chooser identity remains distinct from the entry's active turn player. The frozen V1 boundary inventory is unchanged; the conditional V2 inventory adds search operations only in reports containing search entry components. All exported examples remain `trainingReady=false`.

## Gap review and follow-up

This increment closes final search entry/current-input integration and the reproduced eligibility/result getter hazards. Search source accounting is the immediate remaining COV-006 follow-up: retained rows and legacy omissions do not establish how many capture calls entered, failed or were pending at parent closure. That work must remain conditional on retained parents and separate from game-wide callback totals. Modes and remaining audited family arguments follow it. Inner payment/X integration belongs to COV-007, sustained availability to COV-008, and full-path/peak measurement and conditional copy/writer optimization retain their OPT owners. No cap increase or performance rewrite is bundled here.

## Validation

The focused Java run passes 56 tests with no failures or skips; Python passes 176 tests. Tests exercise actual controller applications for all four precomputed variants, operation/current-state capture, legacy parent independence, hidden operation kinds, exhausted input with a retained successful application, callback errors, eligibility failure, later-ability origin failure, the existing cap and timeout publication with blocked input/result-ID/result-actor getters. Decoder tests reject false selector/rules-state claims, clocks for masked operations, unaudited prompts/pools and missing ownership, while disk/materialized reports agree. The full suite follows in the immediate accounting increment; this isolated snapshot claims focused validation only.

All seven exports remain byte-identical for 52 saved streams, including retained-parent target accounting. Three fresh simple, numeric and Doctor Who/Ur-Dragon games complete normally. The complex game exercises six revalidations, each carrying a partial planning view; these observations do not exercise the four application variants, which have actual-controller unit coverage. Full/filtered datasets, choices, examples and validation match byte for byte; coverage agrees except source-record counts. Two shuffles precede every `GAME_STARTED`. Simple/numeric normalized engine events match the preceding V4 baseline; Monte Carlo differs and supplies no deterministic control. Partial views and unobserved variants remain explicit limits.

Evidence: [actual messages](assessments/COV-006-search/actual-examples.json), [fresh-game/export checks](assessments/COV-006-search/validation.json), [legacy compatibility](assessments/COV-006-search/legacy-compatibility.json), [tests](assessments/COV-006-search/tests.json), [commands](assessments/COV-006-search/commands.json) and [implementation hashes](assessments/COV-006-search/implementation-sources.json).

The subsequent [search accounting increment](Simulation-Search-Capture-Accounting.md#validation-and-increment-review) validates these input changes together with accounting in a full run of 2,622 desktop tests, 23 game tests and 23 GUI tests, plus three fresh games.
