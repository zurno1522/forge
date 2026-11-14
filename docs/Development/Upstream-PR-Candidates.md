# Potential upstream pull requests

Review log for changes to consider submitting to [Card-Forge/forge](https://github.com/Card-Forge/forge). Entries are proposals, not published PRs. Recheck upstream master and open PRs before writing them up; shared-file overlap does not necessarily supersede a fix.

Last review and upstream validation: **2026-11-14**, upstream master `eace840dc308de4e385132f9c07fc5bfad3fbfd2` (2.0.17-SNAPSHOT).

| ID | Proposed change | Status | Suggested submission |
| --- | --- | --- | --- |
| PR-001 | Preserve temporary flashback grants and supported pump expiry callbacks in AI game copies | Confirmed upstream bug; fix and regressions validated | Combine with PR-002 in one focused AI simulation bug-fix PR |
| PR-002 | Match the selected flashback ability by its complete cost as well as description | Confirmed upstream bug; fix and regression validated | Combine with PR-001; independent cause in the same simulation path |
| PR-003 | Structured AI decision observations and optional simulation output | Enhancement to discuss; overlaps an existing upstream proposal | Review upstream interest and PR #12180 before proposing a separate feature PR |

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

Upstream [#12180](https://github.com/Card-Forge/forge/pull/12180) proposes opt-in text decision logging and handler ratings. It overlaps the logging goal, but does not replace structured observations, standalone decision streams, datasets, or coverage reports. Coordinate scope before proposing this enhancement. The local Python exporter and report workflow can remain branch-specific unless upstream wants them.

This is a feature proposal, not a prerequisite for PR-001/002. Observations and candidate coverage remain partial; records are not yet training-ready. Do not present handler rejections or deck performance as proof of engine/card bugs.

## Branch validation and follow-up

The combined working-branch changes passed the desktop reactor: 2,339 tests, zero failures/errors, six skipped. Four Commander coverage games completed normally, yielding 200 validated decisions and 50 selected land plays; no selected action lacked an assessment. Both Monte Carlo games reported zero rollout failures or unsimulatable-card entries. These are coverage checks, not comparative deck-strength evidence. All 14 Python exporter tests pass.

Attachment-detach trace translation, bulk stack-clear handling, subscriber failure reporting, timeout status, and filtered-stream ordering are fixes to this branch's tracing functionality. Keep them out of the engine flashback PR unless an independent upstream reproduction is established.

Unsupported effect-duration copying is a known coverage limit to investigate with a concrete reproduction before proposing another engine fix. No upstream PR has been opened from this log.
