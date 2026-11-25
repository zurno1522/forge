# COV-006: received mode bounds in profile V5

The mode-entry increment deliberately retains V4's unsupported typed mode inputs. The next increment adds only received scalar mode bounds. It does not authorize mode identities, descriptions, offered membership, count, ordering, definition indices, prior cache joins or a legal mode domain as model features.

## Boundary and permission

At the existing `CHARM_MODE_HEURISTIC` selector and `CONTROLLER_MODES` operation, the wrapper already receives `min`, `max` and `allowRepeat`. Project exactly those values into `policy.prompt` as `{kind:MODE_BOUNDS,minimumPicks,maximumPicks,allowRepeat}` with prompt coverage `RECEIVED_MODE_BOUNDS_V1`. Do not normalize reversed or negative bounds, infer them from list length or recalculate script expressions. These are the received constraints for this invocation, not a promise that the rules engine accepts them or that the returned plan satisfies them.

Both audited boundaries retain `PLANNING_WORKING_STATE`. The new permission is explicitly for received mode bounds even during hypothetical policy work. It does not make those values committed rules state. Existing numeric/entity planning prompts remain refused without inspecting their suppliers. A final immutable mode-bounds carrier containing only the three primitive arguments distinguishes this permission before any supplier is invoked; no generic planning opt-in or raw script projection is added.

All producer contexts use schema/profile V5, including unavailable and hidden contexts, so the profile itself does not identify a hidden mode family. Existing V1–V4 decoding and semantics remain unchanged. V5 inherits V4's other fields, permissions, diagnostic bindings and fixed budgets. Each prompt consumes the existing bounded traversal work. Hidden, closed, disabled and simulation-copy capture must not inspect mode input or change the original callback. Budget exhaustion can leave the view unavailable while retaining the operation and result.

## Validation and compatibility

The decoder checks the profile/version pair, exact prompt shape and scalar types, prompt coverage, actual diagnostic callback arguments, audited family boundary, own entry component, timing and planning basis. A mode prompt at another boundary, a mode prompt in V4, numeric/entity prompts claiming planning permission, option-domain features, raw IDs or script fields are invalid. Legacy input is never upgraded by inference. All examples remain `trainingReady=false`.

Retain V1/V2 boundary inventories and the conditional mode inventory extension from the entry increment. Reports should explain V5's limited permission. Benchmark tooling must carry the actual producer profile rather than label a new measurement V4; historical V4 evidence remains unchanged. That bookkeeping does not constitute a new performance assessment: expanded family cost and peak memory still belong to OPT-005.

Regression tests must exercise both audited boundaries, extreme/reversed bounds, exact received arguments, repeated plans, frozen output, identity-domain exclusion, hidden/off/copy paths, budget exhaustion and unchanged refusal of other hypothetical prompt kinds. Real game output must validate in full and filtered streams; mode bounds remain partial inputs, without effect semantics or cached-plan causality.
