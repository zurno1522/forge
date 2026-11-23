# Observation coverage design priorities

Status: proposed follow-up to the implemented [input-context profile](Simulation-Input-Context.md), baseline `f115c6f2843`. This plan changes no runtime behavior or coverage claims.

Coverage work determines which decisions and permitted inputs the dataset represents, when they are captured, and how a selected option can be interpreted. CPU, allocation, retention and storage optimization have a [separate design track](Simulation-Observation-Optimization-Plan.md). Coverage reporting belongs here; performance profiling belongs there.

## Ordered backlog

| Priority | Item | Design and completion criteria |
| --- | --- | --- |
| COV-001 | Coverage definitions and reporting | Distinguish observed choice events, audited input boundaries, captured profile components and usable option bindings. Report absent, unsupported, truncated and budget-exhausted inputs by boundary and game turn. Define each denominator; uninstrumented decisions have unknown counts unless independently observed. A captured partial profile must never be reported as a complete learning example. Validate reports with fixtures containing known omissions. |
| COV-002 | Stack and resolving inputs | Define bounded, permission-filtered stack order, source/controller, targets and relevant parameters, including the currently resolving object. Preserve face-down privacy, local references and explicit unsupported fields. Tests must distinguish pending responses and resolving abilities, freeze earlier state, and prove equal policy projections for equal permitted information. |
| COV-003 | Typed prompt constraints | Define authorized amounts, minimum/maximum picks, optionality, relevant players and prior selections for a small set of audited selectors. Separate prompt constraints from heuristic filtering. Permission must cover option membership, count and ordering as well as identity. Validate actual selector arguments and timing; do not copy rendered prompts or raw ability scripts into features. |
| COV-004 | Safe option and disclosure bindings | Define context-local links from supported selectable options to permitted entities and delivered facts. Start with current, directly established associations. Test disclosure supersets, duplicate/ambiguous choices, stale incarnations, hidden shuffles and movement. Unproved historical associations remain unresolved; raw ID equality is insufficient. |
| COV-005 | Planning and boundary audit | Audit temporary faces, resources, target plans and internal narrowing before adding more contexts. Record whether each seam is a selector, outer operation, application or result. Retain truthful classifications for cached modes, precomputed X and late target additions. Add regressions at actual hypothetical-work paths. |
| COV-006 | Targets and modes | Extend audited selector families using COV-002 through COV-005. Keep parent, nested target and search-operation ownership explicit. Implement one family per commit, then review bypasses, delegated actors, cached branches and missing arguments. A context on an existing record does not cover paths that emit no record. |
| COV-007 | Payment, mana and X | Capture actual inner source choices and successive resource states, not just outer payment entry. Distinguish proposals, attempts, payment and precomputed assignments. Move X input capture to earlier calculations where necessary. Split payment/source selection and X work into individual commits and reviews. |

Each item begins with a field/boundary design and ends with meaningful unit or integration regressions, exporter compatibility checks, representative output and a gap review. New profile/schema semantics must be explicit; existing meanings remain unchanged. All datasets remain `trainingReady=false`.

## Separate later designs

Controlled-player/search-control/team permissions require their own supported-perspective design and tests. Direct/nonstack resolution scopes need audited entry/exit lifetimes before borrowing prior disclosures. Durable historical knowledge and hidden-object continuity are a further design: this backlog does not introduce perfect recall or stable cross-context model identifiers. Full legal sets, rewards and learning policies remain outside it.

## Commit and acceptance boundaries

Each coverage item or independently reviewable selector family gets its own implementation commit. Its gap review states what is newly represented, what remains unavailable and what evidence supports the claim. Performance measurements may accompany a coverage change as a guardrail, but unrelated allocation, serializer or compression changes belong in separate optimization commits.

Budget-exhaustion reporting is coverage work. Changing capture limits, sampling or budget distribution changes which inputs survive and requires a dedicated coverage review and commit; it must not be hidden inside a performance improvement. Existing privacy, cancellation, callback-once, failure-visibility and engine-ordering requirements remain acceptance gates throughout.
