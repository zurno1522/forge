# COV-006: typed single spell-ability requests

V13 records the actual controller callbacks but leaves the single-selector prompt unsupported. The caller/handler audit finds six engine caller classes; only `AssignGroupAi` reads the parameter map, using `Affected`. All five shipped AssignGroup scripts define Players. Other engine callers pass null or the canonical Guava empty map. This increment projects that narrow received request without granting arbitrary parameter serialization, option membership or Card/Spell role identities.

## Capture one bounded request receipt

Accept null and known canonical empty maps as a request with no affected player. Accept an exact native `HashMap` only when empty or containing exactly one `Affected` key with a non-null Player value. Native size/known-key lookup requires no map enumeration and visits at most one value. Do not inspect unknown map implementations, extra/unknown keys or unsupported values; report that request component as unsupported. Canonical empty comparisons use identity, never an arbitrary map's equality or iterator. This deliberately conservative contract covers the audited engine callers; other map implementations and non-Player roles remain outside the permission.

In the visible options supplier, classify the original map once, capture the Player's full raw ID once and retain its exact object only for projection into this same entry's frozen view. Forward the original map/title/list to the original policy unchanged. Off, copy and hidden paths never inspect the new receipt supplier. Do not move engine getters under publication locks; live getter faults retain observation-failure handling and late values/faults after closure are discarded.

V14 single-selector options add an independently versioned diagnostic component:

```text
spellRequest:
  schemaVersion: 1
  coverage: COMPLETE | UNSUPPORTED
  affectedPlayerId: long | null
```

`COMPLETE` describes the supported received shape; null then means a known null/empty map with no affected player. `UNSUPPORTED` always carries null and never means an empty map. An explicit null `Affected`, extra fields, a different value type or an unrecognized map is unsupported. Do not expose unknown map values, names, keys or sizes. The existing options/terminal fields retain V13 meanings.

## Permit the current role

All producer envelopes use V14. At the audited single-selector entry only, permit coverage `RECEIVED_SPELL_REQUEST_V1` with:

```text
kind: SPELL_ABILITY_REQUEST
affectedPlayer: this view's Player reference | null
```

A final typed carrier reads the already captured receipt, never the original map. A supported Player uses the existing current-game object-identity binding; a foreign, copied or uncaptured non-null Player makes the prompt unavailable with `UNRESOLVED_BINDING`. Unsupported receipt shapes make the prompt unavailable with `UNSUPPORTED`, preserving the rest of the partial context. They must not silently become a complete no-parameter request. The internal unsupported marker is not a wire prompt kind.

This carrier also permits received planning arguments, retaining `PLANNING_WORKING_STATE`; unrelated hypothetical suppliers remain uninspected. Reuse the existing related-player storage/binding rather than adding an engine API, registry or durable raw-object reference. Only the detached local reference is a permitted feature. Raw role IDs and the diagnostic receipt remain excluded.

Require V14, the exact single boundary/own entry, exact component fields/types and coverage/role consistency. The projected role must equal the receipt ID through this view's own player bindings. A complete no-player receipt requires a null projection; an unsupported receipt cannot claim a prompt. Old V13 records without the component remain valid and older profiles reject it. Outer operation/count permission, source accounting schema/table (38 named slots), inventories through extension V6 and selection outcomes stay unchanged.

## Validation and following work

Exercise actual friend/foe selection and engine assignment, self/opponent roles, canonical no-parameter callers, original map forwarding and post-callback mutation, unsupported shapes without unknown-value reads, foreign/equal-ID role refusal, planning work/permission, bypass and live/blocked getter closure. Add decoder role/profile/capability negatives, actual emitted examples and legacy/full-filtered parity. Review current/prior gaps before continuing.

Repeated spell-choice progress/history remains the following COV-006 increment. The current request supplies its own local role and does not reconstruct prior selections. Engine-level repetition outside that controller operation and broader historical/option/domain knowledge retain their separately declared scope. COV-004 owns completeness/knowledge permissions, COV-007 payment/X, COV-008 availability and OPT-005 the new receipt/map-classification/role-projection costs. Every example remains `trainingReady=false`.

## Implementation, validation and gap review

V14 implements this request component and typed role carrier without adding engine APIs or source slots. The focused reactor passes 164 Java cases, including fifteen new cases for actual friend/foe policy and engine resolution, both current roles, known empty shapes, opaque/subclass/private-value refusal, frozen pre-callback arguments, equal-ID foreign objects, planning work, three bypasses and four closure/late-fault combinations. Actual AssignGroup resolution gives the friend 23 life and the foe 18; both snapshots precede application. Python passes 233 tests, including nineteen new invalid-record mutations. The current modules compile; the full reactor is not repeated for this increment (V13's full baseline is recorded separately).

All seven export artifacts remain byte-identical for 184 saved V1–V13 streams. Three fresh games finish normally, with matching full/filtered datasets, choices, examples and validation; coverage matches except source-record totals. Source/collector accounting reconciles with zero observation failures, and each game keeps two shuffles before `GAME_STARTED`. The Siege game samples two known no-player requests. The new Pir's Whim deck's selected seed does not reach a friend/foe selector: this is a sampling limit, not evidence of role coverage. Actual engine/controller excerpts separately validate both friend/foe roles, no parameters, an unsupported opaque map and an equal-ID foreign Player: ten lifecycle messages/five selectors pass the decoder. These excerpts are not complete-game traces.

Ordinary and Siege engine sequences match V13. The Pir's Whim game matches an isolated four-class V13 overlay (controller, spell recorder, context kernel and prompt carrier); the checkout/build output is unchanged. This comparison controls engine behavior for these seeds and does not measure whole-build performance or universal deadline neutrality.

Review finds no demonstrated current/prior defect. Unknown maps and non-Player roles deliberately remain unsupported; the shipped caller audit supports this narrow contract. The next COV-006 increment is controller-owned spell progress/prior-return history. Engine-level repetition outside that operation remains a separate owner. COV-004 retains spell definitions, option/domain and long-term knowledge completeness; COV-007 payment/X; COV-008 availability; OPT-005 bounded map classification, receipt allocation and role projection costs. No example becomes training-ready.

Evidence: [tests](assessments/COV-006-spell-request/tests.json), [games](assessments/COV-006-spell-request/validation.json), [actual game messages](assessments/COV-006-spell-request/actual-game-examples.json), [actual engine/controller messages](assessments/COV-006-spell-request/engine-resolution-examples.json), [excerpt validation](assessments/COV-006-spell-request/engine-resolution-validation.json), [legacy bytes](assessments/COV-006-spell-request/legacy-compatibility.json), [source hashes](assessments/COV-006-spell-request/implementation-sources.json), [baseline overlay](assessments/COV-006-spell-request/baseline-ai-sources.json), [fixtures](assessments/COV-006-spell-request/fixture-sources.json) and [commands](assessments/COV-006-spell-request/commands.json).
