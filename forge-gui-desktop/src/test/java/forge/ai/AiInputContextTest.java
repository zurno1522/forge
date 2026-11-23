package forge.ai;

import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.deck.Deck;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AiInputContextTest extends AITest {
    private Game game() {
        final Game game = initAndCreateGame();
        game.setRecordAiDecisions(true);
        return game;
    }

    private Map<String, Object> capture(final Player chooser) {
        return AiInputContext.capture(chooser, null, "BEFORE_SELECTOR", "RULES_STATE", () -> true);
    }

    private Map<?, ?> policy(final Map<String, Object> context) { return (Map<?, ?>) context.get("policy"); }

    private List<?> cards(final Map<String, Object> context) { return (List<?>) policy(context).get("cards"); }

    private Map<?, ?> card(final Map<String, Object> context, final Card source) {
        final var diagnostics = (Map<?, ?>) context.get("diagnostics");
        final List<?> bindings = (List<?>) diagnostics.get("cardBindings");
        for (int i = 0; i < bindings.size(); i++) {
            if (Long.valueOf(((Number) ((Map<?, ?>) bindings.get(i)).get("id")).longValue()).equals(source.getId())) {
                return (Map<?, ?>) cards(context).get(i);
            }
        }
        return null;
    }

    @Test(groups = {"UnitTest", "fast"})
    public void eachBoundaryFreezesItsOwnStateAndSeparatesDiagnosticIdentities() {
        final Game game = game();
        final Player ai = game.getPlayers().get(1);
        final Card bear = addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        final Map<String, Object> before = capture(ai);
        ai.setLife(7, null);
        bear.setTapped(true);
        final Map<String, Object> after = capture(ai);
        Assert.assertEquals(((Map<?, ?>) ((List<?>) policy(before).get("players")).get(1)).get("life"), 20);
        Assert.assertEquals(((Map<?, ?>) ((List<?>) policy(after).get("players")).get(1)).get("life"), 7);
        Assert.assertEquals(card(before, bear).get("tapped"), false);
        Assert.assertEquals(card(after, bear).get("tapped"), true);
        Assert.assertEquals(card(before, bear).get("entityRef"), "c0");
        Assert.assertEquals(((Map<?, ?>) before.get("coverage")).get("stack"), "PUBLIC_ROOT_STACK_V1");
        Assert.assertEquals(((Map<?, ?>) before.get("coverage")).get("history"), "INCOMPLETE");
        Assert.assertEquals(((Map<?, ?>) before.get("coverage")).get("options"), "DIAGNOSTIC_ONLY");
        Assert.assertFalse(policy(before).toString().contains("gameTimestamp"));
        Assert.assertFalse(((Map<?, ?>) cards(before).get(0)).containsKey("id"));
        Assert.expectThrows(UnsupportedOperationException.class, () -> policy(before).clear());
        Assert.expectThrows(UnsupportedOperationException.class, () -> card(before, bear).clear());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void hiddenIdentitiesFacesMemoryAndEngineKeysCannotChangePolicyProjection() {
        Map<?, ?> baseline = null;
        for (final boolean alternate : List.of(false, true)) {
            final Game game = game();
            final Player ai = game.getPlayers().get(1);
            final Player opponent = game.getPlayers().get(0);
            addCardToZone("Ponder", ai, ZoneType.Hand);
            addCardToZone("Grizzly Bears", opponent, ZoneType.Battlefield);
            final Card hiddenHand = addCardToZone(alternate ? "Time Walk" : "Black Lotus", opponent, ZoneType.Hand);
            addCardToZone(alternate ? "Counterspell" : "Shivan Dragon", ai, ZoneType.Library);
            final Card hiddenExile = addCardToZone(alternate ? "Island" : "Mountain", opponent, ZoneType.Exile);
            hiddenExile.turnFaceDown(true);
            final Card publicFaceDown = addCardToZone(alternate ? "Birds of Paradise" : "Lightning Bolt", opponent, ZoneType.Battlefield);
            publicFaceDown.turnFaceDown(true);
            if (alternate) { AiCardMemory.rememberCard(ai, hiddenHand, AiCardMemory.MemorySet.REVEALED_CARDS); }
            final Map<String, Object> context = capture(ai);
            Assert.assertNull(card(context, hiddenHand));
            Assert.assertNull(card(context, hiddenExile));
            Assert.assertNotNull(card(context, publicFaceDown));
            if (baseline == null) { baseline = policy(context); }
            else { Assert.assertEquals(policy(context), baseline); }
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void publicFaceDownCharacteristicsDoNotAuthorizeTheHiddenFace() {
        final Game game = game();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        final Card hidden = addCardToZone("Counterspell", opponent, ZoneType.Battlefield);
        hidden.turnFaceDown(true);
        final Map<?, ?> before = card(capture(ai), hidden);
        Assert.assertEquals(before.get("identityKnown"), false);
        Assert.assertNull(before.get("name"));
        Assert.assertEquals(before.get("power"), 2);
        Assert.assertEquals(before.get("toughness"), 2);
        Assert.assertTrue(before.get("types").toString().contains("Creature"));
        hidden.addMayLookTemp(ai);
        final Map<?, ?> inspected = card(capture(ai), hidden);
        Assert.assertEquals(inspected.get("identityKnown"), true);
        Assert.assertEquals(inspected.get("name"), "Counterspell");
        Assert.assertEquals(inspected.get("power"), 2);
        Assert.assertEquals(before.get("identityKnown"), false);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aFaceUpLibraryCardRequiresAnIndependentViewingGrant() {
        final Game game = game();
        final Player ai = game.getPlayers().get(1);
        final Card top = addCardToZone("Time Walk", ai, ZoneType.Library);
        final Card other = addCardToZone("Black Lotus", ai, ZoneType.Library);
        Assert.assertTrue(top.getView().canFaceDownBeShownTo(ai.getView()));
        Assert.assertFalse(top.getView().canBeShownTo(ai.getView()));
        final Map<String, Object> before = capture(ai);
        Assert.assertTrue(cards(before).isEmpty());
        top.addMayLookTemp(ai);
        final Map<String, Object> after = capture(ai);
        Assert.assertEquals(card(after, top).get("name"), "Time Walk");
        Assert.assertNull(card(after, other));
        Assert.assertTrue(cards(before).isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void permittedLibrarySubsetDoesNotExposeHiddenLibraryOrdering() {
        Map<?, ?> baseline = null;
        for (final boolean reverse : List.of(false, true)) {
            final Game game = game();
            final Player ai = game.getPlayers().get(1);
            for (final String name : reverse ? List.of("Time Walk", "Island", "Black Lotus")
                    : List.of("Black Lotus", "Mountain", "Time Walk")) {
                final Card card = addCardToZone(name, ai, ZoneType.Library);
                if (!card.isLand()) { card.addMayLookTemp(ai); }
            }
            final Map<String, Object> context = capture(ai);
            Assert.assertEquals(cards(context).size(), 2);
            if (baseline == null) { baseline = policy(context); }
            else { Assert.assertEquals(policy(context), baseline); }
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void receivingChooserDeterminesThePrivateView() {
        final Game game = game();
        final Player owner = game.getPlayers().get(0);
        final Player chooser = game.getPlayers().get(1);
        final Card ownersCard = addCardToZone("Black Lotus", owner, ZoneType.Hand);
        final Card choosersCard = addCardToZone("Ponder", chooser, ZoneType.Hand);
        final var ability = ownersCard.getFirstSpellAbility();
        ability.setActivatingPlayer(owner);
        final Map<String, Object> context = AiInputContext.capture(chooser, ability,
                "BEFORE_SELECTOR", "RULES_STATE", () -> true);
        Assert.assertNull(card(context, ownersCard));
        Assert.assertEquals(card(context, choosersCard).get("name"), "Ponder");
        Assert.assertEquals(((Map<?, ?>) context.get("perspective")).get("chooserPlayerId"), chooser.getId());
        Assert.assertEquals(policy(context).get("chooser"), "p1");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void unsupportedControlAndSharedTurnTeamsCannotBorrowAnotherPrivateView() {
        final Game game = game();
        final Player master = game.getPlayers().get(0);
        final Player slave = game.getPlayers().get(1);
        addCardToZone("Black Lotus", slave, ZoneType.Hand);
        slave.addController(game.getNextTimestamp(), master);
        for (final Player perspective : List.of(master, slave)) {
            final Map<String, Object> context = capture(perspective);
            Assert.assertEquals(context.get("status"), "UNAVAILABLE");
            Assert.assertNull(policy(context));
            Assert.assertFalse(context.toString().contains("Black Lotus"));
            Assert.assertEquals(context.get("omissions"), List.of(Map.of("component", "PERSPECTIVE", "reason", "UNSUPPORTED")));
        }
        final Game teamGame = game();
        teamGame.getRules().addAppliedVariant(GameType.TwoHeadedGiant);
        Assert.assertEquals(capture(teamGame.getPlayers().get(1)).get("status"), "UNAVAILABLE");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void cancellationAndDisabledCopiesDoNoAdditionalProjectionWork() {
        final Game game = game();
        final Player ai = game.getPlayers().get(1);
        final AiInputContext.State state = ((PlayerControllerAi) ai.getController()).inputContextState();
        final long before = state.remainingWork();
        final AtomicInteger gates = new AtomicInteger();
        final Map<String, Object> closed = AiInputContext.capture(ai, null, "BEFORE_SELECTOR", "RULES_STATE", () -> {
            gates.incrementAndGet(); return false;
        });
        Assert.assertEquals(closed.get("status"), "UNAVAILABLE");
        Assert.assertEquals(gates.get(), 1);
        Assert.assertEquals(state.remainingWork(), before);
        Thread.currentThread().interrupt();
        try { Assert.assertEquals(capture(ai).get("status"), "UNAVAILABLE"); }
        finally { Thread.interrupted(); }
        Assert.assertEquals(state.remainingWork(), before);
        game.setRecordAiDecisions(false);
        Assert.assertEquals(capture(ai).get("status"), "UNAVAILABLE");
        game.setRecordAiDecisions(true);
        game.markAsSimulationCopy();
        Assert.assertEquals(capture(ai).get("status"), "UNAVAILABLE");
        Assert.assertEquals(state.remainingWork(), before);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void entityAndWholeGameWorkBudgetsReportOmissionsInsteadOfUnboundedCapture() {
        final Game game = game();
        final Player ai = game.getPlayers().get(1);
        for (int i = 0; i <= AiInputContext.MAX_CARDS; i++) { addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield); }
        final Map<String, Object> bounded = capture(ai);
        Assert.assertEquals(cards(bounded).size(), AiInputContext.MAX_CARDS);
        Assert.assertEquals(bounded.get("status"), "PARTIAL");
        Assert.assertTrue(((List<?>) bounded.get("omissions")).contains(Map.of("component", "ENTITIES", "reason", "OUTSIDE_CAPTURED_PREFIX")));
        final Game hiddenGame = game();
        final Player hiddenAi = hiddenGame.getPlayers().get(1);
        for (int i = 0; i < AiInputContext.MAX_CARDS; i++) { addCardToZone("Island", hiddenAi, ZoneType.Library); }
        Assert.assertEquals(capture(hiddenAi).get("status"), "PARTIAL");
        Map<String, Object> exhausted = null;
        for (int i = 0; i < 1_100; i++) {
            exhausted = capture(hiddenAi);
            if ("UNAVAILABLE".equals(exhausted.get("status"))) { break; }
        }
        Assert.assertNotNull(exhausted);
        Assert.assertEquals(exhausted.get("status"), "UNAVAILABLE");
        Assert.assertNull(policy(exhausted));
        Assert.assertTrue(((List<?>) exhausted.get("omissions")).contains(Map.of("component", "CONTEXT", "reason", "BUDGET_EXHAUSTED")));
        Assert.assertEquals(((PlayerControllerAi) hiddenAi.getController()).inputContextState().remainingWork(), 0L);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void privateZoneCapsOmitTheWholeZoneRegardlessOfHiddenCardOrdering() {
        Map<?, ?> baseline = null;
        for (final boolean reverse : List.of(false, true)) {
            final Game game = game();
            final Player ai = game.getPlayers().get(1);
            for (int i = 0; i < AiInputContext.MAX_CARDS - 2; i++) {
                addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
            }
            for (final String name : reverse ? List.of("Time Walk", "Island", "Black Lotus")
                    : List.of("Black Lotus", "Mountain", "Time Walk")) {
                final Card card = addCardToZone(name, ai, ZoneType.Library);
                if (!card.isLand()) { card.addMayLookTemp(ai); }
            }
            final Map<String, Object> context = capture(ai);
            Assert.assertEquals(context.get("status"), "PARTIAL");
            Assert.assertEquals(cards(context).size(), AiInputContext.MAX_CARDS - 2);
            Assert.assertFalse(policy(context).toString().contains("Time Walk"));
            Assert.assertFalse(policy(context).toString().contains("Black Lotus"));
            if (baseline == null) { baseline = policy(context); }
            else { Assert.assertEquals(policy(context), baseline); }
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aChooserBeyondThePlayerPrefixReportsThePrefixLimitRatherThanBudgetExhaustion() {
        final List<RegisteredPlayer> registered = new ArrayList<>();
        for (int i = 0; i <= AiInputContext.MAX_PLAYERS; i++) {
            registered.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("p" + i, null)));
        }
        final GameRules rules = new GameRules(GameType.Constructed);
        final Game game = new Game(registered, rules, new Match(rules, registered, "bounded input"));
        game.setAge(GameStage.Play);
        final Player chooser = game.getPlayers().get(AiInputContext.MAX_PLAYERS);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, chooser);
        game.setRecordAiDecisions(true);
        final Map<String, Object> context = capture(chooser);
        Assert.assertEquals(context.get("status"), "UNAVAILABLE");
        Assert.assertEquals(context.get("omissions"), List.of(Map.of("component", "CONTEXT", "reason", "OUTSIDE_CAPTURED_PREFIX")));
        Assert.assertTrue(((PlayerControllerAi) chooser.getController()).inputContextState().remainingWork() > 0);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void planningStateAndApplicationTimingAreNeverRelabeledAsRulesSelection() {
        final Game game = game();
        final Player ai = game.getPlayers().get(1);
        final Map<String, Object> context = AiInputContext.capture(ai, null,
                "AFTER_SELECTION_BEFORE_APPLICATION", "PLANNING_WORKING_STATE", () -> true);
        Assert.assertEquals(context.get("timing"), "AFTER_SELECTION_BEFORE_APPLICATION");
        Assert.assertEquals(context.get("stateBasis"), "PLANNING_WORKING_STATE");
    }
}
