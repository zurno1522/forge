package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.CostSacrifice;
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

public class AiSacrificePickInputTest extends AITest {
    private record Fixture(Game game, Player ai, SpellAbility ability, CardCollection cards, List<Map<String, Object>> events) { }
    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player ai = game.getPlayers().get(1); final var ability = addCardToZone("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility(); ability.setActivatingPlayer(ai);
        final var cards = new CardCollection(List.of(addCardToZone("Llanowar Elves", ai, ZoneType.Battlefield), addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield)));
        final var events = new ArrayList<Map<String, Object>>(); game.setRecordAiDecisions(true); game.observeEventEmission(e -> { if (e instanceof AiChoiceEvent c) { events.add(c.data()); } }); return new Fixture(game, ai, ability, cards, events);
    }
    private List<Map<String, Object>> picks(Fixture f) { return f.events.stream().filter(c -> "PAYMENT_SACRIFICE_PICK".equals(c.get("boundary"))).toList(); }
    private Map<?, ?> context(Map<?, ?> row) { return (Map<?, ?>) row.get("inputContext"); }
    private Map<?, ?> values(Map<?, ?> row) { return (Map<?, ?>) ((Map<?, ?>) context(row).get("policy")).get("receivedParameters"); }
    private Card pick(Fixture f, int requested, int count, CardCollection prior, java.util.function.Supplier<Card> callback) { return AiPaymentChoiceRecorder.sacrificePick(f.ai, f.ability, f.cards, requested, count, prior, callback); }

    @Test(groups = {"UnitTest", "fast"})
    public void actualSuccessivePicksShrinkTheOriginalPoolAndCarryStillPresentProposals() {
        final Fixture f = fixture(); final var result = new AiCostDecision(f.ai, f.ability, false).decide(new CostSacrifice("2", "Creature", null)); Assert.assertEquals(result.cards.size(), 2);
        final var picks = picks(f); Assert.assertEquals(picks.size(), 2);
        final var outer = f.events.stream().filter(c -> "PAYMENT_SACRIFICE_POOL".equals(c.get("boundary"))).findFirst().orElseThrow();
        for (int i = 0; i < 2; i++) {
            final var start = picks.get(i); Assert.assertEquals(start.get("parentChoiceId"), outer.get("choiceId")); Assert.assertEquals(start.get("stage"), "PAYMENT");
            Assert.assertEquals(values(start).get("requestedSelections"), 2); Assert.assertEquals(values(start).get("priorSelectionCount"), i);
            Assert.assertEquals(((List<?>) values(start).get("previousSelections")).size(), i);
            Assert.assertEquals(((List<?>) ((Map<?, ?>) ((Map<?, ?>) start.get("options")).get("cards")).get("items")).size(), 2 - i);
            final var end = f.events.stream().filter(c -> start.get("choiceId").equals(c.get("choiceId")) && "COMPLETED".equals(c.get("status"))).findFirst().orElseThrow();
            Assert.assertEquals(((Map<?, ?>) end.get("selection")).get("id"), result.cards.get(i).getId());
        }
        final var raw = (Map<?, ?>) ((Map<?, ?>) picks.get(1).get("options")).get("callbackParameters");
        Assert.assertEquals(((Map<?, ?>) ((List<?>) ((Map<?, ?>) raw.get("values")).get("previousSelections")).get(0)).get("id"), result.cards.get(0).getId());
        Assert.assertTrue(f.cards.stream().allMatch(Card::isInPlay)); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void nativeZeroNegativeAndInsufficientRequestsDoNotInventIterations() {
        final Fixture f = fixture(); final var visitor = new AiCostDecision(f.ai, f.ability, false);
        visitor.decide(new CostSacrifice("0", "Creature", null)); visitor.decide(new CostSacrifice("-1", "Creature", null)); visitor.decide(new CostSacrifice("3", "Creature", null));
        Assert.assertTrue(picks(f).isEmpty()); Assert.assertTrue(f.cards.stream().allMatch(Card::isInPlay)); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void receivedNativeHistoryIsFrozenBeforeOriginalMutationAndDoesNotOwnTheAccumulator() {
        final Fixture f = fixture(); final var prior = new CardCollection(f.cards.get(0)); final var chosen = f.cards.get(1); final var calls = new AtomicInteger();
        Assert.assertSame(AiChoiceRecorder.planning(f.ai, () -> pick(f, 2, 1, prior, () -> { calls.incrementAndGet(); prior.clear(); f.cards.clear(); return chosen; })), chosen);
        final var start = picks(f).get(0); Assert.assertEquals(calls.get(), 1); Assert.assertTrue(prior.isEmpty()); Assert.assertTrue(f.cards.isEmpty());
        Assert.assertEquals(values(start).get("priorSelectionCount"), 1); Assert.assertEquals(((List<?>) values(start).get("previousSelections")).size(), 1);
        Assert.assertEquals(context(start).get("stateBasis"), "PLANNING_WORKING_STATE"); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void wholeSixtyFourHistoryIsCompleteAndSixtyFiveIsRefusedBeforeIdentityReads() {
        final Fixture f = fixture(); final var prior = new CardCollection();
        for (int i = 0; i < 64; i++) { prior.add(addCardToZone("Grizzly Bears", f.ai, ZoneType.Battlefield)); }
        pick(f, 65, 64, prior, () -> f.cards.get(0)); Assert.assertEquals(((List<?>) values(picks(f).get(0)).get("previousSelections")).size(), 64);
        final var oversized = new CardCollection();
        for (int i = 0; i < 65; i++) { final var card = Mockito.spy(createCard("Grizzly Bears", f.ai)); oversized.add(card); Mockito.doThrow(new AssertionError("oversized history identity")).when(card).getId(); }
        f.events.clear(); pick(f, 66, 65, oversized, () -> f.cards.get(0)); final var raw = (Map<?, ?>) ((Map<?, ?>) picks(f).get(0).get("options")).get("callbackParameters");
        Assert.assertEquals(raw.get("coverage"), "UNAVAILABLE"); Assert.assertNull(raw.get("values")); Assert.assertNull(values(picks(f).get(0))); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void inconsistentCountsAndUnknownAccumulatorsAreRefusedWithoutRoleGetters() {
        final Fixture f = fixture(); final var card = Mockito.spy(f.cards.get(0)); Mockito.doThrow(new AssertionError("invalid history identity")).when(card).getId();
        final var prior = new CardCollection(card); final var subclass = new CardCollection() { @Override public int size() { throw new AssertionError("unknown accumulator size"); } };
        for (var input : List.of(AiReceivedParameters.sacrificePick(0, 0, prior), AiReceivedParameters.sacrificePick(2, 0, prior), AiReceivedParameters.sacrificePick(2, -1, prior), AiReceivedParameters.sacrificePick(2, 0, subclass))) { Assert.assertEquals(input.coverage(), "UNSUPPORTED"); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void ownPrivateAndForeignPriorMembersRemainUnresolvedRatherThanGainingBindings() {
        final Fixture f = fixture(); final var hidden = addCardToZone("Black Lotus", f.ai, ZoneType.Hand); final var foreign = Mockito.spy(createCard("Grizzly Bears", f.ai)); Mockito.doReturn(f.cards.get(0).getId()).when(foreign).getId(); Mockito.doReturn(f.cards.get(0).getGameTimestamp()).when(foreign).getGameTimestamp();
        for (Card bad : List.of(hidden, foreign)) { f.events.clear(); pick(f, 2, 1, new CardCollection(bad), () -> f.cards.get(0)); Assert.assertNull(values(picks(f).get(0))); Assert.assertTrue(context(picks(f).get(0)).get("omissions").toString().contains("UNRESOLVED_BINDING")); }
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void declinesAndOriginalErrorsPreserveOutcomesWithoutLeakingScopes() {
        final Fixture f = fixture(); Assert.assertNull(pick(f, 1, 0, new CardCollection(), () -> null)); Assert.assertEquals(f.events.get(1).get("status"), "DECLINED");
        final var failure = new IllegalStateException("original"); Assert.assertSame(Assert.expectThrows(IllegalStateException.class, () -> pick(f, 1, 0, new CardCollection(), () -> { throw failure; })), failure);
        Assert.assertEquals(f.events.get(3).get("status"), "ERROR"); pick(f, 1, 0, new CardCollection(), () -> f.cards.get(0)); Assert.assertNull(picks(f).get(2).get("parentChoiceId")); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] bypass() { return new Object[][] {{"OFF"}, {"COPY"}, {"HIDDEN"}}; }
    @Test(dataProvider = "bypass", groups = {"UnitTest", "fast"})
    public void bypassedPicksDoNotReadAccumulatorMetadata(String mode) {
        final Fixture f = fixture(); final var prior = Mockito.spy(new CardCollection()); Mockito.doThrow(new AssertionError("bypassed accumulator")).when(prior).size();
        if (mode.equals("OFF")) { f.game.setRecordAiDecisions(false); } if (mode.equals("COPY")) { f.game.markAsSimulationCopy(); } if (mode.equals("HIDDEN")) { f.ability.getHostCard().turnFaceDown(); }
        Assert.assertSame(pick(f, 1, 0, prior, () -> f.cards.get(0)), f.cards.get(0)); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] late() { return new Object[][] {{false}, {true}}; }
    @Test(dataProvider = "late", groups = {"UnitTest", "fast"})
    public void blockedPriorIdentityCannotHoldClosureOrPublishLateValuesOrFailures(boolean fail) throws Exception {
        final Fixture f = fixture(); final var member = Mockito.spy(createCard("Grizzly Bears", f.ai)); final long identity = member.getId(); final var prior = new CardCollection(member);
        final var entered = new CountDownLatch(1); final var release = new CountDownLatch(1);
        Mockito.doAnswer(call -> { entered.countDown(); Assert.assertTrue(release.await(5, TimeUnit.SECONDS)); if (fail) { throw new IllegalStateException("obsolete prior identity"); } return identity; }).when(member).getId();
        final var worker = Executors.newSingleThreadExecutor(); final var calls = new AtomicInteger();
        try {
            final var result = worker.submit(() -> pick(f, 2, 1, prior, () -> { calls.incrementAndGet(); return f.cards.get(0); }));
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS)); f.game.setRecordAiDecisions(false); release.countDown();
            Assert.assertSame(result.get(5, TimeUnit.SECONDS), f.cards.get(0)); Assert.assertEquals(calls.get(), 1); Assert.assertTrue(f.events.isEmpty()); Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void workChargesPerPriorOccurrenceAndUsesOneNewFixedCell() {
        final Fixture f = fixture(); final var pc = (PlayerControllerAi) f.ai.getController(); final var state = pc.inputContextState(); long before = state.remainingWork();
        AiInputContext.capture(f.ai, f.ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true, null, null, new AiReceivedParameters.Carrier(new AtomicReference<>(AiReceivedParameters.sacrificePick(2, 0, new CardCollection())))); final long empty = before - state.remainingWork(); before = state.remainingWork();
        AiInputContext.capture(f.ai, f.ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true, null, null, new AiReceivedParameters.Carrier(new AtomicReference<>(AiReceivedParameters.sacrificePick(2, 1, new CardCollection(f.cards.get(0)))))); Assert.assertEquals(before - state.remainingWork(), empty + 1);
        final var ledger = pc.configureObservationAccounting(); pick(f, 1, 0, new CardCollection(), () -> f.cards.get(0)); final var cell = (Map<?, ?>) ((Map<?, ?>) ledger.snapshot().get("boundaries")).get("PAYMENT_SACRIFICE_PICK"); Assert.assertEquals(cell.get("entered"), 1L); Assert.assertEquals(cell.get("finished"), 1L); Assert.assertEquals(cell.get("observationFailures"), 0L);
    }
}
