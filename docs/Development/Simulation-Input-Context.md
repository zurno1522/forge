# Inline choice input contexts

This implements the first representative increment of the [observation contract](Simulation-Observation-Contract.md). Optional `inputContext` fields freeze a permitted partial player view at audited AI boundaries. Existing decision, choice, target-provenance and observation versions retain their meanings. All datasets remain `trainingReady=false`.

COV-002 adds the explicitly versioned [active-stack profile, version 2](Simulation-Stack-Input-Context.md). New producers emit version 2; this document's version-1 schema and earlier measurements remain historical. The exporter accepts both versions and keeps their semantics separate.

## Version 1

The envelope contains `schemaVersion:1`, `profile:PERMITTED_CHOICE_VIEW_V1`, `status`, `timing`, `stateBasis`, `perspective`, `coverage`, `policy`, `diagnostics` and `omissions`. `CAPTURED` means the named partial profile was captured; it does not mean complete Magic knowledge. `PARTIAL` identifies typed omissions. `UNAVAILABLE` has null policy and empty diagnostic bindings. Old records without a context remain unavailable; unknown versions are rejected.

Only `policy` is the feature projection. It contains turn/phase, context-local chooser/turn-player references, player life/hand/library counts and floating mana, permitted card characteristics, and actually delivered disclosure facts. It excludes diagnostic IDs, timestamps, hidden-derived candidate membership/order, rendered prompts, AI caches and future results. Diagnostic player/card bindings retain engine IDs and card incarnations for inspection, without asserting observable continuity. Local references have no cross-context lifetime.

The profile considers battlefield, command, graveyard, exile, hand and library. Object visibility and permission to inspect a hidden face are checked separately. Public face-down characteristics can be represented without revealing identity. Face-up library cards require an independent viewing grant. Hidden-zone permitted subsets are canonicalized by represented values; incomplete hidden-zone scans never expose an order-dependent prefix. The legacy `observation` projection remains unchanged.

Stack contents and long-term knowledge are explicitly unsupported/incomplete. Original choice options remain diagnostic-only, including their count and order. Numeric constraints and other arguments remain in existing options; this increment does not certify a complete, replayable policy input.

## Audited entry points

| Owner or boundary | Timing |
| --- | --- |
| Main-phase parent baseline | `BEFORE_OPERATION` |
| Controller targeting | `BEFORE_OPERATION` |
| Numeric range and discrete selectors | `BEFORE_SELECTOR` |
| Announcement operation | `BEFORE_OPERATION` |
| Cost visitor and automatic mana-payment routine | `BEFORE_OPERATION` |
| Discard and sacrifice selectors | `BEFORE_SELECTOR` |
| Effect entity and hidden-origin zone-change card selectors | `BEFORE_SELECTOR`, after delayed reveal delivery |

Candidate-evaluation captures and descendants of audited hypothetical work use `PLANNING_WORKING_STATE`. PlayAi's temporary face/cost evaluation has an explicit planning scope. Other supported entries use `RULES_STATE`. Cached modes, precomputed X assignment, late target additions and exile ranking do not acquire preselection contexts.

New `EFFECT_ENTITY_SELECTION` and `ZONE_CHANGE_CARD_SELECTION` lifecycles retain the original diagnostic options and selected reference/index, with bounded prefixes. Interrupted enumeration reports unavailable options and no invented index. Their callbacks still run once and retain their return/exception.

## Disclosures and lifetime

Both AI controller reveal overloads freeze the delivered face directly, including direct RevealHand/Peek deliveries that do not emit CardsShown. The actual AI delayed-reveal path filters stale cards by declared zone before delivery. A disclosure superset can exceed the selectable subset.

Facts persist for the observed synchronous stack-resolution interval, identified by the resolving stack instance. Normal resolution/removal, control changes and authoritative game close retire them. Failed intervals are poisoned; direct/nonstack paths support only explicit nested current-prompt scopes. No ability-ID ancestry or AI memory is used to infer prior disclosure.

Historical facts always report `currentObjectAssociation:UNRESOLVED`. They preserve what was presented without tracking hidden objects through shuffles or movement. Still-face-down deliveries have explicit unsupported coverage when the reveal API cannot establish the alternate face presented. Controlled-player/search-control and shared-team perspectives remain unavailable pending separate permission tests.

## Bounds and failure behavior

Per-context limits are 16 players, 128 cards, 512 visits, 128 mana units per player, 32 counters per card, 32 disclosure facts and 256 UTF-16 text units. A fixed 256,000-unit game-work allowance is divided among registered players; original participant controllers retain their share. Replacement controlled perspectives are unsupported. Units account for represented traversal work, not CPU instructions. Exhaustion yields explicit unavailable contexts; choice records and gameplay continue.

Traversal runs outside parent publication monitors and checks cancellation. Late captures cannot publish into closed parents. Optional capture failures preserve the base record where publishable, report `CAPTURE_FAILED` and use Game's observation-health channel. Disabled/copy paths do no additional projection or disclosure traversal. These bounds do not guarantee that arbitrary engine getters or iterators cannot block.

The exporter strictly validates the optional schema, local bindings, perspective, timing, coverage and status consistency. Coverage reports distinguish captured, partial, unavailable and absent contexts and count omission reasons. Keep `input_context.py` with the other exporter modules when copying the tool.

## Contract gates and remaining design work

The contract remains broader than this increment. T01 has separate fresh resource snapshots but stack/target input projection is incomplete. T04 explicitly leaves control/team cases unsupported. T08 has delivery-hook/LKI tests rather than exhaustive effect/control combinations. T13 measures CPU, allocation, frozen retention and bytes; peak transient capture retention and nonempty disclosure-buffer cost remain open. The remaining gates have representative regressions and source audits, not exhaustive coverage of all Forge paths.

### Next work

Coverage has its own [ordered design backlog](Simulation-Observation-Coverage-Plan.md): reporting definitions, stack/resolving inputs, typed prompt constraints, safe option bindings, planning audits and broader selector families. Control/team permissions, direct/nonstack lifetimes and historical knowledge require separate focused designs.

COV-001 reporting is implemented; see [the observed-record definitions and gap review](Simulation-Coverage-Reporting.md). No new input component or selector capture is established by that reporting change.

Optimization has an independent [ordered design backlog](Simulation-Observation-Optimization-Plan.md): cost attribution, immutable-copy reduction, construction/ordering allocation, storage/serialization and broader workload validation. Existing measurements below are a baseline, not a requirement to bundle performance changes into coverage work. Two real games and bounded fixtures cannot establish universal overhead or timeout neutrality. Changing budgets or sampling changes data availability and requires an explicit coverage review.

## Validation and measured cost

The timings in this section are historical Gson-probe results. See [OPT-001's production streaming-writer measurements and limits](Simulation-Observation-Cost-Assessment.md) for updated phase attribution; the two serialization paths must not be treated as interchangeable.

The targeted Java run passed 79 tests, including real Intuition resolution with three/fewer available cards, delegated private views, delayed disclosure supersets/stale-zone filtering, immutable face facts, poisoned/closed scopes, paired hidden worlds, game budgets and blocked timeout publication. The full reactor passed: desktop 2,548 tests (six skipped), core 23 and game 23, with no failures. Python passed 112 tests. All 28 exported files across four legacy real traces were byte-identical to the prior exporter.

New simple (seed 12031, Monte Carlo off) and Doctor Who/Ur-Dragon (seed 31003, Monte Carlo on) games completed normally. Both retain two shuffles before GAME_STARTED. Full/filtered datasets, choices, examples and validation outputs match byte-for-byte; coverage differs only in source-record count. The simple game exactly matches normalized baseline engine events. The Monte Carlo game keeps the same winner and turn count but differs in events; timing-limited search is not a deterministic replay guarantee.

| Measurement | Small fixture: 4 battlefield cards/player | Larger fixture: 30 battlefield cards/player |
| --- | ---: | ---: |
| Incremental calling-thread CPU per choice | 218 µs | 360 µs |
| Incremental allocation per choice | 163,017 bytes | 594,609 bytes |
| Reachable frozen context | 30,984 bytes | 104,808 bytes |

The probe compares the same choice with/without context, including event freezing and Gson serialization: four rotated measured rounds of 200 calls after warmup, fresh games to avoid budget exhaustion, no overlapping test/game JVM. It measures retained frozen graphs, **not peak transient heap** or whole-game CPU. Disclosure buffers in this cost fixture are empty; disclosure correctness/bounds are tested separately. The [probe source](../../tools/decision-records/input-context-benchmark/InputContextProbe.java) is development-only.

The simple full trace grew from 563,137 to 1,536,070 raw bytes (gzip 31,336 to 58,019); the complex full trace grew from 1,098,352 to 3,968,359 (gzip 72,882 to 149,023). They contain 174 and 340 contexts respectively; neither exhausted its work budget. Captured-status counts are profile-relative: simple contexts are partial due to unaudited prior disclosure; the complex game has 70 captured and 270 partial. These measurements support keeping bounded inline capture for this increment, with broader cost/retention profiling still outstanding.

Machine-readable [game checks](assessments/input-context/validation.json), [legacy compatibility](assessments/input-context/legacy-compatibility.json), [cost samples](assessments/input-context/benchmark-summary.json) and [actual message excerpts](assessments/input-context/actual-examples.json) and [implementation hashes](assessments/input-context/implementation-sources.json) retain source hashes and limitations. Examples are excerpts from validated streams, not independently complete games.
