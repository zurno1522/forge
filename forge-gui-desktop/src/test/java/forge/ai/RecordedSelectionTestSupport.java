package forge.ai;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;

/** Deterministic selections for engine/collector integration tests, without changing AI policy. */
public final class RecordedSelectionTestSupport {
    private RecordedSelectionTestSupport() { }

    public static void select(final Player player, final SpellAbility... abilities) {
        final var game = player.getGame();
        game.setRecordAiDecisions(true);
        for (final SpellAbility ability : abilities) { ability.setActivatingPlayer(player); }
        ((PlayerControllerAi) player.getController()).getAi().publishDecision(
                new AiDecisionRecorder(game, player, game.nextAiDecisionSequence()), List.of(abilities), "COMPLETED", null);
    }
}
