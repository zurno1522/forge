# Observation work: execution order after COV-001 review

COV-001 is complete. Its [assigned gaps](Simulation-Observation-Coverage-Plan.md#assigned-cov-001-gaps-and-scheduling) do not block measurement of known captured operations. OPT-001 has [phase results and a gap review](Simulation-Observation-Cost-Assessment.md). COV-002 now has [bounded stack inputs and assigned remaining gaps](Simulation-Stack-Input-Context.md). Coverage and optimization continue to use separate implementation commits and a gap review before proceeding to the subsequent item.

The OPT-001 assignment review is complete: implementation checks have no outstanding defect; the open measurement targets stay assigned to OPT-003/004/005. COV-002's remaining typed semantics, bindings, planning boundaries and larger-profile costs are assigned to COV-003/004/005/006 and OPT-005. The order remains appropriate: pause for COV-002 review, then proceed with COV-003's typed prompt constraints.

| Order | Item | Scheduling reason |
| --- | --- | --- |
| 1 | OPT-001 | Representative phase attribution delivered; exact transient retention and broader validation remain assigned to OPT-005. Review these limits before proceeding. Invocation-loss rates are outside this measurement. |
| 2 | COV-002 | Bounded active-stack/resolving inputs implemented in profile V2; pause for review of its assigned limits. |
| 3–4 | COV-003, COV-004 | Typed prompts, then safe bindings and explicit completeness criteria per supported choice family. Root-only stack metadata does not replace either requirement. |
| Conditional | OPT-004 / OPT-002 / OPT-003 | Production streaming serialization is the strongest CPU investigation candidate; redundant freezing is the strongest allocation candidate; canonical ordering is lower priority in these fixtures. Review evidence before selecting an implementation. No optimization is an automatic prerequisite for coverage work. |
| 5 | COV-005 | Audit additional planning/selector boundaries and define independent nested capture-location semantics. |
| 6 | COV-001A | Define invocation/loss accounting before the broad selector rollout. This moves the assigned follow-up ahead of COV-006; it remains independent of OPT-001 and cannot count uninstrumented choices. |
| 7–8 | COV-006, COV-007 | Extend targets/modes, then inner payment/mana/X choices using the audited contracts. Split selector families into individual commits. |
| Checkpoints | OPT-005 | Establish broader baselines and repeat validation after material coverage or optimization changes; do not wait for all implementations to finish. |

This order schedules the newly assigned accounting work before broad capture expansion. It preserves the immediate next item and input/binding priorities. Control/team permissions, direct/nonstack lifetimes and durable historical knowledge remain separate later designs. Capture limits, sampling and player-budget allocation require their own coverage-policy decisions.

See the separate [coverage backlog](Simulation-Observation-Coverage-Plan.md) and [optimization backlog](Simulation-Observation-Optimization-Plan.md) for acceptance criteria. A completed measurement does not itself authorize the next runtime optimization; review its evidence and gaps first.
