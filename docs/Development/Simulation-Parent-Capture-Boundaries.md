# COV-006/HARD-001 follow-up: parent capture and publication boundaries

The assessment-context increment fixes one pre-publication getter/monitor edge. This inventory defines the remaining scope before mode integration. The audited baseline is `84c3b5329c6`; no runtime implementation or new test result is claimed by this design increment.

## Observed ownership

`AiController.getPlayableSpellAbilities` evaluates candidates on a worker. Its timeout handler calls `decision.interrupted(...)` before cancellation/join, and final decision publication later calls `recorder.finish(...)`. Both need the parent monitor. Existing observation getters or iterable traversal under that monitor can therefore delay the timeout handler itself, beyond the intended AI deadline.

The source does not have a separate once-only parent-publication claim. `publishDecision` calls `finish`, registers selected actions and emits the event. A runtime exception in that observation sequence can reach the chooser's policy exception handler. Preparing multiple action registrations can also fail after an earlier registration. These must be reviewed as publication/failure boundaries, rather than assuming a transaction or delivery acknowledgement already exists.

| Boundary | Existing capture work | Required handling |
| --- | --- | --- |
| `beginAssessment` | Root/ID reads under parent monitor before assigning an assessment. | Read outside; admit only while open. Do not mint a late assessment. |
| `detail` | Root/ID reads under parent monitor before recording a reason. | Read outside; verify originating assessment/closure before applying metadata. Preserve generic/private reason handling. |
| `evaluated` | Card/ability action snapshot and root lookup under parent monitor. | Build owned metadata outside; recheck originating assessment/closure before appending or assigning its index. Never attach stale work to a replacement assessment. |
| `lands` | Iterable traversal and card/action getters under parent monitor. | Traverse once outside; preserve order and existing candidate fields. Do not admit a late or partially assembled batch after closure. No new cap or legal-domain claim. |
| `landSearchCandidates` | Offered ability/card reads interleaved with candidate tagging under parent monitor. | Capture diagnostic identities outside, then update owned rows under the monitor. Preserve duplicate matching and existing last-match behavior. |
| `finish` / `publishDecision` | Selected-action projection, record freezing, registration and emission. | Separate capture closure from selected metadata, registration and emission. Preserve policy result and visible observation failures; do not invent a complete parent record when projection failed. |
| `AiActionTracker.selected` / parent target provenance | Per-action preparation may traverse engine target state while tracker/parent monitors are held, and can register a partial prefix. | Prepare all observation metadata before committing registrations. Define cleanup for failure at each publication stage without modifying engine targets or replaying policy. |

## Contract for the capture increment

Move engine graph reads and offered iterable traversal out of the parent monitor. Keep only owned scalar/map/list mutation and closure checks inside. Parent closure or assessment replacement can win while capture is blocked; late work must not change the frozen parent, another assessment or observation health after authoritative closure. No engine-wide lock is introduced.

A runtime observation failure during active capture must remain visible through existing health. These metadata helpers must not replace an otherwise valid AI result or stop subsequent original evaluation merely because tracing failed. Preserve genuine AI deadline control: `AITimeoutTracker.Expired` is not flattened into an ordinary observation error. Fatal `Error` behavior is not expanded by this increment. Do not infer legality, missing candidate counts or callback outcomes from a missing metadata row; parent candidate coverage remains partial.

Tests must force closure while each affected getter/iterator is blocked, release both normally and exceptionally where meaningful, and assert timely closure, immutable output and no late association. Test live failures separately so closure suppression cannot hide active capture faults. Healthy real selections must keep their selected ability/land, diagnostic ordering and existing field meanings. These tests exercise capture units and their timeout interaction, rather than merely reflecting method implementation.

## Contract for the publication increment

An AI's already selected result must survive a runtime observation failure without rerunning its policy. Normal publication must retain existing decision/action IDs and engine event order. No execution should acquire references from a partially prepared action-registration batch. Parent projection, provenance preparation, registration and event emission have distinct failure outcomes; define them before moving locks or adding cleanup.

Event publication returning normally is not collector delivery acknowledgement. If projection cannot produce a valid parent, report health and preserve missing-parent uncertainty. If a valid parent exists but action-link preparation fails, retain available parent evidence without claiming registered execution linkage. Registration/emission failure handling must leave no orphan aliases attributable to an uncommitted batch. Preserve actual execution lifetimes if any engine action has already started; never restore targets or undo gameplay to repair diagnostics.

Do not add new allocator IDs or a persistent registry. Reuse owned batch identity and existing parent/action IDs where appropriate. Keep this hardening separate from copy/writer optimization and from family input permission. A new source denominator for whole-parent capture/delivery requires a separate explicit accounting capability; existing retained-parent ledgers cannot supply it.

## Execution order and review gates

1. Complete the pre-publication assessment/candidate/land capture increment and review its active-failure, closure and assessment-identity cases.
2. Complete selected-action/provenance preparation and publication failure handling with its own contract-focused tests and commit. Check actual call sites rather than assuming ownership from method names.
3. Recheck the full parent boundary inventory, then continue mode operation/selector inputs and remaining COV-006 family arguments.

COV-006 and the earlier HARD-001 lifecycle/failure contract own these fixes; they precede modes. COV-007 still owns inner payment/X seams, COV-008 sustained availability, and OPT-005 updated full-path/peak measurement. No runtime optimization or budget increase is authorized by this inventory.

The subsequent [parent metadata capture increment](Simulation-Parent-Metadata-Hardening.md) implements the first five table entries. Publication/registration remains the immediate next increment; the source table above describes its audited baseline.
