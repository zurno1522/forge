# COV-006: spell-ability operation and selector boundaries

The remaining controller family is `chooseSpellAbilitiesForEffect` and `chooseSingleSpellForEffect`. Source callers comprise ChooseGeneric, VillainousChoice, Venture, CopySpellAbility, AssignGroup and ActivateAbility. They include generated modes/rooms, live stack abilities, group assignment and forced activation; this family is not interchangeable with choosing targets or selecting a Card/Player.

## Preserve the original callbacks

Wrap the outer AI method's existing copy/do-while/remove/append loop once, retaining its original virtual single-selector dispatch. It calls the selector at least once even for a zero or negative request; a non-null result is appended even if removal fails, and duplicate returns remain duplicates. A null ends the loop. Preserve the original returned list, errors, mutable working offers and original parameter-map objects. Do not repair these semantics as part of observation.

Wrap the base single-selector method once, immediately before its original converter callback. Preserve its result/exception and forward the exact list/map/title. Off, copied and hidden paths do not inspect new diagnostic suppliers or opaque parameters. Existing lifecycle/admission, fault visibility, late-result handling and nested diagnostic ownership come from the common recorder.

## V13 first increment

Add two explicitly named sources and own entry locations:

| Choice type | Boundary | Timing/kind |
| --- | --- | --- |
| `EFFECT_MULTI_SPELL_ABILITY_SELECTION` | `CONTROLLER_EFFECT_SPELL_ABILITIES` | `BEFORE_OPERATION` / OPERATION |
| `EFFECT_SPELL_ABILITY_SELECTION` | `CONTROLLER_EFFECT_SPELL_ABILITY` | `BEFORE_SELECTOR` / SELECTOR |

The fixed source accounting table adds two slots (38 named plus UNCLASSIFIED), emitting only nonempty cells. Keep accounting schema 1 and frozen V1/V2 entry inventories. Add a conditional entry-inventory extension V6 for these sources, without changing earlier extension meanings or legacy reports.

Outer diagnostic options contain `coverage`, `items`, `truncated`, `requestedSelections`; single options contain the first three fields. Coverage is `OBSERVED_SPELL_ABILITY_OPTIONS_DIAGNOSTIC_ONLY` or `UNAVAILABLE`. Reuse bounded SPELL_ABILITY references (`id`, nullable `stackId`), at most 256, preserving order and duplicate references. Do not include text, script definitions, titles, opaque maps, offered counts beyond the prefix or legal-domain claims. Null/malformed/getter faults follow the existing capture policy and remain distinct from original callback errors.

Outer terminal results contain `coverage: OBSERVED_RETURNED_SPELL_ABILITY_PREFIX | UNAVAILABLE`, `items`, exact nonnegative int `selectedCount` and `truncated`. They describe the actual returned list, not movements, committed effects or unique successful picks. Single terminals reuse the diagnostic `selected`, `selectedOptionIndex`, `match` representation, with SPELL_ABILITY-only references. A unique captured reference may provide a diagnostic option index; duplicates are ambiguous. References and indices are not permitted model features.

All producer envelopes use V13. At the outer entry only, permit `SPELL_ABILITY_OPERATION_REQUEST` with coverage `RECEIVED_SPELL_OPERATION_V1` and exact signed int `requestedSelections`. This is the received request, not a minimum/maximum legal result. The typed carrier permits planning requests while retaining `PLANNING_WORKING_STATE`; unrelated hypothetical suppliers remain uninspected. Single selectors initially have no typed prompt permission. Both boundaries retain current public/chooser state under the existing partial context rules; option bindings remain unsupported.

Decoder gates require V13, the exact own entry, matching choice type/boundary/timing, exact field sets/types, bounded ability-only references, faithful count/prefix/match semantics and request/prompt equality. Older profiles reject the new sources and permission. Parent links remain diagnostic; neither endpoint grants entity/zone history components, offered-pool features or complete learning examples. Legacy reports remain byte-identical; every example remains `trainingReady=false`.

## Subsequent increments and acceptance

This increment establishes actual source events, ownership and received totals. It does not claim prior-return history or complete typed parameters. The following COV-006 sub-increments must audit the actual `Affected` map used by AssignGroup, null/empty maps at other callers, unknown-key refusal and spell-ability receipt/association semantics. Do not reuse Card/Player incarnation proof or silently serialize an opaque map. VillainousChoice's engine-level repeated outer calls and AssignGroup's per-object loop remain separate owners; no adjacency grouping is allowed.

Use actual base-controller selection and engine effects to verify zero/negative requests, repeated/removal behavior, null/empty/error paths, virtual overrides, exact list/map forwarding, bounded prefixes, duplicate references and unchanged original results/effects. Test hidden/off/copy bypass, live/late faults, nested linkage, own entries, source/collector reconciliation and strict decoder negatives. Compare legacy bytes, full/filtered games and shuffles before `GAME_STARTED`; review current/prior defects before the next sub-increment. Full legal-domain/knowledge requirements remain COV-004; payment/X COV-007; availability COV-008; added capture/copy/serialization costs OPT-005.
