# COV-006: zone choices with movement between callbacks

Heist and Draft need their own audited operation branches. They cannot inherit the ordinary loop's append-before-receipt position. Reuse the generic game/controller callback and bounded observer, keeping movement, random draws, generated objects, effects and trigger delivery in their original order.

| Branch | Request | Receipt boundary | Terminal collection |
| --- | --- | --- | --- |
| `HEIST` | Received `Num`, Exile from Library; source player is the targeted library owner, decider is the heisting player | Immediately after the original callback and before `canExiledBy` or movement | The original successfully heisted, moved cards |
| `DRAFT` | Received `DraftNum`, None from an empty origin list; source/decider is the drafting player | Immediately after the original callback and before the original move to None | The original drafted collection before its later moves to Hand |

Keep null/error behavior original. Heist's rejection of a non-null card still counts as a non-null callback return, with no retained card or movement. Empty random offers still skip the selector. Draft's shuffled spellbook, substitutions, balanced-card lookup and card creation remain inside the original loop. Do not turn generated options into public policy inputs.

A receipt names the object/incarnation at return, before movement. At the next entry its old incarnation or private/unrepresented identity can resolve to null. Never substitute the moved object or claim an earlier return's name as newly public. Attempt ordinal, loop position, non-null returns, retained count and actual movement remain separate facts. No new persistent registry is needed.

## Exact dispatch proof

Both loops construct origin lists at the individual call. Draft specifically supplies a fresh mutable empty list on every call. Preserve those objects rather than hoisting them into the operation request. Extend the passive observer with a before-selector overload taking the actual per-dispatch origins object. Its default delegates to the existing hook. The base AI observer retains that exact object only until return/retirement and a visible child can claim it once.

For these two branches only, allow an actual origin-list object distinct from the outer descriptor when its bounded ordered enum values equal the received operation request. Keep the old identity requirement for ordinary/named/per-type requests. Perform any comparison only in a visible admitted selector supplier, outside publication locks; recheck ownership after reads and discard late results/faults. Do not inspect origin lists in off, copied, hidden or unsupported callbacks. Mismatched objects, destinations, roles or values cannot acquire progress permission.

## Version and acceptance

V12 adds `HEIST` and `DRAFT` to the branch vocabulary at operation and progress entries. Older profiles reject them even when a policy prompt is unavailable. Existing progress metadata schema 1, request/result fields, fixed accounting boundary and inventory are unchanged. No pool/name/type permission, budget increase or readiness claim follows. Existing V1–V11 export artifacts remain byte-identical.

Use actual engine resolution to test multiple moves, empty offers, non-null rejected returns, generated Draft choices, fresh origin-list identities, unchanged ordering of callback/return/movement/trigger facts, zero iterations and original error identity. Reuse observer lifecycle tests and add exact-list refusal plus closure during a blocked comparison. Validate actual emitted rows through the decoder and use full/filtered game exports where sampled. Explicitly distinguish complete-game samples from resolution excerpts.

After review, spell-ability operation/selector coverage is the next COV-006 family. Caller inventory includes ChooseGeneric, VillainousChoice, Venture, CopySpellAbility, AssignGroup and ActivateAbility, rather than only the first two. Opaque parameter maps and secrecy need independent auditing before granting model inputs. COV-007 owns inner payment/X, COV-008 availability and OPT-005 expanded cost/retention and broader workload sampling.
