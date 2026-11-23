# OPT-001: input-context phase costs

This is a development-only measurement of the existing version-1 profile, with a gap review before the next item. Production capture, policy, event order, budgets and schemas are unchanged. The runtime sources are unchanged from `f115c6f2843`; experiment metadata records the current design revision, class/source hashes and commands.

## Method and validation

Four sequential JVMs form two control/profile experiment pairs, with launch order reversed in the second pair. Each uses two-player fixtures with 4 or 30 battlefield cards per player, seven-card hands, 32-card libraries and either zero or 32 delivered disclosure facts. There are nine operation modes, six measured rounds of 400 operations after 200 warmup calls per trial, a discarded initial round and fresh games to avoid budget exhaustion. Twelve separate memory-only samples per profiled JVM exclude reflective graph-sizing work from CPU/allocation results.

The development agent measures outermost capture, canonical ordering, context freeze, event freeze and Game publication spans. A development observer measures serialization using the **actual SimulationTraceWriter.write and BufferedWriter path** with a character-counting sink. The buffer escapes to an observable consumer. Collector envelope construction, UTF-8 encoding and physical I/O are excluded. Gson is used only outside timing for result output, fixture hashing and production-encoder round-trip checks.

This corrects an important limitation of the earlier probe: its Gson serialization cost does not describe Forge's custom streaming encoder. The old results remain historical Gson-probe measurements and must not be substituted for production-writer estimates.

Capture includes ordering and its internal freeze. Publication includes sink serialization and fixture observer bookkeeping. Recursive freezeMap calls are timed once per outermost traversal. Residuals subtract child spans per round; adding separately computed phase medians is not valid. Profiling hooks perturb execution; control/profile differences include JIT and run variation, so no correction factor or speedup claim is derived from them.

All known callback/event/phase counts reconcile. Disabled/copy operations allocate no input-context state, emit no choice events and perform no measured projection work. Every expected input context arrives, none is unavailable, policy hashes agree between controls/profiles and across both experiment pairs, and the production encoder round-trips each representative payload. Six new Python measurement tests bring the suite to 127 passing tests: they reject missing evidence, unavailable captures, policy differences, invalid phase counts and negative residuals. Java development probes compile and run under JDK 21; no new full Java-suite or gameplay result is claimed for this tools-only item.

## Results

The following ranges are the two independent JVMs' six-round medians, not confidence intervals. KB below means 1,000 bytes. Input choice includes the context, both lifecycle events and streaming serialization; it excludes AI policy work and file I/O.

| Battlefield cards/player | Delivered facts | Control CPU/choice | Allocation/choice | Reachable frozen context | Sampled payload-union peak |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 4 | 0 | 0.39–0.86 ms | 132 KB | 30,984 bytes | 57,272 bytes |
| 4 | 32 | 0.78–1.21 ms | 202 KB | 52,912 bytes | 96,192 bytes |
| 30 | 0 | 1.20–2.04 ms | 459 KB | 104,904 bytes | 191,536 bytes |
| 30 | 32 | 1.53–1.96 ms | 530 KB | 126,832 bytes | 230,456 bytes |

Allocation and reachable sizes are stable across the experiment pairs. CPU varies substantially, including between controls and profiles, and is not a portable latency estimate. In the larger 32-fact fixture, measured allocation per choice is approximately:

| Component | Allocated bytes/choice |
| --- | ---: |
| Internal context freeze | 124,184 |
| Event freezes, including the copied context | 155,728 |
| Projection/checks/disclosure-snapshot residual | 196,344–196,368 |
| Canonical ordering | 13,752 |
| Production streaming serialization | 37,528 |

Two freezing passes account for about 280 KB, roughly 53% of this fixture's total allocation. This is not a promised saving: immutable publication still requires ownership and deep-immutability guarantees. The second traversal is the specific OPT-002 investigation candidate.

Serialization is the largest measured CPU stage in the larger fixtures: the larger 32-fact case records approximately 0.99–1.37 ms of profiled serialization CPU versus 0.29–0.46 ms for inclusive capture. Those profiled spans include measurement perturbation and do not establish which writer operation is responsible. The actual streaming encoder allocates much less than the Gson proxy; reducing its allocation is not the main CPU question.

The retained 32-fact buffer is 19,896 reachable bytes; the empty-list sample is 40 bytes. Buffer graphs and context graphs share values, so their sizes cannot be blindly summed. Representative context payloads range from 5,240 to 27,309 UTF-8 bytes, with gzip samples approximately 0.9–1.7 KB. Compression time is not measured. GC counts/time deltas are retained for each operation window; they can include setup/JIT garbage and do not establish collection causality per operation.

## Priority consequences

- OPT-004 is the strongest CPU investigation candidate: profile the production writer's escaping, buffering/locking and traversal costs before changing it. Compression alone will not resolve encoder CPU.
- OPT-002 is the strongest allocation candidate: investigate avoiding the redundant context copy while preserving immutable publication and legacy untrusted-map behavior.
- OPT-003 remains lower priority for canonical ordering in these fixtures. Projection construction is a separate sizeable allocation source; further attribution is needed before changing it.
- The next scheduled coverage item remains COV-002 after review. These findings identify optimization candidates, not authorization to bundle them into coverage commits or proceed past this pause.

## Gap review and ownership

| Remaining gap | Follow-up owner and closure condition |
| --- | --- |
| Exact peak transient capture retention is unmeasured | OPT-005: allocation/heap-profiler validation of in-flight builder state and overlapping roots. Current samples are payload-graph lower bounds at selected freeze-return/serialization boundaries; they omit other locals, writer buffers and some owner state. |
| CPU variability and profiler perturbation | OPT-005: broader JVM/host repetitions and calibration before precise capacity, deadline-neutrality or speedup claims. No overhead subtraction from the current control/profile pair. |
| Writer CPU hotspot below the serialization boundary | OPT-004: determine escaping, synchronization, buffering and traversal contributions using the production implementation before choosing a rewrite. |
| Projection residual includes several operations | OPT-003: attribute permission/view construction, intermediate containers and disclosure snapshot work before implementation. |
| Physical I/O, UTF-8 encoding and collector conversion are excluded | OPT-005/OPT-004: validate the full trace path and storage costs separately. Current timings are not whole-simulation overhead. |
| Multiplayer, long games, larger/capped states and failure-path costs are outside these fixtures | OPT-005: additional workloads with capture-status/budget reporting. The phase agent supports healthy fixtures; unbalanced/error runs are rejected, not treated as cheap samples. |

OPT-001 has delivered phase attribution and sampled retention evidence. Exact transient retention and broader validation targets remain open as assigned above; this is not full closure of every measurement target. Pause here before COV-002 or an optimization implementation. Coverage gaps remain with their [existing owners](Simulation-Observation-Coverage-Plan.md#assigned-cov-001-gaps-and-scheduling); invocation accounting is not inferred from this probe.

## Reproduction and artifacts

Use the [phase-probe instructions](../../tools/decision-records/input-context-benchmark/README.md#opt-001-phase-probe). Raw measurements are standard gzip-compressed JSON objects containing `control` and `profiled` arrays. Extract each array to the named JSON file in a temporary directory and run `summarize-phases.py` to reconstruct complete per-mode distributions and phase summaries.

- [Compact results](assessments/OPT-001/summary.json)
- [Validation checks](assessments/OPT-001/checks.json)
- [Commands, JVM version and source/class hashes](assessments/OPT-001/metadata.json)
- [Raw experiment A](assessments/OPT-001/measurements-a.json.gz)
- [Raw experiment B, reversed order](assessments/OPT-001/measurements-b.json.gz)
