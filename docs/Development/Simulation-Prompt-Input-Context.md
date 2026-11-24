# COV-003: typed controller request constraints

The V3 increment introduced `inputContext.schemaVersion:3`, profile `PERMITTED_CHOICE_VIEW_V3`. New producers use [V4 current associations](Simulation-Context-Bindings.md), retaining these request semantics. Versions 1 and 2 retain their existing shapes and meanings. Version 3 adds nullable `policy.prompt` and `coverage.prompt`; its original diagnostics, record versions, event ordering and work budgets stay unchanged. This is a partial request view, not a complete legal action set or effect description.

## Audited boundaries and permission

| Family | Entry and permitted fields | Evidence and limits |
| --- | --- | --- |
| Numeric range | Both `PlayerControllerAi.chooseNumber(sa,title,min,max[,params])` overloads, before their callbacks: `{kind:NUMBER_RANGE, minimum, maximum}` | The human controller presents these exact bounds, or returns the minimum for a collapsed/reversed range; the base parameter-map overload forwards to that human path. Preserve the supplied integers without expansion, normalization or clamping. They are request bounds, not heuristic X limits or inferred legality. Additional map contents stay unrepresented. |
| Discrete number | `chooseNumber(sa,title,options,relatedPlayer)`, before its callback: `{kind:NUMBER_OPTIONS, values, relatedPlayer}` | The human controller presents the supplied numeric list. Preserve duplicates and order; their presence is authorized by the request API, unlike arbitrary entity pools. Capture the whole list only, at most 64 values. No prefix masquerades as a complete domain. |
| Single entity | `chooseSingleEntityForEffect`, after actual delayed disclosure and before its callback: `{kind:SINGLE_ENTITY, minimumPicks, maximumPicks, optional, relatedPlayer}` | Minimum is zero for an optional request and one otherwise; maximum is one. These describe this invocation only. Entity pool membership, count, ordering and identity remain diagnostic-only. The human path's temporary showing behavior does not prove equivalent AI delivery for arbitrary pools. |

`relatedPlayer` is null only when no player argument was supplied. Otherwise it must bind the exact represented player to a local `p` reference; an unresolved player makes the whole prompt unavailable. It does not default to source controller, activator or chooser. No IDs, rendered/localized titles, raw scripts, parameter maps or selected result enter the projection. No ability text is interpreted to infer an amount's unit or meaning.

The numeric-list caller inventory is `LifeSetEffect` (the named player whose public life total is being assigned), `RollDiceEffect` (the rolling chooser), `GameAction.runPreOpeningHandActions` (the public Emissary's Ploy owner), and `ChooseNumberEffect` (null). These roles and the supplied domain are distinct from a guessed hidden number or an AI-preferred option. Single-entity capture retains only the supplied optionality/player fields; it does not promote the entire request or its undisclosed entity pool to permitted features.

Parameter-map contents, announcement/X operations, modes, payment and heuristic target pools do not acquire typed input coverage here. The presence/content of an arbitrary map does not change the captured scalar fields. Repeated single picks do not acquire their outer operation's total limits or prior selections by inference. Hypothetical planning requests stay unavailable pending COV-005's boundary contract; neither their numeric domain nor its size enters policy.

## Wire and lifecycle contract

`coverage.prompt` is `CONTROLLER_REQUEST_V1` for a captured request, `UNSUPPORTED` when the boundary has no audited request projection, and `UNAVAILABLE` when an opted-in projection cannot be represented. `policy.prompt` is null for the latter two. A refusal at an opted-in boundary adds a typed `PROMPT` omission (`UNSUPPORTED`, `UNRESOLVED_BINDING` or `OUTSIDE_CAPTURED_PREFIX`). Whole-context cancellation, budget exhaustion or failure keeps policy null and all diagnostic bindings empty.

The existing `options:DIAGNOSTIC_ONLY` component still describes original diagnostic pools and unsupported model-facing option bindings. Separately audited numeric domain values are explicitly permitted by `policy.prompt`; that does not authorize copying arbitrary diagnostic option membership/order. Reports retain both declarations and keep their denominators separate.

Prompt work uses the existing per-game allowance, including each represented numeric value. Capture happens inside the existing optional-input failure isolation before callback invocation. The original callback runs once, with its original arguments, result and exception. Disabled/copy capture does not inspect or project requests. Returned payloads contain frozen JSON values, never a live list/player or a callback result.

## Required information and remaining ownership

| Family | Requirement for a future complete example | Owner of remaining work |
| --- | --- | --- |
| Numeric request | Exact request domain, relevant supplied player, typed effect meaning/units where relevant, correct rules/planning boundary and lifecycle | COV-003 defines this partial domain contract; COV-006 adds audited effect-specific inputs. X/payment calculations remain COV-007. |
| Single entity | Local pick constraints, all independently authorized option bindings, relevant player and effect-specific arguments | COV-004 proves safe bindings/completeness; COV-006 audits additional selector arguments. A null related-player argument is not missing data. |
| Repeated/multiple picks | Outer total limits, current remaining domain, prior selections with safe associations and independent capture location | COV-005 defines boundaries/location; COV-006 implements these families. Atomic pick constraints cannot certify the outer operation. |
| Response or resolution choice | The relevant pending/resolving stack inputs in addition to the family's request constraints | COV-002 supplies bounded root metadata; COV-004 checks each family's requirements, including unavailable stack bindings/subability inputs. |

All examples remain `trainingReady=false`; no readiness flag follows from context presence. Larger-profile CPU, retention and budget availability remain OPT-005 validation targets. Typed printed/granted ability semantics and subability roles are still absent; the request kind does not identify an effect or which ability on a card is being used.

## Validation and gap review

The focused Java run passed 65 tests. The full reactor passed 2,571 desktop tests (six skipped), 23 GUI tests and 23 game tests, with no failures. Python passed 141 tests. New regressions exercise actual controller entry points, extreme/reversed request bounds, preserved numeric order/duplicates and explicit player roles, frozen state after mutation/error, equal projections despite hidden entity pool differences, whole-domain refusal at the numeric cap, hypothetical inputs remaining uninspected, foreign-player identity rejection, disabled/copy behavior, capture-failure visibility and work accounting. Exporter regressions reject argument mismatches, malformed profiles/fields, planning claims and invented parent prompts. See [test counts and log hashes](assessments/COV-003/tests.json) and [implementation hashes](assessments/COV-003/implementation-sources.json).

All seven output files remain byte-identical to the previous exporter for twelve saved streams covering absent contexts, V1 and V2, full and filtered logs. See [compatibility checks and source hashes](assessments/COV-003/legacy-compatibility.json). New V3 reports explicitly distinguish permitted numeric request fields from diagnostic pools. Existing profiles are never upgraded by inference.

Four fresh games completed normally: simple constructed (seed 12031, Monte Carlo off), Doctor Who/Ur-Dragon Commander (31003, Monte Carlo on), modal/X constructed (12041, off), and the numeric-prompt test deck (12045, off). They contain 809 present contexts across observed owners, including ten captured prompt projections: one numeric range, two discrete numeric domains and seven single-entity requests. No prompt truncation or game-work exhaustion occurred in these samples. Planning/refusal paths and duplicate numeric domains are covered by regressions rather than claimed as observed here.

Full/filtered datasets, choices, examples and validation files match byte-for-byte; coverage JSON differs only in source-record counts. Each game retains two shuffles before `GAME_STARTED`. The simple game's normalized engine events exactly match the previous V1 run. Monte Carlo differs, so these samples do not establish deterministic replay or universal timeout neutrality. See [game checks and hashes](assessments/COV-003/validation.json), [exact commands](assessments/COV-003/commands.json) and [actual context excerpts for all three families](assessments/COV-003/actual-examples.json). Excerpts are not independently complete game streams.

The supported scalar request increment is complete. The family-requirement table above assigns the remaining bindings/readiness, planning, repeated-pick, effect-specific and payment/X work to COV-004/005/006/007; larger-workload validation remains OPT-005. No current scope defect was reproduced in these final checks. The execution order remains unchanged.

Pause for a gap review before COV-004.
