package forge.ai;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.effects.DraftEffect;
import forge.game.ability.effects.HeistEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.event.GameEventCardChangeZone;
import forge.game.player.Player;
import forge.game.player.ZoneChangeSelectionRequest;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class EngineMovingZoneSelectionTest extends AITest {
    private record Fixture(Game game, Player player, Player target, SpellAbility ability, List<Map<String, Object>> events,
            List<String> order, List<String> movements) { }

    private Fixture fixture(final boolean draft, final int count, final boolean recording) {
        final Game game = initAndCreateGame(); final Player player = game.getPlayers().get(1), target = game.getPlayers().get(0);
        final Card source = addCardToZone("Time Beetle", player, ZoneType.Battlefield);
        final String effect = draft ? "Draft | Spellbook$ Grizzly Bears,Shivan Dragon,Forest | DraftNum$ " : "Heist | ValidTgts$ Opponent | Num$ ";
        final SpellAbility ability = AbilityFactory.getAbility("AB$ " + effect + count + " | Cost$ 0 | Defined$ You", source);
        ability.setActivatingPlayer(player); if (!draft) { ability.getTargets().add(target); }
        for (String name : List.of("Grizzly Bears", "Shivan Dragon", "Elvish Visionary", "Forest")) {
            addCardToZone(name, target, ZoneType.Library);
        }
        final List<Map<String, Object>> events = new ArrayList<>(); final List<String> order = new ArrayList<>(), moves = new ArrayList<>();
        game.setRecordAiDecisions(recording);
        game.observeEventEmission(event -> {
            if (event instanceof AiChoiceEvent choice) {
                var row = choice.data(); events.add(row); order.add("choice:" + row.get("choiceId") + ":" + row.get("status"));
            }
            if (event instanceof GameEventCardChangeZone zone) {
                final String to = zone.to() == null ? "null" : zone.to().zoneType().name();
                order.add("move:" + to); moves.add(zone.card().getName() + ":" + to);
            }
        });
        return new Fixture(game, player, target, ability, events, order, moves);
    }
    private void resolve(Fixture f, boolean draft) { if (draft) { new DraftEffect().resolve(f.ability); } else { new HeistEffect().resolve(f.ability); } }
    private List<Map<String, Object>> starts(Fixture f, String type) {
        return f.events.stream().filter(c -> "STARTED".equals(c.get("status")) && type.equals(c.get("choiceType"))).toList();
    }
    private Map<?, ?> prompt(Map<String, Object> row) {
        return (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) row.get("inputContext")).get("policy")).get("prompt");
    }
    @DataProvider public Object[][] effects() { return new Object[][] {{false}, {true}}; }

    @Test(dataProvider = "effects", groups = {"UnitTest", "fast"})
    public void actualMovingSelectorsHaveOwnedPreMovementReceipts(final boolean draft) {
        final Fixture f = fixture(draft, 2, true);
        final var controller = Mockito.spy((PlayerControllerAi) f.player.getController()); f.player.dangerouslySetController(controller);
        final List<List<ZoneType>> actualOrigins = new ArrayList<>(), outerOrigins = new ArrayList<>();
        Mockito.doAnswer(call -> { outerOrigins.add(((ZoneChangeSelectionRequest) call.getArgument(0)).origins()); return call.callRealMethod(); })
                .when(controller).withZoneChangeSelections(Mockito.any(), Mockito.any());
        Mockito.doAnswer(call -> { actualOrigins.add(call.getArgument(1)); return call.callRealMethod(); })
                .when(controller).chooseSingleCardForZoneChange(Mockito.any(), Mockito.anyList(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.any());
        resolve(f, draft);
        Assert.assertNotSame(actualOrigins.get(0), actualOrigins.get(1));
        for (var origins : actualOrigins) { Assert.assertNotSame(origins, outerOrigins.get(0)); }
        if (draft) { Assert.assertTrue(actualOrigins.stream().allMatch(origins -> origins instanceof ArrayList && origins.isEmpty())); }
        final var outer = starts(f, "ZONE_CHANGE_SELECTION_OPERATION").get(0);
        final var children = starts(f, "ZONE_CHANGE_CARD_SELECTION"); Assert.assertEquals(children.size(), 2);
        Assert.assertEquals(prompt(outer).get("branch"), draft ? "DRAFT" : "HEIST");
        for (int i = 0; i < children.size(); i++) {
            final var child = children.get(i); final var p = prompt(child);
            Assert.assertEquals(child.get("parentChoiceId"), outer.get("choiceId"));
            Assert.assertEquals(p.get("branch"), draft ? "DRAFT" : "HEIST");
            Assert.assertEquals(p.get("attemptIndex"), (long) i); Assert.assertEquals(p.get("priorNonNullReturnCount"), (long) i);
            Assert.assertEquals(p.get("retainedCount"), i);
            if (i == 1) { Assert.assertEquals(p.get("previousReturns"), java.util.Arrays.asList((Object) null)); }
        }
        final var firstTerminal = f.events.stream().filter(c -> children.get(0).get("choiceId").equals(c.get("choiceId")) && "COMPLETED".equals(c.get("status"))).findFirst().orElseThrow();
        final var nextProgress = (Map<?, ?>) ((Map<?, ?>) children.get(1).get("options")).get("zoneSelectionProgress");
        Assert.assertEquals(((List<?>) nextProgress.get("priorReturns")).get(0), ((Map<?, ?>) firstTerminal.get("selection")).get("selected"));
        final int completion = f.order.indexOf("choice:" + outer.get("choiceId") + ":COMPLETED");
        if (draft) {
            Assert.assertTrue(f.order.indexOf("move:Hand") > completion);
            Assert.assertEquals(f.player.getCardsIn(ZoneType.Hand).size(), 2);
        } else {
            final int firstReturn = f.order.indexOf("choice:" + children.get(0).get("choiceId") + ":COMPLETED");
            final int firstMove = f.order.indexOf("move:Exile");
            Assert.assertTrue(firstReturn < firstMove && firstMove < f.order.indexOf("choice:" + children.get(1).get("choiceId") + ":STARTED"));
            Assert.assertTrue(completion > firstMove);
            Assert.assertEquals(f.target.getCardsIn(ZoneType.Exile).size(), 2);
            Assert.assertTrue(f.target.getCardsIn(ZoneType.Exile).stream().allMatch(Card::isFaceDown));
        }
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(dataProvider = "effects", groups = {"UnitTest", "fast"})
    public void recordingPreservesSeededMovementAndOriginalRandomConsumption(final boolean draft) {
        final Random previous = MyRandom.getRandom();
        try {
            final Fixture off = fixture(draft, 2, false); MyRandom.setRandom(new Random(7821)); resolve(off, draft);
            final long expectedTail = MyRandom.getRandom().nextLong();
            final Fixture on = fixture(draft, 2, true); MyRandom.setRandom(new Random(7821)); resolve(on, draft);
            Assert.assertTrue(off.events.isEmpty()); Assert.assertEquals(on.movements, off.movements);
            Assert.assertEquals(MyRandom.getRandom().nextLong(), expectedTail);
        } finally { MyRandom.setRandom(previous); }
    }
    @Test(dataProvider = "effects", groups = {"UnitTest", "fast"})
    public void zeroIterationsAndOriginalSelectorExceptionsDoNotInventMovement(final boolean draft) {
        final Fixture empty = fixture(draft, 0, true); resolve(empty, draft);
        Assert.assertEquals(empty.events.size(), 2); Assert.assertTrue(empty.movements.isEmpty());
        final Fixture broken = fixture(draft, 1, true);
        final var controller = Mockito.spy((PlayerControllerAi) broken.player.getController()); broken.player.dangerouslySetController(controller);
        final var original = new IllegalStateException("original moving selector");
        Mockito.doThrow(original).when(controller).chooseSingleCardForZoneChange(Mockito.any(), Mockito.anyList(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.any());
        Assert.assertSame(Assert.expectThrows(IllegalStateException.class, () -> resolve(broken, draft)), original);
        Assert.assertEquals(broken.events.get(broken.events.size() - 1).get("status"), "ERROR");
        Assert.assertTrue(broken.movements.isEmpty()); Assert.assertNull(broken.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void anEmptyHeistOfferSkipsAllSelectors() {
        final Fixture f = fixture(false, 2, true);
        for (Card card : new CardCollection(f.target.getCardsIn(ZoneType.Library))) {
            if (!card.isLand()) { f.target.getZone(ZoneType.Library).remove(card); }
        }
        resolve(f, false); Assert.assertTrue(starts(f, "ZONE_CHANGE_CARD_SELECTION").isEmpty());
        Assert.assertEquals(f.events.size(), 2); Assert.assertTrue(f.movements.isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void rejectedHeistReturnsStayInTheLibraryAndDoNotIncreaseRetention() {
        final Fixture f = fixture(false, 2, true);
        f.ability.getHostCard().addStaticAbility("Mode$ CantExile | ValidCard$ Card | ForCost$ False");
        resolve(f, false);
        final var children = starts(f, "ZONE_CHANGE_CARD_SELECTION"); Assert.assertEquals(children.size(), 2);
        Assert.assertEquals(prompt(children.get(1)).get("priorNonNullReturnCount"), 1L);
        Assert.assertEquals(prompt(children.get(1)).get("retainedCount"), 0);
        final var terminal = f.events.get(f.events.size() - 1);
        Assert.assertEquals(((Map<?, ?>) terminal.get("selection")).get("selectedCount"), 0);
        Assert.assertTrue(f.movements.isEmpty()); Assert.assertEquals(f.target.getCardsIn(ZoneType.Library).size(), 4);
        Assert.assertNull(f.game.getEventObservationFailure());
    }
}
