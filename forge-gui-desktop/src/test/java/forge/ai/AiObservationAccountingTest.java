package forge.ai;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AiObservationAccountingTest extends AITest {
    private record Fixture(Game game, Player chooser, SpellAbility ability, AiObservationAccounting ledger) { }
    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player chooser = game.getPlayers().get(1);
        final SpellAbility ability = addCardToZone("Fireball", chooser, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(chooser); game.setRecordAiDecisions(true);
        return new Fixture(game, chooser, ability, ((PlayerControllerAi) chooser.getController()).configureObservationAccounting());
    }
    private long total(final Map<String, Object> snapshot, final String field) {
        long total = 0;
        for (final Object row : ((Map<?, ?>) snapshot.get("boundaries")).values()) { total += ((Number) ((Map<?, ?>) row).get(field)).longValue(); }
        return total;
    }

    @Test(groups = {"UnitTest", "fast"})
    public void callbackResultsAreUnchangedAndInputBudgetExhaustionIsNotALostRecord() throws Exception {
        final Fixture f = fixture(); final var state = ((PlayerControllerAi) f.chooser.getController()).inputContextState();
        final var field = AiInputContext.State.class.getDeclaredField("remaining"); field.setAccessible(true);
        ((AtomicLong) field.get(state)).set(0);
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertEquals(AiNumberChoiceRecorder.range(f.chooser, f.ability, "CONTROLLER_NUMBER_RANGE", 0, 2,
                () -> { calls.incrementAndGet(); return 3; }), Integer.valueOf(3));
        f.ledger.close(); final var snapshot = f.ledger.snapshot();
        Assert.assertEquals(calls.get(), 1); Assert.assertEquals(total(snapshot, "entered"), 1L);
        Assert.assertEquals(total(snapshot, "prepared"), 1L); Assert.assertEquals(total(snapshot, "inputUnavailable"), 1L);
        Assert.assertEquals(total(snapshot, "omitted"), 0L); Assert.assertEquals(total(snapshot, "callbackReturned"), 1L);
        Assert.assertEquals(total(snapshot, "startReturned"), 1L); Assert.assertEquals(total(snapshot, "terminalReturned"), 1L);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void unsupportedContextStillCallsTheOriginalCallbackWithoutPublishingOrDeclassifyingItsBoundary() {
        final Fixture f = fixture(); final AtomicInteger calls = new AtomicInteger();
        Assert.assertEquals(AiChoiceRecorder.observe(f.chooser, null, "NUMBER_SELECTION", null, "CONTROLLER_NUMBER_RANGE",
                () -> { throw new AssertionError("options"); }, () -> { calls.incrementAndGet(); return 7; },
                value -> { throw new AssertionError("selection"); }), Integer.valueOf(7));
        f.ledger.close(); final var snapshot = f.ledger.snapshot();
        Assert.assertEquals(calls.get(), 1); Assert.assertEquals(total(snapshot, "omittedUnsupported"), 1L);
        Assert.assertEquals(total(snapshot, "prepared"), 0L);
        Assert.assertEquals(((Map<?, ?>) snapshot.get("boundaries")).keySet(), java.util.Set.of("UNCLASSIFIED"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void callbackExceptionIsTheSameObjectAndIsNotACaptureFailure() {
        final Fixture f = fixture(); final var failure = new IllegalStateException("original callback");
        Assert.assertSame(Assert.expectThrows(IllegalStateException.class, () -> AiNumberChoiceRecorder.range(f.chooser,
                f.ability, "CONTROLLER_NUMBER_RANGE", 0, 2, () -> { throw failure; })), failure);
        f.ledger.close(); final var snapshot = f.ledger.snapshot();
        Assert.assertEquals(total(snapshot, "callbackThrew"), 1L); Assert.assertEquals(total(snapshot, "callbackReturned"), 0L);
        Assert.assertEquals(total(snapshot, "omitted"), 0L); Assert.assertEquals(total(snapshot, "observationFailures"), 0L);
        Assert.assertEquals(total(snapshot, "terminalReturned"), 1L); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void captureFailureIsVisibleAndDoesNotReplaceTheCallbackOrLeakItsMessage() {
        final Fixture f = fixture(); final var failure = new IllegalStateException("private capture message");
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertEquals(AiChoiceRecorder.observe(f.chooser, f.ability, "NUMBER_SELECTION", null, "CONTROLLER_NUMBER_RANGE",
                () -> { throw failure; }, () -> { calls.incrementAndGet(); return 1; }, value -> value), Integer.valueOf(1));
        f.ledger.close(); final var snapshot = f.ledger.snapshot();
        Assert.assertEquals(calls.get(), 1); Assert.assertSame(f.game.getEventObservationFailure(), failure);
        Assert.assertEquals(total(snapshot, "omittedFailure"), 1L); Assert.assertEquals(total(snapshot, "observationFailures"), 1L);
        Assert.assertFalse(snapshot.toString().contains("private capture message"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void normalPublicationReturnDoesNotClaimThatSubscribersSucceeded() {
        final Fixture f = fixture(); final var failure = new IllegalStateException("subscriber");
        f.game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent) { throw failure; } });
        Assert.assertEquals(AiNumberChoiceRecorder.range(f.chooser, f.ability, "CONTROLLER_NUMBER_RANGE", 0, 2, () -> 1), Integer.valueOf(1));
        f.ledger.close(); final var snapshot = f.ledger.snapshot();
        Assert.assertEquals(total(snapshot, "startReturned"), 1L); Assert.assertSame(f.game.getEventObservationFailure(), failure);
        Assert.assertEquals(snapshot.get("collectorStarts"), 0L);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aBlockedCallbackCannotDelayClosureAndLateExitCannotRewriteTheCutoff() throws Exception {
        final Fixture f = fixture(); final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final var executor = Executors.newSingleThreadExecutor();
        try {
            final var callback = executor.submit(() -> AiNumberChoiceRecorder.range(f.chooser, f.ability,
                    "CONTROLLER_NUMBER_RANGE", 0, 2, () -> {
                        entered.countDown();
                        try { Assert.assertTrue(release.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException error) { throw new IllegalStateException(error); }
                        return 2;
                    }));
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
            f.game.closeEventObservations(); f.ledger.close(); final var snapshot = f.ledger.snapshot();
            Assert.assertEquals(total(snapshot, "entered"), 1L); Assert.assertEquals(total(snapshot, "finished"), 0L);
            release.countDown(); Assert.assertEquals(callback.get(5, TimeUnit.SECONDS), Integer.valueOf(2));
            Assert.assertEquals(f.ledger.snapshot(), snapshot);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @DataProvider public Object[][] disabled() { return new Object[][] {{false}, {true}}; }
    @Test(dataProvider = "disabled", groups = {"UnitTest", "fast"})
    public void disabledAndCopiedPolicyDoesNotEnterTheLedger(final boolean copy) {
        final Fixture f = fixture();
        if (copy) { f.game.markAsSimulationCopy(); } else { f.game.setRecordAiDecisions(false); }
        Assert.assertEquals(AiChoiceRecorder.observe(f.chooser, f.ability, "NUMBER_SELECTION", null, "CONTROLLER_NUMBER_RANGE",
                () -> { throw new AssertionError("options"); }, () -> 7, value -> value), Integer.valueOf(7));
        Assert.assertEquals(total(f.ledger.snapshot(), "entered"), 0L);
    }

    @Test(groups = {"UnitTest", "fast"})
    @SuppressWarnings("unchecked")
    public void saturationAndUnknownBoundariesStayExplicitInsteadOfWrappingOrGrowingTheRegistry() throws Exception {
        final var ledger = new AiObservationAccounting(); final var field = AiObservationAccounting.class.getDeclaredField("cells"); field.setAccessible(true);
        final Map<String, long[]> cells = (Map<String, long[]>) field.get(ledger);
        cells.get("UNCLASSIFIED")[0] = Long.MAX_VALUE;
        final var scope = ledger.enter(); scope.classify("unbounded private boundary"); scope.exit(); ledger.close();
        final var snapshot = ledger.snapshot();
        Assert.assertEquals(snapshot.get("overflowed"), true); Assert.assertEquals(snapshot.get("scopeComplete"), false);
        Assert.assertFalse(snapshot.toString().contains("unbounded private boundary"));
        Assert.assertEquals(total(snapshot, "entered"), Long.MAX_VALUE);
    }
}
