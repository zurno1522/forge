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

Tracking is limited to the recorded selections, suppressed in simulation copies, and does not change AI policy, controller return values, rules events, or their ordering. `STARTED` precedes the attempt and the terminal record follows its internal return, so intervening engine events retain their original order. Existing full-trace stack events can be joined using the engine identifiers. Decision-only streams do not include all later stack events.

Execution success is not proof of resolution: a successfully cast spell can be countered, and a resolved spell can have little effect. Resolution, target/mode/payment decisions, and attribution of downstream effects remain separate work. Pass decisions have no selected actions and require no execution records.

The exporter validates identities, lifecycle order, duplicate terminal records, and completeness in normally completed games that declare execution linkage. Administrative timeouts/exceptions may leave an attempt without a terminal result; exports mark it `INCOMPLETE`, without inventing failure or abandonment. Legacy actions without declared linkage are `UNAVAILABLE`. Abandoned, erroneous, unobserved, incomplete, and post-outcome executions are censored for outcome labels. A definitive failed attempt can retain its eventual game outcome; that label still does not rate the action's quality. All rows remain `trainingReady=false`.
