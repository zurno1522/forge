# Decision collection and export

Optional inline choice contexts are documented in [the implemented partial-view profile](../../docs/Development/Simulation-Input-Context.md).

Decision collection is independent of full game tracing. With your usual Forge launcher, run:

```sh
forge.exe sim -f commander -D /path/to/decks -d doctor-who.dck opponent.dck -n 2 -s 31000 -mc on -c 120 -q -log-decisions decisions.jsonl
```

For development, invoke `forge.view.Main sim` with the same options and your normal Java classpath. `-q` controls console game logs; it does not disable structured output.

`-log-decisions` saves run metadata, game starts, AI decisions, standalone subordinate choices, selected-action execution records, linked stack/land event projections, outcomes, game finishes and execution status. Linked projections omit privileged cast details; unrelated full trace events are excluded. No `-log-game` option is needed. You can enable both outputs with different filenames. They share run IDs and event indices; the filtered stream declares that index gaps are expected when a full trace is also enabled. Never pass both streams of the same run to the exporter, or the same decisions would be counted twice. Identical output paths are rejected before truncation.

Export and validate from the repository root (Python 3, standard library only):

```sh
python3 tools/decision-records/export.py decisions.jsonl --output-dir analysis
python3 tools/decision-records/export.py run1.jsonl run2.jsonl --output-dir combined-analysis
python3 tools/decision-records/export.py decisions.jsonl --validate-only
```

The command accepts decision streams and existing full game traces. It validates every input before writing output. Malformed or incomplete inputs fail with a nonzero exit status. Use a fresh output directory; existing export files are not overwritten.

The output directory contains:

- `dataset.jsonl`: one decision per row, with source run/game/event identifiers, player-to-deck mapping, deck hash, seed, pilot settings, engine metadata, game execution status, linked `actionExecutions`, and eventual outcome eligibility.
- `examples.jsonl`: a small selection of real dataset rows covering different pilots, play/pass selections, search availability, execution results and censored outcomes where present. Change the maximum with `--examples N`.
- `manifest.json`: exact source metadata, SHA256 input hashes, deck snapshots, canonical deck hashes, per-game participant mappings and recorded outcomes. Dataset deck hashes resolve to this manifest.
- `coverage.md`: a standalone human-readable report of collection coverage and limitations, requiring no original logs to read.
- `coverage.json`: the same aggregate counts in machine-readable form.
- `validation.json`: a summary of completed structural and consistency checks.
- `choices.jsonl`: additionally emitted for inputs declaring `choiceEventSchemaVersion: 1`, even when empty. It retains standalone subordinate-choice lifecycles, including choices with no main-phase parent. Legacy inputs keep the six-file format.

The exporter checks run/game boundaries, event order, duplicate decision/action IDs, schema versions, player/participant mappings, known observation visibility restrictions, selected-action consistency, action execution lifecycles, winner mappings and aggregate status counts. Normally completed games with declared linkage must have a terminal record for each selected action. These checks cannot prove every possible information leak or strategic rule violation; observations are still partial.

Timeouts, interrupted/error decisions and aborted runs are censored: `outcomeEligible=false` and `outcomeLabel=null`. A forced draw used to finalize a timeout is never a draw reward. In-flight decisions can finish after the outcome is announced but before execution finishes; these remain in their original order, have `postOutcome=true` and are censored. Legitimate draws in normally completed games can have a `DRAW` label. `WIN` and `LOSS` describe the eventual game result, not whether that individual decision was good.

A setup timeout may finalize an administrative draw before `GAME_STARTED`. It remains a counted `TIMEOUT` in reports and the manifest, with `gameStarted=null` and no decision rows or outcome labels. Normally completed games still require a game-start record.

An `EXCEPTION` game can end without `GAME_FINISHED` or an outcome. The exporter preserves its available decisions and failure diagnostics when the authoritative game-execution and run-finish records are present. These decisions are censored. A game with decisions still requires `GAME_STARTED`; normal and timeout games still require their existing outcome/finish records.

The exporter validates into a private temporary SQLite spool, including duplicate IDs, action history, run/game state and coverage aggregates. It materializes output from those validated bytes, so it does not reread changing source files. No database server or persistent database is required; the spool is deleted on completion or failure. It uses the system temporary directory (Python honors `TMPDIR`); output staging uses the output filesystem. Keep `export.py`, `decision_spool.py`, `choice_events.py` and `input_context.py` together when copying the tool.

The CLI streams the datasets, manifest and reports with a bounded SQLite cache. Working memory also depends on the largest input record, input-path arguments and requested example count. Python callers receive a materialized aggregate summary by default; use `compact_summary=True` to return only fixed-size counts. Direct `load_run()` remains an in-memory inspection API.

All inputs must validate before publication. The six legacy files and optional standalone-choice file are first written privately on the output filesystem, then published without overwriting existing files. A publication failure removes files created by that attempt where possible and preserves unrelated files. Publication of the whole output set is not one atomic filesystem operation.

New parent decisions can declare subordinate target-planning coverage and include `decision.subordinateChoices`. These observe specific damage/removal heuristic pools during candidate evaluation: identities offered, actual return, chooser, assessment and any exact selected-candidate link. They are recommendations/preferences; the actual cast can choose differently. Some records select an opponent whose board to inspect rather than a player to target. See [the subordinate-choice contract](../../docs/Development/Simulation-Decision-Records.md#observed-subordinate-target-planning-choices).

Capture is limited to 128 records per decision and a 256-entry prefix per options/prior-target list, with explicit truncation and budget/interruption omissions. Coverage reports show these limits and unavailable legacy metadata. An empty choice array does not establish that no targeting occurred. The remaining choice types and unsupported target paths are still gaps within priority 1.

`AI_SUBORDINATE_CHOICE` extends capture to controller targeting callbacks, delegation, effect-copy targeting and existing target helpers outside a main-phase recorder. It has `STARTED` and terminal records, actual chooser identity, explicit stage and nullable parent decision/action/assessment IDs. `parentChoiceId` records synchronous nested callback causality. No parent is guessed from names or target equality. Hidden source data is masked. Whole targeting callbacks record prior/final target plans, not a fabricated legal pool; helper callbacks retain their existing observed pool. The unsupported generic retarget callback remains a recorded decline. The exporter retains these lifecycles in `choices.jsonl`, validates explicit links after parents arrive, and censors setup, interruption, errors and administrative outcomes. Completion describes the callback, not execution success or strategic quality. These records do not extend the older per-slot provenance format to inferred links.

Decisions declaring `targetChoiceProvenanceSchemaVersion: 1` also support explicit origins on committed target slots in linked `STACK_ADDED.targetChoiceProvenance`. Its ability groups and target slots align with `finalizedTargets`; a non-null slot references the actual helper invocation whose explicit addition survives in the unchanged stored plan. Engine clones preserve that plan identity. Reset/reselect, substitutions and known delegation remain unknown rather than being matched by value. Prefix truncation is explicit; missing snapshots remain unavailable. Coverage reports distinguish explicit origins, unknown slots and truncated slots. See [the provenance contract](../../docs/Development/Simulation-Decision-Records.md#committed-target-choice-provenance). This is attribution of target identities, not of divided allocation choices or strategic quality.

Each selected action in new records has an `actionId`; `AI_ACTION_EXECUTION` records join it to an attempt and terminal result, including the actual ability/stack IDs when casting copied the ability. `actionExecutions` retains those events with their original indices. Coverage reports count `SUCCEEDED`, `FAILED`, `ABANDONED`, `ERROR`, and `UNOBSERVED` results. Abnormal games may contain `INCOMPLETE` attempts; legacy inputs remain `UNAVAILABLE`, without guessing by name or engine ability ID. Uncertain, abandoned, and post-outcome executions are censored. Success means the recorded execution boundary was reached, not that a spell resolved or that the choice was strategically good.

Runs declaring `actionEventSchemaVersion: 1` also retain linked existing engine events in `actionExecutions[].engineEvents`: stack addition, resolution (including `fizzled`), countering, removal, bulk clear, and land play. `engineEventCoverage` distinguishes declared coverage from legacy `UNAVAILABLE` data. The counter reference identifies the countered action, and removal can precede the counter event. A bulk clear is projected per linked entry. Coverage reports count linked event types. Missing resolution events do not become inferred failures, and no downstream effects or per-action rewards are assigned. Both full and filtered inputs export the same limited event projection.

New stack additions also include `finalizedTargets` (nested schema version 1): ordered root/sub-ability target groups with card IDs and game timestamps, player IDs, targeted ability/stack IDs, and optional divided allocations. Names and diagnostic descriptions are excluded. Empty lists are observed choices; null or absent snapshots remain unavailable/legacy data. Coverage reports show snapshot availability and target-reference types. This records committed targets, not the alternatives, later retargeting, or choices made during resolution. See [finalized targets](../../docs/Development/Simulation-Decision-Records.md#finalized-targets) for the schema and capture boundary.

`finalizedModes` records the root modal action's committed mode chain, including repeats and nested effects. Each mode identifies its executed ability and original option index; unknown or ambiguous mappings remain explicit. The adapter handles entwine and ignores stale AI evaluation caches. Coverage reports count snapshot availability and modes per modal action. See [finalized modes](../../docs/Development/Simulation-Decision-Records.md#finalized-modes).

Standalone `MODE_SELECTION` lifecycles additionally retain the observed options and actual stored/returned mode plan, counts and repeat constraints. Exact offered-list indices and original-definition indices are distinct, especially after filtering. Unmapped copies remain explicit; no description or mutable ordering tag supplies a guessed link. Reports count link availability, captured prefixes and repeated references. See [mode choices](../../docs/Development/Simulation-Decision-Records.md#mode-choices).

`finalizedX` retains the root ability's announced mana-cost X, including zero. An unannounced value stays null; it is not calculated from mana spent (an XX spell with X=2 can spend four mana). Effect-defined X and numbers chosen later during resolution are outside this snapshot. See [announced X](../../docs/Development/Simulation-Decision-Records.md#announced-x).

Standalone `NUMBER_SELECTION` and `X_SELECTION` observations retain supplied ranges/discrete options or actually computed heuristic bounds, with explicit unknown alternatives for refinements. They preserve zero, null/declined callbacks, duplicate options and outside-prefix returns. Visible X candidates additionally retain their `evaluatedX` field; that proposal can differ from the eventual committed snapshot. No legal range is inferred from spent mana. See [numeric and X choices](../../docs/Development/Simulation-Decision-Records.md#numeric-and-x-choices).

`finalizedPayments` preserves available engine payment facts: spent mana provenance, recorded life-payment fields, optional/alternative costs, repeated keyword amounts, and identity-only paid-card lists. It is explicitly partial. `lastLifePayment` is not a total and must not be added to `phyrexianLifePaid`; LKI/current card lists can describe the same paid card. Effect-copy histories are not counted as new payments. Unknown card-payment labels are omitted and counted. Counter-removal choices, complete life totals, and complete payment alternatives remain uncovered. See [recorded payment facts](../../docs/Development/Simulation-Decision-Records.md#recorded-payment-facts).

Standalone payment observations distinguish cost-visitor proposals, existing card-option recommendations, actual mana-payment routine outcomes and context-backed mana-pool choices. Completed proposals do not establish payment. Lists are capped, unsupported details are flagged, and reports count each coverage category. See [payment choices](../../docs/Development/Simulation-Decision-Records.md#payment-choices).

Every row has `trainingReady=false`. Version 1 observations are partial and candidate lists are observed heuristic assessments, not complete legal-action sets. Candidate counts can repeat an ability. Old land records and some direct combo selections may lack assessment rows. New land shortlists use `LAND_OPTION` assessments, a `heuristicDefault` flag and a `searchOffered` flag. These options are not counted as rejections. Options without an instantiated ability omit `abilityId` and match selected lands by `sourceId`. Optional `assessmentDetail` preserves more specific rejection causes, card-handler assessments and ratings without changing approval logic. Complete target-choice coverage, fully typed alternatives for every cost, per-candidate search values and downstream effect attribution remain future work. Instrumented standalone target, mode, numeric/X and payment callbacks are exported separately in `choices.jsonl`. See [the record schema](../../docs/Development/Simulation-Decision-Records.md).

Pilot metadata records the requested CLI mode and participant profiles; per-decision search summaries show where search actually ran. Runtime preferences, budgets and fallbacks may change whether a given decision uses search. Older traces without pilot/engine revision metadata retain `UNKNOWN` values, rather than guessing from outcomes or filenames. To identify a development build explicitly, add JVM properties before the main class: `-Dforge.simulation.revision=<commit> -Dforge.simulation.dirty=true` (use `false` for a clean build). These are caller-provided provenance, not an engine self-verification mechanism.

Run the exporter tests with:

```sh
python3 -m unittest discover -s tools/decision-records -p 'test_*.py' -v
```

The first target-pool increment and its post-commit review are documented in [the schema review](../../docs/Development/Simulation-Decision-Records.md#target-choice-commit-review-2026-11-19), with an [actual message example](../../docs/Development/assessments/Subordinate-Targets/actual-example.json), measured coverage and remaining gaps. That historical eight-game run covered removal and damage pools. Subsequent increments add draw/player targeting, attachment/reconfigure, standalone callback stages, mode, numeric/X and payment observations; the latest batch review below records validation and remaining scope. Priority 1 remains in progress.

`TargetChoiceProbe.java` is a development-only calling-thread CPU/allocation control. Build test classes and a classpath as described in [the hardening probe guide](hardening/README.md#build-and-classpath), then run from the repository root with JDK 21 and Xvfb:

```sh
target_probe_cp=$(cat forge-gui-desktop/target/hardening-classpath.txt)
target_probe_dir=$(mktemp -d)
javac -cp "$target_probe_cp" -d "$target_probe_dir" tools/decision-records/TargetChoiceProbe.java
cd forge-gui-desktop
xvfb-run -a java -Xms256m -Xmx1g -Duser.home=/path/to/seeded-forge-home \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.text=ALL-UNNAMED \
  --add-opens=java.base/java.time=ALL-UNNAMED \
  -cp "$target_probe_dir:$target_probe_cp" forge.ai.TargetChoiceProbe /tmp/target-choice-cost.json
```

The current probe compares the same ranking, parent capture, actual target addition and final origin snapshot with and without recorded pool/origin capture. It checks that the chosen object is unchanged and origin availability matches the recording mode. Warmup precedes three rotated 200-iteration samples for four- and 30-object pools; results exclude serialization/file output and engine execution copies. The [current measured samples](../../docs/Development/assessments/Target-Provenance/producer-cost.json) do not isolate provenance overhead from pool capture or establish whole-game throughput or deadline-neutral recording. Earlier measurements of pool capture alone remain in [the first-increment evidence](../../docs/Development/assessments/Subordinate-Targets/producer-cost.json).

For real-game reproduction, use `target-choice-black.dck` or `target-choice-damage.dck` against `trace-green.dck` from `tools/trace-test-decks`, three constructed games at seed 12031 or 12032 respectively, `-mc off`, `-c 90`, and both output options. Commander decks and the remaining seeds are recorded in [the validation](../../docs/Development/assessments/Target-Provenance/validation.json), with [exact workspace commands](../../docs/Development/assessments/Target-Provenance/commands.json). See [the commit review and remaining gaps](../../docs/Development/Simulation-Decision-Records.md#target-provenance-commit-review-2026-11-20) and [an actual linked message](../../docs/Development/assessments/Target-Provenance/actual-example.json).


The [combined five-task review](../../docs/Development/Simulation-Decision-Records.md#combined-batch-validation-and-gap-review-2026-11-20) records current coverage, independent implementation/review commits and remaining gaps. Eleven fresh games validated 609 decisions and 2,701 standalone choices with matching full/filtered exports and preserved pregame shuffle ordering. [Actual messages](../../docs/Development/assessments/Subordinate-Choices/actual-examples.json) include modes, numeric/X choices, trigger preparation and payment proposals. That batch retained one unknown Monte Carlo reconfigure origin after plan rebuilding; the subsequent Monte Carlo follow-up below resolves the reproduced case. The five-task batch completes the agreed observation increments; full subordinate coverage and a complete training state remain open.


Main-phase Monte Carlo final revalidation, restoration and target overrides additionally retain `searchTargetApplications` on their owning decision. These describe actual operations and before/after plans, not complete legal alternatives. Such decisions declare target provenance version 2, allowing committed slots to reference the accepted operation's exact result coordinates; older records remain version 1. Reports count applications, outcomes, capture omissions and search-origin slots. See [Monte Carlo target operations](../../docs/Development/Simulation-Decision-Records.md#monte-carlo-target-operation-provenance).


The [Monte Carlo validation follow-up](../../docs/Development/Simulation-Decision-Records.md#monte-carlo-target-validation-and-remaining-scope-2026-11-20) resolves the previously unknown Reality Chip target through an actual final heuristic-revalidation record. Eight fresh games validated 46 search operations and 22 linked committed target origins, including a declined revalidation followed by accepted restoration. [Actual messages](../../docs/Development/assessments/Monte-Carlo-Targets/actual-examples.json) and version-1 compatibility checks are committed with the evidence. This closes the reproduced main-phase search gap; the observation/visibility design remains pending.


The [choice input-context contract and boundary inventory](../../docs/Development/Simulation-Observation-Contract.md) specifies the next observation capability. It inventories 61 recording entry sites and distinguishes actual selector inputs from cached results, assignments and whole operations. This is a design document: inline contexts, disclosure capture and new policy projections are not implemented or emitted by this stage.
