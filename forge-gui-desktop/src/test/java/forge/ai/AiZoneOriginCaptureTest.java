package forge.ai;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.player.ZoneChangeSelectionRequest;
import forge.game.player.ZoneChangeSelectionObserver;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class AiZoneOriginCaptureTest extends AITest {
    private record Fixture(Game game, Player player, SpellAbility ability, Card card,
            AiObservationAccounting accounting, List<Map<String, Object>> events) { }
    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player player = game.getPlayers().get(1);
        final Card source = addCardToZone("Time Beetle", player, ZoneType.Battlefield);
        final SpellAbility ability = AbilityFactory.getAbility("AB$ ChangeZone | Cost$ 0 | Origin$ Library | Destination$ Hand | ChangeType$ Card", source);
        ability.setActivatingPlayer(player);
        final Card card = addCardToZone("Grizzly Bears", player, ZoneType.Graveyard);
        final var accounting = ((PlayerControllerAi) player.getController()).configureObservationAccounting();
        final List<Map<String, Object>> events = new ArrayList<>(); game.setRecordAiDecisions(true);
        game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { events.add(choice.data()); } });
        return new Fixture(game, player, ability, card, accounting, events);
    }
    private Object result(Fixture f, boolean outer) { return outer ? new CardCollection(f.card) : f.card; }
    private Object observe(Fixture f, boolean outer, List<ZoneType> origins, Supplier<?> callback) {
        if (outer) {
            final var request = new ZoneChangeSelectionRequest(f.ability, ZoneType.Hand, origins, f.player, f.player, 1);
            return f.player.getController().withZoneChangeSelections(request, ignored -> (CardCollection) callback.get());
        }
        return AiEffectChoiceRecorder.zoneChange(f.player, f.ability, ZoneType.Hand, origins, new CardCollection(), true, f.player, () -> (Card) callback.get());
    }
    private Map<?, ?> cell(Fixture f, boolean outer) {
        return (Map<?, ?>) ((Map<?, ?>) f.accounting.snapshot().get("boundaries")).get(outer ? "CONTROLLER_ZONE_CHANGE_SELECTIONS" : "CONTROLLER_ZONE_CHANGE_CARD");
    }
    private void unsupported(Fixture f, boolean outer) {
        Assert.assertTrue(f.events.isEmpty()); Assert.assertNull(f.game.getEventObservationFailure());
        final var cell = cell(f, outer);
        Assert.assertEquals(cell.get("entered"), 1L); Assert.assertEquals(cell.get("prepared"), 0L);
        Assert.assertEquals(cell.get("omittedUnsupported"), 1L); Assert.assertEquals(cell.get("omittedFailure"), 0L);
        Assert.assertEquals(cell.get("observationFailures"), 0L); Assert.assertEquals(cell.get("callbackStarted"), 1L);
        Assert.assertEquals(cell.get("finished"), 1L);
    }
    @DataProvider public Object[][] boundaries() { return new Object[][] {{false}, {true}}; }

    @Test(dataProvider = "boundaries", groups = {"UnitTest", "fast"})
    public void oversizedMetadataRunsTheOriginalCallbackWithoutInspectingTheResult(final boolean outer) {
        final Fixture f = fixture(); final AtomicInteger calls = new AtomicInteger();
        final List<ZoneType> origins = new AbstractList<>() {
            @Override public int size() { return ZoneType.values().length + 1; }
            @Override public ZoneType get(int index) { throw new AssertionError("oversized origins enumerated"); }
            @Override public Iterator<ZoneType> iterator() { throw new AssertionError("oversized origins enumerated"); }
        };
        final Object returned;
        if (outer) {
            final CardCollection collection = Mockito.spy(new CardCollection());
            Mockito.doThrow(new AssertionError("omitted result inspected")).when(collection).size(); returned = collection;
        } else {
            final Card card = Mockito.spy(createCard("Memnite", f.player));
            Mockito.doThrow(new AssertionError("omitted result inspected")).when(card).getGameTimestamp(); returned = card;
        }
        Assert.assertSame(observe(f, outer, origins, () -> { calls.incrementAndGet(); return returned; }), returned);
        Assert.assertEquals(calls.get(), 1); unsupported(f, outer);
    }

    @Test(dataProvider = "boundaries", groups = {"UnitTest", "fast"})
    public void anUnboundedIteratorCannotBypassTheReportedSizeLimit(final boolean outer) {
        final Fixture f = fixture(); final AtomicInteger reads = new AtomicInteger(); final Object returned = result(f, outer);
        final List<ZoneType> origins = new AbstractList<>() {
            @Override public int size() { return 1; }
            @Override public ZoneType get(int i) { throw new AssertionError("index access is not the iterator contract"); }
            @Override public Iterator<ZoneType> iterator() {
                return new Iterator<>() {
                    @Override public boolean hasNext() { return true; }
                    @Override public ZoneType next() {
                        if (reads.incrementAndGet() > ZoneType.values().length) { throw new AssertionError("origin read past cap"); }
                        return ZoneType.Library;
                    }
                };
            }
        };
        Assert.assertSame(observe(f, outer, origins, () -> returned), returned);
        Assert.assertEquals(reads.get(), ZoneType.values().length); unsupported(f, outer);
    }

    @Test(dataProvider = "boundaries", groups = {"UnitTest", "fast"})
    public void nullAndInconsistentOriginsAreUnsupportedInsteadOfInvalidWireData(final boolean outer) {
        final List<List<ZoneType>> requests = new ArrayList<>(); requests.add(null); requests.add(Collections.singletonList(null));
        requests.add(new AbstractList<>() {
            @Override public int size() { return 1; }
            @Override public ZoneType get(int i) { return ZoneType.Library; }
            @Override public Iterator<ZoneType> iterator() { return Collections.emptyIterator(); }
        });
        for (var origins : requests) {
            final Fixture f = fixture(); final Object returned = result(f, outer);
            Assert.assertSame(observe(f, outer, origins, () -> returned), returned); unsupported(f, outer);
        }
    }

    @Test(dataProvider = "boundaries", groups = {"UnitTest", "fast"})
    public void exactMaximumRequestsKeepDuplicateOriginOrderAndNormalPublication(final boolean outer) {
        final Fixture f = fixture(); final var origins = new ArrayList<>(Collections.nCopies(ZoneType.values().length, ZoneType.Library));
        origins.set(1, ZoneType.Graveyard); final Object returned = result(f, outer);
        Assert.assertSame(observe(f, outer, origins, () -> returned), returned);
        Assert.assertEquals(f.events.size(), 2); Assert.assertNull(f.game.getEventObservationFailure());
        final var values = (List<?>) ((Map<?, ?>) f.events.get(0).get("options")).get("origins");
        Assert.assertEquals(values.size(), origins.size()); Assert.assertEquals(values.get(1), "Graveyard");
        Assert.assertEquals(values.stream().filter("Library"::equals).count(), (long) origins.size() - 1);
        Assert.assertEquals(cell(f, outer).get("prepared"), 1L); Assert.assertEquals(cell(f, outer).get("omitted"), 0L);
    }

    @Test(dataProvider = "boundaries", groups = {"UnitTest", "fast"})
    public void aRealOriginGetterFailureStillReportsObservationFailure(final boolean outer) {
        final Fixture f = fixture(); final var original = new IllegalStateException("actual origin getter failure"); final Object returned = result(f, outer);
        final List<ZoneType> origins = new AbstractList<>() {
            @Override public int size() { throw original; }
            @Override public ZoneType get(int i) { throw original; }
        };
        Assert.assertSame(observe(f, outer, origins, () -> returned), returned);
        Assert.assertTrue(f.events.isEmpty()); Assert.assertSame(f.game.getEventObservationFailure(), original);
        Assert.assertEquals(cell(f, outer).get("omittedFailure"), 1L); Assert.assertEquals(cell(f, outer).get("omittedUnsupported"), 0L);
        Assert.assertEquals(cell(f, outer).get("observationFailures"), 1L);
    }

    @Test(dataProvider = "boundaries", groups = {"UnitTest", "fast"})
    public void unsupportedMetadataDoesNotReplaceAnOriginalCallbackError(final boolean outer) {
        final Fixture f = fixture(); final var original = new IllegalArgumentException("original callback");
        Assert.assertSame(Assert.expectThrows(IllegalArgumentException.class, () -> observe(f, outer,
                Collections.nCopies(ZoneType.values().length + 1, ZoneType.Library), () -> { throw original; })), original);
        unsupported(f, outer); Assert.assertEquals(cell(f, outer).get("callbackThrew"), 1L);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void anUnsupportedMarkerThrownByTheCallbackIsStillTheOriginalCallbackError() {
        final Fixture f = fixture(); final RuntimeException original = AiChoiceRecorder.unsupportedOptions();
        Assert.assertSame(Assert.expectThrows(RuntimeException.class, () -> observe(f, false, List.of(ZoneType.Library), () -> { throw original; })), original);
        Assert.assertEquals(f.events.size(), 2); Assert.assertEquals(f.events.get(1).get("status"), "ERROR");
        Assert.assertEquals(cell(f, false).get("prepared"), 1L); Assert.assertEquals(cell(f, false).get("callbackThrew"), 1L);
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] closure() { return new Object[][] {{false, false}, {false, true}, {true, false}, {true, true}}; }
    @Test(dataProvider = "closure", groups = {"UnitTest", "fast"})
    @SuppressWarnings("unchecked")
    public void closureDiscardsLateUnsupportedValuesAndRealGetterFailures(final boolean parentClosure, final boolean fail) throws Exception {
        final Fixture f = fixture(); final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger callbacks = new AtomicInteger(); final CardCollection returned = new CardCollection(f.card);
        final List<ZoneType> origins = new AbstractList<>() {
            @Override public int size() {
                entered.countDown(); try { Assert.assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                if (fail) { throw new IllegalStateException("late diagnostic origin fault"); } return ZoneType.values().length + 1;
            }
            @Override public ZoneType get(int i) { throw new AssertionError("late oversized origins enumerated"); }
        };
        final var parent = new AiDecisionRecorder(f.game, f.player, 1); parent.beginAssessment(f.ability);
        final var field = AiController.class.getDeclaredField("evaluationDecision"); field.setAccessible(true);
        final ThreadLocal<AiDecisionRecorder> evaluation = (ThreadLocal<AiDecisionRecorder>) field.get(((PlayerControllerAi) f.player.getController()).getAi());
        final var worker = Executors.newSingleThreadExecutor();
        try {
            final var result = worker.submit(() -> {
                if (parentClosure) { evaluation.set(parent); }
                try { return observe(f, true, origins, () -> { callbacks.incrementAndGet(); return returned; }); }
                finally { evaluation.remove(); }
            });
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
            if (parentClosure) { Assert.assertNotNull(parent.finish(null, "AI_TIMEOUT", null)); } else { f.game.setRecordAiDecisions(false); }
            release.countDown(); Assert.assertSame(result.get(5, TimeUnit.SECONDS), returned);
            Assert.assertEquals(callbacks.get(), 1); Assert.assertTrue(f.events.isEmpty()); Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    private Card pick(final Fixture f, final ZoneChangeSelectionRequest request,
            final ZoneChangeSelectionObserver observer, final int position) {
        final CardCollection offered = new CardCollection(f.card);
        observer.beforeSelector(offered, true, position, position);
        final Card selected = AiEffectChoiceRecorder.zoneChange(f.player, f.ability, request.destination(), request.origins(), offered,
                true, f.player, () -> f.card);
        observer.returned(selected, 1); return selected;
    }

    @Test(groups = {"UnitTest", "fast"})
    public void unsupportedNestedMetadataMasksTheOlderOwnerAndRestoresItAfterward() {
        final Fixture f = fixture(); final var request = new ZoneChangeSelectionRequest(f.ability, ZoneType.Hand, List.of(ZoneType.Library), f.player, f.player, 2);
        final var nested = new ZoneChangeSelectionRequest(f.ability, ZoneType.Hand,
                Collections.nCopies(ZoneType.values().length + 1, ZoneType.Library), f.player, f.player, 1);
        f.player.getController().withZoneChangeSelections(request, observer -> {
            pick(f, request, observer, 0);
            f.player.getController().withZoneChangeSelections(nested, ignored -> {
                pick(f, request, ignored, 0); return new CardCollection(f.card);
            });
            pick(f, request, observer, 1); return new CardCollection(f.card);
        });
        final var starts = f.events.stream().filter(c -> "STARTED".equals(c.get("status"))).toList();
        Assert.assertEquals(starts.size(), 4); final var root = starts.get(0); final var nestedChild = starts.get(2);
        Assert.assertEquals(starts.get(1).get("parentChoiceId"), root.get("choiceId"));
        Assert.assertNull(nestedChild.get("parentChoiceId")); Assert.assertFalse(((Map<?, ?>) nestedChild.get("options")).containsKey("zoneSelectionProgress"));
        Assert.assertEquals(starts.get(3).get("parentChoiceId"), root.get("choiceId"));
        final var progress = (Map<?, ?>) ((Map<?, ?>) starts.get(3).get("options")).get("zoneSelectionProgress");
        Assert.assertEquals(progress.get("attemptIndex"), 1L); Assert.assertEquals(progress.get("priorNonNullReturnCount"), 1L);
        Assert.assertEquals(cell(f, true).get("entered"), 2L); Assert.assertEquals(cell(f, true).get("prepared"), 1L);
        Assert.assertEquals(cell(f, true).get("omittedUnsupported"), 1L); Assert.assertNull(f.game.getEventObservationFailure());
    }

}
