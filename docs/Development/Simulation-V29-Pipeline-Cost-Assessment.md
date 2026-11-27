# OPT-005: actual collector, UTF-8 and failure-path costs

This increment extends the [controlled prefix comparison](Simulation-V29-Controlled-Cost-Assessment.md) through the real collector and UTF-8 file writer. It measures fixed callbacks, not AI policy or played games. Production behavior, prefix admission and the allowance remain unchanged. [Native Monte Carlo/resource checks](Simulation-Native-Resource-Cost-Assessment.md) are now delivered; the optional hash candidate remains next under the [checkpoint contract](Simulation-V29-Cost-Checkpoint-Contract.md).

## Method and verification

Two control/profile pairs run four nonoverlapping JVMs in opposite orders against production V29. SMALL, MULTIPLAYER and CAPPED states use the existing declared fixture sizes, with an additional public Unicode/quote/backslash/newline/surrogate-pair presentation. NUMBER, maximum-X and Damage wrappers receive fixed original callbacks. Separate modes consume the allowance through real captures, inject an input-supplier exception or close the real writer before the measured interval.

Each trial has four warmup operations and eight measured operations; one rotated pass is discarded. The study has 288 measured rounds / 2,304 measured callbacks, plus 72 discarded rounds. Of the measured callbacks, 1,152 are healthy, 384 exhausted, 384 capture-failure and 384 writer-failure cases. All callbacks retain their original result/object. No allowance is replenished or changed, including during exhaustion setup.

The development bridge uses SimulationTraceCollector and SimulationTraceWriter with actual UTF-8 files. Warmup is flushed before the measured byte offset. The final measured flush is inside whole-operation timing; authoritative close/footer output and subsequent validation are outside it. These writes/flushes reach the operating-system cache without an additional fsync. Output files contain synthetic operation records without a run/game-start envelope and are deliberately not standalone learning traces.

The validator checks complete matrices, source/callback/entry receipts, original completed lifecycles, nonreused choice IDs and contiguous source event indexes. Every measured byte slice and whole file has independent size/hash verification, strict UTF-8 decoding and JSON parsing. Actual contexts pass the ordinary input/entry validators. Unicode must appear in healthy physical output. Control/profile rounds retain the same represented policy. Nine tests use real probe receipts/files and reject byte/hash/slice corruption, reordered events, reused IDs, missing phases/source counts, misleading failure labels and incomplete cohorts. All 377 Python tests pass.

Capture failures charge actual work, preserve the callback and expose the original observation exception; their unavailable context truthfully has unknown admission. The profiler reconciles two event freezes, one entry-location freeze and one unavailable-factory freeze per operation. Exhausted calls retain lifecycle and selected-gate metadata while charging no further work. Writer failures preserve source callbacks and observed input records but deliver zero timed file bytes; failed writer/flush spans unwind and the collector failure remains visible. These cases never enter healthy performance comparisons.

## Measured costs

The table pools eight uninstrumented round means per stratum. CPU includes capture, receipt/event freezing, collector conversion, encoding, UTF-8 file writing and the final flush. KB is decimal. Raw per-round ranges are retained in evidence; these medians are not precise cross-host capacity or deadline estimates.

| Fixture | Wrapper | CPU, ms/op | Allocation, KB/op | Measured UTF-8 bytes/op |
| --- | --- | ---: | ---: | ---: |
| SMALL | NUMBER | 0.585 | 149.2 | 6,741 |
| SMALL | MAX_X | 0.582 | 151.9 | 6,917 |
| SMALL | DAMAGE | 0.461 | 152.3 | 6,976 |
| MULTIPLAYER | NUMBER | 1.654 | 744.7 | 36,842 |
| MULTIPLAYER | MAX_X | 2.096 | 746.6 | 37,018 |
| MULTIPLAYER | DAMAGE | 1.690 | 747.0 | 37,077 |
| CAPPED | NUMBER | 2.015 | 1,019.3 | 49,103 |
| CAPPED | MAX_X | 2.229 | 1,022.3 | 49,280 |
| CAPPED | DAMAGE | 2.096 | 1,023.0 | 49,338 |

The profiled encoding/writing/final-flush stage has medians around 1.06–1.24 ms for MULTIPLAYER and 1.55–1.67 ms for CAPPED. It remains the strongest OPT-004 CPU candidate. Its span includes the final flush outside Game.fireEvent, so it is not wholly nested in publication and must not be subtracted blindly to infer collector-only cost. Capture, freezing and publication also overlap their outer whole-operation span. Instrumented and uninstrumented timings have different bookkeeping costs.

Exhausted medians are around 0.09–0.13 ms/op, capture-failure medians 0.28–0.43 ms/op and writer-failure medians 0.22–0.60 ms/op. These are different failure/availability states, not speedups. Source-observed contexts and physical delivery have explicitly different denominators.

Under this JDK/heap configuration, controller/state shallow sizes are 56/24 bytes and the pure admission-counter graph is 40 reachable bytes. These are current sizes, not a measured V28 delta or copied-controller construction cost. GC counts and separate heap-pool high-water values cover the interval after warmup through cleanup. They include other live state and uncollected garbage; pool peaks need not coincide, and no exclusive transient-capture peak is claimed.

## Gap review and next ownership

The small smoke cohort first verified actual writer/capture failure paths before full timing. Review kept the flush outside publication explicit, recorded exact file offsets instead of estimating delivered bytes, and preserved unsupported GC/pool metrics as unknown. The injected failure uses the normal numeric terminal shape, so its smaller output is caused by unavailable input rather than an unrelated result-format shortcut. No current/prior production defect is reproduced here.

[Native Monte Carlo and whole-process resources](Simulation-Native-Resource-Cost-Assessment.md) are now measured. OPT-005 retains larger resource distributions, copied-controller construction and broader host/heap attribution. Later-family measurements here cover representative maximum-X/Damage received scopes; they do not certify all COV-006/007 families. COV-008C retains production-shaped hash cost/availability review before optional enablement. OPT-004/002/003 retain separate evidence-based implementation commits; no runtime optimization is bundled here. All reports remain trainingReady=false.

Evidence: [checks](assessments/OPT-005-v29-pipeline/checks.json), [raw measured rounds/file receipts](assessments/OPT-005-v29-pipeline/measurements.json.gz), [pooled comparison](assessments/OPT-005-v29-pipeline/comparison.json), [per-pair summaries](assessments/OPT-005-v29-pipeline/summaries.json.gz), [commands/runtime hashes](assessments/OPT-005-v29-pipeline/metadata.json) and [source freeze](assessments/OPT-005-v29-pipeline/source-freeze.json). Representative physical-file data and actual smoke receipts are also frozen as the validator's unit fixtures.
