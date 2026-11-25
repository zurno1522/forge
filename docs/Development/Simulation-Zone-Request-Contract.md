# COV-006: received single zone-change request

The V8 permission, retained by current V9 producers, projects the arguments already received by `PlayerControllerAi.chooseSingleCardForZoneChange`. The existing selector entry remains after delayed reveal delivery and before the original heuristic. It adds no engine operation or repetition ownership. The controller's original callback, return and exception remain unchanged.

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

Coverage is `RECEIVED_ZONE_REQUEST_V1`. A final typed carrier identifies the received-argument permission before any planning supplier is inspected. It retains the received list reference without inspecting it at construction; projection reads the list only inside admitted capture, then freezes the resulting JSON. It grants exactly these fields at rules/planning entry. It does not grant ordinary numeric/single-entity or arbitrary hypothetical supplier permission. A foreign or uncaptured non-null decider makes the prompt unavailable with `UNRESOLVED_BINDING`.

The origin list is a request, not a list of cards or a knowledge grant about those zones. Bound its origin count by the finite ZoneType count already accepted by the diagnostic decoder (and charge traversal against the existing prompt allowance); reject unsupported/null arguments or omit an oversized list explicitly, without truncating a request and presenting it as complete. Charge each projected origin against the existing work budget. Do not change the game allowance, selected-card cap, diagnostic pool permission, local option bindings or actual callback arguments. Freeze the final JSON; later list mutation cannot change an earlier message. Hidden/disabled/copy/closed capture cannot inspect new suppliers or list contents.

The current options remain diagnostic-only, retaining destination, origins, optionality and decider ID. The decoder accepts the new kind only at the audited V8 selector with its own entry, validates exact fields/types/zones/local pick constraints, and compares the received fields with options and current player bindings. Older profiles gain no permission by inference. All examples remain `trainingReady=false`.

## Validation and ownership

Exercise actual controller calls and real delegated Intuition resolution: fresh timing after disclosure, exact origins/destination and role, `None`/empty origins, planning carrier scope, immutable events, foreign role refusal, exhausted work and hidden/off/copy/closed getter bypass. Assert original returns, callback count and exception identity, not only event presence. Decoder negatives must cover old profile, wrong boundary/timing/entry, mismatched request/role, unexpected fields, invalid zones and false readiness. Compare current full/filtered exports and preserve legacy reports and pre-game shuffles.

Engine-owned zone-change repetition remains the next separate COV-006 increment. Do not invent total bounds, previous selections or grouping from ability IDs, nearby events, offered pools or UI progress text. `SameNameAsEach`, `EACH`, general-loop retries, random/predefined bypasses, temporary controller changes and delayed reveal timing require explicit engine/controller ownership. Spell-ability choices remain the following family. COV-004 retains completeness, COV-007 inner payment/X, COV-008 availability, and OPT-005 measured cost/retention. No performance improvement or complete legal-domain claim belongs here.

Design/source evidence: [boundary inventory](assessments/COV-006-zone-request/boundary-inventory.json). The original documentation increment checked seven qualified calls and the existing selector/disclosure adapter without emitting V8. The implementation and its validation follow below. The review found additional request semantics, rather than a demonstrated defect in completed V7 history.


## Implementation validation and gap review

The first four controller/adapter regressions fail in all four cases for missing requests or missing role-omission evidence. The completed focused run passes 92 Java cases, including ten new zone-request cases. They check the actual controller's unchanged return, request timing, `None`/empty origins, null and foreign roles, duplicate/origin order, immutable entry data despite callback mutation/error, original exception identity, hidden/off/copy list bypass, typed planning permission, exact work charging, origin bounds and exhausted input. Three existing delegated Intuition resolution cases now assert the exact new request after actual disclosure delivery while retaining their original visibility and game-state assertions.

The full reactor passes 2,748 desktop tests (six existing skips), 23 game tests and 23 GUI tests. Core/AI compile with no tests. The Python suite passes 206 tests, including disk-backed/materialized validation, current request export and negatives for legacy profile, entry, boundary, timing, scalar types, zones, argument/role agreement, invented history and false permission. Projection-only oversized/null-argument cases do not widen the pre-existing bounded diagnostic request wire domain.

All seven exported artifacts remain byte-identical across 116 saved V1–V7 streams. Both published V4 workload summaries remain unchanged. One actual workload protocol trial emits V8 for one numeric callback and two lifecycle events; it is not an expanded cost/peak assessment.

Seven fresh games complete normally. The six retained scenarios (simple, numeric, modes, Doctor Who/Ur-Dragon, time-counter and counter-focused) contain no standalone zone-change selector. A new search-heavy fixture closes that validation gap: it produces 15 requests, all with a projected prompt, covering Battlefield, Library and Hand destinations. These are received arguments; the Library-to-Library requests followed by separate moves illustrate why this feature does not predict actual movement. `None`/empty origins and delegated/private views are verified by unit/integration cases rather than claimed as sampled game observations. The new fixture was added after the full unit run and validated through its fresh game/export checks; implementation/test sources remain identical to the full run, and fixture hashes are separate.

Full/filtered datasets, choices, examples and validation match byte for byte; coverage agrees except source-record counts. Each game retains two shuffles before `GAME_STARTED`. The five retained games without Monte Carlo match V7 engine output after diagnostic normalization; simple/numeric/modal also match their earlier baselines. Monte Carlo differs and provides no deterministic or universal deadline-neutrality claim. The new fixture has no prior gameplay baseline.

No further demonstrated current/prior runtime defect remains in this increment. The remaining COV-006 item needs an explicit engine/controller operation contract: ordinary-loop retries, deduplicated retained cards, later pile clearing and application cannot be inferred from IDs, adjacent selector rows or the V8 request. Named/EACH, Heist and Draft repetitions retain their separate semantics; spell-ability selection follows. COV-004 retains completeness, COV-007 payment/X, COV-008 sustained availability and OPT-005 added carrier/origin cost and retention. All examples remain `trainingReady=false`.

Evidence: [actual messages](assessments/COV-006-zone-request/actual-examples.json), [seven-game/export validation](assessments/COV-006-zone-request/validation.json), [tests](assessments/COV-006-zone-request/tests.json), [legacy compatibility](assessments/COV-006-zone-request/legacy-compatibility.json), [implementation hashes](assessments/COV-006-zone-request/implementation-sources.json), [fixture hashes](assessments/COV-006-zone-request/fixture-sources.json), [commands](assessments/COV-006-zone-request/commands.json), [profile smoke](assessments/COV-006-zone-request/profile-smoke.json) and [historical workload parity](assessments/COV-006-zone-request/benchmark-legacy.json).
