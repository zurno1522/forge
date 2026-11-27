# COV-008A: V28 sustained-availability baseline

The audited payment/source/X rollout is complete through V28. Two fresh regular four-player Commander games retain decision records after the fixed capture allowance is exhausted, but lose many later input views. This is the existing specified budget policy. No runtime policy, share, budget, permission, representation or engine ordering changes in this increment.

## Method and validation

Both games use two Doctor Who and two Ur-Dragon decks, heuristic play with Monte Carlo off, seeds 31009 and 31010, and the existing 90-second game limit. Both finish normally. Full/filtered dataset, choice, example and validation artifacts match byte for byte; coverage differs only in source-record totals. Their separate availability reports also match. All four initial shuffles precede GAME_STARTED. Source/collector counts reconcile without overflow, capture failures or outstanding callbacks. All examples remain trainingReady=false.

The development report consumes an existing validated coverage audit. It reconciles the record/status/reason/known-parent-omission totals against both turn and boundary marginals. It groups owning chooser IDs by run and game, retaining clock bases and null clocks separately. Ten-turn windows are absolute game-turn intervals 0–9, 10–19 and so on; these are not a player's own turns or equal-duration windows. Earliest observed budget turns are labelled by their own basis, not presented as exact exhaustion instants. Boundary and player marginals do not establish a joint distribution.

All 335 Python tests pass, including seven new behavioral methods covering denominators, clock separation, unknown clocks/choosers, reused IDs, zero/absent-only scopes, reason overlap, reconciliation and invalid declarations. No Java code changes; V28's 323 focused cases remain the current runtime validation. Exporter/audit sources remain unchanged, so V28's 330-stream seven-artifact legacy parity remains applicable. The report is an independent development artifact and does not change those seven exports.

## Observed availability

Counts refer to overlapping observed records, not unique gameplay decisions. A present context can be unavailable; absent contexts are counted separately. CAPTURED/PARTIAL means the named partial profile is available, not complete decision inputs. A budget reason affects one record, irrespective of how many components mention it. Other reasons can overlap.

| Seed | Observed records | Present contexts | Captured/partial views | Absent contexts | Budget-affected records | Budget / present contexts |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 31009 | 2,837 | 2,657 | 661 | 180 | 1,992 | 74.97% |
| 31010 | 1,488 | 1,481 | 805 | 7 | 634 | 42.81% |

Four hidden contexts explain the remaining unavailable views in seed 31009; seed 31010 has 42. Source-ledger totals are 2,589 and 1,346 original-AI standalone invocations, respectively, with zero pre-record omissions or observation failures. Their denominator excludes parent/nested scopes, so it must not be compared with the table as a missing-record count. Uninstrumented invocation counts remain unknown.

| Seed | Chooser ID | Present contexts | Captured/partial | Budget-affected | Earliest independent entry turn with budget reason | Earliest parent-baseline turn with budget reason |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 31009 | 0 | 672 | 160 | 510 | 21 | 21 |
| 31009 | 1 | 593 | 168 | 423 | 24 | 26 |
| 31009 | 2 | 431 | 159 | 272 | 19 | 19 |
| 31009 | 3 | 961 | 174 | 787 | 28 | 28 |
| 31010 | 0 | 703 | 157 | 504 | 15 | 17 |
| 31010 | 1 | 277 | 206 | 71 | 26 | 26 |
| 31010 | 2 | 198 | 198 | 0 | — | — |
| 31010 | 3 | 303 | 244 | 59 | 37 | — |

A dash means no observed budget reason on that clock basis. Equal original-player work shares do not produce equal view counts: invocation counts, board/proof costs and visibility differ. The earlier V4 sample used a narrower observation rollout; it is not a percentage-overhead or availability-regression control for these games.

Full/filtered files contain 15,878,939/14,831,092 bytes for seed 31009 and 15,871,516/15,316,382 bytes for seed 31010. These are whole file sizes, not per-context or incremental capture cost. Observed engine game times are about 68.2 and 41.2 seconds. Neither wall time nor file size isolates tracing CPU, allocation, physical I/O or timeout effects.

## Gap review and ownership

No current/prior-item runtime defect is reproduced. The initial report smoke test caught a development clock-label mismatch; it now accepts the exporter's literal CHOICE_START_EVENT label, with regression coverage. The report keeps absent input separate from explicit budget/hidden unavailability and preserves unknown source scope rather than creating zero-loss claims.

COV-008A is complete. COV-008B must measure candidate admission against actual capture/disclosure work before selecting a production policy. Existing traces lack per-entry debits and counterfactual costs after exhaustion; their marginals cannot predict how many late views a sampler would recover, prove joint family/player bias, or supply inclusion probabilities. The next experiment needs attributed work deltas, nested/disclosure accounting and admission statistics; it must preserve callback behavior and use no engine RNG. COV-008C owns separately versioned producer/consumer implementation after that evidence. The fixed 256,000-unit game allowance and original-player shares remain unchanged.

OPT-005 retains healthy/exhausted CPU/allocation, transient heap, host calibration and full-writer measurements. COV-004 retains complete semantics/domains/bindings/readiness. This baseline supplies evidence for policy design; it does not certify sustained completeness or choose a runtime optimization.

Evidence: [validation](assessments/COV-008-availability/validation.json), [checks/source hashes](assessments/COV-008-availability/checks.json), [commands](assessments/COV-008-availability/commands.json), [seed 31009 report](assessments/COV-008-availability/four-player-31009-availability.json), [seed 31010 report](assessments/COV-008-availability/four-player-31010-availability.json), [seed 31009 coverage](assessments/COV-008-availability/four-player-31009-coverage.json) and [seed 31010 coverage](assessments/COV-008-availability/four-player-31010-coverage.json).
