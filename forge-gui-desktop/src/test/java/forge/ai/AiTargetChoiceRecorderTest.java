package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class AiTargetChoiceRecorderTest extends AITest {
    private record Fixture(Game game, Player ai, SpellAbility ability, Card bear, Card dragon) { }

    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility ability = addCardToZone("Doom Blade", ai, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(ai);
        final Card bear = addCardToZone("Grizzly Bears", game.getPlayers().get(0), ZoneType.Battlefield);
        final Card dragon = addCardToZone("Shivan Dragon", game.getPlayers().get(0), ZoneType.Battlefield);
        game.setRecordAiDecisions(true);
        return new Fixture(game, ai, ability, bear, dragon);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> choices(final AiDecisionEvent event) {
        return (List<Map<String, Object>>) event.data().get("subordinateChoices");
    }

    @SuppressWarnings("unchecked")
    private <T> T evaluating(final Fixture f, final AiDecisionRecorder recorder, final Supplier<T> work) throws Exception {
        final var field = AiController.class.getDeclaredField("evaluationDecision");
        field.setAccessible(true);
        final var context = (ThreadLocal<AiDecisionRecorder>) field.get(((PlayerControllerAi) f.ai.getController()).getAi());
        context.set(recorder);
        try { return work.get(); } finally { context.remove(); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void freezesOfferedPoolAndLinksTheExactEvaluatedCandidate() {
        final Fixture f = fixture();
        final AiDecisionRecorder recorder = new AiDecisionRecorder(f.game, f.ai, 1L << 40);
        recorder.beginAssessment(f.ability);
        final List<Card> pool = new ArrayList<>(List.of(f.bear, f.dragon));
        final long timestamp = f.dragon.getGameTimestamp();
        final var choice = recorder.beginTargetChoice(f.ability, pool, "DESTROY_REMOVAL_POOL");
        pool.clear();
        choice.complete(f.dragon);
        recorder.evaluated(f.ability, "WillPlay");
        final AiDecisionEvent event = recorder.finish(List.of(f.ability), "COMPLETED", null);
        final Map<String, Object> data = choices(event).get(0);
        Assert.assertEquals(data.get("status"), "SELECTED");
        Assert.assertEquals(data.get("selectedOptionIndex"), 1);
        Assert.assertEquals(((List<?>) data.get("options")).size(), 2);
        Assert.assertEquals(data.get("candidateIndex"), 0);
        Assert.assertEquals(data.get("candidateActionId"), f.ai.getId() + ":" + (1L << 40) + ":0");
        f.dragon.setGameTimestamp(timestamp + 1);
        choice.complete(f.bear); // A late worker cannot change the emitted record.
        Assert.assertEquals(((Map<?, ?>) data.get("selected")).get("gameTimestamp"), timestamp);
        Assert.assertEquals(((Map<?, ?>) data.get("selected")).get("id"), f.dragon.getId());
        Assert.expectThrows(UnsupportedOperationException.class, () -> data.put("status", "DECLINED"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void repeatedEvaluationsKeepTheirOwnAssessmentAndChoiceIdentity() {
        final Fixture f = fixture();
        final AiDecisionRecorder recorder = new AiDecisionRecorder(f.game, f.ai, 1);
        recorder.beginAssessment(f.ability);
        recorder.beginTargetChoice(f.ability, List.of(f.bear), "DESTROY_REMOVAL_POOL").complete(f.bear);
        recorder.evaluated(f.ability, "CantPlayAi");
        recorder.beginAssessment(f.ability);
        recorder.beginTargetChoice(f.ability, List.of(f.dragon), "DESTROY_REMOVAL_POOL").complete(f.dragon);
        recorder.evaluated(f.ability, "WillPlay");
        final var data = choices(recorder.finish(null, "COMPLETED", null));
        Assert.assertNotEquals(data.get(0).get("choiceId"), data.get(1).get("choiceId"));
        Assert.assertEquals(data.get(0).get("candidateIndex"), 0);
        Assert.assertEquals(data.get(1).get("candidateIndex"), 1);
        Assert.assertNull(data.get(0).get("candidateActionId"));
        Assert.assertNull(data.get(1).get("candidateActionId"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void cancellationFreezesIncompleteChoiceAndRejectsLateAssessmentAndCompletion() {
        final Fixture f = fixture();
        final AiDecisionRecorder recorder = new AiDecisionRecorder(f.game, f.ai, 1);
        recorder.beginAssessment(f.ability);
        final var pending = recorder.beginTargetChoice(f.ability, List.of(f.bear), "DESTROY_REMOVAL_POOL");
        final var event = recorder.finish(null, "AI_TIMEOUT", null);
        pending.complete(f.bear);
        recorder.evaluated(f.ability, "WillPlay");
        Assert.assertNull(recorder.beginTargetChoice(f.ability, List.of(f.bear), "DESTROY_REMOVAL_POOL"));
        final var data = choices(event).get(0);
        Assert.assertEquals(data.get("status"), "INCOMPLETE");
        Assert.assertNull(data.get("candidateIndex"));
        Assert.assertNull(data.get("selected"));
        Assert.assertTrue(((List<?>) event.data().get("candidates")).isEmpty());
    }

    @DataProvider public Object[][] uncertainSelections() {
        return new Object[][] {{"DECLINED"}, {"OUTSIDE_OBSERVED_POOL"}, {"AMBIGUOUS_MATCH"}};
    }

    @Test(dataProvider = "uncertainSelections", groups = {"UnitTest", "fast"})
    public void uncertainSelectionsNeverInventAnOptionIndex(final String status) {
        final Fixture f = fixture();
        final AiDecisionRecorder recorder = new AiDecisionRecorder(f.game, f.ai, 1);
        recorder.beginAssessment(f.ability);
        final var pool = "AMBIGUOUS_MATCH".equals(status) ? List.of(f.bear, f.bear) : List.of(f.bear);
        final var choice = recorder.beginTargetChoice(f.ability, pool, "DESTROY_REMOVAL_POOL");
        choice.complete("DECLINED".equals(status) ? null : "OUTSIDE_OBSERVED_POOL".equals(status) ? f.dragon : f.bear);
        recorder.evaluated(f.ability, "CantPlayAi");
        final var data = choices(recorder.finish(null, "COMPLETED", null)).get(0);
        Assert.assertEquals(data.get("status"), status);
        Assert.assertNull(data.get("selectedOptionIndex"));
    }

    @DataProvider public Object[][] disabledModes() { return new Object[][] {{false}, {true}}; }

    @Test(dataProvider = "disabledModes", groups = {"UnitTest", "fast"})
    public void realDrawBranchRecordsSelfAndLethalOpponentTargetsWithoutInventingAnOptionPool(final boolean lethal) throws Exception {
        final Fixture f = fixture();
        f.game.getPhaseHandler().devModeSet(PhaseType.MAIN2, f.ai);
        for (final Player p : f.game.getPlayers()) { fillLibrary(p, 20); }
        if (lethal) { f.game.getPlayers().get(0).setLife(2, null); }
        addCardToZone("Swamp", f.ai, ZoneType.Battlefield);
        addCardToZone("Swamp", f.ai, ZoneType.Battlefield);
        final SpellAbility draw = addCardToZone("Sign in Blood", f.ai, ZoneType.Hand).getFirstSpellAbility();
        draw.setActivatingPlayer(f.ai);
        final var recorder = new AiDecisionRecorder(f.game, f.ai, 42);
        recorder.beginAssessment(draw);
        evaluating(f, recorder, () -> ((PlayerControllerAi) f.ai.getController()).getAi().canPlaySa(draw));
        final Player expected = lethal ? f.game.getPlayers().get(0) : f.ai;
        Assert.assertSame(draw.getTargets().getFirstTargetedPlayer(), expected);
        recorder.evaluated(draw, "WillPlay");
        final var choice = choices(recorder.finish(List.of(draw), "COMPLETED", null)).get(0);
        Assert.assertEquals(choice.get("boundary"), "DRAW_PLAYER_POOL");
        Assert.assertEquals(choice.get("poolSnapshotTiming"), "AT_TARGET_ADDITION");
        Assert.assertEquals(((List<?>) choice.get("options")).size(), 2);
        Assert.assertEquals(((Map<?, ?>) choice.get("selected")).get("id"), expected.getId());
        final var group = (Map<?, ?>) ((List<?>) recorder.targetProvenance(draw).capture(draw).get("abilities")).get(0);
        Assert.assertNotNull(((List<?>) group.get("targetChoices")).get(0));
    }

    @Test(dataProvider = "disabledModes", groups = {"UnitTest", "fast"})
    public void disabledRecordingAndRolloutsDoNotInspectThePool(final boolean rollout) {
        final Fixture f = fixture();
        if (rollout) { f.game.markAsSimulationCopy(); } else { f.game.setRecordAiDecisions(false); }
        final Iterable<Card> pool = () -> { throw new IllegalStateException("pool must not be inspected"); };
        final AtomicInteger calls = new AtomicInteger();
        final Card chosen = AiTargetChoiceRecorder.choose(f.ai, f.ability, pool, "DESTROY_REMOVAL_POOL", ignored -> {
            calls.incrementAndGet();
            return f.bear;
        });
        Assert.assertSame(chosen, f.bear);
        Assert.assertEquals(calls.get(), 1);
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void observationFailureIsVisibleWithoutChangingPickerResult() throws Exception {
        final Fixture f = fixture();
        final AiDecisionRecorder recorder = new AiDecisionRecorder(f.game, f.ai, 1);
        recorder.beginAssessment(f.ability);
        final IllegalStateException failure = new IllegalStateException("snapshot failed");
        final Iterable<Card> pool = () -> { throw failure; };
        final Card chosen = evaluating(f, recorder,
                () -> AiTargetChoiceRecorder.choose(f.ai, f.ability, pool, "DESTROY_REMOVAL_POOL", ignored -> f.bear));
        Assert.assertSame(chosen, f.bear);
        Assert.assertSame(f.game.getEventObservationFailure(), failure);
        Assert.assertTrue(choices(recorder.finish(null, "COMPLETED", null)).isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void pickerFailureIsRethrownAndRecordedAsAnObservedError() throws Exception {
        final Fixture f = fixture();
        final AiDecisionRecorder recorder = new AiDecisionRecorder(f.game, f.ai, 1);
        recorder.beginAssessment(f.ability);
        final IllegalArgumentException failure = new IllegalArgumentException("picker failed");
        evaluating(f, recorder, () -> {
            Assert.assertSame(Assert.expectThrows(IllegalArgumentException.class,
                    () -> AiTargetChoiceRecorder.choose(f.ai, f.ability, List.of(f.bear), "DESTROY_REMOVAL_POOL", ignored -> { throw failure; })), failure);
            return null;
        });
        Assert.assertEquals(choices(recorder.finish(null, "ERROR", null)).get(0).get("status"), "ERROR");
        Assert.assertNull(f.game.getEventObservationFailure(), "A picker failure is not a capture failure");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void unrelatedAbilitiesCannotBorrowTheCurrentCandidateContext() {
        final Fixture f = fixture();
        final AiDecisionRecorder recorder = new AiDecisionRecorder(f.game, f.ai, 1);
        recorder.beginAssessment(f.ability);
        final SpellAbility unrelated = addCardToZone("Doom Blade", f.ai, ZoneType.Hand).getFirstSpellAbility();
        Assert.assertNull(recorder.beginTargetChoice(unrelated, List.of(f.bear), "DESTROY_REMOVAL_POOL"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void maskedSourceDoesNotExposeItsHeuristicBoundary() {
        final Fixture f = fixture();
        f.ability.getHostCard().turnFaceDown();
        final AiDecisionRecorder recorder = new AiDecisionRecorder(f.game, f.ai, 1);
        recorder.beginAssessment(f.ability);
        recorder.beginTargetChoice(f.ability, List.of(f.bear), "DESTROY_REMOVAL_POOL").complete(f.bear);
        recorder.evaluated(f.ability, "CantPlayAi");
        final var choice = choices(recorder.finish(null, "COMPLETED", null)).get(0);
        Assert.assertEquals(choice.get("boundary"), "MASKED_TARGET_POOL");
        Assert.assertEquals(choice.get("purpose"), "MASKED_TARGET_PLANNING");
        Assert.assertEquals(((Map<?, ?>) choice.get("selected")).keySet(), java.util.Set.of("type", "id", "gameTimestamp"));
    }

    @Test(dataProvider = "disabledModes", groups = {"UnitTest", "fast"})
    public void realHeuristicsPublishTheirActualFilteredPools(final boolean destroy) {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        final Card spell = addCardToZone(destroy ? "Doom Blade" : "Lightning Bolt", ai, ZoneType.Hand);
        addCardToZone(destroy ? "Swamp" : "Mountain", ai, ZoneType.Battlefield);
        addCardToZone(destroy ? "Swamp" : "Mountain", ai, ZoneType.Battlefield);
        final Card bear = addCardToZone("Grizzly Bears", game.getPlayers().get(0), ZoneType.Battlefield);
        final Card dragon = addCardToZone("Shivan Dragon", game.getPlayers().get(0), ZoneType.Battlefield);
        for (final Player player : game.getPlayers()) { fillLibrary(player, 20); }
        final var events = new AiDecisionRecorderTest.Decisions();
        game.subscribeToEvents(events);
        game.setRecordAiDecisions(true);
        ((PlayerControllerAi) ai.getController()).chooseSpellAbilityToPlay();
        Assert.assertEquals(events.events.size(), 1);
        final var choices = choices(events.events.get(0));
        Assert.assertFalse(choices.isEmpty(), "Actual main-phase evaluation must reach an observed target pool");
        Assert.assertTrue(choices.stream().anyMatch(choice -> choice.get("boundary").equals(destroy ? "DESTROY_REMOVAL_POOL" : "DAMAGE_KILLABLES")));
        if (!destroy) {
            final var choice = choices.stream().filter(c -> "DAMAGE_KILLABLES".equals(c.get("boundary"))).findFirst().orElseThrow();
            Assert.assertEquals(((Map<?, ?>) choice.get("selected")).get("id"), bear.getId());
            Assert.assertTrue(((List<?>) choice.get("options")).stream().noneMatch(ref -> ((Map<?, ?>) ref).get("id").equals(dragon.getId())),
                    "A legal but nonlethal target is outside this heuristic pool");
            final SpellAbility sa = spell.getFirstSpellAbility();
            sa.setActivatingPlayer(ai);
            Assert.assertTrue(sa.canTarget(dragon));
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void realPlayerBurnAdditionHasItsOwnOriginInsteadOfReusingTheOpponentPreference() throws Exception {
        final Game game = initAndCreateThreePlayerGame();
        final Player ai = game.getPlayers().get(1);
        final Player weak = game.getPlayers().get(0);
        weak.setLife(1, null);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        for (final Player p : game.getPlayers()) { fillLibrary(p, 20); }
        addCardToZone("Mountain", ai, ZoneType.Battlefield);
        final SpellAbility burn = addCardToZone("Firebolt", ai, ZoneType.Hand).getFirstSpellAbility();
        burn.setActivatingPlayer(ai);
        game.setRecordAiDecisions(true);
        final var recorder = new AiDecisionRecorder(game, ai, 43);
        recorder.beginAssessment(burn);
        evaluating(new Fixture(game, ai, burn, null, null), recorder,
                () -> ((PlayerControllerAi) ai.getController()).getAi().canPlaySa(burn));
        Assert.assertSame(burn.getTargets().getFirstTargetedPlayer(), weak);
        recorder.evaluated(burn, "WillPlay");
        final var choices = choices(recorder.finish(List.of(burn), "COMPLETED", null));
        final var preference = choices.stream().filter(c -> c.get("boundary").equals("DAMAGE_OPPONENT_POOL")).findFirst().orElseThrow();
        final var addition = choices.stream().filter(c -> c.get("boundary").equals("DAMAGE_PLAYER_POOL")).findFirst().orElseThrow();
        Assert.assertEquals(((List<?>) addition.get("options")).size(), 2);
        Assert.assertNotEquals(addition.get("choiceId"), preference.get("choiceId"));
        Assert.assertEquals(addition.get("purpose"), "TARGET_RECOMMENDATION");
        final var group = (Map<?, ?>) ((List<?>) recorder.targetProvenance(burn).capture(burn).get("abilities")).get(0);
        Assert.assertEquals(((Map<?, ?>) ((List<?>) group.get("targetChoices")).get(0)).get("choiceId"), addition.get("choiceId"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void burnOpponentPreferenceRecordsTheMultiplayerPoolWithoutClaimingACommittedTarget() {
        final Game game = initAndCreateThreePlayerGame();
        final Player ai = game.getPlayers().get(1);
        final Player weakest = game.getPlayers().get(0);
        weakest.setLife(5, null);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        addCardToZone("Mountain", ai, ZoneType.Battlefield);
        for (final Player player : game.getPlayers()) { fillLibrary(player, 20); }
        final var events = new AiDecisionRecorderTest.Decisions();
        game.subscribeToEvents(events);
        game.setRecordAiDecisions(true);
        ((PlayerControllerAi) ai.getController()).chooseSpellAbilityToPlay();
        final var choice = choices(events.events.get(0)).stream()
                .filter(c -> "DAMAGE_OPPONENT_POOL".equals(c.get("boundary"))).findFirst().orElseThrow();
        Assert.assertEquals(((List<?>) choice.get("options")).size(), 2);
        Assert.assertEquals(((Map<?, ?>) choice.get("selected")).get("id"), weakest.getId());
        Assert.assertEquals(((Map<?, ?>) choice.get("selected")).get("type"), "PLAYER");
        Assert.assertEquals(choice.get("stage"), "CANDIDATE_EVALUATION");
        Assert.assertEquals(choice.get("purpose"), "OPPONENT_PREFERENCE");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void cancellationDoesNotWaitForPoolEnumerationOrLetLateCaptureFailurePoisonTheGame() throws Exception {
        final Fixture f = fixture();
        final AiDecisionRecorder recorder = new AiDecisionRecorder(f.game, f.ai, 1);
        recorder.beginAssessment(f.ability);
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        final Iterable<Card> blocked = () -> new java.util.Iterator<>() {
            @Override public boolean hasNext() { return true; }
            @Override public Card next() {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new IllegalStateException("late snapshot failure");
            }
        };
        try {
            final var capture = threads.submit(() -> evaluating(f, recorder,
                    () -> AiTargetChoiceRecorder.choose(f.ai, f.ability, blocked, "DESTROY_REMOVAL_POOL", ignored -> f.bear)));
            Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            final var publish = threads.submit(() -> recorder.finish(null, "AI_TIMEOUT", null));
            final var event = publish.get(1, java.util.concurrent.TimeUnit.SECONDS);
            Assert.assertTrue(choices(event).isEmpty());
            Assert.assertEquals(((Map<?, ?>) event.data().get("subordinateChoiceOmissions")).get("interrupted"), 1L);
            release.countDown();
            Assert.assertSame(capture.get(5, java.util.concurrent.TimeUnit.SECONDS), f.bear);
            Assert.assertNull(f.game.getEventObservationFailure(), "A closed decision's late snapshot is no longer an active observation failure");
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void largeObservedPoolsAreExplicitlyTruncatedWithoutChangingTheChosenObject() throws Exception {
        final Fixture f = fixture();
        final AiDecisionRecorder recorder = new AiDecisionRecorder(f.game, f.ai, 1);
        recorder.beginAssessment(f.ability);
        final List<Card> pool = new ArrayList<>(java.util.Collections.nCopies(256, f.bear));
        pool.add(f.dragon);
        final Card chosen = evaluating(f, recorder,
                () -> AiTargetChoiceRecorder.choose(f.ai, f.ability, pool, "DESTROY_REMOVAL_POOL", original -> original.get(256)));
        recorder.evaluated(f.ability, "WillPlay");
        final var choice = choices(recorder.finish(List.of(f.ability), "COMPLETED", null)).get(0);
        Assert.assertSame(chosen, f.dragon);
        Assert.assertEquals(((List<?>) choice.get("options")).size(), 256);
        Assert.assertEquals(choice.get("optionsTruncated"), true);
        Assert.assertEquals(choice.get("status"), "OUTSIDE_OBSERVED_POOL");
        Assert.assertNull(choice.get("selectedOptionIndex"));
        Assert.assertEquals(((Map<?, ?>) choice.get("selected")).get("id"), f.dragon.getId());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void perDecisionBudgetCountsOmissionsWithoutSkippingAnyPickerCalls() throws Exception {
        final Fixture f = fixture();
        final AiDecisionRecorder recorder = new AiDecisionRecorder(f.game, f.ai, 1);
        final AtomicInteger calls = new AtomicInteger();
        evaluating(f, recorder, () -> {
            for (int i = 0; i < 129; i++) {
                recorder.beginAssessment(f.ability);
                Assert.assertSame(AiTargetChoiceRecorder.choose(f.ai, f.ability, List.of(f.bear), "DESTROY_REMOVAL_POOL", pool -> {
                    calls.incrementAndGet();
                    return pool.get(0);
                }), f.bear);
                recorder.evaluated(f.ability, "WillPlay");
            }
            return null;
        });
        final var event = recorder.finish(List.of(f.ability), "COMPLETED", null);
        Assert.assertEquals(calls.get(), 129);
        Assert.assertEquals(choices(event).size(), 128);
        Assert.assertEquals(((Map<?, ?>) event.data().get("subordinateChoiceOmissions")).get("budget"), 1L);
    }
}
