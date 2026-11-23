# COV-002: bounded active-stack inputs

The validation below records the V2 increment. New producers use [profile V3](Simulation-Prompt-Input-Context.md), retaining these stack semantics and adding typed controller request fields. The exporter still accepts V1 and V2 without upgrading their data.

The V2 increment introduced `inputContext.schemaVersion:2`, `profile:PERMITTED_CHOICE_VIEW_V2`. The exporter retains explicit version-1 support; version 1 never acquires stack semantics by inference. Existing parent/choice/event/provenance schemas, legacy `observation`, IDs and capture budgets are unchanged. Older exporters that reject version 2 need updating.

## Scope and schema

Version 2 adds `policy.stack` and `diagnostics.stackBindings`. The existing permitted current-card fields retain their shape; the version-2 population also includes represented active spell sources currently in the global stack zone. This zone is not a PlayerZone. Source projection uses the chooser's existing visibility/face-inspection checks, including public face-down characteristics separately from hidden identity. Card counts still share the existing 128-card/512-visit limits.

`coverage.stack` is `PUBLIC_ROOT_STACK_V1` for available policy and `UNAVAILABLE` when the entire context is unavailable. The coverage name defines a deliberately partial view of the active stack, not full ability semantics, all targets or a legal response set. Version-1 coverage remains `UNSUPPORTED`.

| Policy field | Meaning |
| --- | --- |
| `stack.entries` | At most eight entries, top first. Each has local `s0`, `s1`, … references. |
| Entry `kind` | `SPELL` or `ABILITY`; no hidden script/API or rendered ability description. |
| Entry `controller` | Local player reference from the instance's activating-player field, or null with unresolved coverage. It is independent of source owner/current card controller. |
| Entry `source` | Local card reference only when the exact represented current object is publicly locatable in battlefield, command, graveyard, face-up exile or active stack. Private-zone, stale/LKI and absent references stay null. Current incarnation must still match the captured binding. |
| Entry `rootX` | Nullable observed root XMANA-cost field for a known source under `RULES_STATE`. Null does not prove that the ability has no X. It is not a computed damage amount or proof of payment. |
| Entry `targets` | At most 16 root targets, preserving order, each `{entityRef,dividedAmount}`. References can address represented public cards, players or exact represented stack abilities. A null divided amount means no observed allocation. Values are engine observations, not legality checks or damage outcomes. |
| Entry `targetsCoverage` | `ROOT_TARGETS_ONLY` for a captured root list, including empty; `UNAVAILABLE` with null targets when association, privacy or bounds prevent capture. A partly unresolved/oversized root list is wholly unavailable. Subability target groups are outside this profile. |
| `stack.truncated` | Active entries extend beyond the retained prefix, with an explicit stack omission. No full stack-size inference is supplied. |
| `stack.isResolving` | Engine active-stack resolving flag. It does not claim that every direct/nonstack resolution is observed. |
| `stack.resolving` / `resolvingCoverage` | Local entry reference for the exact, unambiguous engine resolving ability, or null/unavailable when unresolved. False resolving flag has null reference and captured coverage. |

Diagnostic stack bindings contain `{entityRef,id,abilityId}`. IDs are run/game-scoped diagnostic values, never model numeric features or evidence of observable continuity. Duplicate ability objects cannot establish a unique target/resolving association; bindings are not guessed from ID equality. No observation registry or engine API is introduced.

## Privacy, working state and lifetime

Live root X/targets are omitted for hidden/unknown sources. Hypothetical working-state captures also omit them: pending SA fields can be temporarily modified by AI probes and are not automatically publicly committed information. These omissions are explicit; source/controller/order metadata remains where supported. COV-005 owns the later committed-stack-versus-working-plan contract.

The builder reads only the retained active instances and their represented sources. It does not enumerate the global stack zone or frozen staging queue: staging size must not influence this projection or its traversal-unit budget. Real stack-zone sources require non-LKI status, a current-zone pointer and exact identity membership through the existing zone collection API, since retired objects can retain their old zone pointer. Collection lookups can build/use the engine's internal index; that getter cost is outside explicit node-visit accounting and is an OPT-005 profiling concern. Other source/target associations require exact represented objects and matching incarnations. There is no privileged whole-game current-card lookup to repair missing bindings.

Traversal checks cancellation/work admission outside parent publication monitors. Expected hidden, unresolved and truncated states produce partial coverage, not observation-health failures. Game-work exhaustion or cancellation makes the entire context unavailable with empty bindings. Returned payloads freeze scalar JSON, so later target, X, source-zone and stack mutations cannot rewrite earlier snapshots.

The legacy parent baseline is still own-main-phase/empty-stack. The exporter rejects a version-2 parent policy with pending or resolving stack content. Standalone callbacks can represent the supported nonempty stack/resolution states. Options remain diagnostic-only and all datasets remain `trainingReady=false`.

For later completeness assessment, response/counterspell selectors require the pending stack order and a bound target entry; resolution-time selectors require the resolving reference as well as the relevant root inputs. Main-phase parent decisions instead require an empty, nonresolving stack. Target, mode, numeric and payment selectors can occur in either context: their family contracts must name the inputs they require rather than treating a nonempty stack as universally necessary. An unbound source, unsupported subability input or unavailable resolving reference cannot satisfy a family's requirement merely because the stack component exists. COV-003 defines the typed selector requirements and COV-004 reviews their combined readiness.

## Validation and gap review

Meaningful regressions cover empty/versioned stack capture, Counterspell over Lightning Bolt, local stack targets, root X/divided values, actual resolution and link retirement, hidden-face paired worlds, hypothetical plan isolation, private/stale references, entry/target caps, game closure and frozen-queue invariance of both policy and traversal work. Python validation covers old/new versions, binding consistency, malformed caps/resolving state, hidden/private leakage and empty-stack parent invariants.

The final focused Java run passed 87 tests. The full reactor passed 2,559 desktop tests (six skipped), 23 core tests and 23 game tests, with no failures; Python passed all 134 tests. An earlier full run failed one Monte Carlo Doomsday assertion while decision tracing was disabled; it passed in both final runs. Its cause is not established, and no AI policy was changed to address it. See [test counts and log hashes](assessments/COV-002/tests.json) and [implementation hashes](assessments/COV-002/implementation-sources.json).

Eight saved legacy streams, including absent contexts and profile V1, produce byte-identical datasets, choices, examples, manifests and validation files with the old and new exporters. Existing coverage counts are unchanged. Reports intentionally add profile counts and use profile-neutral descriptions; full report byte identity is not claimed. See [compatibility checks](assessments/COV-002/legacy-compatibility.json).

Fresh simple constructed (seed 12031, Monte Carlo off) and Doctor Who/Ur-Dragon Commander (seed 31003, Monte Carlo on) games completed normally. Full/filtered dataset, choice, example and validation files match byte-for-byte; coverage JSON agrees apart from source-record counts. Both games preserve two shuffles before `GAME_STARTED`. The simple game's normalized engine events exactly match the previous V1 run. The Monte Carlo run differs, so these games do not establish deterministic replay or universal timing neutrality.

The simple game contains 174 contexts with empty stacks. The Commander game contains 341 contexts: 93 have nonempty stacks and 72 observe resolution. All 119 represented entries bind their public sources and capture their root target lists; no stack prefix or game-work exhaustion occurred in these two samples. Their remaining omissions concern prior disclosure scope. Planning-state omissions, private/stale sources and truncation are covered by regressions rather than claimed as exercised by these games. See [counts and stream hashes](assessments/COV-002/validation.json), [exact commands](assessments/COV-002/commands.json) and [actual pending/resolving/target excerpts](assessments/COV-002/actual-examples.json). Excerpts are not standalone complete game streams.

The remaining scope has explicit owners:

| Gap | Owner and next requirement |
| --- | --- |
| Which printed/granted ability, effect semantics, typed amounts and subability roles/targets | COV-003 now defines the supported typed request fields and family requirements. COV-006 owns remaining effect-specific inputs/semantics and broader target/mode families; COV-004 proves any ability/option associations. Root-only metadata is not the full ability. |
| Private, LKI, historical and ambiguous source/target associations; virtual spell-copy sources without current zone membership; public sources across zone incarnations | COV-004 proves supported local bindings; any separate presented-source projection needs an explicit family contract under COV-006. Durable knowledge/continuity stays a later design. Do not invent current-card bindings or infer associations from IDs or names. |
| Publicly committed stack inputs during temporary AI work | COV-005 defines the separate evidence/boundary contract; COV-006 integrates it. Current working-state root parameters remain unavailable. |
| Frozen/staged operations and direct/nonstack resolving lifetimes | COV-005 audits boundaries; direct/nonstack scopes remain their separate lifetime design. Active-stack coverage must not be relabeled as full engine execution coverage. |
| More data per context can affect budget exhaustion, CPU and retention | OPT-005 validates larger/multiplayer/long-game workloads using the new profile. The previous OPT-001 measurements apply to version 1; no optimization speedup or budget increase is claimed here. |

Pause for this review before COV-003. No copying, ordering, serialization or budget optimization is included.
