package forge.ai;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.effects.ChangeZoneEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.player.ZoneChangeSelectionObserver;
import forge.game.player.ZoneChangeSelectionRequest;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public class EngineZoneSelectionBoundaryTest extends AITest {
    private record Fixture(Game game, Player player, PlayerControllerAi controller, SpellAbility ability, List<Card> cards,
            List<String> facts, List<ZoneChangeSelectionRequest> requests, List<Boolean> reveals) { }

    private Fixture fixture(final int iterations, final String extra, final boolean observing) {
        final Game game = initAndCreateGame(); final Player player = game.getPlayers().get(1);
        final PlayerControllerAi controller = Mockito.spy((PlayerControllerAi) player.getController());
        player.dangerouslySetController(controller);
        final Card source = addCardToZone("Time Beetle", player, ZoneType.Battlefield);
        final SpellAbility ability = AbilityFactory.getAbility("AB$ ChangeZone | Cost$ 0 | Origin$ Library | Destination$ Hand"
                + " | ChangeType$ Card | ChangeNum$ " + iterations + " | Mandatory$ True | Shuffle$ False | NoReveal$ True" + extra, source);
        ability.setActivatingPlayer(player);
        final List<Card> cards = new ArrayList<>();
        for (final String name : List.of("Forest", "Island", "Mountain")) { cards.add(addCardToZone(name, player, ZoneType.Library)); }
        final List<String> facts = new ArrayList<>(); final List<ZoneChangeSelectionRequest> requests = new ArrayList<>();
        final List<Boolean> reveals = new ArrayList<>();
        if (observing) {
            Mockito.doAnswer(call -> {
                final ZoneChangeSelectionRequest request = call.getArgument(0); requests.add(request); facts.add("begin");
                final Function<ZoneChangeSelectionObserver, CardCollection> callback = call.getArgument(1);
                final CardCollection result = callback.apply(new ZoneChangeSelectionObserver() {
                    @Override public void beforeSelector(CardCollection offered, boolean optional, int position, int retained) {
                        facts.add("before:" + position + ":" + retained + ":" + offered.size());
                        Assert.assertFalse(optional);
                    }
                    @Override public void returned(Card card, int retained) { facts.add("returned:" + (card == null ? "null" : card.getName()) + ":" + retained); }
                });
                facts.add("complete:" + result.size()); return result;
            }).when(controller).withZoneChangeSelections(Mockito.any(), Mockito.any());
        }
        Mockito.doAnswer(call -> {
            reveals.add(call.getArgument(4) != null);
            final CardCollection offered = call.getArgument(3);
            return offered.isEmpty() ? null : offered.getFirst();
        }).when(controller).chooseSingleCardForZoneChange(Mockito.any(), Mockito.anyList(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.any());
        return new Fixture(game, player, controller, ability, cards, facts, requests, reveals);
    }
    private void resolve(final Fixture f) { new ChangeZoneEffect().resolve(f.ability); }

    @Test(groups = {"UnitTest", "fast"})
    public void actualEngineBracketsOriginalDispatchAndAppendBeforeMovingCards() {
        final Fixture f = fixture(2, "", true); resolve(f);
        Assert.assertEquals(f.facts, List.of("begin", "before:0:0:3", "returned:Forest:1", "before:1:1:2", "returned:Island:2", "complete:2"));
        Assert.assertEquals(f.reveals, List.of(true, false));
        Assert.assertEquals(f.player.getCardsIn(ZoneType.Hand).size(), 2);
        Assert.assertEquals(f.player.getCardsIn(ZoneType.Library).size(), 1);
        final var request = f.requests.get(0);
        Assert.assertSame(request.ability(), f.ability); Assert.assertSame(request.sourcePlayer(), f.player);
        Assert.assertSame(request.decider(), f.player); Assert.assertEquals(request.requestedIterations(), 2);
        Assert.assertEquals(request.origins(), List.of(ZoneType.Library)); Assert.assertEquals(request.destination(), ZoneType.Hand);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void declinedRetryRepeatsLoopPositionAndFirstRevealWithoutInventingARetainedCard() {
        final Fixture f = fixture(2, "", true); final AtomicInteger attempts = new AtomicInteger();
        Mockito.doAnswer(call -> { f.reveals.add(call.getArgument(4) != null); int n = attempts.getAndIncrement(); return n == 0 ? null : f.cards.get(n - 1); })
                .when(f.controller).chooseSingleCardForZoneChange(Mockito.any(), Mockito.anyList(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.any());
        Mockito.doReturn(false).when(f.controller).confirmAction(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.any());
        resolve(f);
        Assert.assertEquals(f.facts, List.of("begin", "before:0:0:3", "returned:null:0", "before:0:0:3", "returned:Forest:1", "before:1:1:2", "returned:Island:2", "complete:2"));
        Assert.assertEquals(f.reveals, List.of(true, true, false));
        Assert.assertEquals(f.player.getCardsIn(ZoneType.Hand).size(), 2);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aConfirmedCancellationEndsTheOperationWithoutMovingCards() {
        final Fixture f = fixture(2, "", true);
        Mockito.doReturn(null).when(f.controller).chooseSingleCardForZoneChange(Mockito.any(), Mockito.anyList(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.any());
        Mockito.doReturn(true).when(f.controller).confirmAction(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.any());
        resolve(f);
        Assert.assertEquals(f.facts, List.of("begin", "before:0:0:3", "returned:null:0", "complete:0"));
        Assert.assertTrue(f.player.getCardsIn(ZoneType.Hand).isEmpty());
        Assert.assertEquals(f.player.getCardsIn(ZoneType.Library).size(), 3);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aNamedSearchDoesNotAcquireAnOrdinaryOperationBoundary() {
        final Fixture f = fixture(1, " | SameNameAsEach$ Self", true); resolve(f);
        Assert.assertTrue(f.requests.isEmpty()); Assert.assertTrue(f.facts.isEmpty());
        Assert.assertEquals(f.reveals.size(), 1);
        Assert.assertTrue(f.player.getCardsIn(ZoneType.Hand).isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void duplicateReturnsRetainTheOriginalDeduplicatedCollection() {
        final Fixture f = fixture(2, "", true);
        Mockito.doReturn(f.cards.get(0)).when(f.controller).chooseSingleCardForZoneChange(Mockito.any(), Mockito.anyList(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.any());
        resolve(f);
        Assert.assertEquals(f.facts, List.of("begin", "before:0:0:3", "returned:Forest:1", "before:1:1:2", "returned:Forest:1", "complete:1"));
        Assert.assertEquals(f.player.getCardsIn(ZoneType.Hand).size(), 1);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void unsatisfiedTypeTotalClearsRetainedCardsBeforeOperationReturns() {
        final Fixture f = fixture(1, " | WithTotalCardTypes$ 2", true); resolve(f);
        Assert.assertEquals(f.facts, List.of("begin", "before:0:0:3", "returned:Forest:1", "complete:0"));
        Assert.assertTrue(f.player.getCardsIn(ZoneType.Hand).isEmpty());
        Assert.assertEquals(f.player.getCardsIn(ZoneType.Library).size(), 3);
    }

    @DataProvider public Object[][] emptyIterations() { return new Object[][] {{0}, {-1}}; }
    @Test(dataProvider = "emptyIterations", groups = {"UnitTest", "fast"})
    public void zeroOrNegativeIterationRequestsKeepTheOriginalNoPickBehavior(final int iterations) {
        final Fixture f = fixture(iterations, "", true); resolve(f);
        Assert.assertEquals(f.facts, List.of("begin", "complete:0")); Assert.assertTrue(f.reveals.isEmpty());
        Assert.assertTrue(f.player.getCardsIn(ZoneType.Hand).isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void randomAndPredefinedChoicesDoNotPretendToInvokeASelector() {
        final Fixture random = fixture(1, " | AtRandom$ True", true); resolve(random);
        Assert.assertEquals(random.facts, List.of("begin", "complete:1")); Assert.assertTrue(random.reveals.isEmpty());
        Assert.assertEquals(random.player.getCardsIn(ZoneType.Hand).size(), 1);
        final Fixture defined = fixture(1, " | Defined$ Self", true); resolve(defined);
        Assert.assertEquals(defined.facts, List.of("begin", "complete:1")); Assert.assertTrue(defined.reveals.isEmpty());
        Assert.assertTrue(defined.player.getCardsIn(ZoneType.Hand).stream().anyMatch(c -> "Time Beetle".equals(c.getName())));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void defaultWrapperPreservesRealSelectionAndOriginalExceptionIdentity() {
        final Fixture normal = fixture(2, "", false); resolve(normal);
        Assert.assertEquals(normal.player.getCardsIn(ZoneType.Hand).size(), 2);
        final Fixture failure = fixture(2, "", false); final var original = new IllegalStateException("original selector");
        Mockito.doThrow(original).when(failure.controller).chooseSingleCardForZoneChange(Mockito.any(), Mockito.anyList(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.any());
        Assert.assertSame(Assert.expectThrows(IllegalStateException.class, () -> resolve(failure)), original);
        Assert.assertTrue(failure.player.getCardsIn(ZoneType.Hand).isEmpty());
    }
}
