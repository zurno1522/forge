package forge.ai;

import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
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

public class AiManaSourceInputTest extends AITest {
    private record Fixture(Game game, Player ai, SpellAbility ability, List<Card> lands, List<SpellAbility> sources,
                           List<Map<String, Object>> events) { }
    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player ai = game.getPlayers().get(1);
        final var ability = addCardToZone("Grizzly Bears", ai, ZoneType.Hand).getFirstSpellAbility(); ability.setActivatingPlayer(ai);
        final List<Card> lands = List.of(addCardToZone("Forest", ai, ZoneType.Battlefield), addCardToZone("Forest", ai, ZoneType.Battlefield));
        final var sources = new ArrayList<SpellAbility>(); for (Card card : lands) { final var source = card.getManaAbilities().get(0); source.setActivatingPlayer(ai); sources.add(source); }
        final var events = new ArrayList<Map<String, Object>>(); game.setRecordAiDecisions(true);
        game.observeEventEmission(e -> { if (e instanceof AiChoiceEvent c) { events.add(c.data()); } }); return new Fixture(game, ai, ability, lands, sources, events);
    }
    private List<Map<String, Object>> starts(Fixture f) { return f.events.stream().filter(c -> "MANA_SOURCE_OPTIONS".equals(c.get("boundary"))).toList(); }
    private Map<?, ?> context(Map<?, ?> start) { return (Map<?, ?>) start.get("inputContext"); }
    private Map<?, ?> parameters(Map<?, ?> start) { return (Map<?, ?>) ((Map<?, ?>) context(start).get("policy")).get("receivedParameters"); }
    private long tapped(Map<?, ?> start) { return ((List<?>) ((Map<?, ?>) context(start).get("policy")).get("cards")).stream().map(c -> (Map<?, ?>) c).filter(c -> "Forest".equals(c.get("name")) && Boolean.TRUE.equals(c.get("tapped"))).count(); }
    private SpellAbility select(Fixture f, ManaCostBeingPaid cost, java.util.function.Supplier<SpellAbility> callback) {
        return AiPaymentChoiceRecorder.manaSource(f.ai, f.ability, cost, ManaCostShard.GENERIC, f.sources, true, false, callback);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualTwoSourcePaymentCapturesSuccessiveLiveRequirementsAndTappedResources() {
        final Fixture f = fixture(); Assert.assertTrue(ComputerUtilMana.payManaCost(f.ability.getPayCosts(), f.ai, f.ability, false));
        final var starts = starts(f); Assert.assertEquals(starts.size(), 2); Assert.assertEquals(parameters(starts.get(0)).get("remainingManaValue"), 2);
        Assert.assertEquals(parameters(starts.get(1)).get("remainingManaValue"), 1); Assert.assertEquals(tapped(starts.get(0)), 0L); Assert.assertEquals(tapped(starts.get(1)), 1L);
        Assert.assertEquals(f.lands.stream().filter(Card::isTapped).count(), 2L); Assert.assertEquals(f.ability.getPayingManaAbilities().size(), 2);
        for (int i = 0; i < starts.size(); i++) {
            final var start = starts.get(i); Assert.assertEquals(start.get("stage"), "PAYMENT"); Assert.assertEquals(context(start).get("stateBasis"), "RULES_STATE");
            Assert.assertEquals(((Map<?, ?>) start.get("entryLocation")).get("kind"), "SELECTOR"); Assert.assertEquals(parameters(start).get("checkCosts"), true);
            final var terminal = f.events.stream().filter(c -> start.get("choiceId").equals(c.get("choiceId")) && "COMPLETED".equals(c.get("status"))).findFirst().orElseThrow();
            final var selected = (Map<?, ?>) terminal.get("selection"); Assert.assertEquals(selected.get("type"), "SPELL_ABILITY");
            Assert.assertEquals(selected.get("id"), f.ability.getPayingManaAbilities().get(i).getId()); Assert.assertFalse(selected.containsKey("paid"));
            Assert.assertEquals(((Map<?, ?>) context(start).get("diagnostics")).get("optionBindings"), Map.of("coverage", "UNSUPPORTED", "items", List.of(), "truncated", false));
        }
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualTargetedSacrificeProposalCanBeRejectedBeforeAnotherSourcePays() {
        final Fixture f = fixture(); final var cantor = addCardToZone("Wild Cantor", f.ai, ZoneType.Battlefield); final var bad = cantor.getManaAbilities().get(0); bad.setActivatingPlayer(f.ai);
        final var pump = addCardToZone("Giant Growth", f.ai, ZoneType.Hand).getFirstSpellAbility(); pump.setActivatingPlayer(f.ai); pump.getTargets().add(cantor);
        final var calls = new AtomicInteger();
        try (var methods = Mockito.mockStatic(ComputerUtilMana.class, Mockito.CALLS_REAL_METHODS)) {
            methods.when(() -> ComputerUtilMana.chooseManaAbility(Mockito.any(), Mockito.same(pump), Mockito.same(f.ai), Mockito.any(), Mockito.anyCollection(), Mockito.eq(true)))
                    .thenAnswer(call -> calls.getAndIncrement() == 0 ? bad : call.callRealMethod());
            Assert.assertTrue(ComputerUtilMana.payManaCost(pump.getPayCosts(), f.ai, pump, false));
        }
        final var starts = starts(f); Assert.assertEquals(starts.size(), 2); Assert.assertTrue(cantor.isInPlay()); Assert.assertFalse(cantor.isTapped());
        Assert.assertTrue(pump.getPayingManaAbilities().stream().noneMatch(a -> a == bad)); Assert.assertEquals(pump.getPayingManaAbilities().size(), 1);
        Assert.assertEquals(parameters(starts.get(0)).get("remainingManaValue"), 1); Assert.assertEquals(parameters(starts.get(1)).get("remainingManaValue"), 1);
        final var first = f.events.stream().filter(c -> starts.get(0).get("choiceId").equals(c.get("choiceId")) && "COMPLETED".equals(c.get("status"))).findFirst().orElseThrow();
        Assert.assertEquals(((Map<?, ?>) first.get("selection")).get("id"), bad.getId()); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void testEmptyAndDirectPublicHelperPathsDoNotInventNonTestSourceObservations() {
        final Fixture f = fixture(); final var cost = new ManaCostBeingPaid(ManaCost.ONE);
        Assert.assertSame(ComputerUtilMana.chooseManaAbility(cost, f.ability, f.ai, ManaCostShard.GENERIC, f.sources, false), f.sources.get(0)); Assert.assertTrue(starts(f).isEmpty());
        Assert.assertTrue(ComputerUtilMana.canPayManaCost(f.ability, f.ai, 0, false)); Assert.assertTrue(starts(f).isEmpty());
        f.game.getAction().moveToGraveyard(f.lands.get(0), null); f.game.getAction().moveToGraveyard(f.lands.get(1), null);
        Assert.assertFalse(ComputerUtilMana.payManaCost(f.ability.getPayCosts(), f.ai, f.ability, false)); Assert.assertTrue(starts(f).isEmpty()); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void originalCostCollectionAndResultIdentityStayUnchangedWhileSnapshotsFreezeBeforeMutation() {
        final Fixture f = fixture(); final var cost = new ManaCostBeingPaid(ManaCost.ONE); final var chosen = f.sources.get(0); final var calls = new AtomicInteger();
        Assert.assertSame(AiChoiceRecorder.planning(f.ai, () -> AiPaymentChoiceRecorder.manaSource(f.ai, f.ability, cost, ManaCostShard.GREEN, f.sources, false, true, () -> {
            calls.incrementAndGet(); f.sources.clear(); cost.addManaCost(ManaCost.ONE); f.ai.setLife(7, null); f.game.getPhaseHandler().devModeSet(PhaseType.MAIN2, f.ai); return chosen;
        })), chosen);
        Assert.assertEquals(calls.get(), 1); Assert.assertTrue(f.sources.isEmpty()); Assert.assertEquals(cost.getConvertedManaCost(), 2);
        final var start = starts(f).get(0); Assert.assertEquals(parameters(start), Map.of("kind", "MANA_SOURCE_REQUEST", "shard", "GREEN", "remainingManaValue", 1, "checkCosts", false, "effect", true));
        Assert.assertEquals(context(start).get("stateBasis"), "PLANNING_WORKING_STATE"); Assert.assertEquals(((Map<?, ?>) start.get("entryLocation")).get("phase"), "MAIN1");
        Assert.assertEquals(((List<?>) ((Map<?, ?>) ((Map<?, ?>) start.get("options")).get("abilities")).get("items")).size(), 2);
        Assert.assertEquals(((Map<?, ?>) ((List<?>) ((Map<?, ?>) context(start).get("policy")).get("players")).get(1)).get("life"), 20); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void declineAndOriginalErrorRemainSeparateFromSuccessfulPaymentAndRestoreParentScope() {
        final Fixture f = fixture(); Assert.assertNull(select(f, new ManaCostBeingPaid(ManaCost.ONE), () -> null)); Assert.assertEquals(f.events.get(1).get("status"), "DECLINED");
        final var failure = new IllegalArgumentException("original selector"); Assert.assertSame(Assert.expectThrows(IllegalArgumentException.class, () -> select(f, new ManaCostBeingPaid(ManaCost.ONE), () -> { throw failure; })), failure);
        Assert.assertEquals(f.events.get(3).get("status"), "ERROR"); select(f, new ManaCostBeingPaid(ManaCost.ONE), () -> f.sources.get(0)); Assert.assertNull(starts(f).get(2).get("parentChoiceId")); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void unknownCostSubclassesAreRefusedWithoutReadingTheirGetters() {
        final Fixture f = fixture(); final var unknown = Mockito.spy(new ManaCostBeingPaid(ManaCost.ONE) { });
        Mockito.doThrow(new AssertionError("unknown cost read")).when(unknown).getConvertedManaCost(); Assert.assertSame(select(f, unknown, () -> f.sources.get(0)), f.sources.get(0));
        final var start = starts(f).get(0); Assert.assertNull(parameters(start)); Assert.assertEquals(((Map<?, ?>) ((Map<?, ?>) start.get("options")).get("callbackParameters")).get("coverage"), "UNSUPPORTED"); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void liveMetadataFailureIsVisibleWithoutReplacingOrRepeatingTheOriginalCallback() {
        final Fixture f = fixture(); final var cost = Mockito.spy(new ManaCostBeingPaid(ManaCost.ONE)); final var failure = new IllegalStateException("current cost getter"); Mockito.doThrow(failure).when(cost).getConvertedManaCost();
        final var calls = new AtomicInteger(); Assert.assertSame(select(f, cost, () -> { calls.incrementAndGet(); return f.sources.get(0); }), f.sources.get(0));
        Assert.assertEquals(calls.get(), 1); Assert.assertSame(f.game.getEventObservationFailure(), failure); Assert.assertTrue(f.events.isEmpty());
    }

    @DataProvider public Object[][] bypass() { return new Object[][] {{"OFF"}, {"COPY"}, {"HIDDEN"}}; }
    @Test(dataProvider = "bypass", groups = {"UnitTest", "fast"})
    public void bypassedSourcesNeverReadNewCostMetadata(String mode) {
        final Fixture f = fixture(); final var cost = Mockito.spy(new ManaCostBeingPaid(ManaCost.ONE)); Mockito.doThrow(new AssertionError("bypassed cost")).when(cost).getConvertedManaCost();
        if (mode.equals("OFF")) { f.game.setRecordAiDecisions(false); }
        if (mode.equals("COPY")) { f.game.markAsSimulationCopy(); }
        if (mode.equals("HIDDEN")) { f.ability.getHostCard().turnFaceDown(); }
        Assert.assertSame(select(f, cost, () -> f.sources.get(0)), f.sources.get(0)); Assert.assertNull(f.game.getEventObservationFailure());
        Assert.assertTrue(f.events.stream().filter(c -> "STARTED".equals(c.get("status"))).allMatch(c -> "MASKED_CHOICE".equals(c.get("choiceType"))));
    }

    @DataProvider public Object[][] late() { return new Object[][] {{false}, {true}}; }
    @Test(dataProvider = "late", groups = {"UnitTest", "fast"})
    public void blockedCostMetadataCannotHoldClosureOrPublishLateValuesOrFailures(boolean fail) throws Exception {
        final Fixture f = fixture(); final var cost = Mockito.spy(new ManaCostBeingPaid(ManaCost.ONE)); final var entered = new CountDownLatch(1); final var release = new CountDownLatch(1);
        Mockito.doAnswer(call -> { entered.countDown(); Assert.assertTrue(release.await(5, TimeUnit.SECONDS)); if (fail) { throw new IllegalStateException("obsolete cost"); } return 1; }).when(cost).getConvertedManaCost();
        final var worker = Executors.newSingleThreadExecutor(); final var calls = new AtomicInteger();
        try {
            final var result = worker.submit(() -> select(f, cost, () -> { calls.incrementAndGet(); return f.sources.get(0); }));
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS)); f.game.setRecordAiDecisions(false); release.countDown(); Assert.assertSame(result.get(5, TimeUnit.SECONDS), f.sources.get(0));
            Assert.assertEquals(calls.get(), 1); Assert.assertTrue(f.events.isEmpty()); Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void requestChargesFiveUnitsAndUsesItsOneNewFixedAccountingCell() {
        final Fixture f = fixture(); final var pc = (PlayerControllerAi) f.ai.getController(); final var state = pc.inputContextState(); long before = state.remainingWork();
        AiInputContext.capture(f.ai, f.ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true); final long base = before - state.remainingWork(); before = state.remainingWork();
        AiInputContext.capture(f.ai, f.ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true, null, null, new AiReceivedParameters.Carrier(new AtomicReference<>(AiReceivedParameters.manaSource(new ManaCostBeingPaid(ManaCost.ONE), ManaCostShard.GENERIC, true, false))));
        Assert.assertEquals(before - state.remainingWork(), base + 5); final var ledger = pc.configureObservationAccounting(); select(f, new ManaCostBeingPaid(ManaCost.ONE), () -> f.sources.get(0));
        final var cells = (Map<?, ?>) ledger.snapshot().get("boundaries"); Assert.assertEquals(cells.size(), 1); final var cell = (Map<?, ?>) cells.get("MANA_SOURCE_OPTIONS");
        Assert.assertEquals(cell.get("entered"), 1L); Assert.assertEquals(cell.get("finished"), 1L); Assert.assertEquals(cell.get("observationFailures"), 0L);
    }
}
