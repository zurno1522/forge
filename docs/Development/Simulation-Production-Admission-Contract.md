# COV-008C: production admission contract

Status: design, following the [controlled](Simulation-Admission-Experiment-Assessment.md) and [native-game](Simulation-Native-Admission-Assessment.md) experiments. This document changes no runtime behavior. Implementation, cost measurement and optional-policy enablement are separate commits with a gap review after each.

## Selection and execution order

Retain fixed-share prefix admission as the production default. Keep the existing 256,000 traversal units per game, integer-divided among original registered players, with no transfer, refund or replenishment. Preserve the existing per-view caps and disclosure charging. This is a compatibility and cost-bound choice, not a claim that prefix admission provides adequate late-game coverage.

The experiments support deterministic hash thinning as an optional policy candidate, but do not support replacing the default. Hash-four recovers late views in Commander seed 31009 and loses late independent-entry views in seed 31010. Hash-eight has a larger early-coverage cost. Reserve improves some late counts but strands work and can lose disclosure deliveries before release. Neither more available views nor lower capture frequency establishes better represented information or lower production overhead. One native hash-eight run timed out; its longer replay matched native behavior, but does not establish deadline neutrality.

Proceed in this order:

1. Implement the V29 admission envelope and bounded prefix counter ownership. Keep prefix as the only enabled production policy. Add producer/reader checks, native examples, source/collector reconciliation and compatibility evidence.
2. Run the broader OPT-005 checkpoint against that actual production implementation. Include healthy, exhausted, bypassed and failure paths, detached metadata/serialization costs, and deadline-sensitive Monte Carlo workloads. Compare the same represented V28 information; account separately for V29 metadata. Existing experimental-monitor timings are not production implementation timings.
3. Implement an isolated production-shaped hash candidate and measure its extra counter/mix/metadata cost under the same checkpoint. Enable optional hash-four only after that evidence and its gap review. Keep its policy/configuration and validation changes in their own commit. Hash-eight and reserve remain development experiments unless separately selected and validated.

This reorders the cost gate before optional runtime enablement. COV-008C retains the optional-policy work through that gate; the checkpoint does not silently declare sustained availability complete. No increase in allowance is selected.

## Boundaries and ownership

Admission controls construction of an input view. It does not gate a native callback, rerun policy, suppress its lifecycle, change an action/result, or change the existing eligibility for publishing a record. Retain permitted entry/location information and explicit unavailable contexts when a policy view is omitted.

Keep the implementation in forge-ai. A small immutable configuration and counter helper may be owned by AiInputContext.State. The simulation adapter configures original PlayerControllerAi instances before recording begins, using the existing controller-configuration seam beside observation accounting. Do not add an AI-policy dependency, string policy settings or an admission registry to Game. Game retains its existing generic observation closure and copied-game guards. Do not use a JVM-global map, process properties read during capture, reflection or a shutdown hook as the production configuration mechanism.

Configure once for the original controller/Game identity, before the first State is allocated, including allocation by disclosure intake. Reject conflicting or late configuration explicitly at setup; never reset funding or an ordinal to accommodate it. A copied game receives neither configuration nor mutable admission state. Reusing an original controller with another Game, replacement/control transfer and unsupported team perspectives must not inherit or create an extra configured share. Preserve the existing unsupported-perspective behavior; test the actual controller identity seam rather than assuming the source-accounting incomplete flag enforces it. Ordinary callers without explicit adapter configuration retain prefix behavior. Optional hash capture must require explicit setup and cannot be activated by a fallback constructor.

Prefix State has one nonnegative long ordinal, initially zero, scoped to the original owning player and Game. All eligible captures for that State share it: parent, candidate, land, standalone, nested target and search. Disclosure intake spends work but does not consume a capture ordinal. A distinct subsequent Game has a new scope; IDs are joined with run/game/player, never with a bare ordinal. Decision/action IDs keep their separate existing meanings.

## Gate and wire semantics

Use input schemaVersion 29 and profile PERMITTED_CHOICE_VIEW_V29. Preserve the V28 represented policy, permission checks, diagnostics and structural caps. Add one required top-level admission object beside policy; it is observation metadata and is never copied into the policy feature projection. V1–V28 retain their exact supported shapes, with absent admission interpreted as unknown. Do not globally permit the development EXPERIMENT_SAMPLED or EXPERIMENT_RESERVED reasons.

The admission object has exactly these fields:

| Field | Meaning |
| --- | --- |
| schemaVersion | Integer 1 for this admission contract |
| policy | FIXED_SHARE_PREFIX_V1 initially; null when the gate is not established |
| denominator | 1 for prefix; null when the gate is not established |
| observationSeed | null for prefix; reserved for an explicitly selected hash policy |
| ordinal | Positive signed-long capture ordinal, or null without an admitted gate ticket |
| status | ADMITTED, BYPASSED, UNESTABLISHED or COUNTER_OVERFLOW initially |

ADMITTED means the gate selected an attempt, not that a view was captured, published or delivered. Its prefix policy/denominator are mandatory and ordinal is in 1..Long.MAX_VALUE. A subsequent work-budget failure or interrupted traversal remains ADMITTED with an UNAVAILABLE input; availability is still described by the existing status/omissions. No remaining-work or whole-capture work claim is added to this envelope.

BYPASSED means a known pre-gate guard refused capture. Its policy, denominator, seed and ordinal are null. Guards for disabled/closed/interrupted capture, copies, hidden source metadata and unsupported perspectives run before counter allocation or increment and before prompt/options/parameter suppliers or policy-state traversal. Preserve their existing omission reasons and masked timing rules. Some guards are owned by the enclosing recorder; their unavailable factory must identify a known bypass explicitly. Do not inspect a hidden source merely to fill admission metadata.

UNESTABLISHED carries the same null fields but means admission cannot be established. In particular, an enclosing recorder that catches an unchanged capture exception may only know CAPTURE_FAILED, not whether the failure happened before or after a ticket. It must emit UNESTABLISHED rather than infer a bypass or reuse the previous capture's ticket. Source failures remain visible through the existing observation failure/accounting path. This status preserves truthful failure handling without a new exception wrapper or thread-local last-ticket lookup. It does not establish that no work was charged.

COUNTER_OVERFLOW identifies a configured prefix counter that has already issued Long.MAX_VALUE. Keep the known prefix policy/denominator, with null seed and ordinal; return unavailable CONTEXT/COUNTER_OVERFLOW without traversal. The final positive ordinal is valid; subsequent attempts stay overflowed. Never wrap, reuse an ordinal or throw into the native callback. The reader accepts this combination only in V29, despite the older COUNTER_OVERFLOW reason already being used for unrelated bounded progress metadata.

Issue a ticket after the existing eligibility guards and before the first native work debit. An exhausted eligible request still consumes an ordinal and reports ADMITTED with BUDGET_EXHAUSTED. Compute the ticket once and pass the detached immutable receipt lexically into all returned envelopes, including early returns inside Capture.build. Factories used outside that call require an explicit bypass/unknown receipt; they cannot allocate an ordinal or invent one from a parent decision. Preserve the original exception and callback result behavior.

The increment/overflow decision is atomic and bounded. Synchronize only the scalar counter or use a CAS loop; do not hold its lock while reading Game/Card state, calling suppliers, capturing disclosures, freezing maps or publishing. Concurrent captures get distinct tickets, but their record publication need not follow ordinal order. Repeated references to the same frozen view retain the same ticket. Cancellation is still governed by the existing open predicate and publication retirement: a ticket is not permission to publish after closure. Closed capture never reopens state. Already charged work is not refunded.

## Optional hash policy extension

Before enablement, add a separately versioned HASH_THINNED_V1 policy with denominator 4, a declared nonnegative signed-long observation seed and SAMPLED_OUT status. Use the experiment's fixed 64-bit mix over the per-player ordinal and seed/player key, with explicit Java overflow semantics and fixed vectors in both languages. The owning chooser ID supplies the declared within-game player key. Reproducibility requires the same scoped player key, seed and eligible-call sequence; do not promise equivalence across different ID allocation or concurrent call schedules.

Use an observation-only seed, explicitly configured and recorded, independent of engine RNG. Do not seed from wall time, engine RNG state, card identity, hidden state, chosen result or eventual outcome. Never advance engine RNG. Reject unknown policies/denominators/seeds before starting a run, including when no trace output is enabled; do not silently fall back. Define CLI and non-CLI setup together in the enablement commit, rather than exposing a partially effective option now.

An omitted hash ticket retains its positive ordinal and declared configuration, status SAMPLED_OUT, input status UNAVAILABLE, null policy projection, empty unavailable diagnostics and exactly CONTEXT/ADMISSION_SAMPLED. Skip state traversal and all input suppliers, charge no capture work, and preserve the original callback/lifecycle. Existing disclosure ingestion is not sampled or reconstructed later. A selected ticket remains ADMITTED even when the same budget cannot fund capture. Test the order of sampling versus budget checks explicitly. The denominator is a deterministic thinning rule, not an unbiased inclusion probability for captured examples.

All eligible families share the same ordinal. Do not stratify by family or outcome, use a stride, or add a first-N exception without selecting a new policy version. A changed policy invalidates old fixed-vector assumptions. Experimental traces do not become ordinary-policy traces just because their outcomes match.

## Reader, export and audit contract

Validate V29 admission strictly before returning from unavailable-context validation. Reject bool-as-int versions/ordinals/seeds, extra/missing fields, unknown policies, policy/denominator mismatches, nonpositive/out-of-range ordinals, and contradictory input-status/omission combinations. Initially reject hash metadata and ADMISSION_SAMPLED in all production profiles. When the optional policy is enabled, accept it only in V29+ with its exact admission contract; legacy profiles and experimental reasons remain unchanged.

Admission belongs beside existing non-feature metadata in exported records/examples. Only the existing policy object supplies player-view features. Preserve unavailable versus absent legacy contexts and all trainingReady=false behavior. Extend coverage reporting with observed admission statuses/configurations using the existing bounded/spooled audit architecture. Old fixtures should preserve existing artifact content where no new metadata exists; new artifacts must explicitly name any schema change. Full and filtered exports agree except their already documented source-record counts.

Do not reject gaps or nonmonotonic publication order as lost source records. Parent capture can produce a view that is never published; an exception can leave admission unknown; a timeout can cut off an admitted capture. A frozen view can appear more than once. Observed ordinal counts are neither callback totals nor exact attempt/delivery rates. Preserve existing source/collector scopes separately; do not manufacture a production attempt ledger from the development shutdown sidecar. If future exact admission-attempt totals are needed, design their bounded closure/overflow schema separately.

## Verification and gap ownership

The prefix implementation must exercise the actual gate and returned envelopes, not only serialize a hand-built receipt. Test real first/last/overflow tickets, two-player isolation, parent/nested shared ownership, disclosure work without ordinal consumption, skipped guards with hostile suppliers, original capture exceptions, fresh-game/copy/controller boundaries, and blocked/late capture closure. Check unique concurrent tickets without assuming publication order. Assert the original native callbacks/results and RNG stream are unchanged. No unbounded map, retained ticket history or event subscription is required by the counter helper.

Reader tests must cover legacy exact shapes, unavailable early returns, malformed admission/status/reason combinations, repeated frozen views and explicit unknown failure admission. Native validation must include damage/X, repeated choices, Commander and a short timeout with full/filtered parity, source/collector health and initial shuffles before GAME_STARTED when present. Keep missing-start early cutoffs explicit. Retain rejected traces; a new metadata version must not weaken ordinary duplicate-outcome validation.

Design review assigns these findings before implementation:

| Finding | Owner / disposition |
| --- | --- |
| No workload-independent availability winner; native prototype overhead not isolated | COV-008C retains prefix default; OPT-005 gate before optional hash enablement |
| Disclosure allocation can precede the first context and shares its budget | COV-008C setup must freeze configuration before any State allocation; preserve intake behavior |
| Controller configuration is not enforced by the collector's incomplete-accounting flag | COV-008C must audit/test actual identity admission and refuse unsupported extra configured shares |
| Exceptions and unpublished parent views can lose a visible gate receipt | COV-008C represents UNESTABLISHED and observed-only counts; exact attempt-ledger extensions require a separate contract |
| Extra metadata and locks can change costs without changing represented policy | OPT-005 measures production V29 against the same V28 information and reports metadata cost separately |
| Finite allowance, incomplete domains/history and permission limitations remain | COV-004 readiness and COV-008 optional-policy work stay open; no complete-example claim |

No executable implementation is included in this design commit. Prefix V29 implementation is the next increment, followed by its gap review and the measurement gate above.
