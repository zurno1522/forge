package forge.ai;

import forge.game.player.Player;
import forge.game.player.ZoneChangeSelectionRequest;
import forge.game.zone.ZoneType;

import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;

/** Temporary audited request arguments. Only AiInputContext projects these into frozen JSON. */
record AiPromptInput(Kind kind, int minimum, int maximum, List<Integer> values, boolean optional,
        Player relatedPlayer, boolean allowRepeat, AiEntitySelectionHistory.Input sequence, ZoneRequest zoneRequest, ZoneOperation zoneOperation, AiZoneSelectionHistory.Input zoneProgress, AiSpellSelectionHistory.Input spellProgress) {
    AiPromptInput(Kind kind, int minimum, int maximum, List<Integer> values, boolean optional, Player relatedPlayer,
            boolean allowRepeat, AiEntitySelectionHistory.Input sequence, ZoneRequest zoneRequest, ZoneOperation zoneOperation,
            AiZoneSelectionHistory.Input zoneProgress) {
        this(kind, minimum, maximum, values, optional, relatedPlayer, allowRepeat, sequence, zoneRequest, zoneOperation, zoneProgress, null);
    }
    enum Kind { NUMBER_RANGE, NUMBER_OPTIONS, SINGLE_ENTITY, MODE_BOUNDS, ENTITY_BOUNDS, ENTITY_PICK_SEQUENCE, ZONE_CHANGE_REQUEST, ZONE_CHANGE_OPERATION_REQUEST, ZONE_PICK_PROGRESS, SPELL_ABILITY_OPERATION_REQUEST, SPELL_ABILITY_REQUEST, SPELL_ABILITY_PICK_PROGRESS, UNSUPPORTED_REQUEST }

    /** Reads only the receipt captured by this entry's visible options supplier. */
    record SpellRequest(AtomicReference<AiSpellRequestInput> input) implements Supplier<AiPromptInput> {
        @Override public AiPromptInput get() {
            final var received = input.get();
            return new AiPromptInput(received != null && received.supported() ? Kind.SPELL_ABILITY_REQUEST : Kind.UNSUPPORTED_REQUEST,
                    0, 0, null, false, received == null ? null : received.affectedPlayer(), false, null, null, null, null);
        }
    }

    /** Received do-while request, never a legal minimum/maximum result bound. */
    record SpellOperation(int requestedSelections) implements Supplier<AiPromptInput> {
        @Override public AiPromptInput get() {
            return new AiPromptInput(Kind.SPELL_ABILITY_OPERATION_REQUEST, requestedSelections, 0, null, false,
                    null, false, null, null, null, null);
        }
    }

    /** Exact audited-operation arguments, without claiming a legal pick bound. */
    record ZoneOperation(ZoneChangeSelectionRequest request) implements Supplier<AiPromptInput> {
        @Override public AiPromptInput get() {
            return new AiPromptInput(Kind.ZONE_CHANGE_OPERATION_REQUEST, 0, 0, null, false,
                    request.decider(), false, null, null, this, null);
        }
    }

    /** Received arguments, not a prediction of movement or a grant of the offered cards. */
    record ZoneRequest(ZoneType destination, List<ZoneType> origins, boolean optional, Player decider) implements Supplier<AiPromptInput> {
        @Override public AiPromptInput get() {
            return new AiPromptInput(Kind.ZONE_CHANGE_REQUEST, optional ? 0 : 1, 1, null, optional, decider, false, null, this, null, null);
        }
    }

    /** Audited received primitives, identifiable without evaluating a hypothetical supplier. */
    record ModeBounds(int minimum, int maximum, boolean allowRepeat) implements Supplier<AiPromptInput> {
        @Override public AiPromptInput get() {
            return new AiPromptInput(Kind.MODE_BOUNDS, minimum, maximum, null, false, null, allowRepeat, null, null, null, null);
        }
    }

    /** Exact received total bounds and role; no pool or prior-selection permission. */
    record EntityBounds(int minimum, int maximum, Player relatedPlayer) implements Supplier<AiPromptInput> {
        @Override public AiPromptInput get() {
            return new AiPromptInput(Kind.ENTITY_BOUNDS, minimum, maximum, null, false, relatedPlayer, false, null, null, null, null);
        }
    }

    static AiPromptInput range(final int minimum, final int maximum) {
        return new AiPromptInput(Kind.NUMBER_RANGE, minimum, maximum, null, false, null, false, null, null, null, null);
    }

    static AiPromptInput numbers(final List<Integer> values, final Player relatedPlayer) {
        return new AiPromptInput(Kind.NUMBER_OPTIONS, 0, 0, values, false, relatedPlayer, false, null, null, null, null);
    }

    static AiPromptInput single(final boolean optional, final Player relatedPlayer) {
        return new AiPromptInput(Kind.SINGLE_ENTITY, optional ? 0 : 1, 1, null, optional, relatedPlayer, false, null, null, null, null);
    }

    static AiPromptInput sequence(final AiEntitySelectionHistory.Input input) {
        return new AiPromptInput(Kind.ENTITY_PICK_SEQUENCE, input.optional() ? 0 : 1, 1, null,
                input.optional(), input.relatedPlayer(), false, input, null, null, null);
    }

    static AiPromptInput zoneProgress(final AiZoneSelectionHistory.Input input, final ZoneRequest received) {
        return new AiPromptInput(Kind.ZONE_PICK_PROGRESS, input.optional() ? 0 : 1, 1, null,
                input.optional(), received.decider(), false, null, received, null, input);
    }

}
