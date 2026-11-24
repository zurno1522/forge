# OPT-005: V4 workload checkpoint

This checkpoint measures `PERMITTED_CHOICE_VIEW_V4` and the COV-001A accounting baseline at `78611be38f7`, before the broad COV-006/007 rollout. It changes development probes, validation and documentation only. No production representation, capture budget, selector, writer or learning policy changes. OPT-005's exact transient-heap, host calibration and full-path attribution work remains open.

## Method and checks

Two independent experiment pairs run sequential, nonoverlapping JVMs, reversing control/profile order in the second pair. Each pair has six rotated measured rounds for five workloads and seven modes: disabled, copied, unaccounted base choice, accounted base choice, numeric choice, entity choice and capture only. Each healthy round has 80 operations after 20 warmup operations in a fresh game; an initial round is discarded. Shorter windows than OPT-001 avoid exhausting the unchanged player share during cost measurement. Separate 1,200-invocation windows intentionally exhaust it.

The fixtures have two/four players, 4/18/30/40 battlefield cards per player, seven-card hands, 32-card libraries, 0/4/8/12 actual stack entries and zero/32 private/32 public delivered facts. Synthetic public names are distinct so the public-proof workload exercises the complete permitted-presentation uniqueness scan. Entity choices exercise current diagnostic option associations. The large fixture deliberately exceeds the 128-card and eight-stack-entry capture caps. Known source accounting is explicitly configured, as it is by the production collector; merely setting the recording flag would omit that cost.

Every callback/event count and source-counter transition reconciles. Controls perform no instrumented phase work; disabled/copy callbacks initialize neither context nor accounting state. Healthy rounds reject unavailable input rather than calling lost information a performance improvement. All 32 public facts bind in uncapped supported views; private facts remain unresolved and capped presentations do not establish disclosure identity. Represented policy hashes agree across rounds, controls/profiles and both experiment pairs. Event-observer validation/counting is included in publication and whole-operation timings. Production-writer serialization uses the existing buffered character-counting sink; collector conversion, UTF-8 encoding and physical I/O are excluded from these operation timings.

Five new measurement-validator tests reject cheap unavailable samples, false public bindings, source/callback/phase counts, wrong dimensions, invalid overlapping spans, noncontiguous exhaustion windows, changed policies and missing rounds/memory evidence. The Python suite passes 157 tests. Java development probes compile and run against the built production baseline; no new full Java-suite result is claimed for this tools-only item.

## Cost and bounded state

Ranges below are the two control JVMs' six-round medians, not confidence intervals. Entity-choice operations include capture, source accounting, both lifecycle events and production streaming serialization, without policy work or physical I/O.

| Workload | Players | Battlefield/player | Stack | Delivered facts | CPU/entity choice | Allocation/entity choice | Reachable detached context |
| --- | ---: | ---: | ---: | --- | ---: | ---: | ---: |
| Small | 2 | 4 | 0 | 0 | 0.46–0.60 ms | 157–158 KB | 36,520 bytes |
| Private facts | 2 | 30 | 4 | 32 private | 1.50–2.04 ms | 694–699 KB | 162,536 bytes |
| Public proofs | 2 | 30 | 4 | 32 public | 1.40–2.09 ms | 705–710 KB | 160,536 bytes |
| Multiplayer | 4 | 18 | 8 | 32 public | 1.65–2.31 ms | 852–858 KB | 194,480 bytes |
| Capped state | 4 | 40 | 12 | 32 public | 2.29–3.10 ms | 1,296–1,306 KB | 283,336 bytes |

The 32-fact public buffer's detached fact payload reaches 22,200 bytes, versus 19,896 for the private fixture. Its 32 proof shells occupy 1,280 shallow bytes. Proofs reference existing Cards and therefore Game graphs; the shell measurement is not their transitive retention or exclusive incremental heap. Sampled payload-union peaks reach 608,672 bytes in the capped entity fixture. These selected freeze/serialization samples omit builder locals, writer buffers and other roots, so they are lower bounds, not exact transient peaks. Shared graph sizes must not be blindly summed.

The invocation scope is 32 shallow bytes. Accounted versus unaccounted base-choice paired medians add 32 allocated bytes in nine of ten workload/pair comparisons; one comparison differs by -32 bytes, illustrating cross-trial JIT/allocation variation. CPU median differences range from -2.85 to +5.63 microseconds. This does not isolate a precise accounting slowdown or establish a speedup. The fixed scalar ledger union, including controller ledgers plus collector receipt and run aggregate, reaches 29,560 bytes for two original AI controllers, 43,192 for four and 125,032 at the 16-controller bound. These are reachable unions including shared constants and fixture list storage, not exclusive retained-heap measurements.

Serialization remains a substantial CPU stage: the public-proof entity fixture's profiled rounds spend roughly 0.91–1.24 ms in streaming serialization and 0.33–0.73 ms in inclusive capture. Profiling perturbs execution and these spans overlap their parents. OPT-004 remains the writer investigation owner; OPT-002 remains the redundant-freeze allocation owner. This checkpoint selects no runtime rewrite. CPU variation, differing fixture semantics and JIT effects prohibit a V1-to-V4 speedup/regression percentage or deadline-neutrality claim from these measurements.

## Sustained availability and a real four-player game

The unchanged global traversal allowance is 256,000 units, split equally among original players. In the fixed four-player public-proof state, the selected chooser has 64,000 units. All four exhaustion runs emit and serialize 1,200 start/terminal lifecycles and preserve original callback returns; 246 views are captured before 954 become explicitly budget-unavailable. Cost summaries exclude these exhaustion runs. Their raw windows preserve available, mixed and unavailable batches separately.

A fresh regular four-player Commander game uses two Doctor Who and two Ur-Dragon decks, heuristic play (`-mc off`), seed 31009 and a 90-second game limit. It finishes normally; the engine reports about 71.9 seconds. Full and filtered files are approximately 16/15 MB. This single wall-time sample includes policy, tracing, startup and physical I/O and is not an overhead comparison or capacity estimate.

The game records 1,490 contexts: 755 are budget-unavailable and four are hidden. The first observed budget omissions occur in the following container turns; these are not independent nested-selector timestamps.

| Player ID | Captured or partial views | Budget-unavailable | Hidden | First budget-unavailable container turn | Timing basis |
| ---: | ---: | ---: | ---: | ---: | --- |
| 0 | 177 | 156 | 2 | 29 | START_EVENT |
| 1 | 186 | 183 | 2 | 37 | START_EVENT |
| 2 | 179 | 51 | 0 | 27 | PARENT_CONTAINER |
| 3 | 189 | 365 | 0 | 35 | START_EVENT |

Source accounting separately counts 1,780 standalone invocations, 1,778 prepared starts/terminal receipts and two unsupported pre-record omissions. Both omitted invocations return from their original callbacks; observation failures are zero. Their boundaries remain unclassified because eligibility failed before source visibility. Do not guess which family they belong to from adjacent events. There are no in-flight invocations at cutoff. These are wrapped invocations, not unique gameplay decisions; view availability and record availability have different denominators.

Full/filtered datasets, choices, examples and validation match byte for byte; coverage matches except source-record counts. All four shuffles precede `GAME_STARTED`. Outcome handling remains unchanged, including censoring the two post-outcome parent decisions. Parent/nested and uninstrumented invocation totals remain unknown, and every learning example remains `trainingReady=false`.

## Gap review and next ownership

| Finding | Owner and required follow-up |
| --- | --- |
| Sustained games exhaust the deliberate fixed budget and lose late-game views | COV-008: design capture availability, sampling/limits and player allocation explicitly, with early/late-turn distributions and bounded cost. The current behavior is as specified; this measurement does not silently raise limits. This becomes a prerequisite for any claim of sustained-game example completeness, not a prerequisite for recording honest selector boundaries. |
| Two original callbacks have unsupported pre-record contexts | COV-006/007 family/bypass audits: establish the actual source conditions and supported nullable-context contract before adding records. Keep these observed omissions unclassified until evidence identifies their family. COV-001A correctly counts them and does not invent IDs or delivery. |
| New nested/inner-selector captures can spend the same budget faster | COV-006/007: retain explicit unavailable views, original callback behavior and separate invocation accounting. Repeat this checkpoint after material rollout; do not label captured-prefix data as complete or increase limits in a coverage-family patch. |
| Exact transient heap, failure-path costs, host/JIT calibration and full-path CPU/storage attribution remain unmeasured | OPT-005 retains these targets. More repetitions on this host do not replace heap profiling or other hosts. Physical files and one normal game do not attribute I/O CPU or establish timeout neutrality. |
| Serialization CPU and redundant freezing remain candidates | OPT-004 and OPT-002, respectively, in separate design/implementation commits with before/after evidence and immutable-publication tests. No runtime optimization is bundled here. |

No current/prior-item functional defect was reproduced in this checkpoint. The fixed-budget availability limit and unsupported contexts are explicit assigned coverage work, not hidden optimizations. Continue with COV-006's target/mode integration, then COV-007, retaining per-family commits and reviews. COV-008 owns the deliberate availability-policy decision before a later sustained-game readiness claim.

## Reproduction and artifacts

See [development workload instructions](../../tools/decision-records/input-context-benchmark/README.md#opt-005-v4-workloads). Raw gzip files contain `control` and `profiled` arrays; extract them into correspondingly named JSON files and run `summarize-workloads.py` to regenerate all per-mode/phase distributions.

- [Compact paired results and exhaustion windows](assessments/OPT-005-v4/summary.json)
- [Raw experiment A](assessments/OPT-005-v4/measurements-a.json.gz) and [reversed-order experiment B](assessments/OPT-005-v4/measurements-b.json.gz)
- [Commands, JVM version and source/class hashes](assessments/OPT-005-v4/metadata.json)
- [Fixed accounting-state reachability](assessments/OPT-005-v4/accounting-state.json)
- [Four-player counts, per-player/container-turn availability and parity](assessments/OPT-005-v4/four-player-validation.json)
- [Validation checks and test scope](assessments/OPT-005-v4/checks.json)
