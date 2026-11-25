# Engine-owned zone selection: source findings for the next contract

The completed V8 request describes a single invocation. A separate engine-owned sequence must distinguish callback attempts, non-null returns, retained selections and actual movements. This source audit does not add runtime permission.

`ChangeZoneEffect.changeHiddenOriginResolve` has three AI-reachable repeated selector branches: `SameNameAsEach`, `EACH`, and the ordinary one-at-a-time loop. Its multi-select branch is AI-excluding. Heist and Draft also repeat this callback, with different acceptance/movement semantics; Learn makes one request and does not establish a sequence. These additional owners must remain explicit rather than inheriting a grouping rule from the shared selector API.

Ordinary-loop facts:

- The loop's `changeNum` is a received iteration limit, not an all-purpose legal successful-return maximum/minimum. A null return can break, or confirmation refusal can decrement the loop index and retry. UI progress text and the loop index cannot identify an attempt uniquely.
- Random and predefined branches bypass the controller selector. They must not produce invented callback rows.
- Non-null callbacks are removed from the working offer, removed from delayed reveal, and then added to `chosenCards`. `CardCollection` inherits deduplicating `FCollection.add`; a non-null return need not increase retained selection count.
- CMC, power and type totals change after the original append. An unsatisfied type total can clear the whole pile after selection. Later `Exactly` can bypass application, and destination alternatives, pile shuffling, ordering and movement occur afterward.
- The source owner, decider, actual controller receiver and ability activator are separate roles. Temporary search control is installed before selection and removed later. Preserve every original virtual controller lookup and dispatch.
- Each inner callback still delivers its own delayed reveal before its selector snapshot. A new outer operation must not deliver reveal early or extend the existing prompt disclosure lifetime across picks.

A first implementation should cover the ordinary branch with an explicit engine/controller callback lifetime. Keep game-to-AI imports, IDs, diagnostics, learning terms and serializer types out of the game-facing contract. Reuse the existing callback-based AI recorder, whose original-result, exception and closure rules already have regression coverage. A private engine selection context can retain the existing mutable locals without exposing them in the public observation API. The original dispatches, removal/append order and postselection code remain unchanged.

Capture must use the actual operation admission, not a nearest active record lookup. Private, callback-scoped bounded receipts should reuse the V7 safety rules: at most 64, exact object/incarnation, fresh current public association, null for unresolved identity, no stale completion after closure and no blocked getter under a parent monitor. Direct/unaudited callbacks keep history unknown. The existing diagnostic parent link can carry ownership; no persistent registry or new sequence ID is needed.

Use a monotonic long attempt ordinal independent of the retrying loop index. Non-null return count and retained-card count are distinct. A final retained set can be smaller than earlier returns; the V7 outer-list count check must not be copied to this family. If a counter cannot remain exact, explicitly mark it unavailable rather than wrapping. Record original counts and outcomes without repairing policy, deduplicating the return history or claiming that accepted cards moved.

Do not widen the V7 entity-sequence component to zone callbacks. Define a separate component and permission only after the engine session contract is concrete. Request bounds, prior returned references and retained counts require independently named semantics. Outer input precedes the loop/reveal, each child has its own entry after any reveal, and global history remains incomplete. Hidden/off/copy/abandoned sessions must not inspect offers, receipts or new suppliers. A new named accounting boundary and conditional inventory extension need their own compatibility tests; existing inventories remain frozen.

Validation needs actual engine retries and cancellation, deduplication, zero/no-callback branches, discarded piles, delegated/control changes, nested lifetimes, mid-operation capture failure, closure during blocked receipt reads, exact source/collector reconciliation and original exception identity. Preserve full/filtered parity and shuffles before GAME_STARTED. Other branches, Heist and Draft remain separate sub-increments, not silently covered paths. Spell-ability choices remain the following COV-006 family; payment/X COV-007, availability COV-008 and expanded cost/retention OPT-005.

## First implementation boundary

Add a generic game/controller operation wrapper with a no-op default:

```text
withZoneChangeSelections(received request descriptor,
                        Function<ZoneChangeSelectionObserver, CardCollection> callback)
```

The default invokes the callback once with a no-op observer. The AI override uses the existing `AiChoiceRecorder` operation lifetime and its exact own admission hook, then invokes the callback once with an owned bounded observer (or no-op when unsupported). The callback result/exception stays original. This avoids a second manual begin/complete/error/close implementation of recorder publication and accounting.

The public descriptor names the ordinary branch, received destination/origins, source owner, decider, ability and requested iteration count. It holds engine references without inspecting collections at construction. The observer reports only engine facts at actual dispatch/return boundaries; it does not choose cards or transform results. The AI implementation owns serialization, permission, bounded history and observation-failure handling.

Move only the ordinary branch's selection loop into a private engine helper, taking one private context of already computed values plus the observer. Preserve initial amount-calculation order and pass the original `chosenCards` instance. The CMC/power/type totals are local to that branch and are not used after its selection block. This private context is not part of the public controller API or a model input. Do not recalculate source/amount getters, duplicate the chosen collection, hoist virtual controller lookups or move the later shuffle, ordering, destination and application code.

Bracket only actual virtual selector dispatches. Report null returns before the existing confirmation/retry path. For non-null returns, preserve original offer removal, reveal removal and `chosenCards.add` ordering, then report the actual returned object and current retained count before later amount updates. Return history preserves duplicates while the engine's retained collection keeps its original deduplication. Complete the operation after existing type-total clearing and before later pile shuffling/reordering/destination/application. A final retained count describes retained choices, not movements.

The observer needs a monotonic long attempt ordinal independent of the retrying loop index, a prior non-null-return count, and the engine's current retained count. A pending attempt on error/closure is explicitly incomplete. Keep these meanings distinct in the later wire contract; the V7 final-list cardinality check does not apply. Prefix/reference and scope proofs reuse V7 rules without granting a zone callback the V7 entity-sequence component. No new persistent sequence ID or registry is needed.

Implementation gates: one original callback; default non-AI/off/copy no inspection; original virtual dispatch; exact own admission and nested restoration; failed intermediate capture cannot borrow an earlier owner; live faults visible and late faults discarded; no fresh engine getters under parent monitors; original callback exception remains original. The first scope leaves named/EACH/Heist/Draft grouping unknown. The next versioned wire permission and accounting extension must be specified before runtime integration. This is an implementation design and source inventory, not a shipped API or permission.

Source hashes: [inventory](assessments/COV-006-zone-sequence/boundary-inventory.json). This design/source item validates call owners, deduplicating collection behavior, retry/clearing boundaries and amount-local lifetimes. It adds no runtime test or new producer profile. The remaining gate is a precise wire/accounting contract for the first callback-scoped operation; runtime integration follows that contract. No current/prior V8 defect was established by this audit.
