# Decision collection and export

Decision collection is independent of full game tracing. With your usual Forge launcher, run:

```sh
forge.exe sim -f commander -D /path/to/decks -d doctor-who.dck opponent.dck -n 2 -s 31000 -mc on -c 120 -q -log-decisions decisions.jsonl
```

For development, invoke `forge.view.Main sim` with the same options and your normal Java classpath. `-q` controls console game logs; it does not disable structured output.

`-log-decisions` saves run metadata, game starts, AI decisions, selected-action execution records, linked stack/land event projections, outcomes, game finishes and execution status. Linked projections omit privileged cast details; unrelated full trace events are excluded. No `-log-game` option is needed. You can enable both outputs with different filenames. They share run IDs and event indices; the filtered stream declares that index gaps are expected when a full trace is also enabled. Never pass both streams of the same run to the exporter, or the same decisions would be counted twice. Identical output paths are rejected before truncation.

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

The exporter checks run/game boundaries, event order, duplicate decision/action IDs, schema versions, player/participant mappings, known observation visibility restrictions, selected-action consistency, action execution lifecycles, winner mappings and aggregate status counts. Normally completed games with declared linkage must have a terminal record for each selected action. These checks cannot prove every possible information leak or strategic rule violation; observations are still partial.

Timeouts, interrupted/error decisions and aborted runs are censored: `outcomeEligible=false` and `outcomeLabel=null`. A forced draw used to finalize a timeout is never a draw reward. In-flight decisions can finish after the outcome is announced but before execution finishes; these remain in their original order, have `postOutcome=true` and are censored. Legitimate draws in normally completed games can have a `DRAW` label. `WIN` and `LOSS` describe the eventual game result, not whether that individual decision was good.

A setup timeout may finalize an administrative draw before `GAME_STARTED`. It remains a counted `TIMEOUT` in reports and the manifest, with `gameStarted=null` and no decision rows or outcome labels. Normally completed games still require a game-start record.

Each selected action in new records has an `actionId`; `AI_ACTION_EXECUTION` records join it to an attempt and terminal result, including the actual ability/stack IDs when casting copied the ability. `actionExecutions` retains those events with their original indices. Coverage reports count `SUCCEEDED`, `FAILED`, `ABANDONED`, `ERROR`, and `UNOBSERVED` results. Abnormal games may contain `INCOMPLETE` attempts; legacy inputs remain `UNAVAILABLE`, without guessing by name or engine ability ID. Uncertain, abandoned, and post-outcome executions are censored. Success means the recorded execution boundary was reached, not that a spell resolved or that the choice was strategically good.

Runs declaring `actionEventSchemaVersion: 1` also retain linked existing engine events in `actionExecutions[].engineEvents`: stack addition, resolution (including `fizzled`), countering, removal, bulk clear, and land play. `engineEventCoverage` distinguishes declared coverage from legacy `UNAVAILABLE` data. The counter reference identifies the countered action, and removal can precede the counter event. A bulk clear is projected per linked entry. Coverage reports count linked event types. Missing resolution events do not become inferred failures, and no downstream effects or per-action rewards are assigned. Both full and filtered inputs export the same limited event projection.

New stack additions also include `finalizedTargets` (nested schema version 1): ordered root/sub-ability target groups with card IDs and game timestamps, player IDs, targeted ability/stack IDs, and optional divided allocations. Names and diagnostic descriptions are excluded. Empty lists are observed choices; null or absent snapshots remain unavailable/legacy data. Coverage reports show snapshot availability and target-reference types. This records committed targets, not the alternatives, later retargeting, or choices made during resolution. See [finalized targets](../../docs/Development/Simulation-Decision-Records.md#finalized-targets) for the schema and capture boundary.

Every row has `trainingReady=false`. Version 1 observations are partial and candidate lists are observed heuristic assessments, not complete legal-action sets. Candidate counts can repeat an ability. Old land records and some direct combo selections may lack assessment rows. New land shortlists use `LAND_OPTION` assessments, a `heuristicDefault` flag and a `searchOffered` flag. These options are not counted as rejections. Options without an instantiated ability omit `abilityId` and match selected lands by `sourceId`. Optional `assessmentDetail` preserves more specific rejection causes, card-handler assessments and ratings without changing approval logic. Target alternatives, later retargeting, mode/X/payment decision records, per-candidate search values and downstream effect attribution remain future work. See [the record schema](../../docs/Development/Simulation-Decision-Records.md).

Pilot metadata records the requested CLI mode and participant profiles; per-decision search summaries show where search actually ran. Runtime preferences, budgets and fallbacks may change whether a given decision uses search. Older traces without pilot/engine revision metadata retain `UNKNOWN` values, rather than guessing from outcomes or filenames. To identify a development build explicitly, add JVM properties before the main class: `-Dforge.simulation.revision=<commit> -Dforge.simulation.dirty=true` (use `false` for a clean build). These are caller-provided provenance, not an engine self-verification mechanism.

Run the exporter tests with:

```sh
python3 -m unittest discover -s tools/decision-records -p 'test_*.py' -v
```
