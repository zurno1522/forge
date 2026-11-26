package forge.ai;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AiTriggerPreparationInputTest extends AITest {
    private record Fixture(Game game, Player player, Player other, SpellAbility ability, Card target, List<Map<String, Object>> events) { }
    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player player = game.getPlayers().get(1), other = game.getPlayers().get(0);
        final var host = addCardToZone("Time Beetle", player, ZoneType.Battlefield);
        final var ability = AbilityFactory.getAbility("AB$ DealDamage | Cost$ 0 | ValidTgts$ Creature.OppCtrl | TgtPrompt$ Choose | NumDmg$ 1", host);
        ability.setActivatingPlayer(player); final var target = addCardToZone("Grizzly Bears", other, ZoneType.Battlefield);
        final var events = new ArrayList<Map<String, Object>>(); game.setRecordAiDecisions(true);
        game.observeEventEmission(e -> { if (e instanceof AiChoiceEvent c) { events.add(c.data()); } });
        return new Fixture(game, player, other, ability, target, events);
    }
    private Map<?, ?> context(Map<String, Object> row) { return (Map<?, ?>) row.get("inputContext"); }
    private Map<?, ?> parameters(Map<String, Object> row) { return (Map<?, ?>) ((Map<?, ?>) context(row).get("policy")).get("receivedParameters"); }
    private Map<String, Object> start(Fixture f) { return f.events.stream().filter(c -> "TRIGGER_PREPARATION".equals(c.get("boundary"))).findFirst().orElseThrow(); }

    @Test(groups = {"UnitTest", "fast"})
    public void preparationCapturesItsEntryDirectiveAndPriorPlanBeforeTheOriginalWork() {
        final Fixture f = fixture(); final var calls = new AtomicInteger();
        Assert.assertFalse(AiChoiceRecorder.triggerPreparation(f.player, f.ability, false, () -> {
            calls.incrementAndGet(); f.player.setLife(7, null); f.game.getPhaseHandler().devModeSet(PhaseType.MAIN2, f.player);
            f.ability.getTargets().add(f.target); return false;
        }));
        final var start = start(f); Assert.assertEquals(parameters(start), Map.of("kind", "TRIGGER_REQUIREMENT", "mandatory", false));
        Assert.assertEquals(start.get("stage"), "TRIGGER_PREPARATION");
        final var entry = (Map<?, ?>) start.get("entryLocation"); Assert.assertEquals(entry.get("kind"), "OPERATION");
        Assert.assertEquals(entry.get("timing"), "BEFORE_OPERATION"); Assert.assertEquals(entry.get("phase"), "MAIN1");
        final var policy = (Map<?, ?>) context(start).get("policy"); Assert.assertEquals(((Map<?, ?>) ((List<?>) policy.get("players")).get(1)).get("life"), 20);
        final var prior = (Map<?, ?>) ((Map<?, ?>) start.get("options")).get("priorTargets");
        Assert.assertTrue(((List<?>) ((Map<?, ?>) ((List<?>) prior.get("abilities")).get(0)).get("targets")).isEmpty());
        Assert.assertEquals(f.events.get(1).get("status"), "COMPLETED"); Assert.assertEquals(((Map<?, ?>) f.events.get(1).get("selection")).get("accepted"), false);
        Assert.assertEquals(f.ability.getTargets().size(), 1); Assert.assertEquals(calls.get(), 1); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualSimultaneousPreparationForwardsMandatoryAndChoosesTheOriginalTarget() {
        final Fixture f = fixture(); final var host = addCardToZone("Flametongue Kavu", f.player, ZoneType.Battlefield);
        final var trigger = host.getTriggers().get(0); final var inner = trigger.ensureAbility(); inner.setActivatingPlayer(f.player);
        final var wrapped = new WrappedAbility(trigger, inner, null); wrapped.setActivatingPlayer(f.player);
        f.player.getController().orderAndPlaySimultaneousSa(List.of(wrapped));
        Assert.assertEquals(parameters(start(f)), Map.of("kind", "TRIGGER_REQUIREMENT", "mandatory", true));
        Assert.assertTrue(wrapped.getTargets().getTargetCards().contains(f.target)); Assert.assertFalse(f.game.getStack().isEmpty());
        Assert.assertTrue(f.events.stream().anyMatch(c -> start(f).get("choiceId").equals(c.get("parentChoiceId"))));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualImmediatePreparationForwardsAnOptionalDirective() {
        final Fixture f = fixture(); final var host = addCardToZone("Flametongue Kavu", f.player, ZoneType.Battlefield);
        final var trigger = host.getTriggers().get(0); final var inner = trigger.ensureAbility(); inner.setActivatingPlayer(f.player);
        final var wrapped = new WrappedAbility(trigger, inner, null); wrapped.setActivatingPlayer(f.player);
        Assert.assertTrue(f.player.getController().playTrigger(host, wrapped, false));
        Assert.assertEquals(parameters(start(f)), Map.of("kind", "TRIGGER_REQUIREMENT", "mandatory", false));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualModalTriggerKeepsOnePlanningSelectorInsideItsPreparationOperation() {
        final Fixture f = fixture(); final var host = addCardToZone("Knight of Autumn", f.player, ZoneType.Battlefield);
        final var trigger = host.getTriggers().get(0); final var inner = trigger.ensureAbility(); inner.setActivatingPlayer(f.player);
        final var wrapped = new WrappedAbility(trigger, inner, null); wrapped.setActivatingPlayer(f.player);
        f.player.getController().orderAndPlaySimultaneousSa(List.of(wrapped));
        final var modes = f.events.stream().filter(c -> "MODE_SELECTION".equals(c.get("choiceType")) && "STARTED".equals(c.get("status"))).toList();
        Assert.assertEquals(modes.stream().filter(c -> "CONTROLLER_MODES".equals(c.get("boundary"))).count(), 1L);
        Assert.assertEquals(modes.stream().filter(c -> "CHARM_MODE_HEURISTIC".equals(c.get("boundary"))).count(), 1L);
        for (var mode : modes) {
            Assert.assertEquals(mode.get("stage"), "TRIGGER_PREPARATION"); Assert.assertEquals(context(mode).get("stateBasis"), "PLANNING_WORKING_STATE");
            final var terminal = f.events.stream().filter(c -> mode.get("choiceId").equals(c.get("choiceId")) && "COMPLETED".equals(c.get("status"))).findFirst().orElseThrow();
            Assert.assertEquals(((Map<?, ?>) terminal.get("selection")).get("selectedCount"), 1);
        }
        Assert.assertEquals(((Map<?, ?>) start(f).get("entryLocation")).get("stateBasis"), "RULES_STATE");
        Assert.assertFalse(f.game.getStack().isEmpty()); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void unknownCompatibilityDirectivesNeverDefaultToMandatoryOrOptional() {
        final Fixture f = fixture(); Assert.assertTrue(AiChoiceRecorder.triggerPreparation(f.player, f.ability, () -> true));
        Assert.assertNull(parameters(start(f))); Assert.assertEquals(context(start(f)).get("status"), "PARTIAL");
        Assert.assertTrue(context(start(f)).get("omissions").toString().contains("PARAMETERS"));
        Assert.assertEquals(((Map<?, ?>) start(f).get("entryLocation")).get("kind"), "OPERATION"); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void nestedDelegatedCallbacksKeepTheirOwnActorAndInheritedPlanningStage() {
        final Fixture f = fixture();
        Assert.assertTrue(AiChoiceRecorder.planning(f.player, () -> AiChoiceRecorder.triggerPreparation(f.player, f.ability, true, () -> {
            AiNumberChoiceRecorder.controllerRange(f.other, f.ability, 0, 3, () -> 2); return true;
        })));
        final var start = start(f); Assert.assertEquals(context(start).get("stateBasis"), "PLANNING_WORKING_STATE");
        final var child = f.events.stream().filter(c -> "CONTROLLER_NUMBER_RANGE".equals(c.get("boundary"))).findFirst().orElseThrow();
        Assert.assertEquals(child.get("chooserPlayerId"), f.other.getId()); Assert.assertEquals(child.get("parentChoiceId"), start.get("choiceId"));
        Assert.assertEquals(child.get("stage"), "TRIGGER_PREPARATION"); Assert.assertEquals(context(child).get("stateBasis"), "PLANNING_WORKING_STATE");
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void originalCallbackErrorsRemainOriginalAndDoNotBecomeObservationFailures() {
        final Fixture f = fixture(); final var original = new IllegalStateException("original preparation"); final var calls = new AtomicInteger();
        Assert.assertSame(Assert.expectThrows(IllegalStateException.class, () -> AiChoiceRecorder.triggerPreparation(f.player, f.ability, true,
                () -> { calls.incrementAndGet(); throw original; })), original);
        Assert.assertEquals(calls.get(), 1); Assert.assertEquals(f.events.get(1).get("status"), "ERROR"); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void livePriorPlanFailureIsVisibleWhileTheCallbackStillRunsOnce() {
        final Fixture f = fixture(); final var ability = Mockito.spy(f.ability); final var failure = new IllegalStateException("prior plan");
        Mockito.doThrow(failure).when(ability).getTargets(); final var calls = new AtomicInteger();
        Assert.assertTrue(AiChoiceRecorder.triggerPreparation(f.player, ability, true, () -> { calls.incrementAndGet(); return true; }));
        Assert.assertEquals(calls.get(), 1); Assert.assertTrue(f.events.isEmpty()); Assert.assertSame(f.game.getEventObservationFailure(), failure);
    }

    @DataProvider public Object[][] late() { return new Object[][] {{false}, {true}}; }
    @Test(dataProvider = "late", groups = {"UnitTest", "fast"})
    public void blockedPriorPlanCannotHoldClosureOrPublishLateValuesOrFaults(boolean fail) throws Exception {
        final Fixture f = fixture(); final var ability = Mockito.spy(f.ability); final var entered = new CountDownLatch(1); final var release = new CountDownLatch(1);
        Mockito.doAnswer(call -> { entered.countDown(); Assert.assertTrue(release.await(5, TimeUnit.SECONDS)); if (fail) { throw new IllegalStateException("obsolete plan"); } return f.ability.getTargets(); }).when(ability).getTargets();
        final var worker = Executors.newSingleThreadExecutor(); final var calls = new AtomicInteger();
        try {
            final var result = worker.submit(() -> AiChoiceRecorder.triggerPreparation(f.player, ability, true, () -> { calls.incrementAndGet(); return true; }));
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS)); f.game.setRecordAiDecisions(false); release.countDown();
            Assert.assertTrue(result.get(5, TimeUnit.SECONDS)); Assert.assertEquals(calls.get(), 1); Assert.assertTrue(f.events.isEmpty()); Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @DataProvider public Object[][] bypass() { return new Object[][] {{"OFF"}, {"COPY"}, {"HIDDEN"}}; }
    @Test(dataProvider = "bypass", groups = {"UnitTest", "fast"})
    public void bypassedPreparationDoesNotReadPriorPlanOrNewEntryData(String mode) {
        final Fixture f = fixture(); final var ability = Mockito.spy(f.ability);
        Mockito.doThrow(new AssertionError("bypassed targets read")).when(ability).getTargets();
        if (mode.equals("OFF")) { f.game.setRecordAiDecisions(false); }
        if (mode.equals("COPY")) { f.game.markAsSimulationCopy(); }
        if (mode.equals("HIDDEN")) { f.ability.getHostCard().turnFaceDown(); }
        Assert.assertTrue(AiChoiceRecorder.triggerPreparation(f.player, ability, true, () -> true)); Assert.assertNull(f.game.getEventObservationFailure());
        Assert.assertTrue(f.events.stream().filter(c -> "STARTED".equals(c.get("status"))).allMatch(c -> "MASKED_CHOICE".equals(c.get("choiceType"))));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void directiveProjectionChargesTwoWorkUnitsAndAccountingUsesTheExistingCell() {
        final Fixture f = fixture(); final var controller = (PlayerControllerAi) f.player.getController(); final var state = controller.inputContextState();
        long before = state.remainingWork(); AiInputContext.capture(f.player, f.ability, "BEFORE_OPERATION", "RULES_STATE", () -> true); final long base = before - state.remainingWork(); before = state.remainingWork();
        AiInputContext.capture(f.player, f.ability, "BEFORE_OPERATION", "RULES_STATE", () -> true, null, null,
                new AiReceivedParameters.Carrier(new java.util.concurrent.atomic.AtomicReference<>(AiReceivedParameters.trigger(false))));
        Assert.assertEquals(before - state.remainingWork(), base + 2);
        final var ledger = controller.configureObservationAccounting(); AiChoiceRecorder.triggerPreparation(f.player, f.ability, true, () -> false);
        final var cells = (Map<?, ?>) ledger.snapshot().get("boundaries"); Assert.assertEquals(cells.size(), 1);
        final var cell = (Map<?, ?>) cells.get("TRIGGER_PREPARATION"); Assert.assertEquals(cell.get("entered"), 1L); Assert.assertEquals(cell.get("finished"), 1L); Assert.assertEquals(cell.get("observationFailures"), 0L);
    }
}
