# Simulation decision records

When decision collection (`sim ... -log-decisions decisions.jsonl`) or structured game tracing (`sim ... -log-game path.jsonl`) is enabled, the real game's AI emits an `AiDecisionEvent`. The desktop trace collector writes it as `AI_DECISION` in the same JSONL stream as game events. The AI has no file-writing dependency. These observational events do not use the rules-event visitor or change engine event ordering.

Version 1 covers play selection with an empty stack during the acting player's own first or second main phase. It includes land selection, spells, abilities, and passing. An optional subordinate-choice capability records specific target-planning pools during these evaluations, as described below. Complete target/mode/payment choice coverage, mulligans, combat and reactive-priority decision records remain outside this version's coverage. Recording is off by default outside traced simulations and suppressed for simulation copies. The AI's existing policy and candidate limits remain in place; the initial observation is captured before its thinking deadline starts. Per-pool capture adds bounded work during evaluation.

Each record retains the usual `game`, `eventIndex`, `turn`, and `phase` envelope, with a nested `decision` object:

- `decisionSchemaVersion`: currently 1.
- `decisionId`: player ID plus a sequence allocated by the game. Join with the run/game identifiers, rather than treating this as globally unique.
- `playerId` and `decisionType` (`MAIN_PHASE_PLAY_SELECTION`).
- `observation`: captured before evaluation, with phase/turn/player, player life totals, hand/library counts, visible cards in public zones and the acting player's hand, and floating mana colors. Visible card details include types, mana cost, creature stats and counters.
- `candidates`: ordered assessment records from the evaluation paths actually visited, with ability/source identifiers and the existing `AiPlayDecision` reason where available. Strategic skips and pre-evaluation filtering have separate labels. The same ability may be assessed more than once. Legacy land fast paths and some combo paths may select an action without candidate assessment rows. New land records contain per-card `LAND_OPTION` rows at the `LAND_SHORTLIST` stage, with `heuristicDefault` and `searchOffered` flags. Uninstantiated land options intentionally have no `abilityId`; match land choices by `sourceId` and kind. These rows are options, not rejections, and do not enumerate every alternative face or payment choice.
- `selection`: `PLAY`, `PASS_PRIORITY`, or `NO_ACTION` on a selection error; `selectedActions` records what the AI returned. New records declare `actionExecutionSchemaVersion: 1` and assign each selected action an `actionId` for joining its execution records.
- `status`: `COMPLETED`, `AI_TIMEOUT`, `EVALUATION_TIMEOUT`, `EVALUATION_ERROR`, or `ERROR`. An interrupted evaluation can still return a partial choice. Do not treat a timeout pass as an ordinary training example.
- `elapsedNanos`: selection-record construction and thinking time, excluding writing the final event.
- Optional `search`: summary of the current flat Monte Carlo result (reason, whether rollouts decided it, evaluated-candidate count, rollout count and duration). This is not a per-candidate score distribution; search scores are not exported in v1.

`candidateCoverage` explicitly reads `OBSERVED_EVALUATIONS_NOT_ALL_LEGAL_ACTIONS`. Existing strategy filters, early exits, and limits mean this is neither an exhaustive legal-action set nor a legal-action mask. Rejections can reflect strategic preference or inability to pay, not rules illegality. Passing does not mean no legal play existed.

`observationCoverage` is `PARTIAL_PLAYER_VIEW_V1`. Opponent hands and all library identities/orders are omitted; face-down identities are conservatively masked, even if the acting player could inspect them. Other-player revealed hands, remembered reveals, detailed mana restrictions, player counters, commander-damage history, and delayed effects are not fully represented. This observation is not yet a complete training state.

Payloads contain frozen JSON values rather than mutable engine/card objects. Worker evaluation captures its own recorder, and a closed recorder ignores late assessments. Collector write/translation failures remain visible through the existing trace failure reporting.

The surrounding diagnostic stream can contain privileged information: training consumers must explicitly use `decision.observation`, rather than feeding every trace field to a policy. Outcomes and engine/deck/build metadata live in the existing run/game records. Administrative game timeouts and errors must be excluded or censored when constructing training outcome labels. Execution linkage is described below; it does not assign per-action rewards.

Standalone collection and export are described in [the exporter guide](../../tools/decision-records/README.md). The decision-only stream includes run/deck/pilot metadata and game outcomes so an exporter can produce a report without any full trace. Full and filtered outputs can be enabled together with different paths. The filtered header declares shared event indices when applicable; no recorded events are reordered.

Candidate assessments can optionally include `assessmentDetail`: a reason code and, for card-handler rejections, the handler name, its original assessment and integer `handlerRating`. Codes distinguish `PREVIOUS_CAST_FAILED_THIS_TURN`, `AI_SPECIFIC_RESTRICTION`, `NO_PLAYABLE_CARD_FACE`, `CANNOT_PAY_COST`, `RESERVED_TAP_RESOURCES`, `CARD_AI_DECLINED` and `CARD_AI_RATING_TOO_LOW`. The outer assessment and AI approval thresholds are unchanged. Handler details are suppressed for masked source identities, and details remain attached to their originating evaluation even after cancellation. Old records without these fields remain valid.

`MissingPhaseRestrictions` is an existing card-handler assessment label for a failed phase check; it does not by itself establish that a card script is missing configuration. Handler assessments explain the handler's return value, while the outer assessment preserves the controller's original result.

Engine IDs and event indices use 64-bit integers; composed decision/action IDs remain strings. See [identifier widths and compatibility](Simulation-Identifier-Widths.md), including multiplayer versioning and consumer integer requirements.

## Observed subordinate target-planning choices

Priority 1 remains in progress. New `AI_DECISION.decision` objects declare `subordinateChoiceSchemaVersion: 1`, `subordinateChoiceCoverage: OBSERVED_HEURISTIC_TARGET_POOLS_V1`, and a `subordinateChoices` array. The records remain inside the parent decision because the observed operations occur during candidate evaluation, before action IDs exist. No extra engine events are emitted or reordered. Full and filtered output retain identical choice data, and exports preserve it within the existing decision object.

The adapters wrap existing chooser calls in `DestroyAi` and `DamageDealAi`. They observe the list already passed to that chooser and invoke the same chooser once on the same original list. They do not enumerate legal actions, rerun the chooser, add randomness, change scoring or apply new legality checks. Pool references are captured before that wrapped call; later mutations of the list or cards cannot change the emitted snapshot.

| Field | Meaning |
| --- | --- |
| `choiceId`, `choiceSchemaVersion` | Opaque choice ID scoped to the parent decision, schema version 1. Current IDs compose the parent ID with a target-choice ordinal; do not parse them for joins. |
| `choiceType`, `stage` | `TARGET_POOL_SELECTION` at `CANDIDATE_EVALUATION`. This is a planning/heuristic observation, not an execution claim. |
| `chooserPlayerId` | The AI player making the observed evaluation, matching the parent decision. |
| `candidateAbilityId`, `candidateIndex` | Candidate identity and nullable index into the parent's assessment array. Repeated evaluations have distinct indices. Cancellation can leave no completed assessment. |
| `abilityId`, `sourceId` | Actual evaluated ability (which can be a sub-ability) and its source card. |
| `candidateActionId` | Nullable link only when that exact candidate object is returned in the parent's selected actions. It does not prove the pool recommendation was committed or executed. No root-lineage/card-name matching is attempted. |
| `boundary`, `purpose` | Identifies the wrapped operation. Current purposes distinguish `TARGET_RECOMMENDATION`, `OPPONENT_PREFERENCE`, and `MASKED_TARGET_PLANNING`. Source-specific boundary/purpose information is masked for hidden source identities. Older choice records may omit `purpose`. |
| `optionCoverage` | `OBSERVED_HEURISTIC_POOL_NOT_ALL_LEGAL_TARGET_SETS`. This is not a complete legal-action mask or an assertion that every entry is a legal spell target. |
| `options`, `priorTargets` | Ordered identity-only references for the observed pool and targets already present on this particular ability when the helper was called. Prior targets are not the entire ability-chain history. |
| `selected`, `selectedOptionIndex` | Actual returned identity and its unique matching index within the captured pool, or null when no unique observed index is available. |
| `status` | `SELECTED` for one observed match; `DECLINED` when the helper returned null; `OUTSIDE_OBSERVED_POOL` or `AMBIGUOUS_MATCH` when the identity has zero or multiple observed matches; `ERROR` when the helper threw; `INCOMPLETE` if the parent published before return. |

Card references contain `{type: CARD, id, gameTimestamp}`; player references contain `{type: PLAYER, id}`; ability references contain `{type: SPELL_ABILITY, id, stackId}`, with nullable stack ID. They carry no names, descriptions, rendered prompts or raw script text. Choice IDs remain strings, and engine identifiers/timestamps remain 64-bit values.

`DAMAGE_OPPONENT_POOL` observes the existing preference for an opponent whose board/life total the damage heuristic will consider. The preferred player need not become a spell target: the AI may subsequently choose a creature. Removal pools and damage killable/fallback/planeswalker pools similarly record recommendations that later logic may decline or replace. Compare them with `finalizedTargets` to inspect differences; matching identities alone does not establish which of several evaluations caused a committed target.

Capture retains at most 128 pool records per parent and a 256-entry prefix of each options/prior-target list. New records declare boolean `optionsTruncated` and `priorTargetsTruncated`; false means the observed helper list was captured in full, not that all legal target sets were enumerated. A chosen object outside a retained prefix is still recorded, with no invented index. `subordinateChoiceOmissions` records nonnegative `budget` and `interrupted` counts. Missing truncation/omission declarations in earlier choice records remain unavailable rather than inferred complete/zero. These metadata fields are additive to subordinate schema 1.

Pool traversal occurs outside the recorder monitor and stops when the parent closes or the evaluation thread is interrupted. Publication can close a timed-out parent without waiting for pool enumeration; snapshots still in flight at publication are counted as interrupted omissions. A recorded pool whose chooser has not yet returned instead retains `INCOMPLETE`. Late capture results/errors cannot mutate that decision or poison a later one. Active capture failures use the existing Game observation-failure channel without changing the chooser's return/exception. These safeguards bound additional pool-capture state; they do not bound the pre-existing observation/candidate graphs or guarantee an untrusted blocking iterator will terminate.

Exporter validation checks the nested schemas, identity/index consistency, exact selected-candidate links, status/index relationships, masked-source boundaries, truncation and omission metadata. Reports distinguish declared/legacy coverage, pool types/purposes, return statuses, captured sizes, budget/interruption omissions and unavailable metadata. Presence of a pool record on a selected targeted action is counted separately from complete choice coverage. Choices remain `trainingReady=false` through their parent dataset row; no choice reward or downstream-effect attribution is assigned.

Remaining scope: execution-time and delegated target choices; retargeting, triggers and copies; other AI handlers; per-option scores/reasons and complete legal sets; modes, announced-X decisions and payment decisions. Observation timestamps still need their own contract before assuming a complete option-to-observation incarnation join. The [collection/export guide](../../tools/decision-records/README.md) describes standalone analysis.

### Target-choice commit review, 2026-11-19

The first implementation is `1b15a89e0c3`; post-commit review produced `2291f2278a1`. Review found that traversing pools under the parent recorder's monitor could block timeout publication, and that capturing arbitrary pool/record counts could add unbounded state. Three regression cases failed on the first commit and passed after the fix: blocked enumeration with a late snapshot failure, a pool exceeding the reference limit, and more chooser calls than the record budget. All original chooser calls still execute. Review also separated opponent preferences from target recommendations and made example sampling include sparse choice records. No rules fix is claimed.

Final validation passed 2,426 desktop cases (six skipped), 23 core cases, 23 game cases and 65 Python exporter tests, with no failures/errors. Ten legacy inputs produced all six export files byte-for-byte identically to the pre-feature exporter. See [the evidence manifest](assessments/Subordinate-Targets/manifest.json) and [legacy comparison](assessments/Subordinate-Targets/legacy-equivalence.json).

Eight fresh recorded games completed normally, producing 471 decisions and 38 pool records. Full and filtered inputs produced byte-identical datasets, examples and validation files; their coverage JSON differed only in source-record count. Every full game retained pregame shuffles before `GAME_STARTED`. Coverage and provenance reports retain the actual input count/kind/hash. See [runtime validation](assessments/Subordinate-Targets/validation.json).

| Workload | Games | Decisions | Pool records | Exact pool presence / selected targeted actions | Target-recommendation presence |
| --- | ---: | ---: | ---: | ---: | ---: |
| Black removal / green, heuristic, seed 12031 | 3 | 194 | 8 | 2 / 4 | 2 / 4 |
| Red / green, heuristic, seed 7451 | 3 | 173 | 30 | 1 / 1 | 0 / 1 |
| Doctor Who / Ur-Dragon, heuristic, seed 31002 | 1 | 49 | 0 | 0 / 0 | 0 / 0 |
| Doctor Who / Ur-Dragon, Monte Carlo, seed 31003 | 1 | 55 | 0 | 0 / 1 | 0 / 1 |

These denominators cover selected actions with an observed targeted stack snapshot, not every decision or subordinate operation. The red records are opponent preferences. Uncovered selections include two Sign in Blood player targets and The Reality Chip's reconfigure target. Zero pools in the Commander samples do not establish absence of choices. All recorded pools were captured in full, with no budget/interruption omissions. The three black games also matched an unrecorded control's winners and turns; this does not establish identical action sequences. The Monte Carlo run reported zero rollout failures and one slow-card exclusion for Yasmin Khan. It produced 17 main-phase search summaries; console totals also include reactive search.

An [actual message example](assessments/Subordinate-Targets/actual-example.json) contains the complete parent decision and its linked stack-addition projection. Befoul's helper selected index 6 from nine observed card identities. Its exact candidate later stacked with the same target. This example illustrates the data, not a general inference rule connecting a recommendation to execution.

The [producer-cost control](assessments/Subordinate-Targets/producer-cost.json) measures one pool plus the existing ranking and parent capture/freeze, excluding serialization and file output. Across three rotated samples, a 30-object pool increased median calling-thread CPU from 764.9 to 791.8 microseconds and allocated bytes from 611,356 to 638,663 per iteration. A four-object pool added about 6.1 KB of allocation; its CPU difference was smaller than timing noise. Capture runs inside the AI's evaluation deadline. These shared-host measurements do not guarantee identical search work, bounded whole-game memory, or timing-neutral gameplay.

The remaining priority-1 work is:

| Order | Gap | Completion evidence |
| --- | --- | --- |
| 1 | Execution and delegation provenance | Distinguish evaluation recommendations from committed/execution-time choices, record who chooses when control is delegated, and link retarget/copy/trigger choices without inferring causation from matching target IDs. Preserve explicit unknown cases. |
| 2 | More target handlers and branches | Add observed choice boundaries for uncovered draw/player targeting, attachment/reconfigure, change-zone, pump/fight and special-case paths. Exercise real multi-option choices; report presence separately from complete coverage. |
| 3 | Modes | Record observed mode options and selections, including repeats/nesting; join the choice to committed modes while retaining changed or abandoned evaluations. Existing finalized snapshots alone are insufficient. |
| 4 | X and payment decisions | Record the available observed number/range and actual selections, then actual payment choices with provenance. Do not turn spent mana or partial accounting fields into invented alternatives. |

The separate observation/visibility contract must establish how option identities and card incarnations join to the player's state. Per-option values/reasons, complete legal sets and per-choice rewards are also absent. Priority 1 remains **in progress**, and dataset rows remain `trainingReady=false`.

### Committed target-choice provenance

The 2026-11-20 follow-up links explicit target additions from the observed removal/damage helper results to the committed stored plan. New parent decisions declare `targetChoiceProvenanceSchemaVersion: 1`. A linked `STACK_ADDED` can include a `targetChoiceProvenance` snapshot alongside the unchanged `finalizedTargets`. The snapshot is captured synchronously at event emission and retained in both streams and exported engine-event projections. No additional event is emitted, and no legality check or chooser is rerun.

| Field | Meaning |
| --- | --- |
| `schemaVersion`, `coverage` | Version 1, `EXPLICIT_TARGET_ADDITIONS_V1`. Coverage is limited to instrumented additions, not all target choices. |
| `abilities` | Ordered root/sub-ability prefix aligned with `finalizedTargets.abilities`. Each `abilityId` is the executed ability ID, which can differ from the evaluated ID after copying. |
| `abilities[].targetChoices` | Ordered prefix aligned with that ability's finalized target slots. An entry is null when no explicit origin is established, or `{choiceId, chooserPlayerId, stage}` when one is established. |
| `choiceId` | References an existing subordinate choice on this action's parent decision, scoped by run and game. The exact candidate must have been selected. Repeated assessments retain distinct choice IDs. |
| `chooserPlayerId`, `stage` | The origin's recorded chooser and `CANDIDATE_EVALUATION` stage. This identifies the evaluation that supplied the stored target; it does not claim a new execution-time choice occurred. |
| `abilitiesTruncated`, `abilities[].targetsTruncated` | Explicit prefix flags: at most 128 ability groups and 256 target slots per group. Omitted slots remain unobserved. |

The AI passes a token for the particular helper invocation to the existing target-addition site. The token can establish provenance once; it cannot attribute repeated additions by matching an object ID. Substituted targets, uninstrumented additions, known delegation and changed incarnations remain unknown. Direct player preferences do not become target origins. Without an observed stack addition, no committed-stack snapshot is inferred.

`TargetChoices` supplies an opaque, lazily allocated identity for its ordered list state. Its engine clone preserves that identity until a mutation attempt, including iterator writes, invalidates it. Resetting targets or removing and readding the same object cannot restore an origin by value equality. Divided allocations do not change this marker: it describes ordered target identities, not allocation choices. The marker contains no IDs, metadata, or references to an AI, recorder, game or previous plan. All choice data lives in a bounded AI-owned registry. On selection, only origins belonging to that exact candidate and its current plan are retained; a different candidate sharing the same target list does not inherit an origin. The action tracker releases its registry when the attempt terminates or the observation session retires.

A null origin means unknown, not that no decision occurred. A missing/null provenance snapshot means unavailable/legacy data. The exporter checks declarations, prefix flags, ability/slot alignment, chooser/stage, exact selected-candidate ownership, and the committed identity including card incarnation. Identity comparison validates an explicit link; it never creates one. Reports count `EXPLICIT_EVALUATION_ADDITION`, `UNKNOWN` and `TRUNCATED` target slots separately from earlier pool-presence metrics. Dataset rows remain `trainingReady=false`.

Remaining scope includes new execution-time selections, delegated choice records, retargeting, effect copies/triggers, other handlers and removal special branches, mode/X/payment choices, and the observation-state identity contract. This closes recommendation-to-commit attribution for unchanged plans at the instrumented additions; it does not complete target-choice coverage or priority 1.

## Selected action execution

`AI_ACTION_EXECUTION` is a separate observational event in both full and decision-only streams. Its normal envelope contains the actual emission turn, phase, and event index. The nested `execution` object has `executionSchemaVersion: 1`, `decisionId`, `actionId`, `playerId`, the selected `abilityId`, `sourceId`, and `kind`, plus `status` and `reason`. Action IDs are the decision ID plus a zero-based selected-action ordinal; join them within their run and game. Candidate evaluations do not get action IDs.

The lifecycle is `STARTED` followed by one terminal record, or `ABANDONED` without a start:

| Status | Meaning |
| --- | --- |
| `STARTED` | The controller began executing this selected action. |
| `SUCCEEDED` | A land play completed (`LAND_PLAYED`), a spell/activation reached the stack (`STACKED`), or a mana ability's immediate activation was registered by the engine and its execution helper completed (`RESOLVED_WITHOUT_STACK`). |
| `FAILED` | The internal casting/activation attempt failed, including rollback, or the land was not played. This uses the internal result, not the controller's priority-loop return value. |
| `ABANDONED` | The selected action was never attempted before the next selection or the end of game execution. An engine game-finish announcement alone does not establish abandonment. |
| `ERROR` | Execution threw; the exception is rethrown after recording the result. Exception messages and card identities are not included in the execution payload. |
| `UNOBSERVED` | The helper returned success but no supported execution boundary was observed. No success is inferred. |

For `STACKED`, `executedAbilityId` and `stackId` identify the actual stack entry. Activated abilities can have a different engine ability ID from the selection. Explicit aliases across casting transformations and the engine's original-ability relationship connect those objects; card names/descriptions are never join keys. Derived triggers and spell copies do not become additional selected actions. Land success follows the engine's land-play counter, including plays whose zone change was replaced; it does not imply the land remained on the battlefield. Immediate mana activations use the engine's activation history and a successful helper return. Other immediate paths without an observed boundary remain `UNOBSERVED`.

Tracking is limited to the recorded selections, suppressed in simulation copies, and does not change AI policy, controller return values, rules events, or their ordering. `STARTED` precedes the attempt and the terminal record follows its internal return, so intervening engine events retain their original order.

Execution success is not proof of resolution: a successfully cast spell can be countered, and a resolved spell can have little effect. Finalized stack targets are described below; target alternatives, mode/payment decision records, and attribution of downstream effects remain separate work. Pass decisions have no selected actions and require no execution records.

The exporter validates identities, lifecycle order, duplicate terminal records, and completeness in normally completed games that declare execution linkage. Administrative timeouts/exceptions may leave an attempt without a terminal result; exports mark it `INCOMPLETE`, without inventing failure or abandonment. Legacy actions without declared linkage are `UNAVAILABLE`. Abandoned, erroneous, unobserved, incomplete, and post-outcome executions are censored for outcome labels. A definitive failed attempt can retain its eventual game outcome; that label still does not rate the action's quality. All rows remain `trainingReady=false`.

## References on engine events

New run headers declare `actionEventSchemaVersion: 1`. Existing `STACK_ADDED`, `STACK_RESOLVED`, `STACK_COUNTERED`, `STACK_REMOVED`, and `LAND_PLAYED` records carry nullable top-level `decisionId` and `actionId` fields. They identify the recorded selection directly responsible for that stack entry or land play. They are null for human actions, unrecorded AI selections, triggers, effect copies, or events without an observed identity match. Running an AI game alone does not guarantee a non-null reference. These fields add correlation to the existing observations; they do not add legality checks or change engine events.

The collector obtains a stack entry's reference while the selection is executing, then retains it by the unique stack ID for later observations. This accommodates copied activation objects and keeps later events linked after the casting attempt has finished. `STACK_COUNTERED` refers to the **countered action**; its diagnostic `cause` identifies the countering ability separately. Forge emits removal before the counter announcement, and the trace preserves that order. A `STACK_REMOVED` event alone does not say why the entry was removed.

`STACK_CLEARED` can represent several actions. Its scalar references are null, with a `clearedActions` list containing each observed linked entry's `stackId`, `decisionId`, and `actionId`. The active ability-to-stack mapping is then cleared. A resolution announcement after a bulk clear can therefore have null references; the collector does not guess from a card name or a past ability ID.

Decision-only output retains a projection of these existing events only when they link to a recorded selection (or list linked entries in a bulk clear). The projection contains the event envelope, reference IDs, available stack/ability IDs, the resolution's `fizzled` flag, and the optional `finalizedTargets`, `finalizedModes`, `finalizedX`, and `finalizedPayments` snapshots described below. It omits source/card descriptions, rendered diagnostic choice details, and other privileged diagnostics. Full traces retain their existing details. Neither output reorders observations; standalone decision-stream indices remain contiguous, while dual output uses shared full-trace indices.

Exports retain these projections in each selected action's `actionExecutions[].engineEvents`, with their original event indices and `postOutcome` flags. A bulk clear is projected once per listed action. `engineEventCoverage` is `LINKED_EVENTS_V1` for declared coverage and `UNAVAILABLE` for legacy/unlinked actions. Absence of a resolution event does not establish failure or resolution: games can end with actions on the stack, and collection can be interrupted. The exporter does not invent a result, downstream-effect attribution, or a per-action reward. Post-outcome engine observations are retained and censored for outcome labels.

## Finalized targets

New `STACK_ADDED` records include `finalizedTargets`, captured from the live committed stack entry at the existing cast event. Its `schemaVersion` is 1. `abilities` contains the root and its sub-ability chain in engine order. Each group has the engine `abilityId`, a boolean `usesTargeting`, and a `targets` list in the engine's selection order. Groups remain separate when several parts of a spell target the same object. No new decision/action IDs are allocated.

Target references contain only these fields:

| Type | Fields beyond `type` |
| --- | --- |
| `CARD` | `id`, `gameTimestamp` identifying the engine card object at capture time |
| `PLAYER` | `id` |
| `SPELL_ABILITY` | `id` for the targeted engine ability, `stackId` for its observed live stack entry or null when unavailable |

An optional integer `dividedAmount` retains the engine's allocation to a target, such as divided damage. The snapshot exports no card/player names, descriptions, hidden faces, or candidate lists. Card names may be joined from an already-visible observation; the snapshot does not supply missing identities. Card timestamps distinguish zone-change incarnations that reuse the same card ID. Existing observations do not yet contain these timestamps, so they are not a complete cross-record object-identity mapping.

An observed ability without targeting has `usesTargeting=false` and an empty target list. An ability that permits choosing zero targets retains `usesTargeting=true` with an empty list when none were chosen. A null snapshot means the live entry was unavailable; an absent field is valid legacy data. Neither means an observed empty choice. Snapshots are copied into JSON values immediately, so later retargeting, resolution, or removal cannot mutate them.

Full and decision-only output carry the same snapshot on linked additions. The exporter validates its shape, root ability identity, identifier types, allocations, and absence of extra diagnostic fields, and retains it in the existing `engineEvents` projection. Reports distinguish observed snapshots from unavailable/legacy ones and count target references by type, including sub-abilities; these counts are not counts of unique targets or decisions.

This closes finalized-target capture for observed stack additions, not the entire action-details gap. It does not enumerate alternatives, explain target selection, record later retargeting, or cover untargeted choices made during resolution. Targets can come from delegated choices or random effects as well as the acting AI. These committed action parameters are action/outcome data, not additional pre-decision observation features. Finalized modes, announced X, and available payment facts are described below.

Boundary review for HARD-001: `SimulationTargetSnapshot` depends only on existing engine target/stack APIs and returns copied JSON values. The collector calls it at its existing cast-event boundary; it adds no AI callback, module dependency, subscriber, retained engine reference, or correlation map. Snapshot retention ends with the existing write; the Python exporter owns the resulting dataset lifetime. Existing collector-to-`AiController` correlation lookups and their timing assumptions remain unchanged. Added work is proportional to the committed ability chain and its target references per observed addition; the completed [HARD-001 assessment](Simulation-Trace-Coupling-Assessment.md) covers broader performance, correlation and lifetimes. This boundary review does not establish that the overall architecture is free of detrimental coupling.

## Finalized modes

`STACK_ADDED.finalizedModes` is an optional version-1 snapshot with `schemaVersion`, boolean `modal`, and an ordered `modes` list. It describes root `Charm` abilities, the engine's standard modal-spell representation. Each mode contains its executed `abilityId` (also found in the target snapshot's ability chain) and a zero-based `optionIndex` in the root's complete `Choices` list. Repeated selections remain repeated entries. Nonmodal actions have `modal=false` and an empty list; a modal zero-choice action has `modal=true` and an empty list. Null or absent data is unavailable/legacy, not an observed absence of modes.

Capture reads the committed sub-ability chain, not the AI's cached `chosenList`: random choices, entwine, and delegated choices can bypass that cache. The read-only adapter matches each copied option's complete chain against original script-parameter maps, consuming nested effects as part of their mode. It does not join by rendered descriptions or assume every sub-effect is a separate mode. Identical option definitions yield a null `optionIndex`; ambiguous chain boundaries or unrecognized definitions yield a null snapshot rather than a guessed mapping. No script parameters or descriptions are exported.

Both outputs and the exporter retain the same mode snapshot on linked additions. Reports count observed/unavailable snapshots and chosen-mode counts for modal actions. These are committed parameters, not mode alternatives, decision reasons, or choices made later during resolution. This adds an adapter dependency on the engine's existing Charm chain construction, with no engine changes, callbacks, persistent maps, or new allocated IDs. The completed [HARD-001 assessment](Simulation-Trace-Coupling-Assessment.md) records targeted implementation follow-ups. Announced X and available payment facts are described below.

## Announced X

`STACK_ADDED.finalizedX` contains `schemaVersion: 1` and nullable `announcedValue`, copied from the committed root ability's announced mana-cost X. Zero is retained as zero. A null value means the engine has no announcement in that field; it is not inferred as zero, and does not describe X calculated by an effect or a later number choice. A null/absent snapshot is unavailable/legacy data. Announced X is separate from mana spent: Walking Ballista with X=2 costs four mana because its cost is XX. Both output streams and exported engine events retain the snapshot, and coverage reports show snapshot availability and announced values. No new callbacks or engine rules checks are introduced.

## Recorded payment facts

`STACK_ADDED.finalizedPayments` is a version-1 snapshot with `coverage: ENGINE_PAYMENT_FACTS_V1`. It is deliberately partial: it captures the committed root ability's available accounting fields, not every cost decision or payment alternative. Null/absent data is unavailable/legacy. Effect copies receive null so copied historical payment lists are not presented as newly paid costs.

The fields are:

- `mana`: one entry per recorded spent mana unit, in engine order, containing `color`, the mana record's nullable `playerId`, nullable source `{id, gameTimestamp}`, and nullable `snow` flag. This is not a complete record of restrictions, producer abilities, or alternative mana-payment plans.
- `lastLifePayment`: the engine's last recorded life payment. `Player.payLife` overwrites this field per payment. It is not a total. `phyrexianLifePaid` is the engine's separate accumulated phyrexian-life accounting field; do not add them together. A Dismember cast can spend four life while these fields read 2 and 4 respectively. Replacements and multiple non-phyrexian life payments are not reconstructed.
- `optionalCosts`: selected `OptionalCost` enum names; nullable `alternativeCost`: the selected alternative-cost enum name; `keywordAmounts`: selected keyword names with engine-aggregated counts, including multikicker/replicate amounts; `withoutManaCost`: the engine's explicit mana-cost waiver flag, not a claim that no mana or other costs were paid.
- `cardPayments`: the engine's known payment-list categories, their `intrinsic` flag, and card `{id, gameTimestamp}` references. LKI and current-card lists remain separate (`Sacrificed` versus `SacrificedCards`, for example); they can refer to the same payment and must not be summed as unique cards. Unknown category labels are omitted and counted in `unrecognizedCardPaymentGroups` rather than exported as arbitrary text.

No card names, script descriptions, or raw cost strings are emitted. Both stream modes and the exporter preserve the same snapshot, and reports count availability, optional/alternative selections, card-payment groups, and unrecognized groups. Empty recorded facts do not prove a zero cost. Counter-removal type/amount choices, complete life-payment totals, non-card payments such as energy, and per-keyword-instance choices require further instrumentation and remain gaps. Payment-choice reasons and available payment alternatives remain outside this dataset. The adapter introduces no AI callback, rules check, or retained engine reference; HARD-001 is assessed, with implementation follow-ups tracked separately.

## Hardening backlog

### HARD-001: assess decision-tracing coupling

Status: **Complete — assessed 2026-11-18; retain the architecture and V1 wire format with bounded follow-ups.** Added 2026-11-16. See [the completed assessment](Simulation-Trace-Coupling-Assessment.md), its dependency/lifetime map, reproducible probes, measured evidence and alternative analysis. The review demonstrated copy-lineage linkage loss, useful-lifetime cleanup gaps and exporter limitations. Repeated wire IDs account for a comparatively small share of sampled stream bytes and do not justify normalization by themselves. No production fixes were bundled with this assessment.

Implementation status is recorded under [HARD-002 through HARD-007](Simulation-Trace-Coupling-Assessment.md#implementation-follow-up-2026-11-18) and [the remaining hardening design](Simulation-Trace-Hardening-Design.md). Emission-time capture now supports same-thread reentrancy, current engine removals have explicit lifetime classification, and the CLI exporter uses a private disk spool. The V1 wire contract is retained. An asynchronous writer and concurrent rules mutation of a Game remain separate capabilities. Completing HARD-001 itself did not implement these follow-ups.

The assessment covered dependencies among `AiDecisionRecorder`, `AiActionTracker`, `AiController`, `SimulationTraceCollector`, and the exporter against the following requirements:

- Map dependency direction and correlation ownership, including the collector's live lookup through `AiController`. Identify reliance on execution timing, subscriber ordering, locking, and engine object aliases.
- Separate wire-format references from retained engine objects. Review mapping/reference lifetimes across completed actions, unclassified removals, bulk clears, cancellation, and simulation copies; identify any unnecessary retention or cleanup gaps.
- Inventory repeated identifiers and identity fields. Establish which are necessary for joins, independent diagnostics, and consistency validation, and which can be derived. Assess schema-version and consumer migration costs before proposing normalization.
- Measure representative trace volume, serialization/allocation overhead, retained memory, and simulation throughput with recording disabled, decision-only output, and full tracing. Assess the maintenance impact of changing an engine identity or AI execution path. Distinguish demonstrated costs from hypothetical risks.
- Compare the current design with focused alternatives, such as a single correlation owner or immutable observational metadata that avoids collector queries into AI internals. Include the complexity and diagnostic costs of each alternative.

Completion requires a written dependency/lifetime map, reproducible measurements, concrete maintenance risks, and a recommendation to retain the design or make a bounded change. Retaining the current architecture is a valid outcome when evidence supports it. Any proposed change must preserve nullable coverage, copied-ability linkage, counter/removal and pregame shuffle ordering, bulk-clear handling, timeout/failure visibility, and full/filtered export consistency, while keeping rules behavior independent of the learning schema. Track any implementation separately after this assessment.
