# COV-008B: controlled admission results

Controlled producer experiments confirm that admission can spread a fixed work allowance later, with tradeoffs in early coverage, family coverage and disclosure availability. No production policy is selected. The unchanged prefix remains the production behavior; the fixed 256,000-unit game allowance and original-player shares are unchanged.

## Method and verification

The [contract](Simulation-Availability-Experiment-Contract.md) defines three fixtures, short/long horizons, ten admission variants and the two isolated source hooks. Three sequential JVMs run unmodified production prefix capture, forward candidate order and reversed candidate order. Each workload/horizon has a discarded warmup and three measured rounds. The 378 measured trials and 126 discarded warmups use actual producer work charges, native recorders, nested parent links and the actual streaming writer with a counting character sink. The explicit fixture schedule has 240 input invocations over six turns or 2,400 over 60 turns; these are synthetic fixture clocks, not independently recorded game clocks. Native gameplay policy is replaced by fixed callbacks solely to isolate observation behavior.

Every overlay prefix matches production on represented-policy samples, availability, capture/disclosure debits and source/callback/lifecycle counts. Every native callback returns its original value/object, the checksum agrees, nested Draw retains its Damage parent, the engine RNG tail is unchanged, and inactive players retain their full shares. Actual spent work plus remaining and explicitly held allowance equals the original chooser share. Failed admitted traversals remain charged. Source-ledger input-unavailable counters reconcile with the experimental groups. There is no collector in this emission-only probe; matching emission counts are not collector-delivery evidence.

The report separates AVAILABLE, admitted budget failures, deliberate sampling, reserve omissions and charged first-tranche failures. It also retains represented card/zone/fact occurrences, rules/planning bases and per-record omission reasons. Uncapped rules-state views bind every fact actually received from the deliberately unique public deliveries, initially 32. Planning views retain their existing refusal. Facts lost to a blocked later delivery are counted as absent, not reconstructed after release.

All 344 Python tests pass, including nine new methods. They challenge funding/debit conservation, missing lifecycles/source scope, stride/hash declarations, healthy versus unavailable costs, profile quality/proof claims, prefix equivalence and incomplete experiment matrices. Separate reader checks first accept an ordinary fixture, then reject each experimental omission reason. Development Java is compiled with --release 17; no production Java or exporter code changes and no new full-reactor result is claimed.

## Availability and cost

All rows below use the same native callbacks and chooser share. Counts are deterministic across the six candidate repetitions; cost ranges below are observed round means, not confidence intervals. Late means fixture turns 31–60.

| Long fixture | Admission | Available profiles | Available at turns 31–60 | Remaining work |
| --- | --- | ---: | ---: | ---: |
| SMALL2 | Prefix | 1,396 | 196 | 0 |
| SMALL2 | Hash 1/4, salt 0 | 572 | 290 | 75,575 |
| SMALL2 | Hash 1/8, salt 0 | 288 | 153 | 101,612 |
| SMALL2 | Half reserve | 1,396 | 698 | 0 |
| PUBLIC4 | Prefix | 271 | 0 | 0 |
| PUBLIC4 | Hash 1/4, salt 0 | 270 | 0 | 0 |
| PUBLIC4 | Hash 1/8, salt 0 | 269 | 134 | 0 |
| PUBLIC4 | Half reserve | 270 | 135 | 0 |
| GROWING4 | Prefix | 271 | 0 | 0 |
| GROWING4 | Hash 1/4, salt 0 | 270 | 0 | 0 |
| GROWING4 | Hash 1/8, salt 0 | 288 | 153 | 250 |
| GROWING4 | Half reserve | 290 | 155 | 0 |

| Short PUBLIC4 fixture | Available profiles | Held work at end |
| --- | ---: | ---: |
| Prefix | 240 | 0 |
| Hash 1/8, salt 0 | 36 | 0 |
| Half reserve | 135 | 32,000 |

| Long PUBLIC4 admission | Whole-run CPU median (min–max), ms | Allocated MB median | Healthy entry median, ms |
| --- | ---: | ---: | ---: |
| Prefix | 520.3 (497.5–790.6) | 256.8 | 1.279 |
| Hash 1/8, salt 0 | 518.1 (503.4–737.4) | 255.1 | 1.271 |
| Half reserve | 498.0 (469.8–707.7) | 250.2 | 1.202 |

Stride samples select exactly one family in the deliberate four-slot loop: Damage, nested Draw, number or entity, depending on offset. A different offset changes the missing families rather than repairing the alias. Hash thinning avoids that fixed pattern in these fixtures. A denominator is an admission rule, not an unbiased inclusion probability for captured examples after budget exhaustion or reservation.

The reserve candidate funds half the original share and unlocks the held half once at fixture turn 31. On the short public-disclosure run, it captures fewer views while stranding 32,000 units. Disclosure ingestion spends the same allowance: a blocked early delivery leaves the first 80 available late profiles in the long PUBLIC4 reserve run without facts, until a successful refresh. Only 55 of its 135 available late profiles contain the 32 delivered facts. Protecting later capture attempts therefore does not guarantee equivalent later input knowledge.

The growing fixture illustrates why visit count is not CPU or representation size. Early available views represent 80 cards (72 battlefield and eight own-hand cards). Later capped views represent 128 cards (120 battlefield and eight own-hand cards), omitting the rest of the public board. They can consume fewer traversal units because whole hidden-zone/prefix guards change which visits occur. All later views declare prefix omissions; complete-public-state proof associations can be refused. Extra available capped profiles do not establish a quality improvement. The raw results retain overlapping disclosure-budget/prefix/unsupported reasons rather than collapsing them into one availability label.

CPU/allocation observations are current-thread measurements on this host with metric reads and validation/aggregate bookkeeping included. Entry spans finish after START serialization, excluding callback/terminal work and later quality aggregation. Whole-run spans include those stages and disclosure refresh; initial fixture/disclosure setup, board creation and final result hashing/encoding are excluded. Parent and child spans must not be added together. Writer totals are characters, not UTF-8 bytes or physical I/O. Diagnostic ID widths can change character totals slightly between otherwise equivalent trials. Healthy, unavailable and sampled entry costs remain separate, so cheap unavailable calls cannot appear as a healthy-capture speedup. These measurements do not certify production overhead, deadline neutrality, exact transient heap or a runtime optimization.

## Gap review and next ownership

Review found two development measurement gaps and closes them here: available-view counts hid changing represented state; and native devModeSet calls in the loop emitted events that invalidated public-proof witnesses. Quality counters and proof assertions now reject those misleading claims. Preliminary runs are excluded from the assessment. Reservation testing additionally showed legitimate lost disclosures after allowance contention; the assertion now checks facts actually received, with explicit initial-population and per-entry association gates. That is a policy tradeoff, not a Forge engine defect.

The controlled portion of COV-008B is complete. Actual-game admission evaluation remains within COV-008B before policy selection: use multiple active players, real family frequencies, parent/search/callback scopes, disclosure lifetimes, variable costs and timeout/cancellation behavior. Validate deterministic heuristic engine/RNG controls, source/collector reconciliation and pregame shuffle ordering. Any candidate loader/telemetry needed for those experiments remains isolated until its own producer/consumer contract exists. Do not run experimental reason records through the ordinary dataset exporter.

COV-008C retains policy selection and separately versioned production admission/omission reporting, including stable observation seed/counter ownership, lifecycle closure, overflow, concurrency and bypass behavior. A half-reserve fixture using development-only reflection is not a proposed production budget API. OPT-005 retains broader host calibration, exact transient retention and collector/UTF-8/physical-writer attribution, with optimizations in separate commits. COV-004 retains complete semantics, domains, bindings and readiness. All examples remain trainingReady=false.

Evidence: [summary](assessments/COV-008-admission/summary.json), [raw aggregate trials](assessments/COV-008-admission/measurements.json.gz), [commands and runtime hashes](assessments/COV-008-admission/metadata.json), [source freeze](assessments/COV-008-admission/source-freeze.json) and [checks](assessments/COV-008-admission/checks.json).
