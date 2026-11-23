# Observation work: execution order after COV-001 review

COV-001 is complete. Its [assigned gaps](Simulation-Observation-Coverage-Plan.md#assigned-cov-001-gaps-and-scheduling) do not block measurement of known captured operations. OPT-001 has [phase results and a gap review](Simulation-Observation-Cost-Assessment.md). COV-002 has [bounded stack inputs and assigned remaining gaps](Simulation-Stack-Input-Context.md). COV-003 adds [typed controller request constraints and family requirements](Simulation-Prompt-Input-Context.md). Coverage and optimization continue to use separate implementation commits and a gap review before proceeding to the subsequent item.

The order remains appropriate. COV-003's supported scalar constraints now have explicit permission, timing and bounds contracts. Option bindings and example completeness remain COV-004; planning boundaries remain COV-005; repeated picks and effect-specific arguments remain COV-006, with X/payment inputs in COV-007. OPT-003/004/005 retain their assigned performance work. Pause for COV-003 review before starting COV-004.

| Order | Item | Scheduling reason |
| --- | --- | --- |
| 1 | OPT-001 | Representative phase attribution delivered; exact transient retention and broader validation remain assigned to OPT-005. Review these limits before proceeding. Invocation-loss rates are outside this measurement. |
| 2 | COV-002 | Bounded active-stack/resolving inputs implemented in profile V2; remaining limits assigned. |
| 3 | COV-003 | Profile V3 adds bounded numeric domains and local single-entity pick constraints. Pause for its gap review. |
| 4 | COV-004 | Safe option/disclosure bindings and explicit completeness criteria per supported choice family. Request constraints and root stack metadata do not certify a complete example. |
| Conditional | OPT-004 / OPT-002 / OPT-003 | Production streaming serialization is the strongest CPU investigation candidate; redundant freezing is the strongest allocation candidate; canonical ordering is lower priority in these fixtures. Review evidence before selecting an implementation. No optimization is an automatic prerequisite for coverage work. |
| 5 | COV-005 | Audit additional planning/selector boundaries and define independent nested capture-location semantics. |
| 6 | COV-001A | Define invocation/loss accounting before the broad selector rollout. This moves the assigned follow-up ahead of COV-006; it remains independent of OPT-001 and cannot count uninstrumented choices. |
| 7–8 | COV-006, COV-007 | Extend targets/modes, then inner payment/mana/X choices using the audited contracts. Split selector families into individual commits. |
| Checkpoints | OPT-005 | Establish broader baselines and repeat validation after material coverage or optimization changes; do not wait for all implementations to finish. |

This order schedules the newly assigned accounting work before broad capture expansion. It preserves the immediate next item and input/binding priorities. Control/team permissions, direct/nonstack lifetimes and durable historical knowledge remain separate later designs. Capture limits, sampling and player-budget allocation require their own coverage-policy decisions.

See the separate [coverage backlog](Simulation-Observation-Coverage-Plan.md) and [optimization backlog](Simulation-Observation-Optimization-Plan.md) for acceptance criteria. A completed measurement does not itself authorize the next runtime optimization; review its evidence and gaps first.
