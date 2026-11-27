# COV-008C: V29 prefix admission implementation

V29 adds detached admission receipts beside the unchanged V28 policy projection. Prefix remains the only enabled production policy. The fixed 256,000-unit game allowance, original-player division, per-view caps and disclosure charging remain unchanged. Hash sampling, reservation and a default budget increase are not enabled.

## Implementation and boundaries

AiInputAdmission issues positive signed-long ordinals using a bounded CAS counter in each AiInputContext.State. No registry, retained ticket history, engine RNG, engine event or shutdown hook is added. All eligible views for that original player/Game share the counter. Disclosure intake can spend work before the first capture without consuming a capture ordinal.

The eligibility guards run before issuing a ticket. A selected ticket remains ADMITTED on budget exhaustion, interrupted traversal and early unavailable returns. The final Long.MAX_VALUE ticket is valid; later requests return an explicit COUNTER_OVERFLOW receipt without charging or invoking input suppliers. Hidden/unsupported/closed bypasses have no ordinal. Enclosing CAPTURE_FAILED handling reports UNESTABLISHED without changing the original exception or borrowing another ticket. Admission metadata never enters policy features.

The trace adapter checks prefix setup before enabling recording. Allocation is bound to the controller created for the original native player, its owning Game and the exact receiving Player. Replacement controllers, foreign receiving players and copied games cannot allocate another State through capture, disclosure intake or prompt setup. Ordinary original-controller callers retain prefix behavior without requiring a simulation command. No AI configuration field or registry is added to Game.

Only the scalar counter is synchronized through CAS. Capture, suppliers, engine getters, freezing and publication run outside it. Closure retains the existing open checks and parent publication retirement. A consumed ordinal is not a delivery acknowledgement; concurrent publication can be reordered, and failed/unpublished captures can leave gaps. Reusing a frozen view reuses its receipt.

The ordinary reader accepts the exact V29 envelope and admission contract, including unavailable early returns, while preserving V1–V28 shapes. It rejects hash configuration and both development omission reasons. Coverage adds an explicitly versioned observed-V29-reference audit only when V29 is present. Repeated references count repeatedly; legacy/absent contexts remain outside that denominator with unknown admission. Existing source/collector scopes are not reinterpreted as a new attempt ledger. All exports remain trainingReady=false.

## Verification

Meaningful producer tests exercise actual first/last/overflow gates, exhausted and mid-capture unavailable returns, disclosure charging, player/Game isolation, replacement and foreign-controller refusal, unchanged features/RNG, original exception identity, concurrent unique tickets and blocked-supplier closure. Existing parent/nested integration now checks their shared ordinal sequence; optional capture failure checks unknown admission on the actual emitted start. Copy bypass tests allocate their control State before marking the Game as a copy, and verify the original body still runs once without additional work.

Review exposed an additional prompt-disclosure allocation path for replacement controllers; it now shares the ownership guard. The first receiving-player guard was also insufficient: assigning a valid controller to a foreign Game's Player could borrow its original budget. The added regression fails against that first compiled candidate with expected BYPASSED / actual ADMITTED, then passes after checking exact receiving identity. These fixes belong to the current COV-008C ownership requirement and are included before advancing.

The [native compatibility runner](../../tools/decision-records/input-context-benchmark/run-prefix-compatibility.py) compiles the four changed V28 classes from 555c3fbbb35 into an isolated classpath. GameAction must match the reference; production class files remain unchanged. Both versions use ordinary prefix capture, with the development launcher used solely for detached RNG snapshots. No experimental gate is installed. Damage/X, repeated choices, regular four-player Commander and a one-second cutoff each run under both versions. Full/filtered streams pass the ordinary reader. Normally completed pairs must match ordered engine events, native results, represented inputs, diagnostic provenance, delivery counts and RNG state/call counts after removing only declared envelope/admission metadata. Timeouts remain censored.

Six legacy fixtures covering absent contexts, typed prompts, bindings, modes, payments and Damage produce 41 byte-identical export artifacts against the committed V28 reader. V29 reader tests also reject malformed receipt fields/status combinations, preserve the last signed long, retain overlapping/reordered frozen references and prohibit new metadata/reasons in legacy profiles. A semantic-comparison regression proves that represented parameters, native results and unavailable/failure delivery counts remain in the native comparison.

All 397 targeted Java cases pass, including thirteen actual admission cases across ten new methods. All 366 Python tests pass, including eight new admission/comparison methods. The final native cohort has six NORMAL games (three equal V28/V29 pairs) and two valid censored TIMEOUT games. Every full/filtered pair validates without observation failures, source scopes are complete and initial shuffles retain their order before GAME_STARTED. Both startup timeouts emit GAME_STARTED but contain no input contexts or admission audit; they establish no exercised-capture coverage. Eight earlier native runs of the first ownership candidate are preliminary, not final-binary validation or cost measurements.

Evidence: [checks](assessments/COV-008-prefix-v29/checks.json), [native summary](assessments/COV-008-prefix-v29/native-summary.json), [full native aggregate reports](assessments/COV-008-prefix-v29/native-results.json.gz), [commands/source/class hashes](assessments/COV-008-prefix-v29/native-metadata.json), [actual native choice messages](assessments/COV-008-prefix-v29/native-choice-messages.jsonl.gz), [legacy artifact hashes](assessments/COV-008-prefix-v29/legacy-exports.json), [Java results](assessments/COV-008-prefix-v29/java-tests.xml.gz), [foreign-receiver fail-before result](assessments/COV-008-prefix-v29/foreign-receiver-before.xml.gz) and [final source freeze](assessments/COV-008-prefix-v29/source-freeze.json).

## Gap review and next ownership

| Finding | Owner / disposition |
| --- | --- |
| Replacement/prompt and foreign receiving-player allocation could borrow or create a share | COV-008C: fixed and covered before completing this increment |
| Experimental V28 overlays could target the changed production gate | COV-008B tools: explicit V28 source guard; archived results retain their original scope |
| Available input, admission and record delivery are distinct denominators | V29 audit names observed references only; source/collector accounting remains separate |
| Enclosing exception handling can lack a gate receipt; unpublished views leave ordinal gaps | Truthful UNESTABLISHED/observed-only reporting; a future exact attempt ledger needs its own closure/overflow contract |
| Metadata and copied-controller ownership fields add cost | OPT-005 next: measure actual V29 CPU/allocation, retention and full writer/collector paths, including disabled/copy and Monte Carlo workloads |
| Hash-four could improve some late views but has workload-dependent losses | COV-008C remains open for a production-shaped candidate and cost-gated optional enablement |
| Partial semantics/domains/history and unbounded-game availability remain | COV-004 readiness and COV-008 policy limits remain; no complete-learning-example claim |

After final native validation and the commit review, proceed to the broader OPT-005 checkpoint. Keep any optimization and optional sampling in separate commits with their own evidence and gap review.
