package forge.ai;

import forge.game.Game;
import forge.game.event.Event;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.AITimeoutTracker;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public class AiDecisionPublicationTest extends AITest {
    private record Fixture(Game game, Player ai, AiController brain, SpellAbility first, SpellAbility second,
                           AiDecisionRecorder recorder, List<AiDecisionEvent> events) { }

    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player ai = game.getPlayers().get(1);
        addCardToZone("Mountain", ai, ZoneType.Battlefield); addCardToZone("Mountain", ai, ZoneType.Battlefield);
        final SpellAbility first = addCardToZone("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility();
        final SpellAbility second = addCardToZone("Shock", ai, ZoneType.Hand).getFirstSpellAbility();
        for (final var ability : List.of(first, second)) {
            ability.setActivatingPlayer(ai); ability.getTargets().add(game.getPlayers().get(0));
        }
        game.AI_FLAT_MONTE_CARLO = false; game.setRecordAiDecisions(true);
        final List<AiDecisionEvent> events = new CopyOnWriteArrayList<>();
        game.observeEventEmission(event -> { if (event instanceof AiDecisionEvent decision) { events.add(decision); } });
        return new Fixture(game, ai, ((PlayerControllerAi) ai.getController()).getAi(), first, second,
                new AiDecisionRecorder(game, ai, 91), events);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void failedProjectionDoesNotPreventTheSelectedSpellFromBeingPlayed() {
        final Fixture f = fixture(); final var ability = org.mockito.Mockito.spy(f.first);
        final var failure = new IllegalStateException("private projection fault");
        org.mockito.Mockito.doThrow(failure).doReturn(f.first.getDescription()).when(ability).getDescription();
        f.brain.publishDecision(f.recorder, List.of(ability), "COMPLETED", null);
        Assert.assertSame(f.game.getEventObservationFailure(), failure); Assert.assertTrue(f.events.isEmpty());
        Assert.assertTrue(((PlayerControllerAi) f.ai.getController()).playChosenSpellAbility(ability));
        Assert.assertEquals(f.game.getStack().peekAbility().getHostCard().getId(), f.first.getHostCard().getId());
        f.game.getStack().resolveStack();
        Assert.assertEquals(f.game.getPlayers().get(0).getLife(), 17);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void failurePreparingALaterActionKeepsTheParentButRegistersNoPrefix() {
        final Fixture f = fixture(); final var recorder = org.mockito.Mockito.spy(f.recorder);
        final var failure = new IllegalStateException("later provenance fault");
        org.mockito.Mockito.doThrow(failure).when(recorder).targetProvenance(f.second);
        f.brain.publishDecision(recorder, List.of(f.first, f.second), "COMPLETED", null);
        Assert.assertSame(f.game.getEventObservationFailure(), failure); Assert.assertEquals(f.events.size(), 1);
        Assert.assertEquals(((List<?>) f.events.get(0).data().get("selectedActions")).size(), 2);
        Assert.assertNull(f.brain.actionTracker().begin(f.first)); Assert.assertNull(f.brain.actionTracker().begin(f.second));
        org.mockito.Mockito.verify(recorder).targetProvenance(f.first);
        org.mockito.Mockito.verify(recorder).targetProvenance(f.second);
    }

    @DataProvider public Object[][] lateFailures() { return new Object[][] {{false}, {true}}; }

    @Test(dataProvider = "lateFailures", groups = {"UnitTest", "fast"})
    public void timeoutWinnerCannotBeOverwrittenOrDuplicatedByALatePublisher(final boolean fail) throws Exception {
        final Fixture f = fixture(); final var ability = org.mockito.Mockito.spy(f.first);
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(ignored -> {
            entered.countDown(); Assert.assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            if (fail) { throw new IllegalStateException("late projection fault"); }
            return f.first.getDescription();
        }).when(ability).getDescription();
        final var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            final var work = threads.submit(() -> f.brain.publishDecision(f.recorder, List.of(ability), "COMPLETED", null));
            Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            threads.submit(() -> f.brain.publishDecision(f.recorder, null, "AI_TIMEOUT", null)).get(2, java.util.concurrent.TimeUnit.SECONDS);
            Assert.assertEquals(f.events.size(), 1); final String frozen = f.events.get(0).data().toString();
            Assert.assertEquals(f.events.get(0).data().get("status"), "AI_TIMEOUT");
            release.countDown(); work.get(5, java.util.concurrent.TimeUnit.SECONDS);
            Assert.assertEquals(f.events.size(), 1); Assert.assertEquals(f.events.get(0).data().toString(), frozen);
            Assert.assertNull(f.brain.actionTracker().begin(ability)); Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @DataProvider public Object[][] preparationClosures() {
        return new Object[][] {{false, false}, {false, true}, {true, false}, {true, true}};
    }

    private AiActionTracker.Registration register(final AiActionTracker tracker, final AiDecisionEvent event,
            final List<SpellAbility> chosen, final AiDecisionRecorder recorder) {
        return tracker.commitSelected(tracker.prepareSelected(tracker.selectionScope(), event, chosen, recorder));
    }

    @Test(dataProvider = "preparationClosures", groups = {"UnitTest", "fast"})
    public void retiredOrSupersededPreparationCannotRegisterOrPoisonTheNewScope(final boolean retire, final boolean fail) throws Exception {
        final Fixture f = fixture(); final var recorder = org.mockito.Mockito.spy(f.recorder);
        final var event = recorder.finish(List.of(f.first), "COMPLETED", null);
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(ignored -> {
            entered.countDown(); Assert.assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            if (fail) { throw new IllegalStateException("late provenance fault"); }
            return new AiTargetProvenance();
        }).when(recorder).targetProvenance(f.first);
        final var tracker = new AiActionTracker(f.game);
        final var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            final var work = threads.submit(() -> register(tracker, event, List.of(f.first), recorder));
            Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            threads.submit(() -> {
                if (retire) { tracker.retire(); }
                else {
                    tracker.abandonPending("SUPERSEDED_BY_NEXT_SELECTION");
                    final var next = new AiDecisionRecorder(f.game, f.ai, 92);
                    register(tracker, next.finish(List.of(f.second), "COMPLETED", null), List.of(f.second), next);
                }
            }).get(2, java.util.concurrent.TimeUnit.SECONDS);
            release.countDown(); work.get(5, java.util.concurrent.TimeUnit.SECONDS);
            Assert.assertNull(tracker.begin(f.first));
            if (!retire) { Assert.assertNotNull(tracker.begin(f.second)); }
            Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); threads.shutdownNow(); tracker.retire(); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void mutableChosenListCannotMakeAnActionAliasBorrowAnotherActionsIdentity() throws Exception {
        final Fixture f = fixture(); final var ability = org.mockito.Mockito.spy(f.first);
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(ignored -> {
            entered.countDown(); Assert.assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return f.first.getDescription();
        }).when(ability).getDescription();
        final List<SpellAbility> chosen = new ArrayList<>(List.of(ability));
        final var thread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            final var work = thread.submit(() -> f.brain.publishDecision(f.recorder, chosen, "COMPLETED", null));
            Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            chosen.set(0, f.second); release.countDown(); work.get(5, java.util.concurrent.TimeUnit.SECONDS);
            Assert.assertNull(f.brain.actionTracker().begin(f.second)); Assert.assertNotNull(f.brain.actionTracker().begin(ability));
            Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); thread.shutdownNow(); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aDeadlineDuringPreparationLeavesTheTimeoutRetryAbleToPublish() {
        final Fixture f = fixture(); final var ability = org.mockito.Mockito.spy(f.first);
        org.mockito.Mockito.doAnswer(ignored -> { AITimeoutTracker.check(); return f.first.getDescription(); }).when(ability).getDescription();
        final Object limit = AITimeoutTracker.arm(System.nanoTime() - 1, false);
        try { Assert.expectThrows(AITimeoutTracker.Expired.class, () -> f.brain.publishDecision(f.recorder, List.of(ability), "COMPLETED", null)); }
        finally { AITimeoutTracker.disarm(limit); }
        f.brain.publishDecision(f.recorder, null, "AI_TIMEOUT", null);
        Assert.assertEquals(f.events.size(), 1); Assert.assertEquals(f.events.get(0).data().get("status"), "AI_TIMEOUT");
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void subscriberFailureIsVisibleWithoutRollingBackSuccessfulPublication() {
        final Fixture f = fixture(); final var failure = new IllegalStateException("subscriber fault");
        f.game.observeEventEmission(event -> { if (event instanceof AiDecisionEvent) { throw failure; } });
        f.brain.publishDecision(f.recorder, List.of(f.first), "COMPLETED", null);
        Assert.assertSame(f.game.getEventObservationFailure(), failure); Assert.assertEquals(f.events.size(), 1);
        final var attempt = f.brain.actionTracker().begin(f.first); Assert.assertNotNull(attempt);
        f.brain.actionTracker().finish(attempt, "FAILED", "TEST_FINISHED");
    }

    @Test(dataProvider = "lateFailures", groups = {"UnitTest", "fast"})
    public void emitterFailureRemovesOnlyThisBatchsUnstartedAliases(final boolean started) {
        final Fixture f = fixture(); final var game = org.mockito.Mockito.spy(f.game);
        final var brain = new AiController(f.ai, game); final var failure = new IllegalStateException("emitter fault");
        final AtomicReference<AiActionTracker.Attempt> attempt = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (invocation.getArgument(0) instanceof AiDecisionEvent) {
                if (started) { attempt.set(brain.actionTracker().begin(f.first)); }
                throw failure;
            }
            return invocation.callRealMethod();
        }).when(game).fireEvent(org.mockito.ArgumentMatchers.any(Event.class));
        brain.publishDecision(f.recorder, List.of(f.first), "COMPLETED", null);
        Assert.assertSame(game.getEventObservationFailure(), failure);
        if (started) {
            Assert.assertNotNull(attempt.get()); Assert.assertNotNull(brain.getActionReference(f.first));
            brain.actionTracker().finish(attempt.get(), "FAILED", "TEST_FINISHED");
        } else { Assert.assertNull(brain.actionTracker().begin(f.first)); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void failedParentConstructionDoesNotReplaceTheRealLandChoice() {
        final Game game = initAndCreateGame(); final Player ai = game.getPlayers().get(1);
        final var land = addCardToZone("Island", ai, ZoneType.Hand);
        for (final Player player : game.getPlayers()) { fillLibrary(player, 20); }
        game.AI_FLAT_MONTE_CARLO = false; game.setRecordAiDecisions(true);
        final var failure = new IllegalStateException("legacy projection fault");
        try (var context = org.mockito.Mockito.mockStatic(AiInputContext.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            context.when(() -> AiInputContext.legacyObservation(game, ai)).thenThrow(failure);
            final var chosen = ((PlayerControllerAi) ai.getController()).chooseSpellAbilityToPlay();
            Assert.assertNotNull(chosen); Assert.assertSame(chosen.get(0).getHostCard(), land);
        }
        Assert.assertSame(game.getEventObservationFailure(), failure);
    }
    @Test(dataProvider = "lateFailures", groups = {"UnitTest", "fast"})
    public void selectionScopeIsCapturedBeforeProjectionAndMissingParentFailuresStayVisible(final boolean fail) throws Exception {
        final Fixture f = fixture(); final var ability = org.mockito.Mockito.spy(f.first);
        final var failure = new IllegalStateException("superseded parent projection fault");
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(ignored -> {
            entered.countDown(); Assert.assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            if (fail) { throw failure; }
            return f.first.getDescription();
        }).when(ability).getDescription();
        final var thread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            final var work = thread.submit(() -> f.brain.publishDecision(f.recorder, List.of(ability), "COMPLETED", null));
            Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            f.brain.actionTracker().abandonPending("SUPERSEDED_BY_NEXT_SELECTION");
            f.brain.publishDecision(new AiDecisionRecorder(f.game, f.ai, 92), List.of(f.second), "COMPLETED", null);
            release.countDown(); work.get(5, java.util.concurrent.TimeUnit.SECONDS);
            Assert.assertNull(f.brain.actionTracker().begin(ability));
            Assert.assertNotNull(f.brain.actionTracker().begin(f.second));
            Assert.assertEquals(f.events.size(), fail ? 1 : 2);
            // Lost parent evidence is different from obsolete alias preparation: it remains a capture fault.
            if (fail) { Assert.assertSame(f.game.getEventObservationFailure(), failure); }
            else { Assert.assertNull(f.game.getEventObservationFailure()); }
        } finally { release.countDown(); thread.shutdownNow(); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aDeadlinePreparingProvenanceDoesNotSpendThePublicationClaim() {
        final Fixture f = fixture(); final var recorder = org.mockito.Mockito.spy(f.recorder);
        org.mockito.Mockito.doAnswer(ignored -> { AITimeoutTracker.check(); return new AiTargetProvenance(); })
                .when(recorder).targetProvenance(f.first);
        final Object limit = AITimeoutTracker.arm(System.nanoTime() - 1, false);
        try { Assert.expectThrows(AITimeoutTracker.Expired.class, () -> f.brain.publishDecision(recorder, List.of(f.first), "COMPLETED",
                forge.ai.simulation.FlatMonteCarloPicker.Result.keepHeuristic(f.first, "kept-heuristic"))); }
        finally { AITimeoutTracker.disarm(limit); }
        f.brain.publishDecision(recorder, null, "AI_TIMEOUT", null);
        Assert.assertEquals(f.events.size(), 1); Assert.assertEquals(f.events.get(0).data().get("status"), "AI_TIMEOUT");
        Assert.assertFalse(f.events.get(0).data().containsKey("search"), "Timeout retry cannot inherit a different preparation's search summary");
        Assert.assertNull(f.brain.actionTracker().begin(f.first)); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] constructionBoundaries() { return new Object[][] {{"legacyObservation"}, {"capture"}}; }

    @Test(dataProvider = "constructionBoundaries", groups = {"UnitTest", "fast"})
    public void deadlineThrownByCaptureConstructionIsNotATraceFailure(final String boundary) {
        final Game game = initAndCreateGame(); final Player ai = game.getPlayers().get(1);
        addCardToZone("Island", ai, ZoneType.Hand);
        for (final Player player : game.getPlayers()) { fillLibrary(player, 20); }
        game.AI_TIMEOUT = 10; game.AI_FLAT_MONTE_CARLO = false; game.setRecordAiDecisions(true);
        try (var context = org.mockito.Mockito.mockStatic(AiInputContext.class, invocation -> {
            if (boundary.equals(invocation.getMethod().getName())) { AITimeoutTracker.check(); }
            return invocation.callRealMethod();
        })) {
            final Object limit = AITimeoutTracker.arm(System.nanoTime() - 1, false);
            try { Assert.assertNull(((PlayerControllerAi) ai.getController()).chooseSpellAbilityToPlay()); }
            finally { AITimeoutTracker.disarm(limit); }
        }
        Assert.assertNull(game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void disabledCaptureDoesNotInspectConstructionInputsOrChangeTheLandChoice() {
        final Game game = initAndCreateGame(); final Player ai = game.getPlayers().get(1);
        final var land = addCardToZone("Island", ai, ZoneType.Hand);
        for (final Player player : game.getPlayers()) { fillLibrary(player, 20); }
        game.AI_FLAT_MONTE_CARLO = false; game.setRecordAiDecisions(false);
        try (var context = org.mockito.Mockito.mockStatic(AiInputContext.class, invocation -> {
            throw new AssertionError("Disabled decision capture must not inspect input");
        })) {
            final var chosen = ((PlayerControllerAi) ai.getController()).chooseSpellAbilityToPlay();
            Assert.assertNotNull(chosen); Assert.assertSame(chosen.get(0).getHostCard(), land);
            context.verifyNoInteractions();
        }
        Assert.assertNull(game.getEventObservationFailure());
    }

    @Test(dataProvider = "constructionBoundaries", groups = {"UnitTest", "fast"})
    public void constructionFailureAfterRecordingClosesCannotPoisonHealth(final String boundary) throws Exception {
        final Game game = initAndCreateGame(); final Player ai = game.getPlayers().get(1);
        final var land = addCardToZone("Island", ai, ZoneType.Hand);
        for (final Player player : game.getPlayers()) { fillLibrary(player, 20); }
        game.AI_FLAT_MONTE_CARLO = false; game.setRecordAiDecisions(true);
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final var thread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            final var work = thread.submit(() -> {
                try (var context = org.mockito.Mockito.mockStatic(AiInputContext.class, invocation -> {
                    if (boundary.equals(invocation.getMethod().getName())) {
                        entered.countDown(); Assert.assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        throw new IllegalStateException("late construction fault");
                    }
                    return invocation.callRealMethod();
                })) { return ((PlayerControllerAi) ai.getController()).chooseSpellAbilityToPlay(); }
            });
            Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            game.setRecordAiDecisions(false); release.countDown();
            final var chosen = work.get(5, java.util.concurrent.TimeUnit.SECONDS);
            Assert.assertNotNull(chosen); Assert.assertSame(chosen.get(0).getHostCard(), land);
            Assert.assertNull(game.getEventObservationFailure());
        } finally { release.countDown(); thread.shutdownNow(); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void provenanceSnapshotDoesNotShareMutableOriginListStorage() {
        final Fixture f = fixture(); final var provenance = new AiTargetProvenance();
        final var target = java.util.Map.<String, Object>of("type", "PLAYER", "id", f.game.getPlayers().get(0).getId());
        final var origins = new ArrayList<AiTargetProvenance.Origin>();
        provenance.added(f.first.getTargets().getSelectionStateIdentity(), origins,
                new AiTargetProvenance.Origin("first-origin", f.ai.getId(), target));
        final var snapshot = provenance.snapshot();
        origins.set(0, new AiTargetProvenance.Origin("later-origin", f.ai.getId(), target));
        final var group = (java.util.Map<?, ?>) ((List<?>) snapshot.capture(f.first).get("abilities")).get(0);
        final var origin = (java.util.Map<?, ?>) ((List<?>) group.get("targetChoices")).get(0);
        Assert.assertEquals(origin.get("choiceId"), "first-origin");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void repeatedPublicationCannotAddAnotherDecisionOrReplaceItsAliases() {
        final Fixture f = fixture();
        f.brain.publishDecision(f.recorder, List.of(f.first), "COMPLETED", null);
        f.brain.publishDecision(f.recorder, List.of(f.second), "COMPLETED", null);
        Assert.assertEquals(f.events.size(), 1);
        Assert.assertNull(f.brain.actionTracker().begin(f.second));
        Assert.assertNotNull(f.brain.actionTracker().begin(f.first));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    @SuppressWarnings("unchecked")
    public void skippedPublicationRetiresItsParentAcrossRecordingReenable() throws Exception {
        final Fixture f = fixture(); f.recorder.beginAssessment(f.first);
        final List<AiChoiceEvent> choices = new CopyOnWriteArrayList<>();
        f.game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { choices.add(choice); } });
        f.game.setRecordAiDecisions(false);
        f.brain.publishDecision(f.recorder, List.of(f.first), "COMPLETED", null);
        f.game.setRecordAiDecisions(true);
        final var field = AiController.class.getDeclaredField("evaluationDecision"); field.setAccessible(true);
        final var context = (ThreadLocal<AiDecisionRecorder>) field.get(f.brain);
        final var callbacks = new java.util.concurrent.atomic.AtomicInteger();
        context.set(f.recorder);
        try {
            final int value = AiChoiceRecorder.observe(f.ai, f.first, "TARGET_SELECTION_RESULT", null, "CONTROLLER_TARGETS",
                    () -> { throw new AssertionError("Retired parent options must not be inspected"); },
                    () -> { callbacks.incrementAndGet(); return 42; },
                    ignored -> { throw new AssertionError("Retired parent result must not be inspected"); });
            Assert.assertEquals(value, 42);
        } finally { context.remove(); }
        f.brain.publishDecision(f.recorder, List.of(f.first), "COMPLETED", null);
        Assert.assertFalse(f.recorder.choiceCaptureOpen()); Assert.assertFalse(f.recorder.publicationOpen());
        Assert.assertEquals(callbacks.get(), 1); Assert.assertTrue(choices.isEmpty()); Assert.assertTrue(f.events.isEmpty());
        Assert.assertNull(f.game.getEventObservationFailure());
    }

}
