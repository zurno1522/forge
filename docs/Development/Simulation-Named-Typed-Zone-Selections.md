# COV-006: named searches and per-type zone selections

Extend the existing engine callback and bounded observer without changing the selector, reveal, retry or movement behavior. V11 adds an explicit operation branch to both permitted operation and progress prompts. V1–V10 retain their exact vocabulary and validation. The named accounting boundary and inventory do not change.

## Operation meanings

| Branch | Lifetime | `requestedIterations` | `loopPosition` |
| --- | --- | --- | --- |
| `ORDINARY_HIDDEN_ORIGIN` | Existing ordinary loop | Received `changeNum` | Existing retryable loop index |
| `SAME_NAME_HIDDEN_ORIGIN` | One evaluated `SameNameAsEach` list | Size of that evaluated named-card list | Index in that list, including skipped entries |
| `EACH_TYPE_HIDDEN_ORIGIN` | One type's inner loop | Received `changeNum` for this type | Inner loop index, starting at zero |

The named list is evaluated once, at its original position. Its size is an explicit invocation-count permission, not permission for its identities, names or order. Empty subsequent offers retain the original skip rule and produce no callback facts. The first named search still dispatches even with no matching cards, preserving its reveal behavior. Null returns do not end either loop. Named returns are reported after the original append/reveal removal.

Each type has a separate operation. Earlier type selections remain in the original shared retained collection, so the first entry can have a positive `retainedCount` and no prior returns in this operation. The terminal collection describes the accumulated retained pile at the end of this type, not exclusively cards returned by this operation. Preserve this distinction in consumers; no equality with return counts is valid. Origin/destination roles and the current callback remain exact. No new type predicate, offered pool, all-types count or across-type history permission is granted. Those constraints remain unknown, as do ordinary-loop validity predicates.

Keep the exact original controller lookup at each selector. The per-operation wrapper must not replace subsequent virtual dispatches with its receiver. Before/return facts and owned admission reuse the existing bounded observer, including nested masking, one-time exact claims, receipt prefix limits, counter overflow and closure rules. Off/copy/hidden paths must not acquire new card-name or receipt reads. Only the named list's existing engine container size is added; no observation getter is moved into engine selection logic.

## Wire and decoder

The outer diagnostic options retain their fields, with `branch` accepting the two new audited variants only at V11 or later. Operation and progress prompts add the exact `branch` field at V11. Older profiles must reject the new variants/field. Progress metadata remains schema 1: its counts are scoped by its parent operation and the parent's branch. Require the child's permitted branch to equal its actual earlier, still-open parent's branch. A V10 child cannot claim progress under a new V11 branch. Current role bindings, argument checks, scalar sequence validation and fresh public receipt associations remain unchanged.

The new branch does not establish full request completeness, legal actions, persistent private knowledge or learning readiness. All examples remain `trainingReady=false`; budgets, sampling and engine rules are unchanged. Heist and Draft remain separate increments because their movements occur between callbacks. Spell-ability inputs follow those increments; payment/X, sustained availability and measured cost remain COV-007, COV-008 and OPT-005.

## Validation gates

Use actual `ChangeZoneEffect` resolution to exercise named skips, first empty dispatch, null continuation, original reveal order, per-type reset with cumulative retention, zero iterations and original exceptions. Check observed versus no-op engine results and original returned object identity. Add exporter negatives for profile/branch mismatch, missing or invented branch and incorrect parent association. Preserve legacy bytes and source/collector reconciliation. Review these changes for current/prior gaps before continuing to Heist/Draft.

## Implementation validation and gap review

V11 is implemented. The full reactor passes 2,801 desktop tests (six existing skips), 23 game tests and 23 GUI tests; core/AI compile with no tests. The earlier focused run passes 121 cases, with two additional engine cases included in the full run. Python passes 220 tests. Actual engine cases verify named skips without renumbering, first empty dispatch, declined-name continuation and reveal order, per-type index reset with cumulative retention, zero/negative iterations, unchanged selection/application, original exceptions and exact branch/history ownership. Decoder negatives reject invented/missing branches, mismatched parent/child branches and new permissions under legacy profiles.

All seven export artifacts are byte-identical across 158 saved V1–V10 streams. Five fresh games finish normally and pass full/filtered dataset, choice, example and validation parity; coverage matches except source-record totals. Source/collector accounting reconciles without observation failures, and each game retains two shuffles before `GAME_STARTED`. The ordinary search fixture's normalized engine events equal V10.

One control matchup samples a named operation. These five games do not sample the EACH branch; no whole-game EACH coverage is claimed. A separate harness executes actual named and EACH effects through the base AI controller, checks original movement/health, and saves real emitted messages. All 18 lifecycle events (nine operations/selectors) pass the decoder's ownership, argument, context and terminal checks. These are resolution excerpts, explicitly distinguished from complete-game traces. An initial launcher overlapped recompilation and failed before producing a trace; it contributes no game result.

Review found no remaining demonstrated current/prior defect in this increment. Per-type ownership intentionally excludes across-type receipt history; current retained count remains cumulative. Named identities, exact type/validity predicates and other opaque constraints remain outside the granted projection and legal-domain completeness. COV-004 owns completeness requirements and later knowledge design. Broader EACH game sampling and the new wrapper/projection costs belong to OPT-005; sustained availability remains COV-008. Heist/Draft are the next COV-006 increment, then spell-ability choices and remaining audited arguments, followed by COV-007 payment/X. No budget increase or learning-readiness claim is made.

Evidence: [tests](assessments/COV-006-zone-variants/tests.json), [game validation](assessments/COV-006-zone-variants/validation.json), [actual game example](assessments/COV-006-zone-variants/actual-game-examples.json), [actual engine-resolution excerpts](assessments/COV-006-zone-variants/engine-resolution-examples.json), [excerpt validation](assessments/COV-006-zone-variants/engine-resolution-validation.json), [legacy compatibility](assessments/COV-006-zone-variants/legacy-compatibility.json), [source hashes](assessments/COV-006-zone-variants/implementation-sources.json), [fixture hashes](assessments/COV-006-zone-variants/fixture-sources.json) and [commands](assessments/COV-006-zone-variants/commands.json).
