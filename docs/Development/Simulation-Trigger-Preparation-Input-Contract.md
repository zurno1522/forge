# COV-006: trigger-preparation operation inputs

The V16 games contain trigger-preparation STARTED records without input contexts. Source review confirms that `PlayerControllerAi.prepareSingleSa` already wraps the original preparation callback before Charm setup, targeting-player assignment/delegation or trigger AI. COV-005 classified this seam as an operation. Its current absence is an integration gap, not evidence that the input must be reconstructed from a result or another record.

## Producer boundary and received directive

Retain the existing TRIGGER_PREPARATION_RESULT type, TRIGGER_PREPARATION boundary/stage, prior/final target-plan diagnostics, callback acceptance, linkage and source accounting cell. Capture the own OPERATION entry and current permitted view at BEFORE_OPERATION, before original preparation. A result-named lifecycle can describe an operation without pretending it is an inner selector. Preserve the actual existing rules/planning basis at this entry; hypothetical callers retain their planning scope. Descendant selectors keep their own entries and actual actors. Do not replay policy, clear/restore a plan for observation, change delegated targeting or equate acceptance with eventual resolution.

Forward the actual `isMandatory` argument from the controller to the recorder. Extend V17's independent receivedParameters permission with TRIGGER_REQUIREMENT and exactly one boolean `mandatory`, captured before callback execution. This is the received preparation directive, not a promise that preparation succeeds. A compatibility helper without that argument records unknown parameters, never inferred false or true. It still captures the actual operation entry/view.

Use the existing schema-1 callbackParameters diagnostic shape with COMPLETE kind TRIGGER_REQUIREMENT, values `{mandatory:boolean}` and null reason; unknown directives use the existing UNSUPPORTED/null representation. Only this own operation may claim the new kind, starting at V17. The final parameter carrier permits the received boolean during planning while preserving PLANNING_WORKING_STATE. Other kinds keep their prior family/profile permissions. Use explicit family whitelists so adding this kind cannot automatically authorize it at entity/numeric boundaries.

Generalize the AI-private parameter observation helper to accept an explicit audited timing/stage; keep existing selector callers' behavior unchanged. No engine API, new allocator, source accounting slot, parent history, arbitrary supplier or pool grant is introduced. Parameter work remains one component unit plus one boolean field under the unchanged game allowance/shares. Hidden/off/copy/closed admission reads no new entry/view/parameter getters; live failures remain visible and late work cannot block closure or revive records.

## Consumer and compatibility

Require V17, the own entry, BEFORE_OPERATION timing and the recorded preparation stage for new trigger inputs. Require matching directive diagnostics/projection and an exact boolean. Parent/nested/search/other callback contexts cannot claim this capability. Preserve legacy trigger records' absent input/entry and old prior/final target-plan shapes. Do not add the new boundary to frozen V1/V2 inventories: report a conditional entry-inventory extension V7. The accounting schema/table remains unchanged, and all old export artifacts remain byte-identical.

Record overlapping outer preparation and inner selectors without calling them unique gameplay decisions. The entry precedes preparation mutation; completion retains the original accepted flag and actual final target plan. No complete legal target set, trigger semantics, static ability knowledge or training readiness follows.

## Gates and ownership

Test actual simultaneous trigger preparation and the controller's mandatory forwarding, pre-callback frozen life/phase/target state, optional/mandatory directives, false/error outcomes, nested target/mode/parameter stages, delegated actors, inherited planning, original callback/result/error identity, masking and bypass, work accounting, live/blocked getter closure and source/collector reconciliation. Validate actual emitted examples and full/filtered games, legacy bytes, strict profile/stage/timing/directive/family/owner refusal and shuffles before GAME_STARTED.

Review current/prior defects before proceeding. Once this gap is closed, COV-006 attachment/attacker role collections follow. COV-004 retains full domain/ability/history knowledge; COV-007 actual cost/payment/X probes; COV-008 availability; OPT-005 preparation entry/view/carrier costs and deadline-sensitive validation. All examples remain trainingReady=false.
