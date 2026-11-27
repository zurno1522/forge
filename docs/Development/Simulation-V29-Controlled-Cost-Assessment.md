# OPT-005: controlled V28/V29 production-prefix costs

The first increment of the [V29 checkpoint contract](Simulation-V29-Cost-Checkpoint-Contract.md) refreshes controlled costs against the actual prefix implementation. It does not complete the broader checkpoint or enable sampling/optimization. Represented policies, statuses, omissions and exhaustion behavior match V28 throughout these fixtures. Metadata has a measurable allocation/size cost; CPU variation prevents a precise admission-overhead percentage.

## Method and validation

Four independent control/profile pairs run eight sequential JVMs, alternating producer and execution order: V29 control-first, V28 profile-first, V28 control-first, V29 profile-first. The runner compiles the four V28 production classes from 555c3fbbb35 into an isolated classpath, checks GameAction compatibility and preserves production classes. V29 uses a54a34c7c58's runtime implementation. Commands, source/class hashes, JDK configuration and the requested order are retained.

Each producer has eight measured rounds per workload/mode across two JVMs. Five fixtures cover small/public/private boards, four players, populated disclosures, active stacks and capped states. Seven modes cover disabled/copy callbacks, base lifecycles with/without source accounting, numeric/entity choices and direct capture. Each healthy trial has twelve warmup operations and forty measured operations; a whole rotated pass is also discarded. There are 1,120 measured workload/mode rounds, twenty separate memory samples and eight 1,200-call exhaustion trials. Measurements use fixed original callbacks, not heuristic policy execution.

All same-workload/mode policy hashes, statuses and omission declarations agree across producers, control/profile variants and rounds. Native callback/event/source receipts and public-proof checks reconcile. Disabled/copied callbacks initialize no observation state and spend no capture work. All uncapped public-disclosure views bind the thirty-two delivered facts; private disclosures remain unresolved, and capped presentations cannot claim unique public associations. Every exhaustion trial has 246 available views followed by 954 unavailable views under the same allowance. Those unavailable windows are excluded from healthy cost summaries.

Review caught an old measurement assumption: the legacy eventFreeze bucket measures all outermost freezeMap calls outside input capture. AiEntryLocation.capture freezes one entry-location receipt per numeric/entity operation in addition to the two event freezes. The first unversioned refresh correctly failed validation and is excluded. Measurement schema V2 explicitly names the broader scope and checks the additional call for the measured V28/V29 paths, while preserving separate event/publication/serialization counts. It does not relabel receipt freezes as extra events. All four final cohorts validate under the final reader. Follow-up source review corrected the initial callback-parameter attribution to the actual entry-location receipt; raw counts and cost figures are unchanged. Other producer versions require their own V2 accounting audit.

A further validator regression reproduced Python's bool/int set collapse: a final boolean schema declaration could disappear into an earlier integer before validation. Every declaration is now type-checked before grouping. The regression fails before the fix and passes after. All 368 Python tests pass, including the two new measurement methods. No production Java changed in this increment; the development probes compile and execute against both producers.

## Results and limits

The table pools eight uninstrumented-control round means per producer. Entity operations include capture, lifecycle freezing/publication, source accounting and production JSON encoding to a buffered character-counting sink. They exclude collector conversion, UTF-8 encoding and physical I/O. Allocation includes the harness bookkeeping used by both producers. CPU ranges are observed round minima/maxima, not confidence intervals.

| Fixture | V28 entity CPU median (range), ms | V29 entity CPU median (range), ms | V28 allocation, KB/op | V29 allocation, KB/op |
| --- | ---: | ---: | ---: | ---: |
| SMALL | 0.568 (0.352–1.473) | 0.721 (0.559–0.920) | 163.8 | 165.7 |
| PRIVATE32 | 2.147 (1.996–2.818) | 2.067 (1.919–6.257) | 704.7 | 706.1 |
| PUBLIC32 | 2.100 (2.024–2.222) | 2.100 (2.049–2.589) | 714.5 | 718.8 |
| MULTIPLAYER | 2.363 (1.840–3.805) | 2.300 (1.761–2.440) | 863.4 | 867.9 |
| CAPPED | 3.272 (3.072–3.445) | 3.320 (3.062–3.577) | 1,312.0 | 1,315.1 |

KB is decimal. Entity median allocation deltas range from about 1.4 to 4.5 KB; direct-capture deltas range from about 1.1 to 3.5 KB. Every healthy fixture context adds 137 encoded UTF-8 bytes at these ordinal widths. Longer ordinals can add digits. Reachable entity-context graphs grow by 824–848 bytes and direct-context graphs by 784–808 bytes, including shared keys/constants; these are not exclusive retained-memory deltas and cannot be summed across views blindly.

Disabled/copied callbacks allocate 136 bytes per operation under both producers in these controls; their CPU medians are around 1–2 microseconds. Fixture/controller creation happens before timing, so this does not measure V29's copied-controller field construction/footprint cost. SMALL CPU variation is especially broad. Neither lower medians elsewhere nor overlapping ranges prove a speedup or deadline neutrality.

In the first V29 profiled pair, PUBLIC32 entity serialization is about 0.997 ms versus 0.455 ms inclusive capture; the corresponding CAPPED spans are about 1.612 and 0.527 ms. Those spans include instrumentation and overlap publication/whole-operation timings. Serialization remains the strongest OPT-004 CPU investigation candidate. Context/outside-capture freezing remains an OPT-002 allocation candidate, while canonical ordering is lower priority in these fixtures. No runtime rewrite is selected by this measurement alone.

## Gap review and next increment

The phase-scope and declaration-type validator defects are closed before proceeding. Next, complete the broader checkpoint's collector/physical UTF-8, later-family, failed/interrupted and native Monte Carlo cohorts. Audit profiler unwinding on implicit exceptions before using it to measure failure paths; current healthy samples reject failed fixtures and do not certify that behavior. Copy/controller creation cost, broader host calibration, GC/heap occupancy and exact transient attribution remain separately named OPT-005 scope. Selected detached-payload union peaks remain lower bounds, not exact live capture heap.

The new profile label does not mean these numeric/entity/direct fixtures exercise every V19–V28 received-parameter family. Keep those later-family measurements explicit. COV-008C's optional hash-four candidate and enablement remain behind the production cost gate; prefix/allowances remain unchanged. COV-004 retains semantic/domain/history completeness and trainingReady remains false.

Evidence: [full raw rounds](assessments/OPT-005-v29-controlled/measurements.json.gz), [per-pair summaries](assessments/OPT-005-v29-controlled/summaries.json.gz), [comparison](assessments/OPT-005-v29-controlled/comparison.json), [commands/runtime hashes](assessments/OPT-005-v29-controlled/metadata.json), [collection-time summarizer](assessments/OPT-005-v29-controlled/summarizer-at-collection.py), [original checks](assessments/OPT-005-v29-controlled/checks.json), [original source freeze](assessments/OPT-005-v29-controlled/source-freeze.json) and [entry-attribution correction/revalidation](assessments/OPT-005-v29-controlled/entry-attribution-correction.json). The original source receipts remain tied to collection/initial analysis. The correction receipt records the latest validator and verifies unchanged raw data, cost and exhaustion figures; summary descriptions now name the entry-location receipt. Collection metadata is not rewritten to claim use of later validation code.
