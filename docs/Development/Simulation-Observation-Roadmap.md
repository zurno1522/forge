# Observation work: execution order after COV-001 review

COV-001 is complete. Its [assigned gaps](Simulation-Observation-Coverage-Plan.md#assigned-cov-001-gaps-and-scheduling) do not block measurement of known captured operations. OPT-001 remains next. Coverage and optimization continue to use separate implementation commits and a gap review before proceeding to the subsequent item.

| Order | Item | Scheduling reason |
| --- | --- | --- |
| 1 | OPT-001 | Attribute cost on the existing profile before adding information or selecting an optimization. Invocation-loss rates are outside this measurement. |
| 2–4 | COV-002, COV-003, COV-004 | Add stack/resolution inputs, typed prompts and safe bindings, with explicit completeness criteria per supported choice family. |
| Conditional | OPT-002 / OPT-003 | Choose copying or construction/ordering work from OPT-001 evidence. Neither is an automatic prerequisite for coverage work; a demonstrated cost concern can bring the relevant optimization forward. |
| 5 | COV-005 | Audit additional planning/selector boundaries and define independent nested capture-location semantics. |
| 6 | COV-001A | Define invocation/loss accounting before the broad selector rollout. This moves the assigned follow-up ahead of COV-006; it remains independent of OPT-001 and cannot count uninstrumented choices. |
| 7–8 | COV-006, COV-007 | Extend targets/modes, then inner payment/mana/X choices using the audited contracts. Split selector families into individual commits. |
| Conditional | OPT-004 | Prioritize storage/serialization when evidence establishes a practical cost or size concern. |
| Checkpoints | OPT-005 | Establish broader baselines and repeat validation after material coverage or optimization changes; do not wait for all implementations to finish. |

This order schedules the newly assigned accounting work before broad capture expansion. It preserves the immediate next item and input/binding priorities. Control/team permissions, direct/nonstack lifetimes and durable historical knowledge remain separate later designs. Capture limits, sampling and player-budget allocation require their own coverage-policy decisions.

See the separate [coverage backlog](Simulation-Observation-Coverage-Plan.md) and [optimization backlog](Simulation-Observation-Optimization-Plan.md) for acceptance criteria. A completed measurement does not itself authorize the next runtime optimization; review its evidence and gaps first.
