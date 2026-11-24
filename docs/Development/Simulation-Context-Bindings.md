# COV-004: current option and disclosure associations

Profile `PERMITTED_CHOICE_VIEW_V4`, `inputContext.schemaVersion:4`, adds bounded diagnostic option associations and directly proved public disclosure associations. Profiles V1–V3 retain their existing shapes and meanings. Record/event versions, IDs, engine ordering, original options, capture limits and `trainingReady=false` remain unchanged.

## Associations and permission

At the existing effect-entity and zone-change controller boundaries, the original diagnostic enumeration also retains temporary exact object references. It enumerates once, with the existing 256-entry cap and interruption behavior. The context builder can join a currently represented public Card by object identity and captured incarnation, or a represented Player by exact object identity. Private-zone and stale/LKI objects stay unresolved, even if their names or IDs match known entities. Duplicate entries may link the same entity; they do not establish a unique selected option index.

`diagnostics.optionBindings` contains `{coverage,items,truncated}`. Coverage is `DIAGNOSTIC_PUBLIC_CURRENT_V1`, `UNSUPPORTED` for boundaries without this capability, or `UNAVAILABLE`. Items are `{optionIndex,entityRef}` with a local card/player reference or null. They must agree with the original diagnostic prefix. Unavailable contexts have no bindings. These links stay outside `policy`: proving an object's current association does not authorize pool membership, count or ordering as model features. Entity pools can include heuristic/private filtering. This increment grants no complete selectable entity set.

Links add bounded lookups over the already captured diagnostic prefix, rather than a second enumeration or privileged whole-game lookup. The existing diagnostic prefix bounds their cost independently of the player-view traversal allowance; this increment changes no work-unit charging or player budgets. It introduces no observation registry or persistent identity map. Temporary exact objects are discarded with the callback's existing capture scope; published payloads contain frozen JSON only.

Direct Card deliveries may also establish a disclosure fact's `currentObjectAssociation` as a local `c` reference. Proof requires a same-game public-zone, face-up, non-LKI object at delivery; its exact represented current incarnation, presented state and projected face/zone must still agree at capture. Identical permitted presentations remain ambiguous; raw object identity cannot break a hidden-order canonicalization tie. Truncated/incomplete entity presentations cannot certify uniqueness. Any intervening engine `GameEvent`, control/lifetime change or authoritative closure invalidates the proof. Diagnostic AI messages do not invalidate it. Reading object/view getters happens outside the disclosure publication lock, with an epoch check before and after the reads. A blocked getter cannot delay authoritative closure or publish a late association.

View-only deliveries, private-zone deliveries, hidden shuffles/movement, ambiguous historical continuity and face-down deliveries remain `UNRESOLVED`. A matching name, raw ID, timestamp or later revelation cannot repair a missing proof. A disclosed superset is retained independently of the selectable subset. The proof is deliberately narrower than ordinary human memory; no durable knowledge protocol is introduced. Planning-state disclosure associations remain unresolved.

## Per-family completeness contract

No example is promoted to ready by this increment. A future readiness declaration requires its own explicit versioned contract and validation; the following are requirements, not inferred guarantees:

| Family | Required evidence before certification | Remaining owner |
| --- | --- | --- |
| Numeric range | Actual request bounds and relevant roles, effect meaning/units where required, supported capture timing/state, uncensored terminal result and a value-level outcome contract. A callback's out-of-domain result is retained but cannot become a valid training label by clamping. | COV-006 for effect-specific inputs; COV-007 for X/payment calculations. |
| Discrete numeric request | Complete authorized numeric list, preserved duplicates/order, supplied role, supported entry state and valid terminal association. Duplicate ordinal labels need an explicit ambiguity/value-level policy; no unique index is invented. | COV-006/007 as applicable. |
| Single entity | Actual local pick constraints and relevant roles, independently authorized pool membership/count/order, safe current/disclosure bindings, effect-specific parameters and an unambiguous terminal selection. Diagnostic links alone satisfy only association evidence. | COV-006 implements audited families after COV-005 boundaries. |
| Response/resolution | The family's inputs plus its relevant pending/resolving stack object, targets and parameters. Root-only stack coverage cannot satisfy an unrecorded subability requirement. Legal execution does not prove a successful effect. | COV-006's family integration; existing execution/resolution links supply lifecycle facts only. |
| Repeated picks/payment | Outer total constraints, actual remaining authorized domain, prior selections/resources and independent selector-entry state. Outer operations and cached assignments are not new atomic selections. | COV-005 defines boundaries/location; COV-006/007 implement them. |

All families must respect perspective, disclosure lifetime, cancellation, late publication, source failure, censoring and outcome ownership. Present context, callback success, a legal engine action or a diagnostic join alone cannot establish a complete learning example. Uninstrumented invocation counts remain unknown; COV-001A owns accounting.

## Gap ownership

Current direct/public associations and the completeness contract belong here. Unproved private/historical continuity remains explicitly unsupported under its separate later knowledge design, rather than inferred from IDs. Pool permission and effect-specific family inputs belong to COV-006; planning/lifetime and independent entry-location contracts to COV-005; X/payment to COV-007; invocation reconciliation to COV-001A. OPT-005 owns larger-profile cost/retention and long-game validation. Retained delivery proof references are bounded at 32 and retired with the existing disclosure interval; they are never a model identity service.

## Validation and review

The final focused Java run passed 66 tests. The full reactor passed 2,578 desktop tests (six skipped), 23 GUI tests and 23 game tests with no failures; Python passed 146 tests. Regressions cover one-pass option enumeration, duplicate/ambiguous options, private/LKI/retired ordinary objects, delivered supersets, view-only evidence, hidden shuffle/movement, identical public-face worlds, proof retirement, immutable earlier snapshots and a blocked proof getter during authoritative close. Export validation rejects malformed versions, identity/prefix mismatches, private or ambiguous associations and incomplete claims. See [test counts/log hashes](assessments/COV-004/tests.json) and [implementation hashes](assessments/COV-004/implementation-sources.json).

The review corrected three current-item gaps before completion: getter work under the disclosure lock, invalidation by diagnostic messages, and arbitrary association of indistinguishable public presentations. Direct source/presentation checks and view-only rejection were also tightened. Earlier broader runs were superseded after review; the final full run validates the reviewed code.

All seven export files remain byte-identical to the previous exporter for twenty saved streams spanning absent contexts and V1–V3, full and filtered logs. See [compatibility results](assessments/COV-004/legacy-compatibility.json). New V4 reports separate context, diagnostic-option-entry and disclosure-fact denominators; these are not unique gameplay decisions or capture-loss rates.

Three fresh simple, Doctor Who/Ur-Dragon and numeric-choice games completed normally. They contain 723 contexts and ten represented diagnostic option entries, all linked to current public entities. Full/filtered datasets, choices, examples and validation files match byte-for-byte; coverage JSON differs only in source-record counts. Each game retains two shuffles before `GAME_STARTED`; the simple game's normalized engine events match the previous run. Monte Carlo differs and is not a deterministic replay/performance control. Direct public disclosure associations and unresolved/private options are exercised by regressions, not claimed as present in these three games. See [checks/hashes](assessments/COV-004/validation.json), [commands](assessments/COV-004/commands.json) and [actual message excerpts](assessments/COV-004/actual-examples.json).

Binding report counts refer to V4 contexts, represented option entries and fact appearances in those contexts. Repeated appearances of the same delivered fact are counted again. No complete-example or unobserved-invocation count is inferred. Current-item review findings are fixed; remaining ownership is listed above. Continue with COV-005.

Continue automatically after validation and code review, fixing any current/earlier-item defect before COV-005. Separate design items retain separate commits.
