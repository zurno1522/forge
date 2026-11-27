# OPT-005: traced native heuristic and Monte Carlo resources

Eight V29 native games extend the [physical pipeline measurements](Simulation-V29-Pipeline-Cost-Assessment.md): two damage-deck seeds and two four-player Doctor Who/Ur-Dragon Commander seeds, each with heuristic and Monte Carlo pilots. Six completed normally; both Commander Monte Carlo games timed out. All four Monte Carlo runs recorded positive rollouts. This is a workload/resource checkpoint, not a tracing-overhead comparison or a learning-readiness claim.

## Results

MB and GB below use decimal units. Allocation is cumulative across Java threads, including exited threads; it is not retained memory. CPU is whole-process CPU and may exceed elapsed time.

| Deck / seed | Pilot | Finish | Process elapsed s | Process CPU s | Java allocated GB | Peak RSS MB | Rollouts |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| Damage / 12073 | Heuristic | Normal | 20.58 | 36.76 | 2.37 | 834.4 | 0 |
| Damage / 12073 | Monte Carlo | Normal | 30.36 | 57.16 | 5.46 | 858.1 | 93 |
| Damage / 12074 | Heuristic | Normal | 17.45 | 32.75 | 2.65 | 733.4 | 0 |
| Damage / 12074 | Monte Carlo | Normal | 19.44 | 36.64 | 3.58 | 780.7 | 65 |
| Commander / 31009 | Heuristic | Normal | 72.06 | 137.79 | 17.22 | 752.8 | 0 |
| Commander / 31009 | Monte Carlo | Timeout | 55.79 | 108.27 | 16.84 | 812.8 | 131 |
| Commander / 31010 | Heuristic | Normal | 43.83 | 84.30 | 10.74 | 889.5 | 0 |
| Commander / 31010 | Monte Carlo | Timeout | 54.76 | 107.37 | 16.75 | 787.0 | 112 |

Heuristic games have a 90-second game limit and Monte Carlo games 45 seconds. Process elapsed includes startup and shutdown; game limits do not bound that entire interval. Opposite pilot order by seed reduces one ordering bias but does not provide enough independent runs for a population distribution or confidence interval. The two timeout runs are censored observations, not faster completed Commander games. Native Monte Carlo deadlines can change actions and outcomes even at the same seed; these pairs are not deterministic equivalence controls.

All sixteen full/filtered streams passed the ordinary reader and produced matching coverage reports apart from the established source-record count. Source accounting was complete and unsaturated with zero observation failures. Each initial participant shuffled before GAME_STARTED. Both timeouts have zero outcome-eligible decisions. A positive Monte Carlo header alone was insufficient: validation also required actual search rollouts.

Commander recorded 1,992 / 523 / 266 / 634 BUDGET_EXHAUSTED context references in the table's order. These counts include repeated views and are not unique capture attempts. Hidden and absent views remain separate, as do partial captures and unsupported history/disclosure components. Damage games had no budget-exhausted views. Finite player shares still limit sustained availability; no allowance was raised or replenished.

## Measurement boundary and reproduction

`NativeGameCostLauncher` only wraps the ordinary desktop entry point with a process-resource sidecar. No phase agent, sampling overlay, funding monitor or callback replacement was installed. The eight JVMs ran sequentially with the existing 256 MB initial / 1 GB maximum heap settings. Actual command lines, production class hashes and all file hashes are retained in the evidence.

Process CPU and all-thread Java allocation cover main-counter setup through the start of the shutdown hook's sampling. Linux RSS and individual heap-pool peaks cover JVM lifetime; GC counts/time also include JVM startup. Heap used at shutdown is a point sample. Pool peaks need not occur together and must not be summed into a live-memory claim. Counters include startup/JIT, gameplay, search, tracing and host effects. In a timeout, worker activity after the trace cutoff may still contribute to process counters. Unsupported metrics remain null. No exclusive capture peak, unbounded-game memory guarantee or CPU overhead percentage is established.

With desktop runtime/test classes built, run from the repository root:

```sh
python3 tools/decision-records/input-context-benchmark/run-native-costs.py \
  --classpath-file /path/to/classpath.txt --java-home /path/to/jdk-21 \
  --user-home /path/to/writable-profile --output-dir /path/to/new-native-results
```

The development launcher requires JDK 21's total-thread allocation API; production still targets Java 17. The frozen execution runner predates the strict resource-helper integration and final runner source/version receipts. Every actual sidecar was subsequently validated with the final helper, bound to its exact native arguments; launcher and production classes remained unchanged. The validation receipt distinguishes these source versions. The final Python suite passed **382 tests**, including actual-resource acceptance, unsupported counters, command mismatches, invalid numeric types and invalid GC/pool scopes.

## Gap review and next owner

This closes the native-resource increment of the [V29 checkpoint](Simulation-V29-Cost-Checkpoint-Contract.md). No new production defect was reproduced. OPT-005 retains exact transient capture attribution, copied-controller construction costs, broader host calibration and larger workload distributions; process RSS does not close those limits. COV-008C retains the production-shaped hash-four cost/availability gate and separate optional enablement commit. OPT-004 remains the strongest CPU optimization candidate on the pipeline evidence; OPT-002 and OPT-003 retain their separate evidence gates. All reports remain trainingReady=false.

Evidence: [per-game resources and availability](assessments/OPT-005-native-resources/comparison.json), [complete coverage/resources](assessments/OPT-005-native-resources/results.json.gz), [commands and production hashes](assessments/OPT-005-native-resources/metadata.json), [raw-file receipts](assessments/OPT-005-native-resources/files.json), [post-execution validation](assessments/OPT-005-native-resources/validation.json), and [runner at execution](assessments/OPT-005-native-resources/runner-at-execution.py). Raw files remain in the execution workspace. Earlier excluded prefix-prototype JSONL files were losslessly gzip-compressed to free space; their original byte hashes are recoverable by decompression and they are not part of this cohort.
