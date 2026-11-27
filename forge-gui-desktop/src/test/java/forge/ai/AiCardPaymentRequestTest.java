package forge.ai;

import forge.game.Game;
import forge.game.card.CardCollection;
import forge.game.cost.CostSacrifice;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class AiCardPaymentRequestTest extends AITest {
    private record Fixture(Game game, Player ai, SpellAbility ability, CardCollection cards, List<Map<String, Object>> events) { }
    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player ai = game.getPlayers().get(1); final var ability = addCardToZone("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility(); ability.setActivatingPlayer(ai);
        final var cards = new CardCollection(List.of(addCardToZone("Forest", ai, ZoneType.Hand), addCardToZone("Mountain", ai, ZoneType.Hand)));
        final var events = new ArrayList<Map<String, Object>>(); game.setRecordAiDecisions(true); game.observeEventEmission(e -> { if (e instanceof AiChoiceEvent c) { events.add(c.data()); } }); return new Fixture(game, ai, ability, cards, events);
    }
    private Map<String, Object> start(Fixture f, String boundary) { return f.events.stream().filter(c -> boundary.equals(c.get("boundary"))).findFirst().orElseThrow(); }
    private Map<?, ?> input(Map<?, ?> row) { return (Map<?, ?>) row.get("inputContext"); }
    private Map<?, ?> parameters(Map<?, ?> row) { return (Map<?, ?>) ((Map<?, ?>) input(row).get("policy")).get("receivedParameters"); }

    @Test(groups = {"UnitTest", "fast"})
    public void actualForcedDiscardBatchKeepsTheOriginalCollectionAliasAndReceivedBounds() {
        final Fixture f = fixture(); final var result = ((PlayerControllerAi) f.ai.getController()).getAi().getCardsToDiscard(2, 2, f.cards, f.ability);
        Assert.assertSame(result, f.cards); Assert.assertEquals(parameters(start(f, "PAYMENT_DISCARD_POOL")), Map.of("kind", "CARD_PAYMENT_REQUEST", "paymentKind", "DISCARD", "minimum", 2, "maximum", 2));
        Assert.assertEquals(f.events.size(), 2); Assert.assertTrue(f.cards.stream().allMatch(c -> c.isInZone(ZoneType.Hand))); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualSacrificeLoopKeepsItsProposalAndParentWhileRecordingItsOuterRequest() {
        final Fixture f = fixture(); final var elf = addCardToZone("Llanowar Elves", f.ai, ZoneType.Battlefield); final var bear = addCardToZone("Grizzly Bears", f.ai, ZoneType.Battlefield);
        final var result = new AiCostDecision(f.ai, f.ability, false).decide(new CostSacrifice("2", "Creature", null)); Assert.assertEquals(result.cards.size(), 2);
        Assert.assertTrue(result.cards.contains(elf)); Assert.assertTrue(result.cards.contains(bear)); Assert.assertTrue(elf.isInPlay()); Assert.assertTrue(bear.isInPlay());
        final var start = start(f, "PAYMENT_SACRIFICE_POOL"); Assert.assertEquals(parameters(start), Map.of("kind", "CARD_PAYMENT_REQUEST", "paymentKind", "SACRIFICE", "minimum", 2, "maximum", 2));
        Assert.assertEquals(start.get("parentChoiceId"), start(f, "COST_VISITOR").get("choiceId")); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void valuesAndDiagnosticPoolAreFrozenBeforeOriginalMutationWithoutReplacingTheResult() {
        final Fixture f = fixture(); final var selected = new CardCollection(f.cards.get(0));
        Assert.assertSame(AiChoiceRecorder.planning(f.ai, () -> AiPaymentChoiceRecorder.cards(f.ai, f.ability, "PAYMENT_DISCARD_POOL", f.cards, 1, 2, () -> { f.cards.clear(); return selected; })), selected);
        final var start = start(f, "PAYMENT_DISCARD_POOL"); Assert.assertEquals(parameters(start), Map.of("kind", "CARD_PAYMENT_REQUEST", "paymentKind", "DISCARD", "minimum", 1, "maximum", 2));
        Assert.assertEquals(input(start).get("stateBasis"), "PLANNING_WORKING_STATE"); Assert.assertTrue(f.cards.isEmpty());
        final var options = (Map<?, ?>) start.get("options"); Assert.assertEquals(((List<?>) ((Map<?, ?>) options.get("cards")).get("items")).size(), 2); Assert.assertEquals(options.get("minimum"), 1); Assert.assertEquals(options.get("maximum"), 2); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void directNativeCountsRemainReceivedValuesRatherThanAnInventedLegalRange() {
        final Fixture f = fixture();
        for (final int[] bounds : new int[][] {{0, 0}, {-1, 2}, {3, 1}, {Integer.MIN_VALUE, Integer.MAX_VALUE}}) {
            f.events.clear(); Assert.assertSame(AiPaymentChoiceRecorder.cards(f.ai, f.ability, "PAYMENT_SACRIFICE_POOL", f.cards, bounds[0], bounds[1], () -> f.cards), f.cards);
            final var value = parameters(start(f, "PAYMENT_SACRIFICE_POOL")); Assert.assertEquals(value.get("minimum"), bounds[0]); Assert.assertEquals(value.get("maximum"), bounds[1]);
        }
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void exileApplicationRetainsItsUnknownInputAndOldOptionShape() {
        final Fixture f = fixture(); Assert.assertSame(AiPaymentChoiceRecorder.cards(f.ai, f.ability, "PAYMENT_EXILE_POOL", f.cards, 1, 1, () -> f.cards), f.cards);
        final var start = start(f, "PAYMENT_EXILE_POOL"); Assert.assertFalse(start.containsKey("inputContext")); Assert.assertFalse(start.containsKey("entryLocation")); Assert.assertFalse(((Map<?, ?>) start.get("options")).containsKey("callbackParameters"));
        Assert.assertEquals(AiReceivedParameters.cardPayment("PAYMENT_EXILE_POOL", 1, 1).coverage(), "UNSUPPORTED");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void requestUsesFourWorkUnitsAndTheExistingCardPolicyCell() {
        final Fixture f = fixture(); final var pc = (PlayerControllerAi) f.ai.getController(); final var state = pc.inputContextState(); long before = state.remainingWork();
        AiInputContext.capture(f.ai, f.ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true); final long base = before - state.remainingWork(); before = state.remainingWork();
        AiInputContext.capture(f.ai, f.ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true, null, null, new AiReceivedParameters.Carrier(new AtomicReference<>(AiReceivedParameters.cardPayment("PAYMENT_DISCARD_POOL", 1, 2))));
        Assert.assertEquals(before - state.remainingWork(), base + 4); final var ledger = pc.configureObservationAccounting(); AiPaymentChoiceRecorder.cards(f.ai, f.ability, "PAYMENT_DISCARD_POOL", f.cards, 1, 2, () -> f.cards);
        final var cells = (Map<?, ?>) ledger.snapshot().get("boundaries"); Assert.assertEquals(cells.size(), 1); final var cell = (Map<?, ?>) cells.get("PAYMENT_DISCARD_POOL"); Assert.assertEquals(cell.get("entered"), 1L); Assert.assertEquals(cell.get("finished"), 1L); Assert.assertEquals(cell.get("observationFailures"), 0L);
    }
}
