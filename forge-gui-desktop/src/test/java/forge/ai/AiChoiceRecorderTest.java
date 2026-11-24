package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
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

public class AiChoiceRecorderTest extends AITest {
    private record Fixture(Game game, Player ai, SpellAbility ability, List<Map<String, Object>> events) { }

    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility ability = addCardToZone("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(ai);
        game.setRecordAiDecisions(true);
        final List<Map<String, Object>> events = new ArrayList<>();
        game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { events.add(choice.data()); } });
        return new Fixture(game, ai, ability, events);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void callbackRunsOnceAndThePriorPlanIsFrozenBeforeItsMutation() {
        final Fixture f = fixture();
        final AtomicInteger calls = new AtomicInteger();
        final boolean result = AiChoiceRecorder.targeting(f.ai, f.ability, "EXECUTION", () -> {
            calls.incrementAndGet();
            f.ability.getTargets().add(f.game.getPlayers().get(0));
            return false;
        });
        Assert.assertFalse(result);
        Assert.assertEquals(calls.get(), 1);
        Assert.assertEquals(f.events.size(), 2);
        Assert.assertEquals(f.events.get(0).get("choiceId"), f.events.get(1).get("choiceId"));
        final Map<?, ?> options = (Map<?, ?>) f.events.get(0).get("options");
        final Map<?, ?> before = (Map<?, ?>) ((List<?>) ((Map<?, ?>) options.get("priorTargets")).get("abilities")).get(0);
        Assert.assertTrue(((List<?>) before.get("targets")).isEmpty());
        final Map<?, ?> selected = (Map<?, ?>) f.events.get(1).get("selection");
        Assert.assertEquals(selected.get("accepted"), false);
        final Map<?, ?> after = (Map<?, ?>) ((List<?>) ((Map<?, ?>) selected.get("targets")).get("abilities")).get(0);
        Assert.assertEquals(((List<?>) after.get("targets")).size(), 1);
        Assert.expectThrows(UnsupportedOperationException.class, () -> f.events.get(0).put("private", "text"));
    }

    @DataProvider public Object[][] disabled() { return new Object[][] {{false}, {true}}; }

    @Test(dataProvider = "disabled", groups = {"UnitTest", "fast"})
    public void disabledAndCopiedGamesNeverEvaluateObservationalSuppliers(final boolean copy) {
        final Fixture f = fixture();
        if (copy) { f.game.markAsSimulationCopy(); } else { f.game.setRecordAiDecisions(false); }
        final Integer result = AiChoiceRecorder.observe(f.ai, f.ability, "TARGET_SELECTION_RESULT", null, "CONTROLLER_TARGETS",
                () -> { throw new IllegalStateException("must not snapshot"); }, () -> 42,
                ignored -> { throw new IllegalStateException("must not snapshot"); });
        Assert.assertEquals(result, Integer.valueOf(42));
        Assert.assertTrue(f.events.isEmpty());
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void nestedCallbacksHaveAnExplicitParentChoiceAndRestoreTheirThreadScope() {
        final Fixture f = fixture();
        AiChoiceRecorder.targeting(f.ai, f.ability, "EXECUTION",
                () -> AiChoiceRecorder.targeting(f.ai, f.ability, "EXECUTION", () -> true));
        Assert.assertEquals(f.events.get(1).get("parentChoiceId"), f.events.get(0).get("choiceId"));
        Assert.assertEquals(f.events.get(1).get("choiceId"), f.events.get(2).get("choiceId"));
        Assert.assertEquals(f.events.get(0).get("choiceId"), f.events.get(3).get("choiceId"));
        AiChoiceRecorder.targeting(f.ai, f.ability, "EXECUTION", () -> true);
        Assert.assertNull(f.events.get(4).get("parentChoiceId"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void executionHelperPoolsAreRecordedUnderTheirActualTargetingCallback() {
        final Fixture f = fixture();
        final Card bear = addCardToZone("Grizzly Bears", f.game.getPlayers().get(0), ZoneType.Battlefield);
        final Card dragon = addCardToZone("Shivan Dragon", f.game.getPlayers().get(0), ZoneType.Battlefield);
        final List<Card> pool = new ArrayList<>(List.of(bear, dragon));
        AiChoiceRecorder.targeting(f.ai, f.ability, "EXECUTION", () ->
                AiTargetChoiceRecorder.select(f.ai, f.ability, pool, "DAMAGE_KILLABLES", values -> {
                    values.clear(); return bear;
                }).addToTargets(f.ability));
        Assert.assertEquals(f.events.size(), 4);
        Assert.assertEquals(f.events.get(1).get("choiceType"), "TARGET_POOL_SELECTION");
        Assert.assertEquals(f.events.get(1).get("stage"), "EXECUTION");
        Assert.assertEquals(f.events.get(1).get("parentChoiceId"), f.events.get(0).get("choiceId"));
        Assert.assertEquals(((List<?>) ((Map<?, ?>) f.events.get(1).get("options")).get("items")).size(), 2);
        Assert.assertEquals(((Map<?, ?>) ((Map<?, ?>) f.events.get(2).get("selection")).get("selected")).get("id"), bear.getId());
        Assert.assertSame(f.ability.getTargets().getFirstTargetedCard(), bear);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void originalCallbackErrorIsRecordedAndRethrownWithoutItsPrivateMessage() {
        final Fixture f = fixture();
        final var error = new IllegalArgumentException("private card name");
        Assert.assertSame(Assert.expectThrows(IllegalArgumentException.class, () ->
                AiChoiceRecorder.targeting(f.ai, f.ability, "EXECUTION", () -> { throw error; })), error);
        Assert.assertEquals(f.events.get(1).get("status"), "ERROR");
        Assert.assertFalse(f.events.toString().contains("private card name"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void snapshotFailureIsVisibleWithoutReplacingTheCallbackResult() {
        final Fixture f = fixture();
        final var error = new IllegalStateException("snapshot");
        final Integer result = AiChoiceRecorder.observe(f.ai, f.ability, "TARGET_SELECTION_RESULT", null, "CONTROLLER_TARGETS",
                () -> { throw error; }, () -> 42, ignored -> null);
        Assert.assertEquals(result, Integer.valueOf(42));
        Assert.assertSame(f.game.getEventObservationFailure(), error);
        Assert.assertTrue(f.events.isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void hiddenSourcesMaskBothOptionsAndResultsWithoutInspectingThem() {
        final Fixture f = fixture();
        f.ability.getHostCard().turnFaceDown();
        final Integer result = AiChoiceRecorder.observe(f.ai, f.ability, "TARGET_SELECTION_RESULT", null, "CONTROLLER_TARGETS",
                () -> { throw new IllegalStateException("private options must not be inspected"); }, () -> 42,
                ignored -> { throw new IllegalStateException("private selection must not be inspected"); });
        Assert.assertEquals(result, Integer.valueOf(42));
        Assert.assertEquals(f.events.get(0).get("choiceType"), "MASKED_CHOICE");
        Assert.assertEquals(f.events.get(0).get("boundary"), "MASKED_CHOICE");
        Assert.assertNull(f.events.get(1).get("selection"));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    @SuppressWarnings("unchecked")
    public void lateEvaluationCompletionDoesNotReadOrEmitIntoTheNextDecision() throws Exception {
        final Fixture f = fixture();
        final AiController brain = ((PlayerControllerAi) f.ai.getController()).getAi();
        final var field = AiController.class.getDeclaredField("evaluationDecision"); field.setAccessible(true);
        final ThreadLocal<AiDecisionRecorder> context = (ThreadLocal<AiDecisionRecorder>) field.get(brain);
        final AiDecisionRecorder recorder = new AiDecisionRecorder(f.game, f.ai, 1);
        recorder.beginAssessment(f.ability); context.set(recorder);
        try {
            final Integer result = AiChoiceRecorder.observe(f.ai, f.ability, "TARGET_SELECTION_RESULT", null, "CONTROLLER_TARGETS",
                    () -> Map.of("coverage", "PRIOR_TARGET_PLAN_ONLY", "priorTargets", AiChoiceRecorder.targetPlan(f.ability)),
                    () -> {
                        recorder.finish(null, "AI_TIMEOUT", null);
                        AiChoiceRecorder.targeting(f.ai, f.ability, null, () -> true);
                        return 42;
                    },
                    ignored -> { throw new IllegalStateException("closed selection must not be read"); });
            Assert.assertEquals(result, Integer.valueOf(42));
        } finally { context.remove(); }
        Assert.assertEquals(f.events.size(), 1);
        Assert.assertEquals(f.events.get(0).get("stage"), "CANDIDATE_EVALUATION");
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void realDelegatedControllerReportsTheChooserSeparatelyFromTheActionOwner() {
        final Game game = initAndCreateThreePlayerGame();
        final Player owner = game.getPlayers().get(0), chooser = game.getPlayers().get(1);
        final Card target = addCardToZone("Grizzly Bears", game.getPlayers().get(2), ZoneType.Battlefield);
        final SpellAbility ability = addCardToZone("Doom Blade", owner, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(owner); ability.setTargetingPlayer(chooser);
        game.setRecordAiDecisions(true);
        final List<Map<String, Object>> events = new ArrayList<>();
        game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { events.add(choice.data()); } });
        final AiController brain = ((PlayerControllerAi) owner.getController()).getAi();
        brain.publishDecision(new AiDecisionRecorder(game, owner, 1), List.of(ability), "COMPLETED", null);
        final var attempt = brain.actionTracker().begin(ability);
        // Deferred targeting runs after the spell has moved out of its owner's private hand.
        ability.setHostCard(game.getAction().moveToStack(ability.getHostCard(), ability));
        final boolean accepted = chooser.getController().chooseTargetsFor(ability);
        final var start = events.stream().filter(e -> "TARGET_SELECTION_RESULT".equals(e.get("choiceType"))).findFirst().orElseThrow();
        final var terminal = events.stream().filter(e -> start.get("choiceId").equals(e.get("choiceId")) && !"STARTED".equals(e.get("status"))).findFirst().orElseThrow();
        Assert.assertEquals(start.get("stage"), "DELEGATED_TARGET_SELECTION");
        Assert.assertEquals(start.get("chooserPlayerId"), chooser.getId());
        Assert.assertEquals(start.get("actionId"), owner.getId() + ":1:0");
        Assert.assertEquals(((Map<?, ?>) terminal.get("selection")).get("accepted"), accepted);
        Assert.assertFalse(ability.getTargets().isEmpty());
        Assert.assertTrue(ability.getTargets().getTargetCards().contains(target));
        brain.actionTracker().finish(attempt, "FAILED", "TEST_FINISHED");
    }

    @Test(groups = {"UnitTest", "fast"})
    @SuppressWarnings("unchecked")
    public void repeatedAssessmentsHaveExplicitLinksAndUnreferencedAssessmentsNeedNoExtraWireId() throws Exception {
        final Fixture f = fixture();
        final var brain = ((PlayerControllerAi) f.ai.getController()).getAi();
        final var field = AiController.class.getDeclaredField("evaluationDecision"); field.setAccessible(true);
        final var context = (ThreadLocal<AiDecisionRecorder>) field.get(brain);
        final var recorder = new AiDecisionRecorder(f.game, f.ai, 5);
        context.set(recorder);
        try {
            for (int i = 0; i < 2; i++) {
                recorder.beginAssessment(f.ability);
                AiChoiceRecorder.targeting(f.ai, f.ability, null, () -> true);
                recorder.evaluated(f.ability, "WillPlay");
            }
            recorder.beginAssessment(f.ability);
            recorder.evaluated(f.ability, "CantPlayAi");
        } finally { context.remove(); }
        final List<?> candidates = (List<?>) recorder.finish(null, "COMPLETED", null).data().get("candidates");
        Assert.assertNotEquals(f.events.get(0).get("assessmentId"), f.events.get(2).get("assessmentId"));
        Assert.assertEquals(((Map<?, ?>) candidates.get(0)).get("assessmentId"), f.events.get(0).get("assessmentId"));
        Assert.assertEquals(((Map<?, ?>) candidates.get(1)).get("assessmentId"), f.events.get(2).get("assessmentId"));
        Assert.assertFalse(((Map<?, ?>) candidates.get(2)).containsKey("assessmentId"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void normalSimultaneousTriggerPreparationRetainsItsStageAndActualTargetPlan() {
        final Fixture f = fixture();
        final Card host = addCardToZone("Flametongue Kavu", f.ai, ZoneType.Battlefield);
        final Player opponent = f.game.getPlayers().get(0);
        final Card bear = addCardToZone("Grizzly Bears", opponent, ZoneType.Battlefield);
        final Card courser = addCardToZone("Nessian Courser", opponent, ZoneType.Battlefield);
        final var trigger = host.getTriggers().get(0);
        final SpellAbility inner = trigger.ensureAbility();
        inner.setActivatingPlayer(f.ai);
        final var wrapper = new forge.game.trigger.WrappedAbility(trigger, inner, null);
        f.ai.getController().orderAndPlaySimultaneousSa(List.of(wrapper));
        final var start = f.events.stream().filter(e -> "TRIGGER_PREPARATION".equals(e.get("boundary"))
                && "TRIGGER_PREPARATION".equals(e.get("stage"))).findFirst().orElseThrow();
        final var terminal = f.events.stream().filter(e -> start.get("choiceId").equals(e.get("choiceId"))
                && "COMPLETED".equals(e.get("status"))).findFirst().orElseThrow();
        Assert.assertEquals(((Map<?, ?>) terminal.get("selection")).get("accepted"), true);
        Assert.assertEquals(wrapper.getTargets().size(), 1);
        Assert.assertTrue(wrapper.getTargets().getTargetCards().contains(bear) || wrapper.getTargets().getTargetCards().contains(courser));
        final var targetPlan = (Map<?, ?>) ((Map<?, ?>) terminal.get("selection")).get("targets");
        final var group = (Map<?, ?>) ((List<?>) targetPlan.get("abilities")).get(0);
        Assert.assertEquals(((List<?>) group.get("targets")).size(), 1);
        Assert.assertFalse(f.game.getStack().isEmpty());
        Assert.assertNull(start.get("actionId"));
        Assert.assertTrue(f.events.stream().anyMatch(e -> start.get("choiceId").equals(e.get("parentChoiceId"))
                && "TRIGGER_PREPARATION".equals(e.get("stage"))));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void existingUnsupportedRetargetCallbackRemainsADeclineRatherThanInventedAiBehavior() {
        final Fixture f = fixture();
        Assert.assertNull(f.ai.getController().chooseNewTargetsFor(f.ability, ignored -> true, true));
        Assert.assertEquals(f.events.get(0).get("stage"), "RETARGET_SELECTION");
        Assert.assertEquals(f.events.get(1).get("status"), "DECLINED");
    }
    @DataProvider public Object[][] lateContextGetters() { return new Object[][] {{false, false}, {true, false}, {false, true}, {true, true}}; }
    @DataProvider public Object[][] lateAdmissionGetters() { return new Object[][] {{false, 1}, {true, 1}, {false, 2}, {true, 2}}; }

    @Test(dataProvider = "lateContextGetters", groups = {"UnitTest", "fast"})
    public void assessmentContextGetterCannotBlockParentCloseOrReturnALateAssociation(final boolean fail, final boolean identifier) throws Exception {
        final Fixture f = fixture(); final var recorder = new AiDecisionRecorder(f.game, f.ai, 17);
        final var ability = org.mockito.Mockito.spy(f.ability);
        recorder.beginAssessment(ability);
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final org.mockito.stubbing.Answer<Object> block = ignored -> {
            entered.countDown(); Assert.assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            if (fail) { throw new IllegalStateException("late context failure"); }
            return identifier ? f.ability.getId() : ability;
        };
        if (identifier) { org.mockito.Mockito.doAnswer(block).when(ability).getId(); }
        else { org.mockito.Mockito.doAnswer(block).when(ability).getRootAbility(); }
        final var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            final var work = threads.submit(() -> recorder.choiceContext(ability));
            Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            final var event = threads.submit(() -> recorder.finish(null, "AI_TIMEOUT", null)).get(2, java.util.concurrent.TimeUnit.SECONDS);
            final String frozen = event.data().toString(); release.countDown();
            Assert.assertNull(work.get(5, java.util.concurrent.TimeUnit.SECONDS));
            Assert.assertEquals(event.data().toString(), frozen); Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test(dataProvider = "lateAdmissionGetters", groups = {"UnitTest", "fast"})
    @SuppressWarnings("unchecked")
    public void closedAssessmentDuringStandaloneAdmissionCannotBecomeAnUnparentedChoice(final boolean fail, final int blockCall) throws Exception {
        final Fixture f = fixture(); final var recorder = new AiDecisionRecorder(f.game, f.ai, 18);
        recorder.beginAssessment(f.ability);
        final var ability = org.mockito.Mockito.spy(f.ability);
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final AtomicInteger getterCalls = new AtomicInteger();
        org.mockito.Mockito.doAnswer(ignored -> {
            if (getterCalls.incrementAndGet() != blockCall) { return f.ability.getRootAbility(); }
            entered.countDown(); Assert.assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            if (fail) { throw new IllegalStateException("late admission failure"); }
            return f.ability.getRootAbility();
        }).when(ability).getRootAbility();
        final var brain = ((PlayerControllerAi) f.ai.getController()).getAi();
        final var field = AiController.class.getDeclaredField("evaluationDecision"); field.setAccessible(true);
        final var context = (ThreadLocal<AiDecisionRecorder>) field.get(brain);
        final AtomicInteger callbacks = new AtomicInteger();
        final var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            final var work = threads.submit(() -> {
                context.set(recorder);
                try { return AiChoiceRecorder.targeting(f.ai, ability, null, () -> { callbacks.incrementAndGet(); return true; }); }
                finally { context.remove(); }
            });
            Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            final var event = threads.submit(() -> recorder.finish(null, "AI_TIMEOUT", null)).get(2, java.util.concurrent.TimeUnit.SECONDS);
            final String frozen = event.data().toString(); release.countDown();
            Assert.assertTrue(work.get(5, java.util.concurrent.TimeUnit.SECONDS));
            Assert.assertEquals(callbacks.get(), 1); Assert.assertTrue(f.events.isEmpty());
            Assert.assertEquals(event.data().toString(), frozen); Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void assessmentChangedDuringGetterWorkCannotAcquireTheReplacementAssessmentId() throws Exception {
        final Fixture f = fixture(); final var recorder = new AiDecisionRecorder(f.game, f.ai, 19);
        recorder.beginAssessment(f.ability);
        final var ability = org.mockito.Mockito.spy(f.ability);
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(ignored -> {
            entered.countDown(); Assert.assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); return f.ability.getRootAbility();
        }).when(ability).getRootAbility();
        final SpellAbility replacement = addCardToZone("Doom Blade", f.ai, ZoneType.Hand).getFirstSpellAbility();
        replacement.setActivatingPlayer(f.ai);
        final var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            final var work = threads.submit(() -> recorder.choiceContext(ability));
            Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            threads.submit(() -> recorder.beginAssessment(replacement)).get(2, java.util.concurrent.TimeUnit.SECONDS);
            release.countDown(); Assert.assertNull(work.get(5, java.util.concurrent.TimeUnit.SECONDS));
            final var current = recorder.choiceContext(replacement); Assert.assertNotNull(current);
            Assert.assertEquals(current.get("candidateAbilityId"), replacement.getId());
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test(groups = {"UnitTest", "fast"})
    @SuppressWarnings("unchecked")
    public void activeAssessmentMetadataFailureStaysVisibleAndPreservesTheOriginalCallback() throws Exception {
        final Fixture f = fixture(); final var recorder = new AiDecisionRecorder(f.game, f.ai, 20);
        recorder.beginAssessment(f.ability);
        final var ability = org.mockito.Mockito.spy(f.ability);
        final var failure = new IllegalStateException("active metadata failure");
        org.mockito.Mockito.doThrow(failure).when(ability).getRootAbility();
        final var brain = ((PlayerControllerAi) f.ai.getController()).getAi();
        final var field = AiController.class.getDeclaredField("evaluationDecision"); field.setAccessible(true);
        final var context = (ThreadLocal<AiDecisionRecorder>) field.get(brain);
        final AtomicInteger callbacks = new AtomicInteger();
        context.set(recorder);
        try {
            Assert.assertTrue(AiChoiceRecorder.targeting(f.ai, ability, null, () -> { callbacks.incrementAndGet(); return true; }));
        } finally { context.remove(); }
        Assert.assertEquals(callbacks.get(), 1); Assert.assertTrue(f.events.isEmpty());
        Assert.assertSame(f.game.getEventObservationFailure(), failure);
    }

}
