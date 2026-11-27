# COV-008B: native-game admission results

Actual heuristic games confirm that input admission can change when state views are available while preserving native play. The policy tradeoffs remain workload-dependent. This increment changes development tools and reports; production admission and its fixed 256,000-unit game allowance remain unchanged. A separately committed startup lifecycle fix closes a real cancellation defect found during this study.

## Method, validation and retained failures

The primary matrix runs native damage/X and repeated-choice decks, two four-player Doctor Who/Ur-Dragon seeds, and a one-second Commander cutoff. Each uses production, prefix, hash-four, hash-eight with two salts, and half-reserve variants: 30 separate sequential JVMs. The [contract](Simulation-Game-Admission-Contract.md) defines isolated source hooks, exact work accounting, experimental-reader quarantine and the two source/observed denominators.

The primary study has 23 NORMAL runs, six valid TIMEOUT traces and one rejected TIMEOUT trace. A Commander hash-eight run hit the 90-second limit after matching the control's first 2,828 engine events, then emitted the forced draw at turn 43. Its separate 180-second diagnostic replay completed normally and matched the control's complete engine events, native AI results and serialized RNG state/call count. The original remains censored. These results do not isolate instrumentation overhead from host/JIT variation or establish deadline neutrality.

One one-second run exposed duplicate outcomes: GameAction initialization overwrote GameOver with Mulligan/Play. No admission State had been registered, so the reserve policy had not executed. The ordinary reader correctly rejected the trace. [Startup termination hardening](Simulation-Startup-Termination-Hardening.md), committed independently as 77c8b454ff6, adds terminal checks under Game's existing monitor at those two transitions. Two native regressions fail before the fix and pass after; 37 focused Java cases pass. Public snapshot/restore/restart APIs remain unchanged. PR-006 records the upstream candidate; no upstream PR was opened.

Twelve fresh post-fix runs cover all six timeout variants and all six normal damage/X variants. All twelve validate. Together with the diagnostic replay, the study retains 42 valid game traces and the original rejected trace. No rejected/censored run is relabeled as a normal result or used as a learning example. Default strict validation still rejects malformed data; --record-invalid produces explicit diagnostic rejection receipts and separate valid/rejected counts.

All normally completed candidate comparisons match their relevant production controls for ordered engine events, native assessments/actions/targets/costs/subordinate results, and RNG state/call counts. InputContext and the target/search inputUnavailable diagnostics are excluded from that comparison, while all other delivery counters remain; input availability/accounting is validated separately. Native source/collector receipts reconcile without observation failures. Full/filtered validation and joint audit reports agree except source-record totals. Initial shuffles precede GAME_STARTED wherever it is observed. Startup cutoffs without GAME_STARTED retain their initial shuffles, an inapplicable game-start ordering field and no meter-coverage claim. Explicitly complete sparse zero-invocation ledgers retain an unknown missing-start rate.

Three executable native meter checks cover PREFIX/HASH8/RESERVE with real State charges: original guard bypasses, a fixed hash vector, charged original exceptions, outside-capture debits, one-time public-turn release and blocked-worker/late cutoff receipts. All 358 Python tests pass, including fourteen new behavioral methods and an actual early-timeout fixture. Source/ordinal/debit/quality/command checks reject inconsistent data. A private reader instance accepts exactly the two experimental reasons only for validation/reporting; it cannot publish datasets and does not modify ordinary reader permissions. All reports remain trainingReady=false.

## Availability tradeoffs

| Commander seed | Admission | Available profile records | Late independent-entry views (turn ≥31) | Late parent-baseline views (turn ≥31) |
| ---: | --- | ---: | ---: | ---: |
| 31009 | Prefix | 661 | 0 | 0 |
| 31009 | Hash 1/4, salt 0 | 625 | 335 | 15 |
| 31009 | Hash 1/8, salt 0 | 336 | 181 | 10 |
| 31009 | Half reserve | 770 | 437 | 21 |
| 31010 | Prefix | 805 | 152 | 13 |
| 31010 | Hash 1/4, salt 0 | 347 | 86 | 8 |
| 31010 | Hash 1/8, salt 0 | 183 | 47 | 3 |
| 31010 | Half reserve | 744 | 372 | 29 |

Available means CAPTURED/PARTIAL under the existing partial profile. Counts overlap across parent, nested and standalone observations; they are not unique gameplay decisions. Late columns preserve independent-entry versus parent-baseline clocks. Parent containers and unknown clocks remain separately labeled in the joint reports. Budget reasons can overlap other omissions, including disclosure losses; they are not a partition of all records.

Hash-four recovers many late views in seed 31009, but captures fewer views overall. In seed 31010 it also captures fewer late independent-entry views than prefix capture. Hash-eight reduces attempts more aggressively, with a larger early-coverage cost. The two salts and different family/actor frequencies are preserved in the full evidence. A denominator is not an unbiased inclusion probability after admission, unavailable perspectives and budget exhaustion.

Reservation protects later opportunities but can reduce early coverage, leave unused work with inactive/eliminated players, and block transient disclosure delivery. Capped later views can represent a different mix of cards/facts for a different visit cost; higher available counts do not certify more complete input. Current-turn/public-state caps, incomplete history, unknown legal domains and incomplete intrinsic semantics remain unchanged.

Actual capture-attempt/debit receipts include views that a parent can fail to publish; the joint reader audit covers observed records. They must not share a denominator. On these normally completed samples, experimental omission receipts reconcile with observed experimental reasons without unexplained residuals. Timeout source/collector and shutdown snapshots have different boundaries; late worker work is outside the sealed per-actor snapshot. Those cutoffs and uninstrumented decision totals remain explicitly limited.

## Gap review and next ownership

Current/prior-item review closes the startup revival defect in its own commit. Tool review also fixes a partly-available skipped-group validation hole, incomplete-matrix success reporting, early cutoff/participant binding and sparse-ledger handling. Strict source checks remain in force. The runner now honors --java-home for execution as well as compilation; the primary execution snapshot is retained, and its captured commands already used the requested executable. The final post-fix campaign uses the corrected runner.

COV-008B's controlled and actual heuristic-game experiment scope is complete. COV-008C is next: choose the supported production policy/defaults, then define and implement versioned admission/omission metadata, observation-only seed/counter ownership, eligibility/privacy, closure/overflow/concurrency and bypass behavior. The development monitor, reflection and GUI launcher are not a production API design. Preserve separate design/implementation commits and review gaps before proceeding.

OPT-005 retains calibrated CPU/allocation, exact transient heap and full collector/UTF-8/physical-writer attribution, plus Monte Carlo deadline-sensitive workloads. JVM process duration and file sizes here are descriptive; the prototype adds synchronization and aggregate bookkeeping. No optimization or default budget increase is selected. COV-004 retains semantics/domains/bindings/readiness. These experiments do not guarantee complete sustained inputs in unbounded games.

Evidence: [results/indexes](assessments/COV-008-native-admission/results.json), [full aggregate reports](assessments/COV-008-native-admission/reports.json.gz), [checks](assessments/COV-008-native-admission/checks.json), [primary commands/runtime hashes](assessments/COV-008-native-admission/primary-metadata.json), [post-fix commands/runtime hashes](assessments/COV-008-native-admission/postfix-metadata.json), [diagnostic commands](assessments/COV-008-native-admission/diagnostic-commands.json), [diagnostic execution sources](assessments/COV-008-native-admission/diagnostic-execution.json), [primary execution runner](assessments/COV-008-native-admission/runner-at-primary-execution.py) and [final source freeze](assessments/COV-008-native-admission/source-freeze.json).
