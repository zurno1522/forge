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
