package forge.ai;

import forge.card.MagicColor;
import forge.card.mana.ManaCostShard;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.cost.Cost;
import forge.game.cost.CostPayment;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.mana.ManaPoolChoiceContext;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.spellability.SpellAbility;
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
import java.util.concurrent.atomic.AtomicReference;

public class AiManaPoolInputTest extends AITest {
    private record Fixture(Game game, Player ai, PlayerControllerAi controller, SpellAbility ability, Mana red, Mana green,
                           List<Mana> options, List<Map<String, Object>> events) { }
    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player ai = game.getPlayers().get(1);
        final var ability = addCardToZone("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility(); ability.setActivatingPlayer(ai);
        final var land = addCardToZone("Mountain", ai, ZoneType.Battlefield);
        final Mana red = new Mana(MagicColor.RED, land, null, ai), green = new Mana(MagicColor.GREEN, land, null, ai);
        ai.getManaPool().addMana(red); ai.getManaPool().addMana(green);
        final var events = new ArrayList<Map<String, Object>>(); game.setRecordAiDecisions(true);
        game.observeEventEmission(e -> { if (e instanceof AiChoiceEvent c) { events.add(c.data()); } });
        return new Fixture(game, ai, (PlayerControllerAi) ai.getController(), ability, red, green, new ArrayList<>(List.of(red, green)), events);
    }
    private ManaPoolChoiceContext request(Fixture f, boolean test) { return new ManaPoolChoiceContext(f.ability, ManaCostShard.GENERIC, test, (byte) -1); }
    private Map<String, Object> start(Fixture f) { return f.events.stream().filter(c -> "MANA_POOL_OPTIONS".equals(c.get("boundary"))).findFirst().orElseThrow(); }
    private Map<?, ?> context(Map<?, ?> row) { return (Map<?, ?>) row.get("inputContext"); }
    private Map<?, ?> received(Map<?, ?> row) { return (Map<?, ?>) ((Map<?, ?>) context(row).get("policy")).get("receivedParameters"); }
    private Map<?, ?> receipt(Map<?, ?> row) { return (Map<?, ?>) ((Map<?, ?>) row.get("options")).get("callbackParameters"); }

    @Test(groups = {"UnitTest", "fast"})
    public void actualRealPoolPaymentRetainsSelectedManaAndCapturesItsRequestBeforeSpending() {
        final Fixture f = fixture(); final var cost = new ManaCostBeingPaid(ManaCost.ONE); final var spent = new ArrayList<Mana>();
        Assert.assertTrue(f.ai.getManaPool().payManaCostFromPool(cost, f.ability, false, spent));
        Assert.assertEquals(spent.size(), 1); Assert.assertSame(spent.get(0), f.red); Assert.assertTrue(cost.isPaid()); Assert.assertEquals(f.ai.getManaPool().totalMana(), 1);
        final var start = start(f); Assert.assertEquals(received(start), Map.of("kind", "MANA_POOL_REQUEST", "testPayment", false, "shard", "GENERIC", "colorsPaid", -1));
        Assert.assertEquals(context(start).get("stateBasis"), "RULES_STATE"); Assert.assertEquals(((Map<?, ?>) start.get("entryLocation")).get("kind"), "SELECTOR");
        final var policy = (Map<?, ?>) context(start).get("policy"); final var player = (Map<?, ?>) ((List<?>) policy.get("players")).get(1);
        Assert.assertEquals(((List<?>) player.get("floatingManaColors")).size(), 2); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualEngineTestInsideRulesParentIsPlanningAndRefundsRatherThanBecomingARealSelection() {
        final Fixture f = fixture(); final var cost = new ManaCostBeingPaid(ManaCost.ONE); final var spent = new ArrayList<Mana>();
        Assert.assertTrue(AiChoiceRecorder.targeting(f.ai, f.ability, "EXECUTION", () -> f.ai.getManaPool().payManaCostFromPool(cost, f.ability, true, spent)));
        Assert.assertTrue(cost.isPaid()); Assert.assertTrue(spent.isEmpty()); Assert.assertEquals(f.ai.getManaPool().totalMana(), 2);
        Assert.assertTrue(f.ai.getManaPool().iterator().hasNext());
        final var start = start(f); Assert.assertEquals(received(start).get("testPayment"), true);
        Assert.assertEquals(context(start).get("stateBasis"), "PLANNING_WORKING_STATE"); Assert.assertEquals(start.get("stage"), "EXECUTION");
        final var parent = f.events.get(0); Assert.assertEquals(start.get("parentChoiceId"), parent.get("choiceId")); Assert.assertEquals(context(parent).get("stateBasis"), "RULES_STATE");
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void sharedAiAffordabilityPathMarksItsOriginalGameWorkAsPlanningWithoutOuterRealPayment() {
        final Fixture f = fixture(); Assert.assertTrue(ComputerUtilMana.canPayManaCost(new Cost("1", false), f.ability, f.ai, 0, false));
        Assert.assertEquals(received(start(f)).get("testPayment"), true); Assert.assertEquals(context(start(f)).get("stateBasis"), "PLANNING_WORKING_STATE");
        Assert.assertEquals(f.ai.getManaPool().totalMana(), 2); Assert.assertFalse(f.events.stream().anyMatch(c -> "AUTO_MANA_PAYMENT".equals(c.get("boundary")))); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void emptyAndSingletonRankedSetsStillBypassTheController() {
        final Fixture f = fixture(); f.ai.getManaPool().removeMana(f.red, f.green);
        Assert.assertNull(CostPayment.getMana(f.ai, ManaCostShard.GENERIC, f.ability, (byte) -1, Map.of(), false));
        f.ai.getManaPool().addMana(f.green); Assert.assertSame(CostPayment.getMana(f.ai, ManaCostShard.GENERIC, f.ability, (byte) -1, Map.of(), true), f.green);
        Assert.assertTrue(f.events.isEmpty()); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void oldUnknownHelperDoesNotInventARealRequestEvenWithinAnActiveParent() {
        final Fixture f = fixture(); AiChoiceRecorder.targeting(f.ai, f.ability, "EXECUTION", () -> {
            Assert.assertSame(CostPayment.getMana(f.ai, ManaCostShard.GENERIC, f.ability, (byte) -1, Map.of()), f.red); return true;
        });
        final var start = start(f); Assert.assertFalse(start.containsKey("inputContext")); Assert.assertFalse(start.containsKey("entryLocation"));
        Assert.assertFalse(((Map<?, ?>) start.get("options")).containsKey("callbackParameters"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void bridgePreservesDefaultNonAiAndOverriddenAiVirtualDispatchAndObjectIdentity() {
        final Fixture f = fixture(); final var other = Mockito.mock(PlayerController.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(f.green).when(other).chooseManaFromPool(f.options); Assert.assertSame(other.chooseManaFromPool(f.options, request(f, true)), f.green);
        Mockito.verify(other, Mockito.times(1)).chooseManaFromPool(f.options);
        final var ai = Mockito.spy(f.controller); Mockito.doReturn(f.green).when(ai).chooseManaFromPool(f.options);
        Assert.assertSame(ai.chooseManaFromPool(f.options, request(f, false)), f.green); Mockito.verify(ai, Mockito.times(1)).chooseManaFromPool(f.options); Assert.assertTrue(f.events.isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void installedSubclassDelegationDoesNotSilentlyAcquireTheBaseReceiverProof() {
        final Fixture f = fixture(); final var calls = new AtomicInteger();
        final var subclass = new PlayerControllerAi(f.game, f.ai, f.ai.getLobbyPlayer()) {
            @Override public Mana chooseManaFromPool(List<Mana> options) { calls.incrementAndGet(); return super.chooseManaFromPool(options); }
        };
        f.ai.dangerouslySetController(subclass);
        final var unreadable = Mockito.spy(request(f, true)); Mockito.doThrow(new AssertionError("unsupported receiver request read")).when(unreadable).shard();
        Assert.assertTrue(AiChoiceRecorder.targeting(f.ai, f.ability, "EXECUTION", () -> {
            Assert.assertSame(subclass.chooseManaFromPool(f.options, unreadable), f.red); return true;
        }));
        Assert.assertEquals(calls.get(), 1); Assert.assertFalse(start(f).containsKey("inputContext")); Assert.assertFalse(((Map<?, ?>) start(f).get("options")).containsKey("callbackParameters"));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void nestedUnknownOrUninstalledRequestsMaskThenRestoreAnOlderPendingWitness() {
        final Fixture f = fixture(); final var uninstalled = Mockito.mock(PlayerControllerAi.class);
        final Mana result = AiPaymentChoiceRecorder.withPoolRequest(f.ai, f.controller, f.options, request(f, false), () -> {
            Assert.assertSame(AiPaymentChoiceRecorder.withPoolRequest(f.ai, f.controller, f.options, null,
                    () -> AiPaymentChoiceRecorder.manaFromPool(f.ai, f.options, () -> f.green)), f.green);
            Assert.assertSame(AiPaymentChoiceRecorder.withPoolRequest(f.ai, uninstalled, f.options, request(f, true),
                    () -> AiPaymentChoiceRecorder.manaFromPool(f.ai, f.options, () -> f.green)), f.green);
            Assert.assertTrue(f.events.isEmpty()); return AiPaymentChoiceRecorder.manaFromPool(f.ai, f.options, () -> f.red);
        });
        Assert.assertSame(result, f.red); Assert.assertEquals(received(start(f)).get("testPayment"), false); Assert.assertEquals(f.events.size(), 2);
        Assert.assertNull(start(f).get("parentChoiceId")); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void failedMetadataConsumesItsWitnessAndNestedLegacyCallCannotBorrowIt() {
        final Fixture f = fixture(); final var request = Mockito.spy(request(f, true)); final var failure = new IllegalStateException("request getter");
        Mockito.doThrow(failure).when(request).shard();
        Assert.assertTrue(AiChoiceRecorder.targeting(f.ai, f.ability, "EXECUTION", () -> {
            Assert.assertSame(AiPaymentChoiceRecorder.withPoolRequest(f.ai, f.controller, f.options, request, () ->
                    AiPaymentChoiceRecorder.manaFromPool(f.ai, f.options, () -> AiPaymentChoiceRecorder.manaFromPool(f.ai, f.options, () -> f.red))), f.red); return true;
        }));
        Mockito.verify(request, Mockito.times(1)).shard(); Assert.assertSame(f.game.getEventObservationFailure(), failure);
        final var nested = start(f); Assert.assertFalse(nested.containsKey("inputContext")); Assert.assertFalse(((Map<?, ?>) nested.get("options")).containsKey("callbackParameters"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aDisabledNestedEntryCannotAcquireAnOlderRequestAfterRecordingIsReenabled() {
        final Fixture f = fixture(); final var unreadable = Mockito.spy(request(f, false)); Mockito.doThrow(new AssertionError("disabled request read")).when(unreadable).shard();
        Assert.assertSame(AiPaymentChoiceRecorder.withPoolRequest(f.ai, f.controller, f.options, unreadable, () -> {
            f.game.setRecordAiDecisions(false);
            return AiPaymentChoiceRecorder.withPoolRequest(f.ai, f.controller, f.options, unreadable, () -> {
                f.game.setRecordAiDecisions(true); return AiPaymentChoiceRecorder.manaFromPool(f.ai, f.options, () -> f.green);
            });
        }), f.green);
        Assert.assertTrue(f.events.isEmpty()); Assert.assertSame(f.controller.chooseManaFromPool(f.options, request(f, false)), f.red);
        Assert.assertEquals(received(start(f)).get("testPayment"), false); Assert.assertNull(start(f).get("parentChoiceId")); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void requestRequiresTheExactOriginalListAndIsClaimedOnlyOnce() {
        final Fixture f = fixture(); final var different = new ArrayList<>(f.options);
        AiPaymentChoiceRecorder.withPoolRequest(f.ai, f.controller, f.options, request(f, true), () -> {
            Assert.assertSame(AiPaymentChoiceRecorder.manaFromPool(f.ai, different, () -> f.green), f.green); Assert.assertTrue(f.events.isEmpty());
            return AiPaymentChoiceRecorder.manaFromPool(f.ai, f.options, () -> {
                Assert.assertSame(AiPaymentChoiceRecorder.manaFromPool(f.ai, f.options, () -> f.green), f.green); return f.red;
            });
        });
        final var starts = f.events.stream().filter(c -> "STARTED".equals(c.get("status"))).toList(); Assert.assertEquals(starts.size(), 2);
        Assert.assertTrue(starts.get(0).containsKey("inputContext")); Assert.assertFalse(starts.get(1).containsKey("inputContext")); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void preCallbackViewAndRequestAreFrozenAndOriginalErrorsRestoreTheScope() {
        final Fixture f = fixture(); final var error = new IllegalArgumentException("original callback"); final var calls = new AtomicInteger();
        Assert.assertSame(Assert.expectThrows(IllegalArgumentException.class, () -> AiPaymentChoiceRecorder.withPoolRequest(f.ai, f.controller, f.options, request(f, false), () ->
                AiPaymentChoiceRecorder.manaFromPool(f.ai, f.options, () -> {
                    calls.incrementAndGet(); f.ai.setLife(7, null); f.game.getPhaseHandler().devModeSet(PhaseType.MAIN2, f.ai); f.ai.getManaPool().removeMana(f.green); throw error;
                }))), error);
        Assert.assertEquals(calls.get(), 1); final var policy = (Map<?, ?>) context(start(f)).get("policy");
        Assert.assertEquals(((Map<?, ?>) ((List<?>) policy.get("players")).get(1)).get("life"), 20);
        Assert.assertEquals(((Map<?, ?>) start(f).get("entryLocation")).get("phase"), "MAIN1"); Assert.assertEquals(f.events.get(f.events.size() - 1).get("status"), "ERROR");
        Assert.assertNull(f.game.getEventObservationFailure()); f.events.clear(); Assert.assertSame(f.controller.chooseManaFromPool(f.options, request(f, true)), f.red);
        Assert.assertNull(start(f).get("parentChoiceId")); Assert.assertEquals(received(start(f)).get("testPayment"), true);
    }

    @DataProvider public Object[][] bypass() { return new Object[][] {{"OFF"}, {"COPY"}, {"HIDDEN"}}; }
    @Test(dataProvider = "bypass", groups = {"UnitTest", "fast"})
    public void bypassedTypedChoicesNeverReadManaMetadata(String mode) {
        final Fixture f = fixture(); final var mana = Mockito.spy(f.red); Mockito.doThrow(new AssertionError("bypassed metadata read")).when(mana).getSourceCard(); final var options = new ArrayList<>(List.of(mana, f.green));
        if (mode.equals("OFF")) { f.game.setRecordAiDecisions(false); }
        if (mode.equals("COPY")) { f.game.markAsSimulationCopy(); }
        if (mode.equals("HIDDEN")) { f.ability.getHostCard().turnFaceDown(); }
        Assert.assertSame(f.controller.chooseManaFromPool(options, request(f, true)), mana); Assert.assertNull(f.game.getEventObservationFailure());
        Assert.assertTrue(f.events.stream().filter(c -> "STARTED".equals(c.get("status"))).allMatch(c -> "MASKED_CHOICE".equals(c.get("choiceType"))));
    }

    @DataProvider public Object[][] late() { return new Object[][] {{false}, {true}}; }
    @Test(dataProvider = "late", groups = {"UnitTest", "fast"})
    public void blockedMetadataDoesNotHoldClosureOrPublishLateValuesOrFailures(boolean fail) throws Exception {
        final Fixture f = fixture(); final var mana = Mockito.spy(f.red); final var entered = new CountDownLatch(1); final var release = new CountDownLatch(1);
        Mockito.doAnswer(call -> { entered.countDown(); Assert.assertTrue(release.await(5, TimeUnit.SECONDS)); if (fail) { throw new IllegalStateException("obsolete"); } return f.red.getSourceCard(); }).when(mana).getSourceCard();
        final var options = new ArrayList<>(List.of(mana, f.green)); final var worker = Executors.newSingleThreadExecutor();
        try {
            final var result = worker.submit(() -> f.controller.chooseManaFromPool(options, request(f, true)));
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS)); f.game.setRecordAiDecisions(false); release.countDown();
            Assert.assertSame(result.get(5, TimeUnit.SECONDS), mana); Assert.assertTrue(f.events.isEmpty()); Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void nullShardRefusesParametersAndKnownRequestWorkUsesTheExistingAccountingCell() {
        final Fixture f = fixture(); f.controller.chooseManaFromPool(f.options, new ManaPoolChoiceContext(f.ability, null, false, (byte) 0));
        Assert.assertNull(received(start(f))); Assert.assertEquals(receipt(start(f)).get("coverage"), "UNSUPPORTED");
        final var state = f.controller.inputContextState(); long before = state.remainingWork(); AiInputContext.capture(f.ai, f.ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true); final long base = before - state.remainingWork();
        before = state.remainingWork(); AiInputContext.capture(f.ai, f.ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true, null, null,
                new AiReceivedParameters.Carrier(new AtomicReference<>(AiReceivedParameters.manaPool(request(f, false)))));
        Assert.assertEquals(before - state.remainingWork(), base + 4);
        final var ledger = f.controller.configureObservationAccounting(); f.controller.chooseManaFromPool(f.options, request(f, true));
        final var cell = (Map<?, ?>) ((Map<?, ?>) ledger.snapshot().get("boundaries")).get("MANA_POOL_OPTIONS");
        Assert.assertEquals(cell.get("entered"), 1L); Assert.assertEquals(cell.get("finished"), 1L); Assert.assertEquals(cell.get("observationFailures"), 0L); Assert.assertNull(f.game.getEventObservationFailure());
    }
}
