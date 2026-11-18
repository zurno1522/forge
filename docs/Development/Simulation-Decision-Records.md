# Simulation decision records

When decision collection (`sim ... -log-decisions decisions.jsonl`) or structured game tracing (`sim ... -log-game path.jsonl`) is enabled, the real game's AI emits an `AiDecisionEvent`. The desktop trace collector writes it as `AI_DECISION` in the same JSONL stream as game events. The AI has no file-writing dependency. These observational events do not use the rules-event visitor or change engine event ordering.

Version 1 covers play selection with an empty stack during the acting player's own first or second main phase. It includes land selection, spells, abilities, and passing. It does not cover mulligans, combat, reactive priority, or subordinate target/mode/payment choices. Recording is off by default outside traced simulations and suppressed for simulation copies. The AI's existing policy and candidate limits remain in place; observation capture occurs before its thinking deadline starts.

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

No card names, script descriptions, or raw cost strings are emitted. Both stream modes and the exporter preserve the same snapshot, and reports count availability, optional/alternative selections, card-payment groups, and unrecognized groups. Empty recorded facts do not prove a zero cost. Counter-removal type/amount choices, complete life-payment totals, non-card payments such as energy, and per-keyword-instance choices require further instrumentation and remain gaps. Choice reasons and available alternatives remain outside this dataset. The adapter introduces no AI callback, rules check, or retained engine reference; HARD-001 is assessed, with implementation follow-ups tracked separately.

## Hardening backlog

### HARD-001: assess decision-tracing coupling

Status: **Complete — assessed 2026-11-18; retain the architecture and V1 wire format with bounded follow-ups.** Added 2026-11-16. See [the completed assessment](Simulation-Trace-Coupling-Assessment.md), its dependency/lifetime map, reproducible probes, measured evidence and alternative analysis. The review demonstrated copy-lineage linkage loss, useful-lifetime cleanup gaps and exporter limitations. Repeated wire IDs account for a comparatively small share of sampled stream bytes and do not justify normalization by themselves. No production fixes were bundled with this assessment.

Implementation remains open under [HARD-002 through HARD-007](Simulation-Trace-Coupling-Assessment.md#follow-up-work-separate-implementation-commits): execution-specific lineage, authoritative retirement, exceptional-run export, exporter scaling, dispatch/capture contracts, and bidirectional identity validation. Keep these as separate changes with behavior-based regressions. Completing HARD-001 does not mark those defects fixed.

The assessment covered dependencies among `AiDecisionRecorder`, `AiActionTracker`, `AiController`, `SimulationTraceCollector`, and the exporter against the following requirements:

- Map dependency direction and correlation ownership, including the collector's live lookup through `AiController`. Identify reliance on execution timing, subscriber ordering, locking, and engine object aliases.
- Separate wire-format references from retained engine objects. Review mapping/reference lifetimes across completed actions, unclassified removals, bulk clears, cancellation, and simulation copies; identify any unnecessary retention or cleanup gaps.
- Inventory repeated identifiers and identity fields. Establish which are necessary for joins, independent diagnostics, and consistency validation, and which can be derived. Assess schema-version and consumer migration costs before proposing normalization.
- Measure representative trace volume, serialization/allocation overhead, retained memory, and simulation throughput with recording disabled, decision-only output, and full tracing. Assess the maintenance impact of changing an engine identity or AI execution path. Distinguish demonstrated costs from hypothetical risks.
- Compare the current design with focused alternatives, such as a single correlation owner or immutable observational metadata that avoids collector queries into AI internals. Include the complexity and diagnostic costs of each alternative.

Completion requires a written dependency/lifetime map, reproducible measurements, concrete maintenance risks, and a recommendation to retain the design or make a bounded change. Retaining the current architecture is a valid outcome when evidence supports it. Any proposed change must preserve nullable coverage, copied-ability linkage, counter/removal and pregame shuffle ordering, bulk-clear handling, timeout/failure visibility, and full/filtered export consistency, while keeping rules behavior independent of the learning schema. Track any implementation separately after this assessment.
