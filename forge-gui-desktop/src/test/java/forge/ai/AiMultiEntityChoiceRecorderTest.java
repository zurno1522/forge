package forge.ai;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.player.DelayedReveal;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollection;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class AiMultiEntityChoiceRecorderTest extends AITest {
    private record Fixture(Game game, Player chooser, SpellAbility ability, FCollection<Card> options,
            List<Map<String, Object>> events, AiObservationAccounting ledger) { }

    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player chooser = game.getPlayers().get(1);
        final Card source = addCardToZone("Time Beetle", chooser, ZoneType.Battlefield);
        final SpellAbility ability = AbilityFactory.getAbility("AB$ TimeTravel | Cost$ 0", source);
        ability.setActivatingPlayer(chooser);
        final FCollection<Card> options = new FCollection<>(List.of(
                addCardToZone("Grizzly Bears", chooser, ZoneType.Battlefield),
                addCardToZone("Shivan Dragon", chooser, ZoneType.Battlefield)));
        final List<Map<String, Object>> events = new ArrayList<>();
        game.setRecordAiDecisions(true);
        final AiObservationAccounting ledger = ((PlayerControllerAi) chooser.getController()).configureObservationAccounting();
        game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { events.add(choice.data()); } });
        return new Fixture(game, chooser, ability, options, events, ledger);
    }

    private List<Card> choose(final Fixture f, final int min, final int max) {
        return f.chooser.getController().chooseEntitiesForEffect(f.options, min, max, null, f.ability,
                "private rendered prompt", f.game.getPlayers().get(0), Map.of("unrecorded", "private parameter"));
    }

    private Map<String, Object> outer(final Fixture f) {
        return f.events.stream().filter(e -> "CONTROLLER_EFFECT_ENTITIES".equals(e.get("boundary"))).findFirst().orElseThrow();
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualControllerRecordsOuterBoundsAndOrderedInnerPicksWithoutChangingTheirRemainingPools() {
        final Fixture f = fixture();
        Assert.assertEquals(choose(f, 1, 2), new ArrayList<>(f.options));
        Assert.assertEquals(f.options.size(), 2, "The caller's offer is not the controller's narrowed working collection");
        Assert.assertEquals(f.events.size(), 6);
        final var outer = outer(f);
        Assert.assertEquals(((Map<?, ?>) outer.get("entryLocation")).get("kind"), "OPERATION");
        Assert.assertEquals(((Map<?, ?>) outer.get("options")).get("min"), 1);
        Assert.assertEquals(((Map<?, ?>) outer.get("options")).get("max"), 2);
        for (final int index : List.of(1, 3)) {
            Assert.assertEquals(f.events.get(index).get("parentChoiceId"), outer.get("choiceId"));
            Assert.assertEquals(f.events.get(index).get("boundary"), "CONTROLLER_EFFECT_ENTITY");
        }
        Assert.assertEquals(((List<?>) ((Map<?, ?>) f.events.get(1).get("options")).get("items")).size(), 2);
        Assert.assertEquals(((List<?>) ((Map<?, ?>) f.events.get(3).get("options")).get("items")).size(), 1);
        Assert.assertEquals(((Map<?, ?>) f.events.get(1).get("options")).get("optional"), false);
        Assert.assertEquals(((Map<?, ?>) f.events.get(3).get("options")).get("optional"), true);
        final var selected = (Map<?, ?>) f.events.get(5).get("selection");
        Assert.assertEquals(selected.get("selectedCount"), 2);
        final var refs = (List<?>) selected.get("items");
        Assert.assertEquals(((Map<?, ?>) refs.get(0)).get("id"), f.options.get(0).getId());
        Assert.assertEquals(((Map<?, ?>) refs.get(1)).get("id"), f.options.get(1).getId());
        Assert.assertFalse(f.events.toString().contains("private rendered prompt"));
        Assert.assertFalse(f.events.toString().contains("private parameter"));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualControllerProjectsReceivedTotalBoundsAndRelatedRoleWithoutPromotingTheOfferedPool() {
        final Fixture f = fixture(); choose(f, 0, 1);
        final var input = (Map<?, ?>) outer(f).get("inputContext");
        final var policy = (Map<?, ?>) input.get("policy");
        Assert.assertEquals(input.get("schemaVersion"), 16);
        Assert.assertEquals(input.get("profile"), "PERMITTED_CHOICE_VIEW_V16");
        Assert.assertEquals(input.get("timing"), "BEFORE_OPERATION");
        Assert.assertEquals(policy.get("prompt"), Map.of("kind", "ENTITY_BOUNDS", "minimumPicks", 0,
                "maximumPicks", 1, "relatedPlayer", "p0"));
        Assert.assertEquals(((Map<?, ?>) ((Map<?, ?>) input.get("diagnostics")).get("optionBindings")).get("coverage"), "UNSUPPORTED");
        Assert.assertEquals(((Map<?, ?>) input.get("coverage")).get("prompt"), "RECEIVED_ENTITY_BOUNDS_V1");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void emptyZeroBoundOperationRetainsTheOriginalInitialDeclinedSelectorAndACompletedEmptyResult() {
        final Fixture f = fixture(); f.options.clear();
        Assert.assertTrue(choose(f, 0, 0).isEmpty());
        Assert.assertEquals(f.events.size(), 4);
        Assert.assertEquals(f.events.get(1).get("parentChoiceId"), outer(f).get("choiceId"));
        Assert.assertEquals(f.events.get(2).get("status"), "DECLINED");
        Assert.assertEquals(f.events.get(3).get("status"), "COMPLETED");
        Assert.assertEquals(((Map<?, ?>) f.events.get(3).get("selection")).get("selectedCount"), 0);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void sourceAccountingCountsTheOuterOperationSeparatelyFromItsTwoOverlappingSelectors() {
        final Fixture f = fixture(); choose(f, 1, 2); f.ledger.close();
        final var rows = (Map<?, ?>) f.ledger.snapshot().get("boundaries");
        final var outer = (Map<?, ?>) rows.get("CONTROLLER_EFFECT_ENTITIES");
        final var inner = (Map<?, ?>) rows.get("CONTROLLER_EFFECT_ENTITY");
        for (final String name : List.of("entered", "prepared", "callbackStarted", "callbackReturned", "startReturned", "terminalReturned", "finished")) {
            Assert.assertEquals(outer.get(name), 1L, name);
            Assert.assertEquals(inner.get(name), 2L, name);
        }
        Assert.assertEquals(outer.get("omitted"), 0L);
        Assert.assertEquals(f.ledger.snapshot().get("scopeComplete"), true);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void outerEntryFollowsActualDelayedDeliveryAndDoesNotExtendItsKnowledgeLifetime() {
        final Fixture f = fixture(); final Player owner = f.game.getPlayers().get(0);
        final Card first = addCardToZone("Island", owner, ZoneType.Library);
        final Card second = addCardToZone("Forest", owner, ZoneType.Library);
        final FCollection<Card> options = new FCollection<>(List.of(first, second));
        final var reveal = new DelayedReveal(new CardCollection(options), ZoneType.Library, PlayerView.get(owner));
        Assert.assertEquals(f.chooser.getController().chooseEntitiesForEffect(options, 1, 1, reveal, f.ability,
                "unrecorded", owner, null), List.of(first));
        final var input = (Map<?, ?>) outer(f).get("inputContext");
        final var facts = (List<?>) ((Map<?, ?>) input.get("policy")).get("disclosedFacts");
        Assert.assertEquals(facts.size(), 2);
        Assert.assertEquals(((Map<?, ?>) facts.get(0)).get("name"), "Island");
        final var after = AiInputContext.capture(f.chooser, f.ability, "BEFORE_OPERATION", "RULES_STATE", () -> true);
        Assert.assertTrue(((List<?>) ((Map<?, ?>) after.get("policy")).get("disclosedFacts")).isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void failedPlanningOperationKeepsTheOriginalExceptionAndRestoresTheNextSelectorsScope() {
        final Fixture f = fixture(); final var error = new IllegalArgumentException("original operation failure");
        Assert.assertSame(Assert.expectThrows(IllegalArgumentException.class, () -> AiChoiceRecorder.planning(f.chooser,
                () -> AiEffectChoiceRecorder.multiple(f.chooser, f.ability, f.options, 0, 2, null,
                        () -> { throw error; }))), error);
        Assert.assertEquals(f.events.get(1).get("status"), "ERROR");
        final var input = (Map<?, ?>) outer(f).get("inputContext");
        Assert.assertEquals(input.get("stateBasis"), "PLANNING_WORKING_STATE");
        Assert.assertEquals(((Map<?, ?>) input.get("coverage")).get("prompt"), "RECEIVED_ENTITY_BOUNDS_V1");
        f.chooser.getController().chooseSingleEntityForEffect(f.options, null, f.ability, "unrecorded", false, null, null);
        Assert.assertNull(f.events.get(2).get("parentChoiceId"));
        Assert.assertEquals(((Map<?, ?>) f.events.get(2).get("inputContext")).get("stateBasis"), "RULES_STATE");
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void returnedPrefixPreservesDuplicatesAndActualCountWithoutClampingToTheReceivedBounds() {
        final Fixture f = fixture(); final List<Card> result = Collections.nCopies(300, f.options.get(0));
        Assert.assertSame(AiEffectChoiceRecorder.multiple(f.chooser, f.ability, f.options, 0, 1, null, () -> result), result);
        final var selected = (Map<?, ?>) f.events.get(1).get("selection");
        Assert.assertEquals(selected.get("selectedCount"), 300);
        Assert.assertEquals(((List<?>) selected.get("items")).size(), 256);
        Assert.assertEquals(selected.get("truncated"), true);
        Assert.assertEquals(((List<?>) selected.get("items")).get(0), ((List<?>) selected.get("items")).get(1));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void exhaustedInputRetainsTheSuccessfulOperationAndAForeignEqualIdCannotBindItsRole() throws Exception {
        final Fixture f = fixture(); final Player foreign = initAndCreateGame().getPlayers().get(0);
        AiEffectChoiceRecorder.multiple(f.chooser, f.ability, f.options, 0, 1, foreign, List::of);
        final var input = (Map<?, ?>) outer(f).get("inputContext");
        Assert.assertNull(((Map<?, ?>) input.get("policy")).get("prompt"));
        Assert.assertTrue(((List<?>) input.get("omissions")).contains(Map.of("component", "PROMPT", "reason", "UNRESOLVED_BINDING")));
        final var state = ((PlayerControllerAi) f.chooser.getController()).inputContextState();
        final var field = AiInputContext.State.class.getDeclaredField("remaining"); field.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) field.get(state)).set(0);
        f.events.clear(); Assert.assertEquals(choose(f, 1, 2), new ArrayList<>(f.options));
        Assert.assertEquals(((Map<?, ?>) outer(f).get("inputContext")).get("status"), "UNAVAILABLE");
        Assert.assertEquals(f.events.get(5).get("status"), "COMPLETED");
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] bypassOffers() { return new Object[][] {{"HIDDEN"}, {"DISABLED"}, {"COPY"}}; }
    @Test(dataProvider = "bypassOffers", groups = {"UnitTest", "fast"})
    public void unavailableOuterObservationDoesNotInspectItsOffersOrReturnedList(final String reason) {
        final Fixture f = fixture();
        if ("HIDDEN".equals(reason)) { f.ability.getHostCard().turnFaceDown(); }
        if ("DISABLED".equals(reason)) { f.game.setRecordAiDecisions(false); }
        if ("COPY".equals(reason)) { f.game.markAsSimulationCopy(); }
        final Iterable<Card> privateOffer = () -> { throw new AssertionError("hidden offer"); };
        final List<Card> privateResult = new java.util.AbstractList<>() {
            @Override public Card get(final int index) { throw new AssertionError("hidden return"); }
            @Override public int size() { throw new AssertionError("hidden return count"); }
        };
        final var calls = new java.util.concurrent.atomic.AtomicInteger();
        Assert.assertSame(AiEffectChoiceRecorder.multiple(f.chooser, f.ability, privateOffer, 0, 2, null,
                () -> { calls.incrementAndGet(); return privateResult; }), privateResult);
        Assert.assertEquals(calls.get(), 1);
        if ("HIDDEN".equals(reason)) {
            Assert.assertEquals(f.events.size(), 2);
            Assert.assertEquals(f.events.get(0).get("boundary"), "MASKED_CHOICE");
            final var input = (Map<?, ?>) f.events.get(0).get("inputContext");
            Assert.assertEquals(input.get("profile"), "PERMITTED_CHOICE_VIEW_V16");
            Assert.assertEquals(input.get("timing"), "UNESTABLISHED");
            Assert.assertEquals(input.get("stateBasis"), "UNESTABLISHED");
            Assert.assertNull(f.events.get(1).get("selection"));
        } else { Assert.assertTrue(f.events.isEmpty()); }
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] off() { return new Object[][] {{false}, {true}}; }
    @Test(dataProvider = "off", groups = {"UnitTest", "fast"})
    public void disabledAndCopiedControllerLoopsKeepTheirOriginalSelections(final boolean copy) {
        final Fixture f = fixture();
        if (copy) { f.game.markAsSimulationCopy(); } else { f.game.setRecordAiDecisions(false); }
        Assert.assertEquals(choose(f, 1, 2), new ArrayList<>(f.options));
        Assert.assertTrue(f.events.isEmpty());
        Assert.assertNull(f.game.getEventObservationFailure());
    }
}
