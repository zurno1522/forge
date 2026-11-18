# HARD-001 development probes

These tools reproduce the [coupling assessment](../../../docs/Development/Simulation-Trace-Coupling-Assessment.md). They are not shipped engine dependencies, training data generators, or additions to the unit-test suite. They deliberately use existing `AITest`/recorded-selection fixtures and reflection to inspect internal ownership. Run on Linux with JDK 21, Python 3 and a display/Xvfb. `ru_maxrss` is reported in Linux KiB.

Production baseline is `f2bc148cecf`. The assessment commit only adds documentation and these development tools; production sources remain that baseline. Later fixes should change the defect-probe results. Use matching source/resources and compiled classes; do not benchmark stale classes while Maven recompiles them.

## Build and classpath

With the project's supported Maven/JDK setup and dependencies available:

```sh
xvfb-run -a mvn -pl forge-gui-desktop -am -DskipTests install
mvn -pl forge-gui-desktop dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=target/hardening-dependencies.txt
python3 - <<'PY'
from pathlib import Path
import os
r = Path.cwd().resolve()
paths = [r / 'forge-gui-desktop/target/test-classes']
paths += [r / m / 'target/classes' for m in ('forge-gui-desktop', 'forge-core', 'forge-game', 'forge-ai', 'forge-gui')]
deps = (r / 'forge-gui-desktop/target/hardening-dependencies.txt').read_text().strip()
(r / 'forge-gui-desktop/target/hardening-classpath.txt').write_text(os.pathsep.join(map(str, paths)) + os.pathsep + deps)
PY
```

In the reviewed workspace, the already built classpath is `/workspace/.forge-tools/trace-classpath.txt`; source `/workspace/.forge-tools/env.sh` for cached Maven/JDK/Xvfb paths. The measured JDK lives at `/workspace/.forge-tools/jdk/usr/lib/jvm/java-21-openjdk-amd64`. Build output and cached dependencies are not committed. A missing dependency remains an environment setup issue, not evidence about architecture.

## Allocation, retention and gameplay

The measured seed home is `/workspace/.forge-home`, with `UI_LANGUAGE=en-US`, `UI_CURRENT_AI_PROFILE=Default` and `LOAD_CARD_SCRIPTS_LAZILY=false`. Each macro run gets a separate copy of that home; the evidence manifest records the preference-file hash. Outside this workspace, make a dedicated seed home (not your general home directory), allowing other preferences to use the baseline's defaults:

```sh
mkdir -p /tmp/hard001-seed-home/.forge/preferences
cat > /tmp/hard001-seed-home/.forge/preferences/forge.preferences <<'EOF'
UI_LANGUAGE=en-US
UI_CURRENT_AI_PROFILE=Default
LOAD_CARD_SCRIPTS_LAZILY=false
EOF
```

From the repo root, use that seed and a fresh output directory outside it:

```sh
xvfb-run -a python3 tools/decision-records/hardening/run.py \
  --classpath-file forge-gui-desktop/target/hardening-classpath.txt \
  --java-home /path/to/jdk-21 \
  --forge-home /path/to/seeded-forge-home \
  --commander-decks tools/decision-records/hardening/decks \
  --simple-decks tools/decision-records/hardening/decks \
  --output-dir /tmp/hard001-new-run
```

`run.py` first runs a fixed selection/cast/resolve workload, with 2,000 warmup operations for each output mode, then three rotated 400-operation trials per mode and board size. Fixtures use 4 or 30 battlefield creatures per player, 7 hand cards and 30 library cards. This measures caller-thread trace-pipeline allocation/CPU and buffered local writes; it does not run heuristic candidate evaluation. The controlled JSONL files are scoped probe output without complete run boundaries, not valid exporter inputs. The size agent only counts reachable graph objects; it is not an exclusive-heap/dominator profiler. Post-GC heap includes the whole VM and the retained fixture Game.

The same command then runs three fresh JVM repetitions per mode for two CLI workloads, sequentially with no overlapping probe. Each starts from a separate copy of the supplied Forge home. The simple workload uses three constructed games, seed 7451; the Commander workload uses one regular Commander game, seed 31002. Both use `-mc off`, `-q`, 90-second game clocks, `-Xms256m -Xmx1g`, and either no output, `-log-decisions`, or `-log-game`. The committed decks preserve the exact input lists, including the workspace red deck's Goblin Trailblazer substitution. CLI setup/JIT/teardown is included in process time; source-defined `Game Result` timings exclude final trace finalization/flush. Check normal completion and outcome signatures before comparing. Matching outcomes do not establish identical off-mode action work.

The runner saves raw measurements, commands, source revision/dirty status, Java version, and outcome signatures. `--macro-only` skips the controlled stage. A 360-second process watchdog fails a stuck CLI run rather than accepting partial output. Do not overlap independent benchmark workloads when interpreting timing. A shared host, GC/JIT variation and three repetitions still limit inference. Allocation, reachable map bytes, live heap and peak process RSS are different measures.

## Correctness/failure reproductions

Compile the two stand-alone fixtures with the same classpath and run from `forge-gui-desktop` so resources resolve:

```sh
probe_dir=/tmp/hard001-correctness
mkdir -p "$probe_dir/classes"
probe_cp="$(cat forge-gui-desktop/target/hardening-classpath.txt)"
javac -cp "$probe_cp" -d "$probe_dir/classes" \
  tools/decision-records/hardening/ActionCorrelationProbe.java \
  tools/decision-records/hardening/TraceFailureProbe.java
cd forge-gui-desktop
xvfb-run -a java -cp "$probe_dir/classes:$probe_cp" forge.view.ActionCorrelationProbe "$probe_dir/correlation"
xvfb-run -a java -cp "$probe_dir/classes:$probe_cp" forge.view.TraceFailureProbe "$probe_dir/failure"
```

Use a separate `-Duser.home=...` if you do not want fixtures to initialize the default Forge home. These are development-only, deterministic fixtures, not normal AI selection benchmarks. The correlation probe exercises ordinary Archivist, real Necrotic Ooze/Archivist, and a deliberately reentrant EventBus play. The failure probe overrides `Match.startGame`, posts a start and deterministic selection, then throws through the real runner. It also checks persistent flush failure. On the baseline, both exceptional streams fail `export.py --validate-only` because `GAME_FINISHED` is missing; the error is preserved in the streams. A future repair should make the intended failure traces valid and censored rather than erase the exception.

## Schema bytes and exporter scaling

```sh
python3 tools/decision-records/hardening/measure_schema.py sizes /path/to/complete-trace-corpus > sizes.json
python3 tools/decision-records/hardening/measure_schema.py exporter "$PWD" /path/to/decision-stream.jsonl > exporter.json
```

The size counterfactual is deletion-only and checks compact reserialization equality. Use a directory containing only the complete input streams; controlled probe fragments are not inputs. The exporter probe uses 1/8/32 distinct-run-ID copies of a supplied real run and slim synthetic one-game fixtures of 1k/4k/12k pass decisions. It uses fresh CPython child processes and temporary files, and reports process peak RSS/CPU. Synthetic repetition is a scalability exercise, not independent gameplay evidence.

The committed primary summaries are under `docs/Development/assessments/HARD-001`. The original wire-size corpus is retained at `/workspace/work/action-detail-completion`; its hashes are recorded in the evidence manifest. Raw logs/traces remain workspace artifacts; generated byte counts and timings will vary on another runtime. The assessment documents which findings are source-proven, reproduced on real interactions, scripted controls, or unproven risks.
