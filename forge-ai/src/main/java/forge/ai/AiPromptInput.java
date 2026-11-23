package forge.ai;

import forge.game.player.Player;

import java.util.List;

/** Temporary audited request arguments. Only AiInputContext projects these into frozen JSON. */
record AiPromptInput(Kind kind, int minimum, int maximum, List<Integer> values, boolean optional,
        Player relatedPlayer) {
    enum Kind { NUMBER_RANGE, NUMBER_OPTIONS, SINGLE_ENTITY }

    static AiPromptInput range(final int minimum, final int maximum) {
        return new AiPromptInput(Kind.NUMBER_RANGE, minimum, maximum, null, false, null);
    }

    static AiPromptInput numbers(final List<Integer> values, final Player relatedPlayer) {
        return new AiPromptInput(Kind.NUMBER_OPTIONS, 0, 0, values, false, relatedPlayer);
    }

    static AiPromptInput single(final boolean optional, final Player relatedPlayer) {
        return new AiPromptInput(Kind.SINGLE_ENTITY, optional ? 0 : 1, 1, null, optional, relatedPlayer);
    }

}
