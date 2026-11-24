# COV-005: planning boundaries and independent entry location

This item completes the boundary audit and defines the location contract consumed by COV-006/007. It changes no producer, event schema or visibility permission. Current V4 output does **not** yet contain `entryLocation`. Missing legacy location remains unknown/container-based; no report may infer a child entry from its parent baseline.

The audited source baseline is `6ed88c170b7`. The [machine-readable inventory](assessments/COV-005/boundary-inventory.json) records 63 qualified production adapter calls: 32 target, 15 numeric, six payment, six controller operations, two mode and two effect selectors. There are 25 selector, nine operation, 28 application and one result sites. Counts describe instrumented call sites, not runtime invocation coverage or all Forge choices. Adapter-internal forwarding, utilities, declarations and commented lines are excluded. Source hashes and line locations are retained; nearby string literals locate calls but do not establish their semantics.

## Audit findings and integration requirements

| Boundary | Established meaning | Requirement and owner |
| --- | --- | --- |
| `AiTargetChoiceRecorder.choose/select` | Selector entry before the original callback; original pools may already be heuristic-filtered. Nested records currently have parent-based reporting, with no fresh context. | COV-006 captures independent entry and fresh planning input before the callback. Preserve each family’s actual pool semantics and receiving chooser. |
| `selectedFrom/addSelected` | A selected value exists before the wrapper. `atTargetAddition()` currently changes legacy timing after capture. | COV-006 passes application timing into admission at the start. Keep legacy `AT_TARGET_ADDITION`; new input timing is `AFTER_SELECTION_BEFORE_APPLICATION`, never `BEFORE_SELECTOR`. |
| Final search target operations | `HEURISTIC_REVALIDATION` is a composite operation; restore/override/discovery/guard variants apply already generated choices. Search can have disturbed the live plan already. | COV-006 captures the operation’s own entry and planning view. Do not reuse the main-phase baseline or claim that a target application is a new selector. |
| `CharmAi` mode evaluation | An actual mode heuristic runs inside its wrapper; prepared candidate faces, resources and targets can be hypothetical. | COV-006 captures fresh selector state, with planning inherited from evaluation/temporary scopes. Mode membership/IDs remain diagnostic unless separately authorized. |
| Controller mode callback | `SpellAbilityPicker` can return intercepted/planned combinations and apply planned targets; the controller can return `getChosenList()` or call trigger setup only when absent. | Treat the outer callback as an operation. COV-006 enters a planning scope before handling cached/intercepted plans unless a separately audited branch proves committed-state input. Do not rerun Charm logic or invent a fresh selector for a cached return. Actual inner selection remains separately owned. |
| `PlayAi.chooseSingleCard` | An existing planning scope encloses transformed-face and cast/cost evaluation. Descendants inherit it; the recorder does not restore engine state. | Preserve the scope and exception cleanup. Other unproved temporary evaluation paths remain unsupported or explicitly planning; a fresh read is not a committed-state proof. |
| Numeric controller selectors | Actual range/discrete requests precede their callbacks. Announcement is an operation. | Preserve V3/V4 request semantics. A resolving stack does not override an enclosing planning scope. Parameter maps and effect semantics remain outside the current typed request projection. |
| `MAX_X_HEURISTIC` setter | The bound/value are computed first; `AIXMax` may already have assigned provisional X. Cached Charm X and non-free-choice paths return earlier. | COV-007 captures calculation entry after those guards and before resource/provisional mutations. Keep setter/application facts distinct; cached X is not a new calculation. |
| X refinements | Ten Draw/Damage sites assign an already calculated refinement, sometimes after targets exist. | COV-007 retains truthful application records and instruments earlier calculations only at audited seams. Do not label a setter’s current state as input to the earlier calculation. |
| Cost visitor/discard/sacrifice | Visitor is an outer operation. Discard can replace/narrow its supplied list; sacrifice performs successive picks/removals. | COV-007 captures actual payer and inner selection states, including remaining candidates/prior picks where supported. One outer request is not every inner choice. |
| Exile payment ranking | Ranking/filtering already happened before the existing wrapper returns a sublist. | Preserve the application seam. COV-007 adds an earlier selector boundary rather than relabeling old post-ranking data. |
| Automatic mana payment | The outer real-payment operation changes reservations/conversions, chooses/activates sources and spends/refunds mana. `chooseManaAbility` also serves test/probe paths. | COV-007 instruments actual non-test source selection at the call site with its current shard/resources. Test payment on the original game must not become a gameplay payment record. A returned ability is not proof that activation succeeded. |
| Mana pool tie-break | The controller chooses the first of already weighted/deduplicated best ties, with an active same-chooser ability context. | Keep that limited claim. Singleton, empty and unowned paths do not establish full mana-source selection. |
| Effect/zone-change controller callbacks | Delayed disclosure is delivered before selector capture. Supersets and selectable subsets differ. Repeated entity picks remove prior selections between callbacks. | COV-006 retains actual delivery and captures each invocation independently. Local single-pick limits do not establish outer total constraints or prior selections. |
| Direct disclosure association | V4 uses an immediate public proof and retires it after any engine `GameEvent`; diagnostic AI events do not retire it. View-only/private/historical evidence remains unresolved. | This conservative contract is safe and accepted. Relaxing retirement for particular informational events or adding historical continuity requires a separate permission/lifetime design; it is not an optimization or a missing current association repair. |

The audited adapter inventory still omits many Forge APIs and bypasses. COV-001A owns invocation accounting for named instrumented boundaries; it cannot count uninstrumented choices. COV-006/007 must audit bypasses inside each family they expand and keep remaining scope explicit.

## Entry-location contract for COV-006/007

Add an optional, independently versioned `entryLocation` to the owning child/operation record. Reuse existing choice/application IDs, chooser ownership, run and game. No new location ID, database, registry or engine revision/cache protocol is needed. The metadata is diagnostic; raw player IDs and parent joins do not become numeric features.

```json
{
  "schemaVersion": 1,
  "status": "CAPTURED",
  "kind": "SELECTOR",
  "timing": "BEFORE_SELECTOR",
  "stateBasis": "PLANNING_WORKING_STATE",
  "turn": 5,
  "phase": "MAIN1",
  "turnPlayerId": 1,
  "reason": null
}
```

`kind` is `SELECTOR`, `OPERATION`, `APPLICATION` or `RESULT`. Capture it from the audited seam, not from a callback’s later success. Input timing is respectively `BEFORE_SELECTOR`, `BEFORE_OPERATION`, `AFTER_SELECTION_BEFORE_APPLICATION`, or `UNESTABLISHED` for a result with no new input boundary. A composite callback can contain a nested selector; each has its own kind and owner.

The state basis is `RULES_STATE`, `PLANNING_WORKING_STATE` or `UNESTABLISHED`. Explicit temporary-planning scope and candidate evaluation take precedence over stack resolving/phase labels. Search target applications use planning state because their live plan may already be hypothetical. Cached/result metadata never reconstructs an earlier state. Typed working prompt parameters remain unavailable unless a later family contract independently proves them permitted.

Captured selector, operation and application descriptors require their corresponding timing and an established rules/planning basis. A captured result descriptor has `UNESTABLISHED` timing **and state basis**: its clock is the current return invocation, not the old decision’s input time/state. An outer operation around a cached return can still capture its own current operation inputs; that does not create an earlier selector snapshot.

Capture the clock at actual entry, before expensive pool/view traversal and before the callback or assignment. Read no engine getters while holding a parent publication monitor. Turn is a nonnegative engine turn; phase includes `UNESTABLISHED`, and a null turn player is valid before turn establishment. Capture on the engine’s existing synchronous callback path; do not create a global game lock or pretend to provide an atomic full-engine snapshot.

For `status:UNAVAILABLE`, `kind`, `timing` and `stateBasis` are `UNESTABLISHED`, clock fields are null, and `reason` is `HIDDEN`, `INTERRUPTED`, `UNSUPPORTED` or `CAPTURE_FAILED`. Capture failure must preserve the base record where publishable and remain visible through observation health. Do not include private exception messages. Hidden-source metadata does not declassify a masked choice. Disabled/copy paths inspect no new entry state. A late capture cannot attach to another parent, assessment or game.

Missing fields on legacy rows mean absent capability. Completion must not overwrite entry values. Existing top-level event time, parent baseline time, target provenance and `poolSnapshotTiming` retain their meanings. A new entry descriptor does not repair an old record’s location or prove the view is complete.

## Reporter and validator obligations

Validate the location’s own version, exact fields, kind/timing/state combinations, public clock identities and unavailable reasons. Reject unknown versions and contradictory claims. Validate against the same owning context when present; never borrow another record’s clock or perspective to fill gaps. Admission/publication and capture timing must be established by producer tests, not inferred by the decoder.

For records with a captured entry, report child turn/player from that entry with an explicit `ENTRY_LOCATION` basis. Keep legacy nested/search groups `PARENT_CONTAINER`; unavailable entry clocks stay unknown with their own basis. Preserve old report values when the new component is absent. If the boundary inventory grows, introduce an explicitly named new inventory rather than retrospectively relabeling the V1 inventory.

Track observed entry-location statuses and kinds separately from view component coverage. A surviving descriptor cannot reconstruct the time/boundary of an omitted capture. COV-001A must supply independent invocation/loss accounting before any capture-loss rate is claimed. No new location metadata makes a training example ready.

## Required integration regressions

| Case | Required observation |
| --- | --- |
| Parent then child after life/phase/resource changes | The child records its own entry; earlier frozen values do not change. Parent time is not substituted. |
| Precomputed target, cached mode or X assignment | Application/operation/result classification remains truthful; no extra heuristic call or fake preselection input. |
| Temporary faces/costs and an exception | Descendants are planning; scope markers retire after the exception without the recorder restoring/replaying policy. |
| Nested target and final search operation | Each captures before its actual work; independently frozen entry/context survive later target and phase mutation. |
| Delayed reveal and delegated recipient | Entry follows delivery and uses the receiving chooser; a disclosed superset does not become the selectable domain. |
| Original-game payment test and simulation copy | No new gameplay selection capture or entry getter work. Actual payment captures current resources at each supported inner seam. |
| Blocked capture, timeout, close and late completion | Publication does not wait on a getter; the late result cannot attach to a new decision/game. Callback result/exception and engine ordering are preserved. |
| Legacy/new/full/filtered exports | Legacy absence stays unknown; new metadata round-trips and full/filtered results agree. Shuffles still precede `GAME_STARTED`. |
| Malformed versions, clocks and kind/timing combinations | Structural rejection, without inventing legal alternatives or repairing fields from a parent. |

## Review and handoff

COV-005 is a design/source-audit item; no new runtime test result, fresh game, location field or permission expansion is claimed here. Inventory paths, source hashes, call counts and document links are checked. The reviewed findings belong to COV-006 (target/mode/location integration) and COV-007 (payment/X seams), with invocation/loss reconciliation in COV-001A. No current or earlier-item functional defect was identified that requires a runtime change in this audit.

Continue with COV-001A, then the OPT-005 larger-profile checkpoint before broad COV-006/007 expansion. Those implementations retain their own commits and gap reviews.
