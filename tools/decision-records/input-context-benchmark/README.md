# Development cost probe

`InputContextProbe` measures calling-thread CPU/allocation for disabled choices,
base choices, choices with contexts, the unchanged legacy view and the new view.
It includes synchronous freezing and Gson serialization, without selector work.
Four rotated rounds use 200 calls after 75 warmup calls in fresh games. The two
fixtures have 4 or 30 battlefield cards per player, plus hands and libraries.

Compile these two files with Java 21 against the built desktop test/runtime
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
