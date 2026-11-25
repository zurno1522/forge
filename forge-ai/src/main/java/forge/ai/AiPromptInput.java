package forge.ai;

import forge.game.player.Player;

import java.util.List;
import java.util.function.Supplier;

/** Temporary audited request arguments. Only AiInputContext projects these into frozen JSON. */
record AiPromptInput(Kind kind, int minimum, int maximum, List<Integer> values, boolean optional,
        Player relatedPlayer, boolean allowRepeat) {
    enum Kind { NUMBER_RANGE, NUMBER_OPTIONS, SINGLE_ENTITY, MODE_BOUNDS, ENTITY_BOUNDS }

    /** Audited received primitives, identifiable without evaluating a hypothetical supplier. */
    record ModeBounds(int minimum, int maximum, boolean allowRepeat) implements Supplier<AiPromptInput> {
        @Override public AiPromptInput get() {
            return new AiPromptInput(Kind.MODE_BOUNDS, minimum, maximum, null, false, null, allowRepeat);
        }
    }

    /** Exact received total bounds and role; no pool or prior-selection permission. */
    record EntityBounds(int minimum, int maximum, Player relatedPlayer) implements Supplier<AiPromptInput> {
        @Override public AiPromptInput get() {
            return new AiPromptInput(Kind.ENTITY_BOUNDS, minimum, maximum, null, false, relatedPlayer, false);
        }
    }

    static AiPromptInput range(final int minimum, final int maximum) {
        return new AiPromptInput(Kind.NUMBER_RANGE, minimum, maximum, null, false, null, false);
    }

    static AiPromptInput numbers(final List<Integer> values, final Player relatedPlayer) {
        return new AiPromptInput(Kind.NUMBER_OPTIONS, 0, 0, values, false, relatedPlayer, false);
    }

    static AiPromptInput single(final boolean optional, final Player relatedPlayer) {
        return new AiPromptInput(Kind.SINGLE_ENTITY, optional ? 0 : 1, 1, null, optional, relatedPlayer, false);
    }

}
