package forge.ai;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.effects.ChangeZoneEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.player.ZoneChangeSelectionRequest;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AiZoneOperationRecorderTest extends AITest {
    private record Fixture(Game game, Player ai, SpellAbility ability, CardCollection cards, List<Map<String, Object>> events) {
        ZoneChangeSelectionRequest request(int iterations) {
            return new ZoneChangeSelectionRequest(ability, ZoneType.Hand, List.of(ZoneType.Library), game.getPlayers().get(0), ai, iterations);
        }
    }
    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player ai = game.getPlayers().get(1);
        final Card source = addCardToZone("Time Beetle", ai, ZoneType.Battlefield);
        final SpellAbility ability = AbilityFactory.getAbility("AB$ ChangeZone | Cost$ 0 | Origin$ Library | Destination$ Hand | ChangeType$ Card | ChangeNum$ 2 | Mandatory$ True | Shuffle$ False", source);
        ability.setActivatingPlayer(ai);
        final CardCollection cards = new CardCollection();
        cards.add(addCardToZone("Forest", ai, ZoneType.Library)); cards.add(addCardToZone("Island", ai, ZoneType.Library));
        final List<Map<String, Object>> events = new ArrayList<>(); game.setRecordAiDecisions(true);
        game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { events.add(choice.data()); } });
        return new Fixture(game, ai, ability, cards, events);
    }
    private Map<?, ?> input(Map<String, Object> row) { return (Map<?, ?>) row.get("inputContext"); }
    private Map<?, ?> prompt(Map<String, Object> row) { return (Map<?, ?>) ((Map<?, ?>) input(row).get("policy")).get("prompt"); }

    @Test(groups = {"UnitTest", "fast"})
    public void directOperationCapturesReceivedRolesBeforeTheCallbackAndReturnsTheSameCollection() {
        final Fixture f = fixture(); final AtomicInteger calls = new AtomicInteger();
        final var ledger = ((PlayerControllerAi) f.ai.getController()).configureObservationAccounting();
        Assert.assertSame(f.ai.getController().withZoneChangeSelections(f.request(-3), observer -> {
            calls.incrementAndGet(); f.ai.setLife(7, null); return f.cards;
        }), f.cards);
        Assert.assertEquals(calls.get(), 1); Assert.assertEquals(f.events.size(), 2);
        final var start = f.events.get(0);
        Assert.assertEquals(start.get("choiceType"), "ZONE_CHANGE_SELECTION_OPERATION");
        Assert.assertEquals(((Map<?, ?>) start.get("entryLocation")).get("kind"), "OPERATION");
        Assert.assertEquals(input(start).get("timing"), "BEFORE_OPERATION"); Assert.assertEquals(input(start).get("schemaVersion"), 24);
        Assert.assertEquals(prompt(start), Map.of("branch", "ORDINARY_HIDDEN_ORIGIN", "kind", "ZONE_CHANGE_OPERATION_REQUEST", "requestedIterations", -3,
                "destination", "Hand", "origins", List.of("Library"), "sourcePlayer", "p0", "decider", "p1"));
        Assert.assertEquals(((Map<?, ?>) ((List<?>) ((Map<?, ?>) input(start).get("policy")).get("players")).get(1)).get("life"), 20);
        Assert.assertEquals(((Map<?, ?>) f.events.get(1).get("selection")).get("coverage"), "OBSERVED_RETAINED_CARD_PREFIX");
        Assert.assertEquals(((Map<?, ?>) f.events.get(1).get("selection")).get("selectedCount"), 2);
        Assert.assertEquals(((Map<?, ?>) ((Map<?, ?>) input(start).get("diagnostics")).get("optionBindings")).get("coverage"), "UNSUPPORTED");
        final var cells = (Map<?, ?>) ledger.snapshot().get("boundaries");
        Assert.assertEquals(cells.size(), 1);
        final var accounting = (Map<?, ?>) cells.get("CONTROLLER_ZONE_CHANGE_SELECTIONS");
        for (String field : List.of("entered", "prepared", "callbackStarted", "callbackReturned", "finished", "startReturned", "terminalReturned")) {
            Assert.assertEquals(accounting.get(field), 1L);
        }
        Assert.assertEquals(accounting.get("omitted"), 0L); Assert.assertEquals(accounting.get("observationFailures"), 0L);
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualEngineSelectorsHaveAnOuterOwnerAndTheirOwnProgress() {
        final Fixture f = fixture(); new ChangeZoneEffect().resolve(f.ability);
        final var outer = f.events.get(0); Assert.assertEquals(outer.get("choiceType"), "ZONE_CHANGE_SELECTION_OPERATION");
        final var children = f.events.stream().filter(c -> "CONTROLLER_ZONE_CHANGE_CARD".equals(c.get("boundary"))).toList();
        Assert.assertEquals(children.size(), 2);
        Assert.assertTrue(((List<?>) ((Map<?, ?>) input(outer).get("policy")).get("disclosedFacts")).isEmpty());
        Assert.assertEquals(((List<?>) ((Map<?, ?>) input(children.get(0)).get("policy")).get("disclosedFacts")).size(), 2);
        for (final var child : children) {
            Assert.assertEquals(child.get("parentChoiceId"), outer.get("choiceId"));
            Assert.assertEquals(prompt(child).get("kind"), "ZONE_PICK_PROGRESS");
            Assert.assertFalse(((Map<?, ?>) child.get("options")).containsKey("selectionSequence"));
        }
        Assert.assertEquals(((Map<?, ?>) f.events.get(f.events.size()-1).get("selection")).get("selectedCount"), 2);
        Assert.assertEquals(f.ai.getCardsIn(ZoneType.Hand).size(), 2); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void emptyAndNullReturnsAndOriginalErrorsRetainDistinctOutcomes() {
        final Fixture f = fixture(); final var controller = f.ai.getController();
        final CardCollection empty = new CardCollection(); Assert.assertSame(controller.withZoneChangeSelections(f.request(0), observer -> empty), empty);
        Assert.assertEquals(f.events.get(1).get("status"), "COMPLETED");
        Assert.assertNull(controller.withZoneChangeSelections(f.request(1), observer -> null));
        Assert.assertEquals(f.events.get(3).get("status"), "DECLINED");
        final var original = new IllegalStateException("original callback");
        Assert.assertSame(Assert.expectThrows(IllegalStateException.class, () -> controller.withZoneChangeSelections(f.request(1), observer -> { throw original; })), original);
        Assert.assertEquals(f.events.get(5).get("status"), "ERROR"); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void nestedOperationsRestoreOwnershipAndKeepTheirOwnRequestAndInput() {
        final Fixture f = fixture(); final var controller = f.ai.getController();
        controller.withZoneChangeSelections(f.request(2), observer -> {
            controller.withZoneChangeSelections(f.request(1), nested -> new CardCollection()); return f.cards;
        });
        Assert.assertEquals(f.events.get(1).get("parentChoiceId"), f.events.get(0).get("choiceId"));
        Assert.assertEquals(prompt(f.events.get(0)).get("requestedIterations"), 2);
        Assert.assertEquals(prompt(f.events.get(1)).get("requestedIterations"), 1);
        controller.withZoneChangeSelections(f.request(0), observer -> new CardCollection());
        Assert.assertNull(f.events.get(4).get("parentChoiceId")); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void nullableRolesAndForeignEqualIdRolesAreNotInterchangeable() {
        final Fixture f = fixture(); final Player foreign = initAndCreateGame().getPlayers().get(0);
        for (boolean source : List.of(false, true)) {
            f.events.clear(); final var r = new ZoneChangeSelectionRequest(f.ability, ZoneType.None, List.of(), source ? foreign : f.ai, source ? f.ai : foreign, 1);
            Assert.assertSame(f.ai.getController().withZoneChangeSelections(r, observer -> f.cards), f.cards);
            Assert.assertNull(prompt(f.events.get(0)));
            Assert.assertTrue(input(f.events.get(0)).get("omissions").toString().contains("UNRESOLVED_BINDING"));
        }
        f.events.clear(); f.ai.getController().withZoneChangeSelections(new ZoneChangeSelectionRequest(f.ability, ZoneType.None, List.of(), null, null, 0), observer -> f.cards);
        Assert.assertNull(prompt(f.events.get(0)).get("sourcePlayer")); Assert.assertNull(prompt(f.events.get(0)).get("decider"));
    }

    @DataProvider public Object[][] bypass() { return new Object[][] {{"OFF"}, {"COPY"}, {"HIDDEN"}}; }
    @Test(dataProvider = "bypass", groups = {"UnitTest", "fast"})
    public void disabledCopiedAndHiddenOperationsDoNotInspectRequestListsOrResultGetters(String mode) {
        final Fixture f = fixture();
        if ("OFF".equals(mode)) { f.game.setRecordAiDecisions(false); }
        if ("COPY".equals(mode)) { f.game.markAsSimulationCopy(); }
        if ("HIDDEN".equals(mode)) { f.ability.getHostCard().turnFaceDown(); }
        final List<ZoneType> forbidden = new java.util.AbstractList<>() {
            @Override public int size() { throw new AssertionError("origins inspected"); }
            @Override public ZoneType get(int index) { throw new AssertionError("origins inspected"); }
        };
        final CardCollection result = Mockito.spy(new CardCollection());
        Mockito.doThrow(new AssertionError("result inspected")).when(result).size();
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertSame(f.ai.getController().withZoneChangeSelections(new ZoneChangeSelectionRequest(f.ability, ZoneType.Hand, forbidden, f.ai, f.ai, 1),
                observer -> { calls.incrementAndGet(); return result; }), result);
        Assert.assertEquals(calls.get(), 1);
        if ("HIDDEN".equals(mode)) { Assert.assertNull(input(f.events.get(0)).get("policy")); }
        else { Assert.assertTrue(f.events.isEmpty()); }
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void liveDiagnosticFailureIsVisibleWithoutReplacingTheCallbackResult() {
        final Fixture f = fixture(); final var original = new IllegalStateException("origin capture");
        final List<ZoneType> broken = new java.util.AbstractList<>() {
            @Override public int size() { return 1; }
            @Override public ZoneType get(int index) { throw original; }
        };
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertSame(f.ai.getController().withZoneChangeSelections(new ZoneChangeSelectionRequest(f.ability, ZoneType.Hand, broken, f.ai, f.ai, 1),
                observer -> { calls.incrementAndGet(); return f.cards; }), f.cards);
        Assert.assertEquals(calls.get(), 1); Assert.assertSame(f.game.getEventObservationFailure(), original);
        Assert.assertTrue(f.events.isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void planningCarrierUsesExistingWorkAndDoesNotGrantOtherHypotheticalInputs() {
        final Fixture f = fixture(); final var state = ((PlayerControllerAi) f.ai.getController()).inputContextState();
        long before = state.remainingWork(); AiInputContext.capture(f.ai, f.ability, "BEFORE_OPERATION", "PLANNING_WORKING_STATE", () -> true);
        long base = before - state.remainingWork(); before = state.remainingWork();
        final var captured = AiInputContext.capture(f.ai, f.ability, "BEFORE_OPERATION", "PLANNING_WORKING_STATE", () -> true, new AiPromptInput.ZoneOperation(f.request(2)));
        Assert.assertEquals(before - state.remainingWork(), base + 2);
        Assert.assertEquals(((Map<?, ?>) captured.get("coverage")).get("prompt"), "RECEIVED_ZONE_OPERATION_V1");
        final var refused = AiInputContext.capture(f.ai, f.ability, "BEFORE_OPERATION", "PLANNING_WORKING_STATE", () -> true, () -> { throw new AssertionError("unrelated input inspected"); });
        Assert.assertNull(((Map<?, ?>) refused.get("policy")).get("prompt"));
    }
    @Test(groups = {"UnitTest", "fast"})
    public void retainedPrefixDoesNotClampTheActualCollectionToTheReceivedIterationRequest() {
        final Fixture f = fixture(); final CardCollection many = new CardCollection();
        for (int i = 0; i < 257; i++) { many.add(createCard("Memnite", f.ai)); }
        Assert.assertSame(f.ai.getController().withZoneChangeSelections(f.request(0), observer -> many), many);
        final var selected = (Map<?, ?>) f.events.get(1).get("selection");
        Assert.assertEquals(selected.get("selectedCount"), 257); Assert.assertEquals(selected.get("truncated"), true);
        Assert.assertEquals(((List<?>) selected.get("items")).size(), 256); Assert.assertEquals(many.size(), 257);
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void exhaustedInputKeepsTheOriginalCallbackAndCompletedResult() throws Exception {
        final Fixture f = fixture(); final var state = ((PlayerControllerAi) f.ai.getController()).inputContextState();
        final var remaining = AiInputContext.State.class.getDeclaredField("remaining"); remaining.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) remaining.get(state)).set(0);
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertSame(f.ai.getController().withZoneChangeSelections(f.request(2), observer -> { calls.incrementAndGet(); return f.cards; }), f.cards);
        Assert.assertEquals(calls.get(), 1); Assert.assertEquals(input(f.events.get(0)).get("status"), "UNAVAILABLE");
        Assert.assertEquals(f.events.get(1).get("status"), "COMPLETED"); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] groupedBranches() {
        return new Object[][] {{"SAME_NAME_HIDDEN_ORIGIN", " | SameNameAsEach$ Valid Land.YouCtrl"},
                {"EACH_TYPE_HIDDEN_ORIGIN", " | ChangeType$ EACH Forest & Island"}};
    }
    @Test(dataProvider = "groupedBranches", groups = {"UnitTest", "fast"})
    public void groupedEngineChoicesHaveTheirExactBranchAndScopedHistory(final String branch, final String parameters) {
        final Fixture f = fixture();
        addCardToZone("Forest", f.ai, ZoneType.Battlefield); addCardToZone("Island", f.ai, ZoneType.Battlefield);
        final SpellAbility ability = AbilityFactory.getAbility("AB$ ChangeZone | Cost$ 0 | Origin$ Library | Destination$ Hand"
                + " | ChangeType$ Card | ChangeNum$ 1 | Mandatory$ True | Shuffle$ False" + parameters, f.ability.getHostCard());
        ability.setActivatingPlayer(f.ai); new ChangeZoneEffect().resolve(ability);
        final var starts = f.events.stream().filter(c -> "STARTED".equals(c.get("status"))).toList();
        final var outers = starts.stream().filter(c -> "ZONE_CHANGE_SELECTION_OPERATION".equals(c.get("choiceType"))).toList();
        final var children = starts.stream().filter(c -> "ZONE_CHANGE_CARD_SELECTION".equals(c.get("choiceType"))).toList();
        Assert.assertEquals(outers.size(), branch.startsWith("SAME") ? 1 : 2); Assert.assertEquals(children.size(), 2);
        for (final var outer : outers) { Assert.assertEquals(prompt(outer).get("branch"), branch); }
        for (int i = 0; i < children.size(); i++) {
            final var child = children.get(i); final var parent = outers.get(outers.size() == 1 ? 0 : i);
            Assert.assertEquals(child.get("parentChoiceId"), parent.get("choiceId"));
            Assert.assertEquals(prompt(child).get("branch"), branch);
            Assert.assertEquals(prompt(child).get("attemptIndex"), outers.size() == 1 ? (long) i : 0L);
            Assert.assertEquals(prompt(child).get("retainedCount"), i);
            Assert.assertEquals(prompt(child).get("priorNonNullReturnCount"), outers.size() == 1 ? (long) i : 0L);
        }
        Assert.assertEquals(f.ai.getCardsIn(ZoneType.Hand).size(), 2); Assert.assertNull(f.game.getEventObservationFailure());
    }

}
