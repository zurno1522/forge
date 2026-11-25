# COV-006: received single zone-change request

This contract defines the next increment; it grants no runtime permission by itself. The intended implementation projects the arguments already received by `PlayerControllerAi.chooseSingleCardForZoneChange`. The existing selector entry remains after delayed reveal delivery and before the original heuristic. It adds no engine operation or repetition ownership. The controller's original callback, return and exception remain unchanged.

## Source boundary and semantics

The current audit finds seven engine call sites across four owners: four calls in `ChangeZoneEffect`, one in `HeistEffect`, one in `DraftEffect`, and one in `Player.learn`. The abstract `PlayerController` declaration defines the callback. No other AI implementation is introduced.

These are received request fields, not a prediction of the selected card's actual movement. `DraftEffect` supplies destination `None` and an empty origin list for newly created spellbook options. `Player.learn` supplies destination `Hand` and origins `Sideboard,Hand`, but a hand choice discards/draws rather than moving that same card to hand. Preserve these values and their order exactly. Optionality describes the received request even where the existing heuristic does not consult it. The decider role must bind by current player object identity; it is separate from the actual controller chooser and the ability's activating player.

## V8 permission

All producer envelopes advance to schema/profile V8, including hidden/unavailable envelopes, inheriting V1–V7 meanings. At the audited `CONTROLLER_ZONE_CHANGE_CARD` selector only, project `policy.prompt`:

```text
kind: ZONE_CHANGE_REQUEST
minimumPicks: optional ? 0 : 1
maximumPicks: 1
optional: exact received boolean
destination: exact received ZoneType name, including None
origins: ordered received ZoneType names, including an empty list
decider: current local player reference, or null for a null received role
```

Coverage is `RECEIVED_ZONE_REQUEST_V1`. An immutable typed carrier identifies the received-argument permission before any planning supplier is inspected. It grants exactly these fields at rules/planning entry. It does not grant ordinary numeric/single-entity or arbitrary hypothetical supplier permission. A foreign or uncaptured non-null decider makes the prompt unavailable with `UNRESOLVED_BINDING`.

The origin list is a request, not a list of cards or a knowledge grant about those zones. Bound its origin count by the finite ZoneType count already accepted by the diagnostic decoder (and charge traversal against the existing prompt allowance); reject unsupported/null arguments or omit an oversized list explicitly, without truncating a request and presenting it as complete. Charge each projected origin against the existing work budget. Do not change the game allowance, selected-card cap, diagnostic pool permission, local option bindings or actual callback arguments. Freeze the final JSON; later list mutation cannot change an earlier message. Hidden/disabled/copy/closed capture cannot inspect new suppliers or list contents.

The current options remain diagnostic-only, retaining destination, origins, optionality and decider ID. The decoder accepts the new kind only at the audited V8 selector with its own entry, validates exact fields/types/zones/local pick constraints, and compares the received fields with options and current player bindings. Older profiles gain no permission by inference. All examples remain `trainingReady=false`.

## Validation and ownership

Exercise actual controller calls and real delegated Intuition resolution: fresh timing after disclosure, exact origins/destination and role, `None`/empty origins, planning carrier scope, immutable events, foreign role refusal, exhausted work and hidden/off/copy/closed getter bypass. Assert original returns, callback count and exception identity, not only event presence. Decoder negatives must cover old profile, wrong boundary/timing/entry, mismatched request/role, unexpected fields, invalid zones and false readiness. Compare current full/filtered exports and preserve legacy reports and pre-game shuffles.

Engine-owned zone-change repetition remains the next separate COV-006 increment. Do not invent total bounds, previous selections or grouping from ability IDs, nearby events, offered pools or UI progress text. `SameNameAsEach`, `EACH`, general-loop retries, random/predefined bypasses, temporary controller changes and delayed reveal timing require explicit engine/controller ownership. Spell-ability choices remain the following family. COV-004 retains completeness, COV-007 inner payment/X, COV-008 availability, and OPT-005 measured cost/retention. No performance improvement or complete legal-domain claim belongs here.

Design/source evidence: [boundary inventory](assessments/COV-006-zone-request/boundary-inventory.json). This documentation increment checks seven qualified calls and the existing selector/disclosure adapter. It adds no runtime test or V8 output. The review found additional request semantics, rather than a demonstrated defect in completed V7 history.
