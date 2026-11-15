# Identifier widths

Engine identifiers and trace event indices use signed 64-bit `long` values. The shared `IIdentifiable` and `TrackableObject` contracts also return `long`, so card/player views, ability views, snapshots, UI lookups, and network references retain the same identity without narrowing conversions. Gameplay quantities such as mana amounts, damage, counters, turns, and list indices retain their existing types.

Process-wide game, spell ability, stack instance, static ability, replacement effect, and trigger allocators use atomic long counters. This prevents both 32-bit rollover in long simulation batches and duplicate allocations from concurrent simulation work. Existing LKI/copy identity rules are retained: LKI can preserve an ID, while a new ability instance receives a fresh ID. The existing explicit trigger reset remains an explicit reset rather than an allocation.

Card IDs and hidden-card IDs are allocated within each game using long counters. The decision sequence was already a per-game long. Decision and action IDs remain strings composed from player/decision sequence and selected-action ordinal. Use run ID plus game number with those diagnostic IDs; event index establishes ordering rather than action identity.

Every identity lookup must use the full ID. Hash codes remain 32-bit hashes, and collisions are permitted; equality compares the full identifier (and card state where applicable). Do not compare identity by hash alone, truncate with an int cast, or box an ID as Integer when looking up a Long-keyed map. Scripted `CardUID_`/`PlayerUID_` restrictions and saved game ID references parse long values.

AI deadline and simulation-audit lookups use a `(long gameId, long playerId)` session key. Packing the pair into one long with a 32-bit shift would discard high bits after widening.

Multiplayer delta keys are typed records containing object type, full long ID, and a separate card-state ordinal. The earlier packed int key retained only 28 ID bits and decoded them as a signed value. Multiplying a card ID by 16 to pack its state is no longer necessary. Delta references, combat references, tracker lookups, and request/reply correlation also carry long IDs.

This changes the Java API and serialized multiplayer representation. Login and affected serialization versions are advanced: multiplayer peers must use matching builds. A new build must not accept an old peer and silently narrow IDs. Ordinary textual saved-game references and older JSON traces with small integer IDs remain readable.

JSON engine identifiers and event indices remain integer JSON numbers; the Python exporter retains them exactly. Consumers must use integer types capable of holding the full value. In JavaScript, use a lossless JSON/integer strategy once identifiers exceed `Number.MAX_SAFE_INTEGER`; the composed decision/action strings already avoid floating-point conversion.

The maximum positive long is 9,223,372,036,854,775,807. This migration increases capacity; it does not provide literally unlimited execution or make infinite combos terminate. Simulation execution limits still apply.
