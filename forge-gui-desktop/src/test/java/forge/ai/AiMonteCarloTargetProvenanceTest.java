package forge.ai;

import forge.ai.simulation.FlatMonteCarloPicker;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AiMonteCarloTargetProvenanceTest extends AITest {
    private record Fixture(Game game, Player ai, AiController brain, SpellAbility ability, Card first, Card second,
                           AiDecisionRecorder recorder) { }
    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (int i = 0; i < 3; i++) { addCardToZone("Island", ai, ZoneType.Battlefield); }
        final Card chip = addCardToZone("The Reality Chip", ai, ZoneType.Battlefield);
        final SpellAbility ability = chip.getSpellAbilities().stream().filter(sa -> sa.getApi() == ApiType.Attach).findFirst().orElseThrow();
        ability.setActivatingPlayer(ai);
        final Card first = addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        final Card second = addCardToZone("Nessian Courser", ai, ZoneType.Battlefield);
        game.setRecordAiDecisions(true);
        return new Fixture(game, ai, ((PlayerControllerAi) ai.getController()).getAi(), ability, first, second,
                new AiDecisionRecorder(game, ai, 1));
    }
    @SuppressWarnings("unchecked")
    private void initialPlan(final Fixture f) throws Exception {
        f.recorder.beginAssessment(f.ability);
        final Field field = AiController.class.getDeclaredField("evaluationDecision"); field.setAccessible(true);
        final var context = (ThreadLocal<AiDecisionRecorder>) field.get(f.brain);
        context.set(f.recorder);
        try { AiTargetChoiceRecorder.select(f.ai, f.ability, List.of(f.first, f.second), "ATTACH_CARD_POOL", pool -> f.first).addToTargets(f.ability); }
        finally { context.remove(); }
        f.recorder.evaluated(f.ability, "WillPlay");
    }
    private boolean controllerApply(final Fixture f, final GameObject target, final AiDecisionRecorder.SearchTargetOperation operation) throws Exception {
        final Field field = AiController.class.getDeclaredField("decisionRecorder"); field.setAccessible(true); field.set(f.brain, f.recorder);
        final Method method = AiController.class.getDeclaredMethod("applySearchTarget", SpellAbility.class, GameObject.class, AiDecisionRecorder.SearchTargetOperation.class);
        method.setAccessible(true);
        try { return (boolean) method.invoke(f.brain, f.ability, target, operation); }
        finally { field.set(f.brain, null); }
    }
    private Map<?, ?> firstApplication(final AiDecisionEvent event) {
        return (Map<?, ?>) ((List<?>) event.data().get("searchTargetApplications")).get(0);
    }
    private Map<?, ?> origin(final Fixture f) {
        final var snapshot = f.brain.getTargetChoiceProvenance(f.ability);
        return (Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) snapshot.get("abilities")).get(0)).get("targetChoices")).get(0);
    }
    @DataProvider public Object[][] applications() {
        return new Object[][] {{AiDecisionRecorder.SearchTargetOperation.ROLLOUT_TARGET_OVERRIDE, false},
                {AiDecisionRecorder.SearchTargetOperation.RESTORE_APPROVED_TARGET, true},
                {AiDecisionRecorder.SearchTargetOperation.DISCOVERED_TARGET, false},
                {AiDecisionRecorder.SearchTargetOperation.GUARD_REAIM, false}};
    }
    @Test(dataProvider = "applications", groups = {"UnitTest", "fast"})
    public void actualControllerApplicationLinksTheNewPlanThroughARealReconfigureExecution(final AiDecisionRecorder.SearchTargetOperation operation,
            final boolean restore) throws Exception {
        final Fixture f = fixture(); initialPlan(f);
        final Card expected = restore ? f.first : f.second;
        Assert.assertTrue(controllerApply(f, expected, operation));
        Assert.assertSame(f.ability.getTargets().get(0), expected);
        final AtomicReference<Map<String, Object>> stacked = new AtomicReference<>();
        f.game.observeEventEmission(event -> {
            if (event instanceof GameEventSpellAbilityCast) { stacked.set(f.brain.getTargetChoiceProvenance(f.game.getStack().peekAbility())); }
        });
        f.brain.publishDecision(f.recorder, List.of(f.ability), "COMPLETED", null);
        Assert.assertTrue(((PlayerControllerAi) f.ai.getController()).playChosenSpellAbility(f.ability));
        Assert.assertNotNull(stacked.get());
        Assert.assertEquals(stacked.get().get("schemaVersion"), 2);
        final Map<?, ?> group = (Map<?, ?>) ((List<?>) stacked.get().get("abilities")).get(0);
        final Map<?, ?> origin = (Map<?, ?>) ((List<?>) group.get("targetChoices")).get(0);
        Assert.assertNotNull(origin);
        Assert.assertEquals(origin.get("choiceId"), f.ai.getId() + ":1:search-target:0");
        Assert.assertEquals(origin.get("stage"), "SEARCH_TARGET_APPLICATION");
        Assert.assertEquals(origin.get("abilityIndex"), 0); Assert.assertEquals(origin.get("targetIndex"), 0);
        Assert.assertNull(f.game.getEventObservationFailure());
    }
    @Test(groups = {"UnitTest", "fast"})
    public void failedIllegalApplicationPreservesTheOldPlanAndDoesNotClaimAnOrigin() throws Exception {
        final Fixture f = fixture(); initialPlan(f);
        final Card enemy = addCardToZone("Shivan Dragon", f.game.getPlayers().get(0), ZoneType.Battlefield);
        Assert.assertFalse(controllerApply(f, enemy, AiDecisionRecorder.SearchTargetOperation.ROLLOUT_TARGET_OVERRIDE));
        Assert.assertSame(f.ability.getTargets().get(0), f.first);
        final var event = f.recorder.finish(List.of(f.ability), "COMPLETED", null);
        Assert.assertEquals(firstApplication(event).get("status"), "DECLINED");
        Assert.assertTrue(((List<?>) firstApplication(event).get("changedAbilities")).isEmpty());
        final var snapshot = f.recorder.targetProvenance(f.ability).capture(f.ability);
        final var group = (Map<?, ?>) ((List<?>) snapshot.get("abilities")).get(0);
        Assert.assertEquals(((Map<?, ?>) ((List<?>) group.get("targetChoices")).get(0)).get("stage"), "CANDIDATE_EVALUATION");
    }
    @Test(groups = {"UnitTest", "fast"})
    public void unchangedRevalidationKeepsItsExistingCauseButChangedRevalidationGetsANewCause() throws Exception {
        final Fixture f = fixture(); initialPlan(f);
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertTrue(f.recorder.searchTargets(f.ability, AiDecisionRecorder.SearchTargetOperation.HEURISTIC_REVALIDATION, null,
                () -> { calls.incrementAndGet(); return true; }));
        Assert.assertTrue(f.recorder.searchTargets(f.ability, AiDecisionRecorder.SearchTargetOperation.HEURISTIC_REVALIDATION, null,
                () -> { calls.incrementAndGet(); return FlatMonteCarloPicker.applyTarget(f.ability, f.second); }));
        Assert.assertEquals(calls.get(), 2);
        f.brain.publishDecision(f.recorder, List.of(f.ability), "COMPLETED", null);
        final var attempt = f.brain.actionTracker().begin(f.ability);
        Assert.assertEquals(origin(f).get("choiceId"), f.ai.getId() + ":1:search-target:1");
        f.brain.actionTracker().finish(attempt, "FAILED", "TEST_FINISHED");
    }
    @Test(groups = {"UnitTest", "fast"})
    public void discardedCandidatesAndLaterEqualIdentityMutationsCannotBorrowAnOperationOrigin() throws Exception {
        for (final boolean discarded : new boolean[]{false, true}) {
            final Fixture f = fixture(); initialPlan(f);
            Assert.assertTrue(controllerApply(f, f.second, AiDecisionRecorder.SearchTargetOperation.ROLLOUT_TARGET_OVERRIDE));
            final SpellAbility chosen = discarded ? f.ability.copy() : f.ability;
            if (!discarded) { f.ability.resetTargets(); f.ability.getTargets().add(f.second); }
            f.brain.publishDecision(f.recorder, List.of(chosen), "COMPLETED", null);
            final var attempt = f.brain.actionTracker().begin(chosen);
            final var snapshot = f.brain.getTargetChoiceProvenance(chosen);
            Assert.assertNull(((List<?>) ((Map<?, ?>) ((List<?>) snapshot.get("abilities")).get(0)).get("targetChoices")).get(0));
            f.brain.actionTracker().finish(attempt, "FAILED", "TEST_FINISHED");
        }
    }
    @Test(groups = {"UnitTest", "fast"})
    public void timeoutFreezesTheApplicationBeforeItsLateResultAndPreservesTheOriginalCallback() throws Exception {
        final Fixture f = fixture(); initialPlan(f);
        final AtomicReference<AiDecisionEvent> frozen = new AtomicReference<>();
        Assert.assertTrue(f.recorder.searchTargets(f.ability, AiDecisionRecorder.SearchTargetOperation.RESTORE_APPROVED_TARGET, f.first, () -> {
            frozen.set(f.recorder.finish(null, "AI_TIMEOUT", null));
            return FlatMonteCarloPicker.applyTarget(f.ability, f.first);
        }));
        Assert.assertEquals(firstApplication(frozen.get()).get("status"), "INCOMPLETE");
        Assert.assertNull(firstApplication(frozen.get()).get("afterTargets"));
        Assert.assertNull(f.game.getEventObservationFailure());
    }
    @Test(groups = {"UnitTest", "fast"})
    public void originalErrorsAreRethrownWithoutSerializingTheirPrivateMessage() throws Exception {
        final Fixture f = fixture(); initialPlan(f);
        final IllegalStateException failure = new IllegalStateException("private diagnostic");
        final var caught = Assert.expectThrows(IllegalStateException.class, () -> f.recorder.searchTargets(f.ability,
                AiDecisionRecorder.SearchTargetOperation.HEURISTIC_REVALIDATION, null, () -> { throw failure; }));
        Assert.assertSame(caught, failure);
        final var event = f.recorder.finish(null, "ERROR", null);
        Assert.assertEquals(firstApplication(event).get("status"), "ERROR");
        Assert.assertFalse(event.data().toString().contains("private diagnostic"));
    }
    @Test(groups = {"UnitTest", "fast"})
    public void captureBudgetDoesNotSkipAnyTargetApplications() {
        final Fixture f = fixture(); final AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 130; i++) {
            Assert.assertTrue(f.recorder.searchTargets(f.ability, AiDecisionRecorder.SearchTargetOperation.RESTORE_APPROVED_TARGET,
                    f.first, () -> { calls.incrementAndGet(); return FlatMonteCarloPicker.applyTarget(f.ability, f.first); }));
        }
        final var event = f.recorder.finish(List.of(f.ability), "COMPLETED", null);
        Assert.assertEquals(calls.get(), 130);
        Assert.assertEquals(((List<?>) event.data().get("searchTargetApplications")).size(), 128);
        Assert.assertEquals(((Map<?, ?>) event.data().get("searchTargetApplicationOmissions")).get("budget"), 2L);
        Assert.assertSame(f.ability.getTargets().get(0), f.first);
    }
    @DataProvider public Object[][] disabled() { return new Object[][]{{false}, {true}}; }
    @Test(dataProvider = "disabled", groups = {"UnitTest", "fast"})
    public void disabledAndCopiedGamesApplyTargetsWithoutObservingThem(final boolean copy) {
        final Fixture f = fixture();
        if (copy) { f.game.markAsSimulationCopy(); } else { f.game.setRecordAiDecisions(false); }
        Assert.assertTrue(f.recorder.searchTargets(f.ability, AiDecisionRecorder.SearchTargetOperation.RESTORE_APPROVED_TARGET,
                f.first, () -> FlatMonteCarloPicker.applyTarget(f.ability, f.first)));
        Assert.assertFalse(f.recorder.finish(List.of(f.ability), "COMPLETED", null).data().containsKey("searchTargetApplications"));
        Assert.assertSame(f.ability.getTargets().get(0), f.first);
    }
    @Test(groups = {"UnitTest", "fast"})
    public void maskedSourcesDoNotExposeTheOperationOrAnyTargetPlan() {
        final Fixture f = fixture(); f.ability.getHostCard().turnFaceDown();
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertTrue(f.recorder.searchTargets(f.ability, AiDecisionRecorder.SearchTargetOperation.ROLLOUT_TARGET_OVERRIDE,
                f.second, () -> { calls.incrementAndGet(); return true; }));
        final var application = firstApplication(f.recorder.finish(List.of(f.ability), "COMPLETED", null));
        Assert.assertEquals(calls.get(), 1); Assert.assertEquals(application.get("operation"), "MASKED");
        Assert.assertEquals(application.get("status"), "MASKED");
        Assert.assertNull(application.get("requestedTarget")); Assert.assertNull(application.get("beforeTargets"));
        Assert.assertNull(application.get("afterTargets"));
    }
    @Test(groups = {"UnitTest", "fast"})
    public void observerFailureIsVisibleWithoutReplacingTheActualResult() {
        final Fixture f = fixture(); final AtomicInteger calls = new AtomicInteger();
        f.ability.setTargets(new forge.game.spellability.TargetChoices() {
            @Override public java.util.Iterator<GameObject> iterator() { throw new IllegalStateException("snapshot failed"); }
        });
        Assert.assertTrue(f.recorder.searchTargets(f.ability, AiDecisionRecorder.SearchTargetOperation.HEURISTIC_REVALIDATION,
                null, () -> { calls.incrementAndGet(); return true; }));
        Assert.assertEquals(calls.get(), 1); Assert.assertNotNull(f.game.getEventObservationFailure());
    }
    @Test(groups = {"UnitTest", "fast"})
    public void blockedCaptureDoesNotBlockTimeoutPublicationOrPoisonItWhenTheWorkerReturns() throws Exception {
        final Fixture f = fixture();
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        f.ability.setTargets(new forge.game.spellability.TargetChoices() {
            @Override public java.util.Iterator<GameObject> iterator() {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new IllegalStateException("late snapshot failure");
            }
        });
        final var result = executor.submit(() -> f.recorder.searchTargets(f.ability,
                AiDecisionRecorder.SearchTargetOperation.HEURISTIC_REVALIDATION, null, () -> true));
        try {
            Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            final var frozen = executor.submit(() -> f.recorder.finish(null, "AI_TIMEOUT", null)).get(5, java.util.concurrent.TimeUnit.SECONDS);
            Assert.assertTrue(((List<?>) frozen.data().get("searchTargetApplications")).isEmpty());
            Assert.assertEquals(((Map<?, ?>) frozen.data().get("searchTargetApplicationOmissions")).get("interrupted"), 1L);
            release.countDown(); Assert.assertTrue(result.get(5, java.util.concurrent.TimeUnit.SECONDS));
            Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void failureCapturingTheResultIsExplicitAndDoesNotReplaceASuccessfulCallback() {
        final Fixture f = fixture();
        Assert.assertTrue(f.recorder.searchTargets(f.ability, AiDecisionRecorder.SearchTargetOperation.HEURISTIC_REVALIDATION, null, () -> {
            f.ability.setTargets(new forge.game.spellability.TargetChoices() {
                @Override public java.util.Iterator<GameObject> iterator() { throw new IllegalStateException("result snapshot failed"); }
            });
            return true;
        }));
        final var application = firstApplication(f.recorder.finish(null, "COMPLETED", null));
        Assert.assertEquals(application.get("status"), "CAPTURE_FAILED");
        Assert.assertNull(application.get("afterTargets"));
        Assert.assertNotNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void failureInALaterAbilityCannotPublishAPartialOperationOrigin() throws Exception {
        final Fixture f = fixture(); initialPlan(f);
        final var child = org.mockito.Mockito.mock(forge.game.spellability.AbilitySub.class);
        final var targets = new forge.game.spellability.TargetChoices();
        org.mockito.Mockito.when(child.getTargets()).thenReturn(targets);
        org.mockito.Mockito.when(child.getId()).thenReturn(99999L);
        org.mockito.Mockito.when(child.getActivatingPlayer()).thenThrow(new IllegalStateException("later ability capture failed"));
        f.ability.setSubAbility(child);
        Assert.assertTrue(f.recorder.searchTargets(f.ability, AiDecisionRecorder.SearchTargetOperation.HEURISTIC_REVALIDATION, null, () -> {
            f.ability.resetTargets(); f.ability.getTargets().add(f.second); targets.add(f.first); return true;
        }));
        final var event = f.recorder.finish(List.of(f.ability), "COMPLETED", null);
        Assert.assertEquals(firstApplication(event).get("status"), "CAPTURE_FAILED");
        final var snapshot = f.recorder.targetProvenance(f.ability).capture(f.ability);
        Assert.assertNull(((List<?>) ((Map<?, ?>) ((List<?>) snapshot.get("abilities")).get(0)).get("targetChoices")).get(0));
        Assert.assertNotNull(f.game.getEventObservationFailure());
    }

}
