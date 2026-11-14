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
        final SpellAbility hiddenAbility = hidden.getFirstSpellAbility();
        hidden.turnFaceDown(true);
        final int life = ai.getLife();
        final AiDecisionRecorder recorder = new AiDecisionRecorder(game, ai, 1);
        recorder.detail(hiddenAbility, Map.of("reason", "CARD_AI_DECLINED", "handler", "CounterAi", "handlerRating", 12));
        recorder.evaluated(hiddenAbility, "CantPlayAi");
        ai.setLife(7, null);
        final AiDecisionEvent event = recorder.finish(null, "COMPLETED", null);
        final String contents = event.data().toString();
        Assert.assertTrue(contents.contains("Ponder"));
        Assert.assertFalse(contents.contains("Black Lotus"));
        Assert.assertFalse(contents.contains("Time Walk"));
        Assert.assertFalse(contents.contains("Counterspell"));
        Assert.assertFalse(contents.contains("CounterAi"));
        final Map<?, ?> maskedAssessment = (Map<?, ?>) ((List<?>) event.data().get("candidates")).get(0);
        Assert.assertFalse(((Map<?, ?>) maskedAssessment.get("assessmentDetail")).containsKey("handlerRating"));
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
    @Test(groups = {"UnitTest", "fast"})
    public void recordsLandShortlistWithoutChangingChoice() {
        final java.util.Random saved = forge.util.MyRandom.getRandom();
        try {
            String baseline = null;
            for (final boolean enabled : List.of(false, true)) {
                forge.util.MyRandom.setRandom(new java.util.Random(42));
                final Game game = initAndCreateGame();
                final Player ai = game.getPlayers().get(1);
                addCardToZone("Island", ai, ZoneType.Hand);
                addCardToZone("Forest", ai, ZoneType.Hand);
                addCardToZone("Ponder", ai, ZoneType.Hand);
                for (final Player player : game.getPlayers()) { fillLibrary(player, 20); }
                final Decisions decisions = new Decisions();
                game.subscribeToEvents(decisions);
                game.setRecordAiDecisions(enabled);
                final List<SpellAbility> selected = ((PlayerControllerAi) ai.getController()).getAi().chooseSpellAbilityToPlay();
                Assert.assertNotNull(selected);
                if (!enabled) {
                    baseline = selected.get(0).getHostCard().getName();
                } else {
                    Assert.assertEquals(selected.get(0).getHostCard().getName(), baseline);
                    final List<?> candidates = (List<?>) decisions.events.get(0).data().get("candidates");
                    final List<?> lands = candidates.stream().filter(c -> "LAND_OPTION".equals(((Map<?, ?>) c).get("assessment"))).toList();
                    Assert.assertEquals(lands.size(), 2);
                    Assert.assertEquals(lands.stream().filter(c -> Boolean.TRUE.equals(((Map<?, ?>) c).get("heuristicDefault"))).count(), 1L);
                    Assert.assertTrue(lands.stream().allMatch(c -> Boolean.FALSE.equals(((Map<?, ?>) c).get("searchOffered"))));
                }
            }
        } finally {
            forge.util.MyRandom.setRandom(saved);
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void failedCastAssessmentRetainsItsSpecificCause() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card spell = addCardToZone("Emrakul, the Aeons Torn", ai, ZoneType.Hand);
        for (final Player p : game.getPlayers()) { fillLibrary(p, 20); }
        game.setRecordAiDecisions(true);
        final Decisions decisions = new Decisions();
        game.subscribeToEvents(decisions);
        final AiController brain = ((PlayerControllerAi) ai.getController()).getAi();
        brain.noteFailedCast(AiController.castKey(spell.getFirstSpellAbility()));
        brain.chooseSpellAbilityToPlay();
        final List<?> candidates = (List<?>) decisions.events.get(0).data().get("candidates");
        Assert.assertTrue(candidates.stream().anyMatch(c -> {
            final Map<?, ?> row = (Map<?, ?>) c;
            return "CantPlayAi".equals(row.get("assessment")) && row.get("assessmentDetail") instanceof Map<?, ?> detail
                    && "PREVIOUS_CAST_FAILED_THIS_TURN".equals(detail.get("reason"));
        }));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void closedRecorderIgnoresLateDetailsAndAssessments() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility spell = addCardToZone("Ponder", ai, ZoneType.Hand).getFirstSpellAbility();
        final AiDecisionRecorder recorder = new AiDecisionRecorder(game, ai, 1);
        recorder.beginAssessment(spell);
        recorder.detail(spell, Map.of("reason", "AI_SPECIFIC_RESTRICTION"));
        recorder.evaluated(spell, "CantPlayAi");
        final AiDecisionEvent event = recorder.finish(null, "COMPLETED", null);
        recorder.detail(spell, Map.of("reason", "late"));
        recorder.evaluated(spell, "late");
        final List<?> candidates = (List<?>) event.data().get("candidates");
        Assert.assertEquals(candidates.size(), 1);
        Assert.assertEquals(((Map<?, ?>) ((Map<?, ?>) candidates.get(0)).get("assessmentDetail")).get("reason"), "AI_SPECIFIC_RESTRICTION");
    }

}
