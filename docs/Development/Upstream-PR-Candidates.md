# Potential upstream pull requests

Review log for changes to consider submitting to [Card-Forge/forge](https://github.com/Card-Forge/forge). Entries are proposals, not published PRs. Recheck upstream master and open PRs before writing them up; shared-file overlap does not necessarily supersede a fix.

Last review and upstream validation: **2026-11-14**, upstream master `eace840dc308de4e385132f9c07fc5bfad3fbfd2` (2.0.17-SNAPSHOT).

| ID | Proposed change | Status | Suggested submission |
| --- | --- | --- | --- |
| PR-001 | Preserve temporary flashback grants and supported pump expiry callbacks in AI game copies | Confirmed upstream bug; fix and regressions validated | Combine with PR-002 in one focused AI simulation bug-fix PR |
| PR-002 | Match the selected flashback ability by its complete cost as well as description | Confirmed upstream bug; fix and regression validated | Combine with PR-001; independent cause in the same simulation path |
| PR-003 | Structured AI decision observations and optional simulation output | Enhancement to discuss; overlaps an existing upstream proposal | Review upstream interest and PR #12180 before proposing a separate feature PR |
| PR-004 | Widen engine identifiers and dependent lookups to 64 bits | Implemented locally; classification and upstream review pending | Capacity/hardening enhancement candidate; review public API and multiplayer compatibility costs before proposing |
| PR-005 | Preserve full IDs in multiplayer delta keys | Implemented with PR-004; classification and upstream review pending | Review the packed-key range limitation independently; determine whether it warrants a separate bug-fix PR |

## PR-001: temporary grants and expiry in copied games

Snapcaster Mage can grant a graveyard spell flashback, but the normal `GameCopier` path rebuilds non-battlefield cards from their printed definitions. The temporary grant disappears in the simulation. Temporary battlefield pumps can also outlive their intended duration when their expiry callback is absent from the copy.

The branch maps supported `PumpEffect` cleanup commands into the copied game's phase queues, including player-specific queues. It restores the associated graveyard keyword grant with its expiry, leaves the original game independent, and avoids regranting permission to an object that changed zones.

Scope: `GameCommand`, `Phase`, `PumpEffect`, `GameCopier`, and `GameSimulationFlashbackTest`. This is an engine/AI simulation bug, not a Snapcaster card-script or learning-data bug. Other unsupported duration callbacks remain outside this fix; it does not claim complete copying of every temporary effect.

## PR-002: printed and granted flashback costs

A printed flashback ability and a temporary granted version can share a description but have different costs. `GameSimulator` previously matched by description alone and could choose the unaffordable printed cost instead of the selected granted cost.

The fix restricts flashback matching to flashback candidates, copies cast choices, and compares complete costs using `Cost.toSimpleString()`. It declines the simulation rather than substituting another cost when no matching version exists. Scope: `GameSimulator` and the shared regression class.

## Reproduction and validation for PR-001/002

An isolated checkout of the upstream revision above contained only five production fix files and `forge-gui-desktop/src/test/java/forge/ai/simulation/GameSimulationFlashbackTest.java`. No decision-recording, CLI, exporter, or telemetry changes were included. The fix applied without conflicts.

| Validation | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Unpatched upstream plus regressions | 6 | 5 | 0 | 0 |
| Patched upstream, focused regressions | 6 | 0 | 0 | 0 |
| Patched upstream, full desktop suite | 2,524 | 0 | 0 | 7 |

The full `forge-gui-desktop -am` reactor completed successfully, including dependency modules. Baseline failures were assertion failures, not build/setup failures.

| Regression method in `GameSimulationFlashbackTest` | Unpatched | Patched |
| --- | --- | --- |
| `snapcasterGrantedFlashbackCanBePlayedInCopyWithoutMutatingOriginal` | Fail | Pass |
| `copiedGrantExpiresWithoutExpiringSourceOrPrintedFlashback` | Fail | Pass |
| `grantedFlashbackKeepsItsOwnCostBesidePrintedFlashback` | Fail | Pass |
| `mappedPumpExpirationPreservesOriginalCreatureStats` | Fail | Pass |
| `playerSpecificExpirationUsesTheCopiedPlayer` | Fail | Pass |
| `zoneChangeDoesNotRegrantExpiredPermissionInCopy` | Pass (control) | Pass |

From the repository root, using the project's supported Java/Maven setup and a display or Xvfb where needed:

```sh
mvn -pl forge-gui-desktop -am -Dtest=GameSimulationFlashbackTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl forge-gui-desktop -am test
```

The upstream review examined 247 open PR metadata entries and relevant diffs. No superseding fix was found at the reviewed revision. Related PRs to recheck:

- [#11746](https://github.com/Card-Forge/forge/pull/11746): ReplaySpell response approval in PumpAi; different behavior.
- [#11019](https://github.com/Card-Forge/forge/pull/11019): empty explicit flashback-cost syntax; different path.
- [#12834](https://github.com/Card-Forge/forge/pull/12834): stack-description wording, including PumpEffect; shared-file overlap.
- [#3556](https://github.com/Card-Forge/forge/pull/3556): switched power/toughness cleanup; overlaps PumpEffect/GameCopier and may need reconciliation.
- [#11493](https://github.com/Card-Forge/forge/pull/11493): hybrid safety evaluation of costs and delayed triggers; does not replace these fixes.
- [#12246](https://github.com/Card-Forge/forge/pull/12246) and [#12147](https://github.com/Card-Forge/forge/pull/12147): undo/memento work, rather than the normal GameCopier path.
- [#12264](https://github.com/Card-Forge/forge/pull/12264): shared-zone ownership copying.
- [#11154](https://github.com/Card-Forge/forge/pull/11154) and [#10585](https://github.com/Card-Forge/forge/pull/10585): cast/land history copying.

Before submission, rerun the focused regressions on then-current upstream and review any changes to the overlapping files. Keep the bug-fix PR limited to the five production files and shared regression test.

## PR-003: structured AI decisions

The branch records immutable, partial player-view observations, observed candidate assessments, selected actions, interruption status, land shortlists, rejection details, and Monte Carlo summaries. See [the schema](Simulation-Decision-Records.md) and [collection/export guide](../../tools/decision-records/README.md).

Execution-linkage follow-up: selected actions now receive stable correlation IDs, and separate observational records describe execution attempts and results, including copied activated-ability IDs. Existing stack lifecycle and land-play observations carry nullable decision/action references; filtered output and exports retain limited linked-event projections. This belongs with the structured-data enhancement rather than PR-001/002. Downstream effect attribution remains future work; execution success and observed resolution are not strategic-quality labels.

Hardening follow-up: [HARD-001 — assess decision-tracing coupling](Simulation-Decision-Records.md#hard-001-assess-decision-tracing-coupling) remains open ahead of broader instrumentation expansion. Evaluate dependency direction, correlation ownership, reference lifetimes, redundant fields, measured overhead, and maintenance costs before deciding whether the architecture needs changes. This is an assessment task, not a confirmed defect or an approved refactor.

Finalized-target follow-up: stack additions now expose an identity-only snapshot of ordered root/sub-ability targets and divided allocations in both diagnostic and filtered output. Capture uses existing engine APIs at the collector's existing cast-event boundary, without another AI callback or correlation map. The bounded dependency/lifetime review is recorded in [the target schema](Simulation-Decision-Records.md#finalized-targets); HARD-001's broader assessment remains open. Committed root modes now record the executed mode chain (including repeats and entwine) separately from the AI evaluation cache. Announced mana-cost X is also retained without inferring it from mana spent. Recorded payment facts now include mana/card provenance and optional/alternative/repeated costs with explicit accounting limits. Target alternatives, later retargeting, complete non-card cost decisions, and life-payment aggregation remain separate scope within this enhancement.

Upstream [#12180](https://github.com/Card-Forge/forge/pull/12180) proposes opt-in text decision logging and handler ratings. It overlaps the logging goal, but does not replace structured observations, standalone decision streams, datasets, or coverage reports. Coordinate scope before proposing this enhancement. The local Python exporter and report workflow can remain branch-specific unless upstream wants them.

This is a feature proposal, not a prerequisite for PR-001/002. Observations and candidate coverage remain partial; records are not yet training-ready. Do not present handler rejections or deck performance as proof of engine/card bugs.

## PR-004/005: classification and compatibility review

Classification note (2026-11-15): this branch primarily serves personal simulation work. Treat the 64-bit migration as proactive capacity and robustness work, not an established ordinary-game defect. Decide the upstream classification (enhancement, hardening, refactor, or a narrower bug fix) when reviewing requirements, evidence, and scope. Boundary tests establish behavior at large IDs; they do not establish that ordinary upstream games currently encounter these limits.

The shared identifier API and multiplayer serialization changed, including login serialization versioning. Multiplayer peers require matching updated builds. This is acceptable for the current personal branch, but the compatibility impact, migration approach, and upstream acceptance need explicit review before submission.

Keep PR-005's signed 28-bit packed-key limitation distinct from the broader PR-004 migration. It may justify a defect classification independently, subject to checking upstream's supported ID range and reproducing the issue on then-current upstream. Do not label the entire migration a bug fix merely because it includes that change. Both candidates remain deferred; no upstream classification or submission has been decided.

## Branch validation and follow-up

Identifier follow-up (2026-11-15): [the identifier-width design](Simulation-Identifier-Widths.md) describes atomic process-wide allocation, full-ID equality, typed composite lookup keys, long trace indices, and the required multiplayer serialization version change. These candidates have not been checked against current upstream PRs and are not automatically ready for submission. Boundary regressions cover IDs above the old int limit, hash collisions, parallel allocation, network round trips, and trace ordering.

Earlier decision-coverage validation passed the desktop reactor: 2,339 tests, zero failures/errors, six skipped. Four Commander coverage games completed normally, yielding 200 validated decisions and 50 selected land plays; no selected action lacked an assessment. Both Monte Carlo games reported zero rollout failures or unsimulatable-card entries. These are coverage checks, not comparative deck-strength evidence. That validation also passed all 14 Python exporter tests.

Attachment-detach trace translation, bulk stack-clear handling, subscriber failure reporting, timeout status, and filtered-stream ordering are fixes to this branch's tracing functionality. Keep them out of the engine flashback PR unless an independent upstream reproduction is established.

Unsupported effect-duration copying is a known coverage limit to investigate with a concrete reproduction before proposing another engine fix. No upstream PR has been opened from this log.
