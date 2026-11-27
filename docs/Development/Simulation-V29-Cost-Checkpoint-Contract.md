# OPT-005: production V29 cost checkpoint

Status: measurement design after [prefix V29](Simulation-Prefix-Admission-Assessment.md), baseline a54a34c7c58. This is separate from coverage-policy implementation and changes no runtime defaults, serializer or capture representation. Review each measurement increment before selecting a runtime change.

## Questions and cohorts

Establish the cost of the actual V29 implementation, including its admission receipts and controller ownership fields. The V28 experiment monitor is not a production baseline. Match represented information when comparing prefix versions; deliberate sampled/unavailable input is not a capture optimization. Native Monte Carlo outcomes and work counts can differ with deadlines and cannot serve as deterministic same-seed equivalence controls.

Run these independently reviewable increments:

1. **Controlled producer and metadata comparison.** Refresh the five existing SMALL/PRIVATE32/PUBLIC32/MULTIPLAYER/CAPPED workloads on V29, preserving healthy versus exhaustion windows, private/public disclosure proof checks, disabled/copy paths, base accounting, numeric/entity operations and direct capture. Use matched isolated V28 classes from 555c3fbbb35 for representation-preserving comparisons. Retain the source-pinned V29 compatibility evidence separately. Use at least four measured rotated rounds after discarded warmup, and reverse control/profile order in a second nonoverlapping pair. Record phase CPU/allocation, whole-operation CPU/allocation, reachable detached payloads and encoded sizes. Declare which family inputs are actually exercised; this does not by itself measure all V19–V28 families.
2. **Expanded families and complete emission path.** Add fixed-callback observations for later received-parameter families, including Damage and maximum-X, plus explicit exhausted/closed/failure work. Exercise native collector conversion and the production writer through real UTF-8 files, with flush and physical size/hash receipts. Keep a writer-only sink cohort for attribution; character counts alone are not physical bytes. Include two- and four-player/capped states and populated disclosure buffers. Test the measurement validator against wrong status/family/callback/source/byte/cutoff claims, not only fixed synthetic numbers.
3. **Deadline-sensitive native workloads and resource limits.** Record ordinary heuristic and Monte Carlo games with simple decks and complex Commander decks. Preserve source/collector health, timeout censoring, pregame ordering, actual input availability and native execution status. For Monte Carlo, compare distributions of completed/censored work with declared deadlines and process CPU/allocation/heap evidence where supported. Do not require equal actions/outcomes, call a faster truncated game an overhead improvement, or infer unavailable views from absent traces. Tracing-disabled controls have unknown observation coverage. Report startup/JIT and host effects explicitly.
4. **Policy candidate gate.** Measure the extra cost of a production-shaped hash-four candidate without the experimental debit monitor, aggregate maps, reflection-based funding changes or per-capture history. Validate fixed hash vectors, per-player scoping, original callback/RNG behavior and the selected/omitted accounting contract. This candidate remains isolated until its cost/availability evidence and review support optional enablement. Configuration, wire validation and enablement retain a separate COV-008C commit.

Run JVMs sequentially, recording exact commands, revision/dirty state, source and selected class hashes, JDK/host configuration, dimensions, warmup and measured rounds. No Maven/class rebuild may overlap a game/probe. Refuse mixed or incomplete cohorts rather than publishing an apparently complete result. Process elapsed time must exclude validation time and is still not isolated capture CPU.

## Cost and resource contracts

Use calling-thread CPU/allocation counters for synchronous controlled operations, with counter availability checked. Separate disabled/copy, base lifecycle/accounting, healthy capture, deliberately omitted capture, exhausted capture and failed/interrupted work. Include an empty/control operation to expose timing and bookkeeping floors. Report per-round raw values, median and range; do not manufacture precise slowdown percentages from noisy near-zero controls. Parent/child and publication/writer spans overlap and must not be added.

Validate unchanged represented policy hashes across equivalent prefix controls/rounds; metadata and ID-width byte differences are separate costs. Preserve exact native callback returns, thrown exception identity and invocation counts. Charge admitted failed work once. A canceled or failed observation can preserve the original callback while reporting unavailable input; it is not a healthy sample. Validate populated disclosure facts and supported public associations, not only view counts. No fixture may replenish or borrow the production allowance to hide exhaustion.

Attribute collector/envelope conversion, encoding, UTF-8 and physical writing separately where the harness can establish those spans. Distinguish buffered write/flush from close and from end-to-end native process duration. Keep output ordering, bytes, failure visibility and source/collector receipts observable. Record raw UTF-8 and separately measured compressed bytes; compression ratio alone is neither lower encoder CPU nor a runtime-output-format recommendation.

Memory reports must distinguish:

- Allocated bytes on the measured thread.
- Reachable detached context/event/buffer unions and fixed scalar ledgers, with shared roots counted once.
- Shallow proof/controller/counter additions versus referenced existing Game/Card graphs.
- Selected transient payload samples, which are lower bounds.
- JVM-reported heap/pool occupancy peaks, which include other live objects and uncollected garbage and are not exclusive capture retention. Separate pool peaks need not occur simultaneously.

Do not relabel sampled payload graphs or heap occupancy as exact peak live capture memory. If an exact transient attribution remains unmeasured, retain that specific OPT-005 follow-up and its instrumentation/resource requirement explicitly. A finite benchmark establishes its measured scope, not an unbounded-game memory or deadline guarantee.

## Decision and gap review

After each increment, close reproduced current/prior defects and assign later scope to its owner. A missing measurement or a noisy result must remain a limitation rather than becoming a passing comparison. Validate source/code freezes and keep representative native artifacts separate from synthetic operation output; neither is a training-readiness claim.

Only choose runtime optimizations supported by the refreshed measurements. OPT-004 owns serialization/storage, OPT-002 redundant immutable copies and OPT-003 construction/canonical ordering; each needs a separate design/implementation commit, equivalent output/permission tests and before/after evidence. Workload generation, profile-label support and measurement hardening belong to OPT-005 and are not runtime speedups.

COV-008C continues to own optional hash configuration/enablement and the finite-allowance availability tradeoff. The default remains prefix, the allowance remains fixed, and COV-004 completeness/history/domain requirements stay open. All analysis and examples remain trainingReady=false.
