# COV-006/HARD-001: selected decision publication contract

Parent metadata capture is implemented in `c866db0c54d`. This design covers the remaining selected-action/provenance preparation and publication boundary. It adds no wire fields, allocator IDs, delivery acknowledgement or learning-readiness claim.

## Ownership and sequence

A selected decision is already an AI result. Observation preparation must not rerun policy or turn a runtime tracing failure into a different play. Genuine `AITimeoutTracker.Expired` remains deadline control, and fatal `Error` handling is unchanged.

1. Check recording/publication lifetime and capture the action tracker's current selection scope before expensive metadata work. The scope is a private object-identity marker, not a serialized ID or invocation registry. Starting another selection or retiring the tracker invalidates pending preparation.
2. Close child capture admission and freeze its counter cutoffs under the parent monitor. Copy the actual chosen list and project selected-action metadata outside that monitor. Complete owned parent bookkeeping and freeze the event under the monitor. The prepared result owns both the frozen event and the matching actual ability references; IDs must not reconstruct those objects.
3. Snapshot owned provenance state and allowed choice IDs under the parent monitor, then inspect engine target groups outside it. Existing exact-state/incarnation checks still decide whether a cause is known. Do not substitute a parent input snapshot or restore targets.
4. Prepare the entire action-registration batch outside the tracker monitor. Commit no prefix if a later action's preparation fails. Check the captured selection scope again before committing.
5. Claim publication once per parent only after heavy preparation. An expired preparation must leave the chooser's timeout retry able to publish; a late loser must not emit a second parent or acquire aliases. Frozen winner data cannot change.
6. Commit owned registrations under the tracker monitor, then emit the parent synchronously in the existing order. A normal emitter return remains distinct from collector receipt. If emission itself throws, remove only unstarted registrations from this owned batch; preserve execution lifetimes that have actually started.

The public engine still receives its original selected abilities. No engine targets, payments or rules state are undone to repair diagnostics. Healthy decision/action IDs and message field meanings remain unchanged.

## Failure disposition

| Failure boundary | Required result |
| --- | --- |
| Parent construction / legacy observation | Report an active runtime observation failure and continue policy without a parent recorder. Do not fabricate a parent or fallback identity. The current constructor runs before the chooser's policy try block, so this boundary needs an explicit guard. |
| Selected-action projection | Report an active runtime observation failure, preserve the selected result and leave parent evidence unavailable. No partial action registrations. |
| Provenance / registration preparation | If a valid parent exists, retain it and report the runtime fault, with no partially prepared execution aliases. |
| Retired or superseded action-registration preparation | Drop late link preparation and suppress obsolete failures. Do not invalidate or register into a newer selection. A missing parent projection remains an active capture fault while its parent/game publication is open. |
| Genuine deadline during preparation | Preserve deadline control and allow the ordinary timeout publication path. Do not relabel it as a tracing error or spend the publication claim early. |
| Duplicate/late publication | Emit/register only the claimant. A pre-claim losing failure cannot poison a completed parent. |
| Emitter API throws | Report the active runtime fault and clean up this batch's unstarted aliases, without replaying publication or gameplay. Partial delivery cannot be inferred away. |
| Trace subscriber fails inside `Game.fireEvent` | Existing game dispatch records health and may return normally. Preserve healthy registrations; a subscriber fault is not proof that every collector missed the event. |

Recording closure suppresses late health updates and late publication. A publisher's own failure after claiming remains reportable while recording is active; input closure alone is not publication completion. Missing whole-parent delivery and uninstrumented callback totals remain unknown. Existing retained-parent capture ledgers cannot count a parent that was never delivered.

## Validation boundary

Tests must exercise source projection failure without changing a real selected play, a later registration failure without a retained prefix, blocked preparation across retirement/supersession, genuine deadlines and timeout retries, concurrent publication winners, and emitter versus subscriber failure. Healthy execution-copy aliases and target origins must retain their existing behavior. New scopes must not retain parent graphs after preparation/retirement.

Review each stage against its actual call sites, then run the full suite and fresh full/filtered export checks. Preserve engine event ordering, including shuffles before `GAME_STARTED`. Runtime optimization, capture-budget policy, model-facing option membership and new whole-parent source denominators remain separate work.

The [publication hardening increment](Simulation-Parent-Publication-Hardening.md) implements and validates this contract. Publication permission can be claimed or retired; neither state is collector acknowledgement.
