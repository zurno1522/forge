# Development cost probe

## V29 prefix admission compatibility

V29 adds gate receipts while retaining prefix capture. Run the source-pinned V28
comparison with the built desktop runtime/test classpath and no overlapping JVMs:

```sh
python3 tools/decision-records/input-context-benchmark/run-prefix-compatibility.py \
  --classpath-file /path/to/classpath.txt --java-home /path/to/jdk-21 \
  --user-home /path/to/writable-profile --output-dir /path/to/new-prefix-results
```

The runner compiles the four changed V28 classes from reference `555c3fbbb35`
into an isolated directory, never overwriting production classes. It checks that
GameAction matches that reference. The launcher records engine RNG snapshots
only; no experimental sampling overlay is loaded. Damage/X, repeated choices,
four-player Commander and a one-second timeout each run under V28 and V29.
Normal games must match engine events, native AI results, all represented inputs
and RNG state/counts after removing the expected envelope/admission metadata.
Both streams pass the ordinary reader and reconcile source/collector accounting.
Timeouts remain censored and have no normal-behavior equivalence claim.

The older `run-game-admission.py` and controlled admission experiments are pinned
to the V28 producer boundaries. Their checked hooks refuse V29; do not use them
to replace the new production gate or reinterpret their archived evidence as V29
measurements. Native process durations include startup/gameplay/host effects;
validation time is separate. Neither isolates admission overhead. OPT-005 owns cost
measurement against the actual production V29 baseline.

The workload probe declares `inputContextProfile` from the built producer on
every row. The workload summarizer preserves historical rows without this field
as the frozen V4 format, and rejects mixed legacy/declarations, differing profiles
or unsupported profiles. Current V29 measurements must not be reported as older profiles.
Existing V4 assessments remain historical results; this metadata update does not
measure the subsequent coverage increments.

Current workload rows use measurement schema V2. The historical eventFreeze key
counts all outermost freezes outside capture, including entry-location
receipts; it is not an event-only span. V2 validates that extra freeze for V28/V29 without
changing event/publication totals. Mixed/unknown/boolean declarations are rejected.
The producer-profile declaration alone does not certify all newly added families.
For a matched V28 control, pass `--reference 555c3fbbb35` to `run-workloads.py`;
it compiles source-pinned classes into an isolated directory and checks GameAction
compatibility instead of borrowing an earlier checkout. Reverse control/profile
order in a second pair. See the [controlled V29 assessment](../../../docs/Development/Simulation-V29-Controlled-Cost-Assessment.md).

`InputContextProbe` measures calling-thread CPU/allocation for disabled choices,
base choices, choices with contexts, the unchanged legacy view and the new view.
It includes synchronous freezing and Gson serialization, without selector work.
Four rotated rounds use 200 calls after 75 warmup calls in fresh games. The two
fixtures have 4 or 30 battlefield cards per player, plus hands and libraries.

Compile `InputContextProbe.java` and `InputContextSizeAgent.java` with Java 21 against the built desktop test/runtime
classpath (including `AITest`). Package `InputContextSizeAgent.class` in an agent
JAR with `Premain-Class: forge.ai.InputContextSizeAgent`. Run the probe from
`forge-gui-desktop` with `-javaagent:<jar>`, the same classpath, and Java opens for
`java.util`, `java.lang`, `java.lang.reflect`, `java.text` and `java.time`.
The sole program argument is the destination JSON path. Use a display/Xvfb as
for desktop tests, and run without overlapping game/test JVMs.

Reachable bytes describe the last detached frozen context, not transient peak
heap. Disclosure buffers are empty in this fixture. These samples do not certify
all game sizes, control perspectives, timeouts or historical-knowledge coverage.
See the [profile and measured results](../../../docs/Development/Simulation-Input-Context.md).

## OPT-005 collector/UTF-8 and failure cohort

With desktop classes built, run `run-pipeline-costs.py` using the same required
classpath/JDK/profile/output arguments as the compatibility runner. Use the
default four rounds, four warmup calls and eight measured calls. Repeat with
`--order profile-first` into a second new directory. Validate each with:

```sh
python3 tools/decision-records/input-context-benchmark/summarize-pipeline-costs.py \
  /path/to/pipeline-results --output /path/to/pipeline-summary.json
```

`--smoke --rounds 1 --iterations 2 --warmup 1` is an explicit small verification
run; its validator also requires `--smoke`. It cannot pass as a complete study.
These are fixed synthetic callbacks through the actual collector and UTF-8
writer, not games or standalone exportable traces. Matched byte offsets/hashes,
event order and ordinary context/entry validation establish what was delivered.
Intentional capture/writer failures remain separate from healthy costs.
The pipeline agent instruments writer calls and the final flush; that flush is
outside publication, so the overlapping spans are not additive. The controlled
phase probe retains its original instrumentation unless this pipeline flag is set.
See the [pipeline assessment](../../../docs/Development/Simulation-V29-Pipeline-Cost-Assessment.md).

## Agent failure prerequisite

Before measuring failed operations, validate the agent's exception cleanup:

```sh
python3 tools/decision-records/input-context-benchmark/run-agent-unwind-check.py \
  --java-home /path/to/jdk-21 --output-dir /path/to/new-unwind-results \
  --classpath-file /path/to/classpath.txt --user-home /path/to/writable-profile
```

Omit the last two arguments for the isolated agent unit only. Its fixture classes
are separate from the agent JAR and the real Forge classpath. Pass
`--agent-reference 159b6c05bce` to reproduce the expected isolated fail-before result;
that run exits nonzero.
The [exception-hardening assessment](../../../docs/Development/Simulation-Profiler-Exception-Hardening.md)
records the scope; these checks are not cost samples or played games.

## OPT-001 phase probe

The historical probe above uses Gson; its serialization measurements are not
measurements of Forge's production JSONL encoder. The new phase probe uses the
actual `SimulationTraceWriter.write` path through `TraceWriterProbeSink`, with a
BufferedWriter and observable character-counting sink. It excludes collector
envelope construction, UTF-8 encoding and filesystem I/O. Gson is used only for
untimed result output, fixture hashing and production-encoder round-trip checks.

`InputContextPhaseAgent` instruments capture, private-zone ordering, outermost
context/event freezes and Game publication in development JVMs. It uses JDK 21's
internal ASM with explicit module exports. No production source, gameplay schema
or capture budget is changed. Normal Forge execution never installs this agent.

With desktop runtime/test classes already built and a classpath file containing
both, run from the repository root:

```sh
python3 tools/decision-records/input-context-benchmark/run-phases.py \
  --classpath-file /path/to/classpath.txt --java-home /path/to/jdk-21 \
  --user-home /path/to/writable-profile --output-dir /path/to/new-results-a
python3 tools/decision-records/input-context-benchmark/run-phases.py \
  --classpath-file /path/to/classpath.txt --java-home /path/to/jdk-21 \
  --user-home /path/to/writable-profile --order profile-first \
  --output-dir /path/to/new-results-b
python3 tools/decision-records/input-context-benchmark/summarize-phases.py \
  /path/to/new-results-a --output /path/to/summary-a.json
```

The runner needs Xvfb (`xvfb-run`) in PATH. It compiles the development classes,
starts control/profile JVMs sequentially, and records commands, source/class
hashes and JVM version. Avoid overlapping game/test JVMs. Default trials have
200 warmup calls and 400 measured operations, six rotated measured rounds plus a
discarded initial round, fresh games, two board sizes and 0/32 disclosures.

The summarizer rejects missing rounds, unavailable contexts, mismatched policy
hashes, callback/event/phase-count errors, disabled/copy capture work and invalid
inclusive-span subtraction. Memory-only samples are excluded from CPU/allocation
statistics. Freeze spans count recursive map traversal once. Capture includes
its freeze/ordering children; publication includes serialization. Residuals are
computed per round, not by adding independently computed medians.

Memory probes sample source/result payload unions at freeze-return boundaries.
They do not establish exact transient heap peaks or include every builder local,
writer buffer or disclosure-owner object. Disclosure fact-buffer reachability is
reported separately; shared objects prevent blindly summing graph sizes. Profile
hooks perturb CPU; control/profile differences include run/JIT variation and
must not be used as a correction factor. The agent supports the healthy fixture
paths only: unbalanced spans or observation failures invalidate the run.

See [OPT-001 findings and remaining gaps](../../../docs/Development/Simulation-Observation-Cost-Assessment.md).

## OPT-005 V4 workloads

`InputContextWorkloadProbe` adds two/four-player V4 fixtures with actual stack
entries, current option bindings, 32 private/public disclosure facts and capped
boards. Public presentations are distinct synthetic names, so all 32 public
proofs exercise uniqueness checks. Original-controller accounting is explicitly
configured; disabled/copy policy still initializes no state. The probe runs no
AI policy, selector search or physical file writer.

Run the two pairs sequentially, without overlapping other game/test JVMs:

```sh
python3 tools/decision-records/input-context-benchmark/run-workloads.py \
  --classpath-file /path/to/classpath.txt --java-home /path/to/jdk-21 \
  --user-home /path/to/writable-profile --iterations 80 --warmup 20 --rounds 6 \
  --output-dir /path/to/v4-a
python3 tools/decision-records/input-context-benchmark/run-workloads.py \
  --classpath-file /path/to/classpath.txt --java-home /path/to/jdk-21 \
  --user-home /path/to/writable-profile --iterations 80 --warmup 20 --rounds 6 \
  --order profile-first --output-dir /path/to/v4-b
python3 tools/decision-records/input-context-benchmark/summarize-workloads.py \
  /path/to/v4-a --output /path/to/v4-a/summary.json
```

Healthy windows are shorter than OPT-001's to stay within unchanged player
shares. The summarizer rejects unavailable healthy captures, inconsistent
source/callback/event counts, unproved public bindings and policy differences.
Separate 1,200-invocation stress windows intentionally exhaust one chooser's
share and retain available/mixed/unavailable batch statistics. Their cheap
unavailable calls never enter healthy cost distributions.

`AccountingStateProbe` can be compiled into a runner's classes directory against
that directory and the production classpath. Run `forge.ai.AccountingStateProbe
/path/to/accounting-state.json` with the runner's agent installed, profiling false
and the same module exports/opens. It measures union reachability for 2/4/16
controller ledgers plus receipt/run ledgers and the shallow invocation scope.
It creates no Game; shared constants remain included in reachable sizes.

Payload-union samples are lower bounds, not exact transient heap. Disclosure
proof shells reference live Game/Card graphs; their shallow sizes and detached
fact payloads do not establish exclusive retention and must not be blindly
summed. The [checkpoint assessment](../../../docs/Development/Simulation-V4-Workload-Assessment.md)
records results, a real four-player sample, coverage-policy assignments and the
remaining OPT-005 measurement limits.

## COV-008 observed availability

Use an existing validated exporter output; this development report does not add
fields to the seven exporter artifacts or select a runtime sampling policy:

```sh
python3 tools/decision-records/input-context-benchmark/summarize-availability.py \
  /path/to/export/coverage.json --turn-window 10 \
  --output /path/to/availability.json
```

The report reconciles observed-record totals against both turn and boundary
marginals. Player groups include run/game identity. Known parent capture
omissions stay separate from the observed-record denominator; absent contexts
and unavailable input are separate counts. Turn windows retain their clock
basis and unknown clocks. Earliest observed budget turns do not identify the
exact exhaustion instant. Available means a captured/partial named profile,
not complete inputs or a learning-ready example. Player/boundary marginals do
not establish their joint distribution. Source accounting is copied as declared;
missing scope is unknown. Use the normal exporter/validation gates first.

See the [staged availability design](../../../docs/Development/Simulation-Sustained-Availability-Design.md).

## COV-008 controlled admission trials

The [experiment contract](../../../docs/Development/Simulation-Availability-Experiment-Contract.md)
describes the fixed fixtures, isolated funding/capture hooks and acceptance gates.
The runner compiles into its output directory and verifies that selected production
class hashes do not change. It never replaces target/classes. Three sequential
JVMs supply an unmodified production prefix control, all candidate policies and
a reversed candidate order, with discarded warmups and three measured rounds.

```sh
python3 tools/decision-records/input-context-benchmark/run-admission.py \
  --classpath-file /path/to/classpath.txt --java-home /path/to/jdk-21 \
  --user-home /path/to/writable-profile --output-dir /path/to/admission
python3 tools/decision-records/input-context-benchmark/summarize-admission.py \
  /path/to/admission --output /path/to/admission/summary.json
```

Run without concurrent game/test JVMs. An optional `--smoke` checks one long
public-disclosure fixture; its incomplete matrix is refused as an assessment.
The generated overlay admits exactly the same inputs as production for PREFIX.
STRIDE/HASH omit only input traversal; original recorders, callbacks and streaming
START/terminal writes still run. RESERVE funds half the original share, unlocking
the remaining half once at fixture turn 31. Its developer-only reflection is
confined to the isolated State. No allowance is refunded or borrowed.

Experimental omission reasons are deliberately unsupported by the ordinary trace
reader. The output is aggregate development telemetry, not exported gameplay or
training records. Turn windows are an explicit fixture schedule (cycles 0–9 are
fixture turn 1); they are not independently captured engine turn clocks. All four
source families have fixed callback behavior to isolate observation costs.

Work debits reconcile with actual State allowances, including disclosure deliveries
and charged failed traversals. Profile-quality counters retain represented card/
zone/disclosure occurrences and overlapping omission reasons. More available
partial views do not establish more complete inputs. Healthy, unavailable and
sampled entry spans stay separate. Entry spans end after START serialization;
whole-run measurements include their parents and must not be added to them.
Metric/validation/bookkeeping overhead remains included; uncapped rules-state proof bindings are verified against received facts, including legitimate losses when a shared allowance blocks later deliveries; initial fixture/disclosure setup, board creation and final
result hashing/encoding are excluded. Writer counts are characters, without UTF-8
conversion or physical I/O. No collector delivery or exact heap claim is made.

## COV-008 native game admission

The [actual-game contract](../../../docs/Development/Simulation-Game-Admission-Contract.md)
keeps the meter and admission gates in an isolated development classpath. This
campaign runs native heuristic games, including the real collector and physical
full/filtered writers. Its monitor/telemetry overhead is not a production cost
estimate. Run game JVMs sequentially:

```sh
python3 tools/decision-records/input-context-benchmark/run-game-admission.py \
  --classpath-file /path/to/classpath.txt --java-home /path/to/jdk-21 \
  --user-home /path/to/writable-profile --output-dir /path/to/native-admission
python3 tools/decision-records/input-context-benchmark/validate-game-admission.py \
  /path/to/native-admission --output /path/to/native-admission/reports
```

The full matrix has native damage/X and repeated-choice games, two four-player
Commander seeds and a one-second Commander timeout, each with production,
prefix, hash-four, hash-eight (two salts) and reserve variants. `--smoke` runs
three damage/X variants; `--cases` selects explicitly declared subcampaigns.
Strict validation requires every command in the declared matrix to finish;
`--partial` on the validator is only for incremental progress checks.

Validation loads a fresh private reader instance and extends its reason set by
exactly the two experimental reasons. It invokes validation/coverage only, never
a dataset export directory. The ordinary reader remains unchanged and rejects
those records. Input views and parent target/search `inputUnavailable` counters
are excluded from native-result comparison; all other delivery counters,
assessments/actions/targets/costs/results remain. Each is still structurally
validated and parent accounting is reported separately. Normally completed
variants must match the production engine, AI-result and serialized RNG-state
projections. Timeout outcomes stay censored and their partial sequences are not
normal-game controls.

The sidecar owns actual capture-attempt/debit receipts, including calls which a
parent never publishes. The joint reader audit owns observed records and retains
its independent entry/container clocks. Their denominators/cutoffs differ.
Outside-capture work includes disclosure intake; it is not refunded by sampling.
Per-State monitors seal coherent snapshots while worker tickets can be in flight;
late completions do not rewrite those snapshots. These development semantics do
not implement a production seed/budget/lifetime API.

`GameAdmissionHooksCheck` is compiled against the development meter and run with
the generated overlay first on its classpath for PREFIX, HASH8 and RESERVE. It
checks native closed/disabled guards, a fixed hash vector, charged original
exceptions, actual outside debits, one-time native-turn release and blocked-worker
cutoff/late receipts. It runs no synthetic gameplay policy and writes no dataset.

`--record-invalid` is an explicit diagnostic mode: a rejected input receives
INVALID_TRACE and an error/hash receipt, with no coverage or learning claim.
Strict validation still fails on malformed outcomes. `campaign-summary.json`
reports valid/rejected counts separately. Startup timeouts can precede
GAME_STARTED and meter registration; initial shuffle events are retained and
no game-start ordering/capture-coverage claim is invented. Complete sparse
zero-invocation ledgers retain an unknown missing-start rate.

A normally completed diagnostic replay can be run separately after validation:

```sh
python3 tools/decision-records/input-context-benchmark/replay-game-admission.py \
  /path/to/native-admission --output-dir /path/to/diagnostic-replays --seconds 180
```

Replays retain the original censored trace and require normally completed
engine/AI/RNG equality against its original normal control. A longer diagnostic
limit does not change the original execution status or the production default.
Ensure xvfb-run is on PATH, as for the primary runner. The runner honors
--java-home for both compilation and execution.

## Native process-resource checkpoint

`run-native-costs.py` accepts the required classpath/JDK/profile/output paths shown
above and runs eight sequential ordinary traced games: two seeds each for damage
and four-player Commander, with heuristic and Monte Carlo pilots. It requires
actual Monte Carlo rollouts, validates full/filtered reports, initial shuffle order
and timeout censoring, and binds strict resource sidecars to the native arguments.
The development launcher needs JDK 21; production remains Java 17. No phase agent
is installed. CPU/all-thread allocation, JVM-lifetime RSS/pool peaks and shutdown
heap have different scopes; none isolates capture retention or tracing overhead.
See the [native resource assessment](../../../docs/Development/Simulation-Native-Resource-Cost-Assessment.md).
