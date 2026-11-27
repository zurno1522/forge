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
| PR-006 | Prevent startup stage writes from reviving a completed game | Reproduced locally; same unguarded source on fetched master; no superseding fix identified in scoped open-PR review | Separate engine lifecycle fix; adapt portable regression and rerun current upstream/CI before submission |

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

Hardening assessment: [HARD-001](Simulation-Trace-Coupling-Assessment.md) completed 2026-11-18. Retain the architecture and V1 wire references; separately track HARD-002–007 for reproduced copy-lineage attribution loss, authoritative cleanup, exception/export agreement, exporter scaling, dispatch assumptions and identity validation. Measurements do not justify a broad wire/schema rewrite. The real Necrotic Ooze activation successfully reaches the engine stack in the reproduction; the demonstrated defect is its missing trace linkage. These findings concern this branch's recording/export functionality and do not establish another upstream engine/card defect.

Implementation follow-up (2026-11-18): copy attribution now uses the immediate execution-copy predecessor while preserving the rules meaning of `getOriginalAbility()`. Terminal cleanup, sink-close failure handling, exceptional-run export, order-independent identity validation, and the first exporter scaling improvements are implemented with regressions. These remain part of PR-003. The added immediate-predecessor accessor supports observational correlation; it is not evidence of an upstream rules bug. Reentrant/async capture and within-game retention bounds remain design work; the exporter still retains loaded input graphs and rows. See [current status and evidence](Simulation-Trace-Coupling-Assessment.md#implementation-follow-up-2026-11-18).

Remaining-design implementation (2026-11-19): synchronous emission-time capture now covers same-thread reentrancy, classified stack removals release history during play, and the exporter uses a private disk-backed spool. The game owns observation retirement, including trackers from replaced controllers. These changes remain within PR-003's recording/export enhancement and do not establish an upstream rules/card defect. The generic observer API and internal removal-event fields need upstream API/network review if submitted; the V1 JSON contract is unchanged. See [implementation and validation](Simulation-Trace-Coupling-Assessment.md#remaining-design-implementation-2026-11-19). Asynchronous output and concurrent rules mutation are separate scope.

Finalized-target follow-up: stack additions now expose an identity-only snapshot of ordered root/sub-ability targets and divided allocations in both diagnostic and filtered output. Capture uses existing engine APIs at the collector's existing cast-event boundary, without another AI callback or correlation map. The bounded dependency/lifetime review is recorded in [the target schema](Simulation-Decision-Records.md#finalized-targets); HARD-001's broader assessment is now complete, with follow-up work recorded separately. Committed root modes now record the executed mode chain (including repeats and entwine) separately from the AI evaluation cache. Announced mana-cost X is also retained without inferring it from mana spent. Recorded payment facts now include mana/card provenance and optional/alternative/repeated costs with explicit accounting limits. Target alternatives, later retargeting, complete non-card cost decisions, and life-payment aggregation remain separate scope within this enhancement.

Upstream [#12180](https://github.com/Card-Forge/forge/pull/12180) proposes opt-in text decision logging and handler ratings. It overlaps the logging goal, but does not replace structured observations, standalone decision streams, datasets, or coverage reports. Coordinate scope before proposing this enhancement. The local Python exporter and report workflow can remain branch-specific unless upstream wants them.

Subordinate-choice follow-up (2026-11-19): the first increment observes existing removal/damage target-planning pools inside parent decisions, with exact candidate links, bounded identity snapshots and explicit capture/coverage limits. The post-commit review fixed cancellation blocking and unbounded pool capture; regressions and eight real games validate the branch's instrumentation. Opponent preferences are distinguished from target recommendations. Execution/delegated targeting, other handlers, modes, X and payment choices remain work; this does not complete the milestone or establish an upstream rules/card defect. Keep it within PR-003's enhancement classification. See [review, measurements and remaining gaps](Simulation-Decision-Records.md#target-choice-commit-review-2026-11-19).

Committed-target follow-up (2026-11-20): explicit addition tokens and an opaque engine target-plan state marker now support recommendation-to-commit attribution at instrumented removal/damage sites. Reset/reselect and shared plans from a different selected candidate do not invent links; unknown slots remain explicit. The marker carries no wire IDs or AI references, and the recorder/tracker own bounded choice registries. This remains PR-003 instrumentation, not a new upstream rules defect. Review the optional `TargetChoices` marker API and iterator wrapping if upstream submission is proposed; delegated/retargeted choices and broader handler coverage remain separate work. See [the contract and remaining scope](Simulation-Decision-Records.md#committed-target-choice-provenance).

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


### Subordinate payment observation boundary (2026-11-20)

The learning branch adds `CostDecisionMakerBase.decide(CostPart)` with a default visitor call, used by both `CostPayment` paths. `AiCostDecision` overrides it for observational events; human decision makers retain the default. This is an extension point for telemetry, **not an engine defect**. Any upstream proposal should be classified as infrastructure/observability and review the added engine API independently of learning event schemas. No PR is opened by this entry. Payment proposals, actual routine results and finalized payment facts remain distinct, and no selection/payment policy is changed.


### Monte Carlo target-operation provenance (2026-11-20)

Final main-phase search revalidation/restoration/replacement now records its own operation and exact committed target origins. The Reality Chip example exposed missing provenance after a plan rebuild, not an established Forge engine or card-script defect. Keep this work with PR-003's observational feature discussion. The increment changes only AI recording/controllers, exporter validation/reporting and tests; it adds no rules-level API or learning IDs. Target-provenance wire version 2 is explicit for the new operation references, while old records retain version 1. No upstream PR is opened by this entry.

### Inline choice-context follow-up

Representative inline player-view contexts, scoped delivered facts, and exporter validation belong with PR-003 as an observability enhancement. They do not establish a Forge rules or card defect. This increment adds no engine API or learning identifiers to forge-game. Review the optional wire capability and its privacy/coverage limits if proposing it upstream; no PR is opened by this entry. See [the implemented profile and remaining gaps](Simulation-Input-Context.md).

### Prefix admission metadata (2026-11-27)

The [V29 prefix increment](Simulation-Prefix-Admission-Assessment.md) adds observation-only gate receipts, bounded player/Game counters and original/receiving-controller ownership checks. It keeps native callbacks, the policy projection and the fixed allowance. Replacement/prompt and foreign-player allocation gaps are branch-specific observation hardening under PR-003, not new Forge card/rules defects. Optional sampling remains behind production cost measurement. Review wire compatibility and privacy/coverage limits before any upstream proposal; no PR was opened.

### Spell-ability observation increment (2026-11-25)

V13 adds controller spell-ability operation/selector records, own entry clocks and received outer counts, validated by the full reactor, legacy exports and normal constructed/Commander games. It preserves the original AI do-while, including one pick for a nonpositive direct request. No shipped zero-count caller or upstream rules defect is established; do not classify that test edge as an engine bug without a separate caller/requirements audit and upstream review. Typed single parameters and spell-choice history remain local recording work under PR-003's enhancement scope. See the [contract and review](Simulation-Spell-Ability-Choice-Contract.md).

### Monte Carlo scavenge scenario reliability (2026-11-25)

The V15 coverage reactor runs 2,887 desktop tests with one failure: `FlatMonteCarloActivatedScenarioTest.scavengeOntoACreature` chooses null instead of Slitherhead. A controlled twelve-seed harness reproduces this with both initial V15 and a four-class pre-V15 AI overlay (`3c8dac5aa44`), with tracing disabled in every sample. Each fresh JVM fails its first sample (seed 1), then passes seeds 2–12. This establishes pre-existing scenario instability and suggests cold/deadline sensitivity; the exact cause, broader frequency and current upstream status are unverified.

Keep this as validation triage, not a confirmed engine/card defect or a ready PR. Investigate search/test reliability and check current upstream before proposing a fix. Do not weaken the assertion or increase production budgets to hide the failure. Final trace regressions pass independently. [Evidence and limits](assessments/COV-006-spell-progress/scavenge-triage/summary.json).

### Disabled nested entity-operation masking (2026-11-25)

A nested entity operation entered while tracing is disabled could borrow an older pending history claim if its callback reenabled tracing. The regression fails before the fix and passes after routing the disabled path through the existing null-owner masking scope. This is a defect in this branch's PR-003 observation feature, not an established upstream engine/card issue. No new engine API, schema or feature permission is added. [Reproduction and review](Simulation-Entity-Off-Masking-Hardening.md).

### Neutral floating-mana request bridge (2026-11-26)

COV-007A adds a callback-local engine request carrying the paid ability, current shard, test flag and converge mask through the existing floating-mana tie-break. Default and AI bridges preserve old virtual controller dispatch, weighting/deduplication, selected-object identity, spending and refunds. Old direct APIs remain supported with unknown request context. The engine record contains no tracing types, IDs or AI dependency.

Track this with PR-003 as an enhancement candidate; consider whether the neutral API should be proposed independently if upstream has a use for richer custom controllers. Losing a test flag from our trace attribution is a branch observation gap, not evidence of an upstream rules/card defect. A standalone proposal would need then-current upstream/PR review and portable controller-dispatch/refund tests separated from branch-specific trace fixtures. No upstream PR is opened here. [Contract, actual engine validation and limits](Simulation-Mana-Pool-Request-Contract.md).

## PR-006: startup can overwrite a terminal game stage

COV-008B native timeout testing exposed two outcomes from one game: initialization overwrote GameOver with Mulligan/Play after a forced draw. No admission State had been created, so the experimental policy had not run. GameAction now checks terminal state and writes each startup stage under Game's existing monitor. Public setAge and snapshot/restart semantics remain unchanged. Both native regression paths fail before the fix and pass after it; the focused suite passes 37 cases.

On 2026-11-27, fetched master GameAction/Game sources still contain the unguarded assignments and setter. Review covered all 242 open PR titles/bodies and changed filenames for the seven nearest candidates (#12253, #13098, #12246, #12790, #11627, #11314, #12180); no superseding fix was identified in that scoped review. This is not an exhaustive unrelated-patch audit or upstream runtime validation. Adapt the regression's branch draw helper and rerun then-current upstream CI before a PR. No upstream PR opened. [Reproduction, retained invalid trace and review](Simulation-Startup-Termination-Hardening.md).
