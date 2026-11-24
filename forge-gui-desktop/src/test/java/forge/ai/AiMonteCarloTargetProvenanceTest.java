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
        final AtomicReference<AiDecisionEvent> decision = new AtomicReference<>();
        f.game.observeEventEmission(event -> { if (event instanceof AiDecisionEvent emitted) { decision.set(emitted); } });
        final AtomicReference<Map<String, Object>> stacked = new AtomicReference<>();
        f.game.observeEventEmission(event -> {
            if (event instanceof GameEventSpellAbilityCast) { stacked.set(f.brain.getTargetChoiceProvenance(f.game.getStack().peekAbility())); }
        });
        f.brain.publishDecision(f.recorder, List.of(f.ability), "COMPLETED", null);
        final Map<?, ?> application = firstApplication(decision.get());
        Assert.assertEquals(((Map<?, ?>) application.get("entryLocation")).get("kind"), "APPLICATION");
        Assert.assertEquals(((Map<?, ?>) application.get("inputContext")).get("timing"), "AFTER_SELECTION_BEFORE_APPLICATION");
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
        final Map<?, ?> input = (Map<?, ?>) application.get("inputContext");
        Assert.assertEquals(input.get("timing"), "UNESTABLISHED"); Assert.assertNull(input.get("policy"));
        Assert.assertEquals(input.get("stateBasis"), "UNESTABLISHED");
        final Map<?, ?> entry = (Map<?, ?>) application.get("entryLocation");
        Assert.assertEquals(entry.get("reason"), "HIDDEN"); Assert.assertNull(entry.get("turn"));
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

    @Test(groups = {"UnitTest", "fast"})
    public void revalidationFreezesCurrentPlanningInputBeforeTheCallbackInsteadOfBorrowingTheParent() {
        final Fixture f = fixture();
        f.ai.setLife(6, null); f.game.getPhaseHandler().devModeSet(forge.game.phase.PhaseType.MAIN2, f.ai);
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertTrue(f.recorder.searchTargets(f.ability, AiDecisionRecorder.SearchTargetOperation.HEURISTIC_REVALIDATION, null, () -> {
            calls.incrementAndGet(); f.ai.setLife(9, null);
            f.game.getPhaseHandler().devModeSet(forge.game.phase.PhaseType.MAIN1, f.ai);
            return FlatMonteCarloPicker.applyTarget(f.ability, f.second);
        }));
        final var event = f.recorder.finish(List.of(f.ability), "COMPLETED", null);
        final var application = firstApplication(event);
        final Map<?, ?> entry = (Map<?, ?>) application.get("entryLocation");
        final Map<?, ?> input = (Map<?, ?>) application.get("inputContext");
        Assert.assertEquals(calls.get(), 1); Assert.assertEquals(entry.get("kind"), "OPERATION");
        Assert.assertEquals(entry.get("phase"), "MAIN2");
        Assert.assertEquals(input.get("timing"), "BEFORE_OPERATION");
        Assert.assertEquals(input.get("stateBasis"), "PLANNING_WORKING_STATE");
        final Map<?, ?> policy = (Map<?, ?>) input.get("policy");
        Assert.assertEquals(((Map<?, ?>) ((List<?>) policy.get("players")).get(1)).get("life"), 6);
        final Map<?, ?> baseline = (Map<?, ?>) ((Map<?, ?>) event.data().get("inputContext")).get("policy");
        Assert.assertEquals(((Map<?, ?>) ((List<?>) baseline.get("players")).get(1)).get("life"), 20);
        Assert.assertNull(policy.get("prompt"));
        Assert.assertEquals(((Map<?, ?>) ((Map<?, ?>) input.get("diagnostics")).get("optionBindings")).get("coverage"), "UNSUPPORTED");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void unavailableSearchViewDoesNotOmitTheApplicationOrItsCallbackResult() throws Exception {
        final Fixture f = fixture();
        final var state = ((PlayerControllerAi) f.ai.getController()).inputContextState();
        final var field = AiInputContext.State.class.getDeclaredField("remaining"); field.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) field.get(state)).set(0);
        Assert.assertTrue(controllerApply(f, f.second, AiDecisionRecorder.SearchTargetOperation.GUARD_REAIM));
        final var application = firstApplication(f.recorder.finish(List.of(f.ability), "COMPLETED", null));
        final Map<?, ?> input = (Map<?, ?>) application.get("inputContext");
        Assert.assertEquals(input.get("status"), "UNAVAILABLE");
        Assert.assertEquals(input.get("omissions"), List.of(Map.of("component", "CONTEXT", "reason", "BUDGET_EXHAUSTED")));
        Assert.assertEquals(application.get("status"), "ACCEPTED"); Assert.assertNotNull(application.get("afterTargets"));
        Assert.assertSame(f.ability.getTargets().get(0), f.second); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void failedEligibilityGetterDoesNotReplaceTheOriginalSearchCallback() {
        final Fixture f = fixture(); final var ability = org.mockito.Mockito.spy(f.ability);
        final var failure = new IllegalStateException("eligibility failed");
        org.mockito.Mockito.doThrow(failure).when(ability).getHostCard();
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertTrue(f.recorder.searchTargets(ability, AiDecisionRecorder.SearchTargetOperation.HEURISTIC_REVALIDATION, null,
                () -> { calls.incrementAndGet(); return true; }));
        Assert.assertEquals(calls.get(), 1); Assert.assertSame(f.game.getEventObservationFailure(), failure);
    }

    @DataProvider public Object[][] blockingSearchStages() { return new Object[][] {{"INPUT"}, {"RESULT_ID"}, {"RESULT_ACTOR"}}; }
    @Test(dataProvider = "blockingSearchStages", groups = {"UnitTest", "fast"})
    public void searchEngineGettersCannotHoldTheParentPublicationMonitor(final String stage) throws Exception {
        final Fixture f = fixture();
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final Runnable block = () -> {
            entered.countDown();
            try { Assert.assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new IllegalStateException(failure); }
            throw new IllegalStateException("late snapshot failure");
        };
        final var ability = org.mockito.Mockito.spy(f.ability);
        if ("INPUT".equals(stage)) {
            final Card card = org.mockito.Mockito.spy(f.first);
            final var zone = f.ai.getZone(ZoneType.Battlefield); zone.remove(f.first); zone.add(card);
            org.mockito.Mockito.doAnswer(ignored -> { block.run(); return 0; }).when(card).getNetPower();
        }
        final AtomicInteger calls = new AtomicInteger();
        final var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            final var work = threads.submit(() -> f.recorder.searchTargets(ability,
                    AiDecisionRecorder.SearchTargetOperation.HEURISTIC_REVALIDATION, null, () -> {
                        calls.incrementAndGet(); ability.getTargets().add(f.second);
                        if ("RESULT_ID".equals(stage)) {
                            org.mockito.Mockito.doAnswer(ignored -> { block.run(); return 0L; }).when(ability).getId();
                        } else if ("RESULT_ACTOR".equals(stage)) {
                            org.mockito.Mockito.doAnswer(ignored -> { block.run(); return f.ai; }).when(ability).getActivatingPlayer();
                        }
                        return true;
                    }));
            Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            final var event = threads.submit(() -> f.recorder.finish(null, "AI_TIMEOUT", null)).get(2, java.util.concurrent.TimeUnit.SECONDS);
            final String frozen = event.data().toString();
            if ("INPUT".equals(stage)) { Assert.assertTrue(((List<?>) event.data().get("searchTargetApplications")).isEmpty()); }
            else { Assert.assertEquals(firstApplication(event).get("status"), "INCOMPLETE"); }
            release.countDown(); Assert.assertTrue(work.get(5, java.util.concurrent.TimeUnit.SECONDS));
            Assert.assertEquals(calls.get(), 1); Assert.assertEquals(event.data().toString(), frozen);
            Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); threads.shutdownNow(); }
    }

}
