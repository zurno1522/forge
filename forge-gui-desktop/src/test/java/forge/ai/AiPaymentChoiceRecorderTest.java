package forge.ai;

import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.CardCollection;
import forge.game.cost.*;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AiPaymentChoiceRecorderTest extends AITest {
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
    private Map<String, Object> start(final Fixture f, final String boundary) {
        return f.events.stream().filter(e -> boundary.equals(e.get("boundary"))).findFirst().orElseThrow();
    }
    private Map<?, ?> selection(final Fixture f, final Map<String, Object> start) {
        return (Map<?, ?>) f.events.stream().filter(e -> start.get("choiceId").equals(e.get("choiceId"))
                && "COMPLETED".equals(e.get("status"))).findFirst().orElseThrow().get("selection");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void visitorProposalPrecedesPaymentAndIsNotAClaimThatLifeWasPaid() {
        final Fixture f = fixture();
        final int life = f.ai.getLife();
        final AiCostDecision visitor = new AiCostDecision(f.ai, f.ability, false);
        final AtomicInteger calls = new AtomicInteger();
        final CostPayLife part = new CostPayLife("2", null);
        final PaymentDecision proposed = AiPaymentChoiceRecorder.decide(f.ai, f.ability, part, false,
                () -> { calls.incrementAndGet(); return part.accept(visitor); });
        Assert.assertEquals(calls.get(), 1);
        Assert.assertEquals(f.ai.getLife(), life);
        Assert.assertEquals(selection(f, start(f, "COST_VISITOR")).get("decisionCount"), 2);
        Assert.assertTrue(part.payAsDecided(f.ai, proposed, f.ability, false));
        Assert.assertEquals(f.ai.getLife(), life - 2);
        Assert.assertFalse(selection(f, start(f, "COST_VISITOR")).containsKey("paid"));
    }

    @DataProvider public Object[][] paymentPaths() { return new Object[][]{{false}, {true}}; }

    @Test(dataProvider = "paymentPaths", groups = {"UnitTest", "fast"})
    public void costPaymentUsesTheVisitorBoundaryAndPreservesDeclines(final boolean computer) {
        for (final boolean impossible : new boolean[]{false, true}) {
            final Fixture f = fixture();
            final int life = f.ai.getLife();
            final Cost cost = new Cost(impossible ? "PayLife<100>" : "PayLife<2>", true);
            final CostPayment payment = new CostPayment(cost, f.ability);
            final AiCostDecision visitor = new AiCostDecision(f.ai, f.ability, false);
            Assert.assertEquals(computer ? payment.payComputerCosts(visitor) : payment.payCost(visitor), !impossible);
            Assert.assertEquals(f.ai.getLife(), impossible ? life : life - 2);
            final Map<String, Object> s = start(f, "COST_VISITOR");
            Assert.assertEquals(((Map<?, ?>) s.get("options")).get("costKind"), "CostPayLife");
            Assert.assertTrue(f.events.stream().anyMatch(e -> s.get("choiceId").equals(e.get("choiceId"))
                    && (impossible ? "DECLINED" : "COMPLETED").equals(e.get("status"))));
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void laterDeclinedCostLeavesEarlierDiscardProposalVisibleWithoutDiscardingIt() {
        final Fixture f = fixture();
        final var card = addCardToZone("Forest", f.ai, ZoneType.Hand);
        final CostPayment payment = new CostPayment(new Cost("Discard<1/Card> Sac<1/Artifact>", true), f.ability);
        Assert.assertFalse(payment.payComputerCosts(new AiCostDecision(f.ai, f.ability, false)));
        Assert.assertTrue(card.isInZone(ZoneType.Hand));
        Assert.assertTrue(f.ability.getHostCard().isInZone(ZoneType.Hand));
        final var first = start(f, "COST_VISITOR");
        Assert.assertEquals(((Map<?, ?>) first.get("options")).get("costKind"), "CostDiscard");
        Assert.assertFalse(((List<?>) ((Map<?, ?>) selection(f, first).get("cards")).get("items")).isEmpty());
        Assert.assertTrue(f.events.stream().anyMatch(e -> "DECLINED".equals(e.get("status"))));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void sacrificePoolRemainsStableWhileOriginalSelectionMutatesItsList() {
        final Fixture f = fixture();
        final var creature = addCardToZone("Llanowar Elves", f.ai, ZoneType.Battlefield);
        addCardToZone("Forest", f.ai, ZoneType.Battlefield);
        final PaymentDecision decision = new AiCostDecision(f.ai, f.ability, false).decide(new CostSacrifice("1", "Creature", null));
        Assert.assertEquals(decision.cards, new CardCollection(creature));
        Assert.assertTrue(creature.isInPlay());
        final var parent = start(f, "COST_VISITOR");
        final var pool = start(f, "PAYMENT_SACRIFICE_POOL");
        Assert.assertEquals(pool.get("parentChoiceId"), parent.get("choiceId"));
        final var cards = (Map<?, ?>) ((Map<?, ?>) pool.get("options")).get("cards");
        Assert.assertEquals(((List<?>) cards.get("items")).size(), 1);
        Assert.assertEquals(((Map<?, ?>) ((List<?>) cards.get("items")).get(0)).get("id"), creature.getId());
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void discardSnapshotsExistingOptionsBeforeHeuristicRemovesCards() {
        final Fixture f = fixture();
        final var forest = addCardToZone("Forest", f.ai, ZoneType.Hand);
        final var mountain = addCardToZone("Mountain", f.ai, ZoneType.Hand);
        final CardCollection offered = new CardCollection(List.of(forest, mountain));
        final CardCollection selected = ((PlayerControllerAi) f.ai.getController()).getAi().getCardsToDiscard(1, 1, offered, f.ability);
        Assert.assertEquals(selected.size(), 1);
        final var options = (Map<?, ?>) start(f, "PAYMENT_DISCARD_POOL").get("options");
        Assert.assertEquals(((List<?>) ((Map<?, ?>) options.get("cards")).get("items")).size(), 2);
        Assert.assertEquals(((List<?>) selection(f, start(f, "PAYMENT_DISCARD_POOL")).get("items")).size(), 1);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void realAutoPaymentRecordsPaidRequirementAndDoesNotRecordAffordabilityPreview() {
        final Fixture f = fixture();
        addCardToZone("Mountain", f.ai, ZoneType.Battlefield);
        Assert.assertTrue(ComputerUtilMana.canPayManaCost(f.ability, f.ai, 0, false));
        Assert.assertFalse(f.events.stream().anyMatch(e -> "AUTO_MANA_PAYMENT".equals(e.get("boundary"))));
        Assert.assertTrue(ComputerUtilMana.payManaCost(f.ability.getPayCosts(), f.ai, f.ability, false));
        Assert.assertEquals(selection(f, start(f, "AUTO_MANA_PAYMENT")).get("costSatisfied"), true);
        Assert.assertFalse(f.ability.getPayingMana().isEmpty());
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void manaPoolChoiceRetainsOriginalObjectAndOnlyUsesAnActiveActualContext() {
        final Fixture f = fixture();
        final var land = addCardToZone("Mountain", f.ai, ZoneType.Battlefield);
        final Mana red = new Mana(MagicColor.RED, land, null, f.ai);
        final List<Mana> offered = List.of(red, new Mana(MagicColor.GREEN, land, null, f.ai));
        Assert.assertSame(f.ai.getController().chooseManaFromPool(offered), red);
        Assert.assertTrue(f.events.isEmpty());
        AiPaymentChoiceRecorder.decide(f.ai, f.ability, new CostPayLife("1", null), false, () -> {
            Assert.assertSame(f.ai.getController().chooseManaFromPool(offered), red);
            return PaymentDecision.number(1);
        });
        Assert.assertEquals(start(f, "MANA_POOL_OPTIONS").get("parentChoiceId"), start(f, "COST_VISITOR").get("choiceId"));
        Assert.assertEquals(selection(f, start(f, "MANA_POOL_OPTIONS")).get("color"), (int) MagicColor.RED);
    }
}
