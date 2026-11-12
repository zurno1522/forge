package forge.ai;

import com.google.common.eventbus.Subscribe;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AiDecisionRecorderTest extends AITest {
    public static final class Decisions {
        final List<AiDecisionEvent> events = new ArrayList<>();
        @Subscribe public void receive(final AiDecisionEvent event) { events.add(event); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void recordsRealSelectionWithoutChangingChosenLand() {
        for (final boolean enabled : List.of(false, true)) {
            final Game game = initAndCreateGame();
            final Player ai = game.getPlayers().get(1);
            final Card land = addCardToZone("Island", ai, ZoneType.Hand);
            for (final Player player : game.getPlayers()) { fillLibrary(player, 20); }
            final Decisions decisions = new Decisions();
            game.subscribeToEvents(decisions);
            game.setRecordAiDecisions(enabled);
            final List<SpellAbility> selected = ((PlayerControllerAi) ai.getController()).getAi().chooseSpellAbilityToPlay();
            Assert.assertNotNull(selected);
            Assert.assertEquals(selected.get(0).getHostCard(), land);
            Assert.assertEquals(decisions.events.size(), enabled ? 1 : 0);
            if (enabled) {
                final Map<String, Object> data = decisions.events.get(0).data();
                Assert.assertEquals(data.get("selection"), "PLAY");
                Assert.assertEquals(data.get("status"), "COMPLETED");
                Assert.assertTrue(data.get("selectedActions") instanceof List<?>);
                final Map<?, ?> chosen = (Map<?, ?>) ((List<?>) data.get("selectedActions")).get(0);
                Assert.assertEquals(chosen.get("abilityId"), selected.get(0).getId());
            }
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void recordsAssessmentAndPassWithoutClaimingCompleteLegality() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCardToZone("Emrakul, the Aeons Torn", ai, ZoneType.Hand);
        for (final Player player : game.getPlayers()) { fillLibrary(player, 20); }
        game.setRecordAiDecisions(true);
        final Decisions decisions = new Decisions();
        game.subscribeToEvents(decisions);
        final List<SpellAbility> selected = ((PlayerControllerAi) ai.getController()).getAi().chooseSpellAbilityToPlay();
        Assert.assertTrue(selected == null || selected.isEmpty());
        Assert.assertEquals(decisions.events.size(), 1);
        final Map<String, Object> data = decisions.events.get(0).data();
        Assert.assertEquals(data.get("selection"), "PASS_PRIORITY");
        Assert.assertEquals(data.get("candidateCoverage"), "OBSERVED_EVALUATIONS_NOT_ALL_LEGAL_ACTIONS");
        final List<?> candidates = (List<?>) data.get("candidates");
        Assert.assertFalse(candidates.isEmpty());
        Assert.assertNotNull(((Map<?, ?>) candidates.get(0)).get("assessment"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void omitsHiddenIdentityAndFreezesStateBeforeEvaluation() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        addCardToZone("Ponder", ai, ZoneType.Hand);
        addCardToZone("Black Lotus", opponent, ZoneType.Hand);
        addCardToZone("Time Walk", ai, ZoneType.Library);
        final Card hidden = addCardToZone("Counterspell", opponent, ZoneType.Exile);
        hidden.turnFaceDown(true);
        final int life = ai.getLife();
        final AiDecisionRecorder recorder = new AiDecisionRecorder(game, ai, 1);
        ai.setLife(7, null);
        final AiDecisionEvent event = recorder.finish(null, "COMPLETED", null);
        final String contents = event.data().toString();
        Assert.assertTrue(contents.contains("Ponder"));
        Assert.assertFalse(contents.contains("Black Lotus"));
        Assert.assertFalse(contents.contains("Time Walk"));
        Assert.assertFalse(contents.contains("Counterspell"));
        final Map<?, ?> observation = (Map<?, ?>) event.data().get("observation");
        final List<?> players = (List<?>) observation.get("players");
        Assert.assertEquals(((Map<?, ?>) players.get(1)).get("life"), life);
        Assert.expectThrows(UnsupportedOperationException.class, () -> event.data().put("status", "wrong"));
        Assert.expectThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) players.get(1)).clear());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void suppressesCopiesAndOtherPhasesAndRetainsEvaluationTimeout() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Decisions decisions = new Decisions();
        game.subscribeToEvents(decisions);
        game.setRecordAiDecisions(true);
        game.getPhaseHandler().devModeSet(PhaseType.UPKEEP, ai);
        ((PlayerControllerAi) ai.getController()).getAi().chooseSpellAbilityToPlay();
        Assert.assertTrue(decisions.events.isEmpty());
        game.markAsSimulationCopy();
        Assert.assertFalse(game.isRecordingAiDecisions());
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        ((PlayerControllerAi) ai.getController()).getAi().chooseSpellAbilityToPlay();
        Assert.assertTrue(decisions.events.isEmpty());
        final AiDecisionRecorder recorder = new AiDecisionRecorder(game, ai, 1);
        recorder.interrupted("EVALUATION_TIMEOUT");
        final AiDecisionEvent event = recorder.finish(null, "COMPLETED", null);
        recorder.interrupted("late worker");
        Assert.assertEquals(event.data().get("status"), "EVALUATION_TIMEOUT");
    }
}
