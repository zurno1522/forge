# Development cost probe

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
