package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
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

public class AiZoneRequestInputTest extends AITest {
    private record Fixture(Game game, Player chooser, SpellAbility ability, Card card, List<Map<String, Object>> events) { }
    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player chooser = game.getPlayers().get(1);
        final SpellAbility ability = addCardToZone("Cultivate", chooser, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(chooser);
        final Card card = addCardToZone("Island", chooser, ZoneType.Library);
        final List<Map<String, Object>> events = new ArrayList<>();
        game.setRecordAiDecisions(true);
        game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { events.add(choice.data()); } });
        return new Fixture(game, chooser, ability, card, events);
    }
    private Map<?, ?> input(final Fixture f) { return (Map<?, ?>) f.events.get(0).get("inputContext"); }
    private Map<?, ?> prompt(final Fixture f) { return (Map<?, ?>) ((Map<?, ?>) input(f).get("policy")).get("prompt"); }

    @Test(groups = {"UnitTest", "fast"})
    public void actualControllerRetainsReceivedZoneRequestAndOriginalReturn() {
        final Fixture f = fixture(); final Player decider = f.game.getPlayers().get(0);
        Assert.assertSame(f.chooser.getController().chooseSingleCardForZoneChange(ZoneType.Battlefield,
                List.of(ZoneType.Library), f.ability, new CardCollection(f.card), null, "private title", true, decider), f.card);
        Assert.assertEquals(prompt(f), Map.of("kind", "ZONE_CHANGE_REQUEST", "minimumPicks", 0, "maximumPicks", 1,
                "optional", true, "destination", "Battlefield", "origins", List.of("Library"), "decider", "p0"));
        Assert.assertEquals(input(f).get("timing"), "BEFORE_SELECTOR");
        Assert.assertEquals(((Map<?, ?>) f.events.get(0).get("entryLocation")).get("kind"), "SELECTOR");
        Assert.assertFalse(f.events.toString().contains("private title"));
        Assert.assertEquals(f.events.get(1).get("status"), "COMPLETED");
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void noneDestinationAndEmptyOriginsDescribeARequestWithoutInventingAZoneOrHistory() {
        final Fixture f = fixture(); final AtomicInteger calls = new AtomicInteger();
        Assert.assertSame(AiEffectChoiceRecorder.zoneChange(f.chooser, f.ability, ZoneType.None, List.of(), new CardCollection(f.card),
                false, null, () -> { calls.incrementAndGet(); return f.card; }), f.card);
        Assert.assertEquals(calls.get(), 1);
        Assert.assertEquals(prompt(f).get("destination"), "None");
        Assert.assertEquals(prompt(f).get("origins"), List.of());
        Assert.assertEquals(prompt(f).get("minimumPicks"), 1);
        Assert.assertNull(prompt(f).get("decider"));
        Assert.assertFalse(prompt(f).containsKey("priorSelectionCount"));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void orderedOriginsAreFrozenBeforeTheOriginalCallbackMutatesAndThrows() {
        final Fixture f = fixture(); final List<ZoneType> origins = new ArrayList<>(List.of(ZoneType.Sideboard, ZoneType.Hand, ZoneType.Hand));
        final var failure = new IllegalStateException("original callback"); final AtomicInteger calls = new AtomicInteger();
        Assert.assertSame(Assert.expectThrows(IllegalStateException.class, () ->
                AiEffectChoiceRecorder.zoneChange(f.chooser, f.ability, ZoneType.Hand, origins, new CardCollection(f.card), true, f.chooser,
                        () -> { calls.incrementAndGet(); origins.clear(); throw failure; })), failure);
        Assert.assertEquals(calls.get(), 1);
        Assert.assertEquals(prompt(f).get("origins"), List.of("Sideboard", "Hand", "Hand"));
        Assert.expectThrows(UnsupportedOperationException.class, () -> ((List<?>) prompt(f).get("origins")).clear());
        Assert.assertEquals(f.events.get(1).get("status"), "ERROR");
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aForeignEqualIdDeciderCannotAcquireACurrentPlayerReference() {
        final Fixture f = fixture(); final Player foreign = initAndCreateGame().getPlayers().get(0);
        Assert.assertEquals(foreign.getId(), f.game.getPlayers().get(0).getId());
        Assert.assertSame(AiEffectChoiceRecorder.zoneChange(f.chooser, f.ability, ZoneType.Hand, List.of(ZoneType.Library),
                new CardCollection(f.card), false, foreign, () -> f.card), f.card);
        Assert.assertNull(prompt(f));
        Assert.assertTrue(((List<?>) input(f).get("omissions")).contains(Map.of("component", "PROMPT", "reason", "UNRESOLVED_BINDING")));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] bypass() { return new Object[][] {{"OFF"}, {"COPY"}, {"HIDDEN"}}; }
    @Test(dataProvider = "bypass", groups = {"UnitTest", "fast"})
    public void hiddenDisabledAndCopiedChoicesDoNotInspectRequestLists(final String mode) {
        final Fixture f = fixture();
        if ("OFF".equals(mode)) { f.game.setRecordAiDecisions(false); }
        if ("COPY".equals(mode)) { f.game.markAsSimulationCopy(); }
        if ("HIDDEN".equals(mode)) { f.ability.getHostCard().turnFaceDown(); }
        final List<ZoneType> forbidden = new java.util.AbstractList<>() {
            @Override public ZoneType get(int index) { throw new AssertionError("origins inspected"); }
            @Override public int size() { throw new AssertionError("origin count inspected"); }
        };
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertSame(AiEffectChoiceRecorder.zoneChange(f.chooser, f.ability, ZoneType.Hand, forbidden,
                new CardCollection(f.card), false, null, () -> { calls.incrementAndGet(); return f.card; }), f.card);
        Assert.assertEquals(calls.get(), 1);
        if ("HIDDEN".equals(mode)) {
            Assert.assertEquals(f.events.get(0).get("choiceType"), "MASKED_CHOICE");
            Assert.assertNull(input(f).get("policy"));
        } else { Assert.assertTrue(f.events.isEmpty()); }
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void planningRequestChargesEachOriginAndDoesNotGrantArbitraryHypotheticalSuppliers() {
        final Fixture f = fixture(); final var state = ((PlayerControllerAi) f.chooser.getController()).inputContextState();
        final long before = state.remainingWork();
        AiInputContext.capture(f.chooser, f.ability, "BEFORE_SELECTOR", "PLANNING_WORKING_STATE", () -> true);
        final long baseWork = before - state.remainingWork(); final long start = state.remainingWork();
        final var context = AiInputContext.capture(f.chooser, f.ability, "BEFORE_SELECTOR", "PLANNING_WORKING_STATE", () -> true,
                new AiPromptInput.ZoneRequest(ZoneType.Hand, List.of(ZoneType.Library, ZoneType.Graveyard, ZoneType.Library), true, null));
        Assert.assertEquals(start - state.remainingWork(), baseWork + 4);
        Assert.assertEquals(((Map<?, ?>) context.get("coverage")).get("prompt"), "RECEIVED_ZONE_REQUEST_V1");
        Assert.assertEquals(((Map<?, ?>) ((Map<?, ?>) context.get("policy")).get("prompt")).get("origins"), List.of("Library", "Graveyard", "Library"));
        final var refused = AiInputContext.capture(f.chooser, f.ability, "BEFORE_SELECTOR", "PLANNING_WORKING_STATE", () -> true,
                () -> { throw new AssertionError("hypothetical supplier inspected"); });
        Assert.assertNull(((Map<?, ?>) refused.get("policy")).get("prompt"));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void oversizedOrUnsupportedZoneArgumentsAreUnavailableRatherThanTruncatedRequests() {
        final Fixture f = fixture();
        final List<ZoneType> inconsistentCount = new java.util.AbstractList<>() {
            @Override public int size() { return 1; }
            @Override public ZoneType get(int index) { return ZoneType.Library; }
            @Override public java.util.Iterator<ZoneType> iterator() {
                return java.util.Collections.nCopies(AiInputContext.MAX_REQUEST_ZONES + 1, ZoneType.Library).iterator();
            }
        };
        final List<AiPromptInput.ZoneRequest> requests = List.of(
                new AiPromptInput.ZoneRequest(ZoneType.Hand, inconsistentCount, false, null),
                new AiPromptInput.ZoneRequest(ZoneType.Hand, java.util.Collections.nCopies(AiInputContext.MAX_REQUEST_ZONES + 1, ZoneType.Library), false, null),
                new AiPromptInput.ZoneRequest(null, List.of(), false, null),
                new AiPromptInput.ZoneRequest(ZoneType.Hand, null, false, null),
                new AiPromptInput.ZoneRequest(ZoneType.Hand, java.util.Collections.singletonList(null), false, null));
        for (final var request : requests) {
            final var context = AiInputContext.capture(f.chooser, f.ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true, request);
            Assert.assertNull(((Map<?, ?>) context.get("policy")).get("prompt"));
            Assert.assertEquals(((Map<?, ?>) context.get("coverage")).get("prompt"), "UNAVAILABLE");
            Assert.assertTrue(context.get("omissions").toString().contains("PROMPT"));
        }
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void exhaustedInputStillReturnsTheOriginalCardOnce() throws Exception {
        final Fixture f = fixture(); final var state = ((PlayerControllerAi) f.chooser.getController()).inputContextState();
        final var remaining = AiInputContext.State.class.getDeclaredField("remaining"); remaining.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) remaining.get(state)).set(0);
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertSame(AiEffectChoiceRecorder.zoneChange(f.chooser, f.ability, ZoneType.Hand, List.of(ZoneType.Library),
                new CardCollection(f.card), false, null, () -> { calls.incrementAndGet(); return f.card; }), f.card);
        Assert.assertEquals(calls.get(), 1); Assert.assertEquals(input(f).get("status"), "UNAVAILABLE");
        Assert.assertEquals(f.events.get(1).get("status"), "COMPLETED");
        Assert.assertNull(f.game.getEventObservationFailure());
    }
}
