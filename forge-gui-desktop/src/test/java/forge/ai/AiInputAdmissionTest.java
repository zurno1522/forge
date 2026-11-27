package forge.ai;

import forge.game.Game;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class AiInputAdmissionTest extends AITest {
    private Map<String, Object> capture(Player p) {
        return AiInputContext.capture(p, null, "BEFORE_SELECTOR", "RULES_STATE", () -> true);
    }
    private Map<?, ?> receipt(Map<?, ?> context) { return (Map<?, ?>) context.get("admission"); }
    private AtomicLong scalar(Object owner, String name) throws Exception {
        final var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return (AtomicLong) f.get(owner);
    }
    private Object state(PlayerControllerAi controller) throws Exception {
        final var f = PlayerControllerAi.class.getDeclaredField("inputContextState"); f.setAccessible(true); return f.get(controller);
    }

    @Test(groups={"UnitTest", "fast"})
    public void actualCapturesUseSeparatePlayerAndGameCountersWithoutChangingFeaturesOrRng() {
        final Game game = initAndCreateGame(); game.setRecordAiDecisions(true);
        final Player first = game.getPlayers().get(0), second = game.getPlayers().get(1);
        final long rng = MyRandom.callCount();
        final var before = capture(first); final var after = capture(first); final var other = capture(second);
        Assert.assertEquals(before.get("schemaVersion"), 29);
        Assert.assertEquals(receipt(before).get("ordinal"), 1L);
        Assert.assertEquals(receipt(after).get("ordinal"), 2L);
        Assert.assertEquals(receipt(other).get("ordinal"), 1L);
        Assert.assertEquals(before.get("policy"), after.get("policy"));
        Assert.assertFalse(((Map<?, ?>) before.get("policy")).containsKey("admission"));
        Assert.assertEquals(MyRandom.callCount(), rng);
        Assert.expectThrows(UnsupportedOperationException.class, () -> receipt(before).clear());
        final Game fresh = initAndCreateGame(); fresh.setRecordAiDecisions(true);
        Assert.assertEquals(receipt(capture(fresh.getPlayers().get(0))).get("ordinal"), 1L);
    }

    @Test(groups={"UnitTest", "fast"})
    public void disclosureIntakeSpendsWorkButNotCaptureOrdinalsAndPreventsLateSetup() {
        final Game game = initAndCreateGame(); final Player p = game.getPlayers().get(1);
        final var ai = (PlayerControllerAi) p.getController(); ai.configureInputCapture();
        game.setRecordAiDecisions(true);
        final long share = AiInputContext.MAX_GAME_WORK / 2;
        final var cards = new CardCollection(addCardToZone("Forest", p, ZoneType.Hand));
        AiEffectChoiceRecorder.prompt(p, () -> { ai.reveal(cards, ZoneType.Hand, p); return null; });
        Assert.assertTrue(ai.inputContextState().remainingWork() < share);
        Assert.expectThrows(IllegalStateException.class, ai::configureInputCapture);
        Assert.assertEquals(receipt(capture(p)).get("ordinal"), 1L);
    }

    @DataProvider public Object[][] bypasses() { return new Object[][]{{"OFF"}, {"COPY"}, {"CLOSED"}, {"INTERRUPTED"}}; }
    @Test(dataProvider="bypasses", groups={"UnitTest", "fast"})
    public void guardsAllocateNoStateAndNeverReadInputSuppliers(String guard) throws Exception {
        final Game game = initAndCreateGame(); final Player p = game.getPlayers().get(1);
        final var ai = (PlayerControllerAi) p.getController();
        if (!guard.equals("OFF")) { game.setRecordAiDecisions(true); }
        if (guard.equals("COPY")) { game.markAsSimulationCopy(); }
        if (guard.equals("CLOSED")) { game.closeEventObservations(); }
        if (guard.equals("INTERRUPTED")) { Thread.currentThread().interrupt(); }
        try {
            final var c = AiInputContext.capture(p, null, "BEFORE_SELECTOR", "RULES_STATE", () -> true,
                    () -> { throw new AssertionError("prompt"); }, () -> { throw new AssertionError("options"); });
            Assert.assertEquals(receipt(c).get("status"), "BYPASSED"); Assert.assertNull(receipt(c).get("ordinal"));
            Assert.assertNull(state(ai));
        } finally { Thread.interrupted(); }
    }

    @Test(groups={"UnitTest", "fast"})
    public void replacementControllerCannotAllocateAnotherShareOrReceiveDisclosureState() throws Exception {
        final Game game = initAndCreateGame(); final Player p = game.getPlayers().get(1);
        final var original = (PlayerControllerAi) p.getController(); original.configureInputCapture();
        game.setRecordAiDecisions(true); Assert.assertEquals(receipt(capture(p)).get("ordinal"), 1L);
        final long remaining = original.inputContextState().remainingWork();
        final var replacement = new PlayerControllerAi(game, p, p.getLobbyPlayer()); p.dangerouslySetController(replacement);
        try {
            Assert.assertEquals(receipt(capture(p)).get("status"), "BYPASSED");
            Assert.expectThrows(IllegalStateException.class, replacement::configureInputCapture);
            replacement.reveal(new CardCollection(), ZoneType.Hand, p);
            Assert.assertNull(state(replacement));
            Assert.expectThrows(IllegalStateException.class, replacement::inputContextState);
        } finally { p.dangerouslySetController(original); }
        Assert.assertEquals(original.inputContextState().remainingWork(), remaining);
        Assert.assertEquals(receipt(capture(p)).get("ordinal"), 2L);
        Assert.assertNull(game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest", "fast"})
    public void foreignGameChooserCannotBorrowAnOtherwiseValidOriginalControllerBudget() {
        final Game first = initAndCreateGame(); first.setRecordAiDecisions(true);
        final Player owner = first.getPlayers().get(1); final var controller = (PlayerControllerAi) owner.getController();
        Assert.assertEquals(receipt(capture(owner)).get("ordinal"), 1L);
        final long remaining = controller.inputContextState().remainingWork();
        final Game second = initAndCreateGame(); second.setRecordAiDecisions(true);
        final Player foreign = second.getPlayers().get(1); final var ownController = foreign.getController();
        foreign.dangerouslySetController(controller);
        try {
            Assert.assertEquals(receipt(capture(foreign)).get("status"), "BYPASSED");
            Assert.assertTrue(AiEffectChoiceRecorder.prompt(foreign, () -> true));
            Assert.assertEquals(controller.inputContextState().remainingWork(), remaining);
            Assert.assertNull(second.getEventObservationFailure());
        } finally { foreign.dangerouslySetController(ownController); }
        Assert.assertEquals(receipt(capture(owner)).get("ordinal"), 2L);
        Assert.assertEquals(receipt(capture(foreign)).get("ordinal"), 1L);
    }

    @Test(groups={"UnitTest", "fast"})
    public void exhaustedAndMidCaptureUnavailableReturnsRetainTheirActualTicket() throws Exception {
        for (long remaining : List.of(0L, 1L, 3L)) {
            final Game game = initAndCreateGame(); game.setRecordAiDecisions(true); final Player p = game.getPlayers().get(1);
            final var state = ((PlayerControllerAi) p.getController()).inputContextState(); scalar(state, "remaining").set(remaining);
            final var c = capture(p); Assert.assertEquals(c.get("status"), "UNAVAILABLE");
            Assert.assertEquals(receipt(c).get("status"), "ADMITTED"); Assert.assertEquals(receipt(c).get("ordinal"), 1L);
            Assert.assertEquals(receipt(capture(p)).get("ordinal"), 2L);
        }
    }

    @Test(groups={"UnitTest", "fast"})
    public void actualGateIssuesLastLongThenRefusesTraversalWithoutWrappingOrCharging() throws Exception {
        final Game game = initAndCreateGame(); game.setRecordAiDecisions(true); final Player p = game.getPlayers().get(1);
        final var state = ((PlayerControllerAi) p.getController()).inputContextState();
        final var field = AiInputContext.State.class.getDeclaredField("admission"); field.setAccessible(true);
        scalar(field.get(state), "ordinal").set(Long.MAX_VALUE - 1);
        Assert.assertEquals(receipt(capture(p)).get("ordinal"), Long.MAX_VALUE);
        final long before = state.remainingWork();
        for (int i = 0; i < 2; i++) {
            final var c = AiInputContext.capture(p, null, "BEFORE_SELECTOR", "RULES_STATE", () -> true,
                    () -> { throw new AssertionError("overflow prompt"); });
            Assert.assertEquals(receipt(c).get("status"), "COUNTER_OVERFLOW"); Assert.assertNull(receipt(c).get("ordinal"));
            Assert.assertEquals(c.get("omissions"), List.of(Map.of("component", "CONTEXT", "reason", "COUNTER_OVERFLOW")));
        }
        Assert.assertEquals(state.remainingWork(), before);
    }

    @Test(groups={"UnitTest", "fast"})
    public void captureFailureKeepsExceptionIdentityAndUnknownFactoryDoesNotReuseItsTicket() {
        final Game game = initAndCreateGame(); game.setRecordAiDecisions(true); final Player p = game.getPlayers().get(1);
        final var error = new IllegalStateException("capture");
        Assert.assertSame(Assert.expectThrows(IllegalStateException.class, () -> AiInputContext.capture(p, null,
                "BEFORE_SELECTOR", "RULES_STATE", () -> true, () -> { throw error; })), error);
        final var failed = AiInputContext.unavailable(p, "BEFORE_SELECTOR", "RULES_STATE", "CAPTURE_FAILED");
        Assert.assertEquals(receipt(failed).get("status"), "UNESTABLISHED"); Assert.assertNull(receipt(failed).get("ordinal"));
        Assert.assertEquals(receipt(capture(p)).get("ordinal"), 2L);
    }

    @Test(groups={"UnitTest", "fast"})
    public void concurrentCounterTicketsAreUniqueWithoutRetainingAnyHistory() throws Exception {
        final var counter = new AiInputAdmission(); final var pool = Executors.newFixedThreadPool(4);
        try {
            final var tasks = new java.util.ArrayList<java.util.concurrent.Future<List<Object>>>();
            for (int t = 0; t < 4; t++) { tasks.add(pool.submit(() -> {
                final var ids = new java.util.ArrayList<Object>();
                for (int i = 0; i < 64; i++) { ids.add(counter.next().get("ordinal")); } return ids;
            })); }
            final var all = new HashSet<Object>();
            for (final var task : tasks) { for (Object id : task.get(5, TimeUnit.SECONDS)) { Assert.assertTrue(all.add(id)); } }
            Assert.assertEquals(all.size(), 256); Assert.assertEquals(counter.next().get("ordinal"), 257L);
        } finally { pool.shutdownNow(); }
    }

    @Test(groups={"UnitTest", "fast"})
    public void blockedSupplierHoldsNoCounterLockAndLateReturnCannotReopenClosedCapture() throws Exception {
        final Game game = initAndCreateGame(); game.setRecordAiDecisions(true); final Player p = game.getPlayers().get(1);
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicReference<Map<String, Object>> result = new AtomicReference<>(); final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread worker = new Thread(() -> {
            try { result.set(AiInputContext.capture(p, null, "BEFORE_SELECTOR", "RULES_STATE", () -> true, () -> {
                entered.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("release"); } }
                catch (InterruptedException e) { throw new IllegalStateException(e); } return AiPromptInput.range(0, 1);
            })); } catch (Throwable e) { failure.set(e); }
        }); worker.start();
        try {
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
            Assert.assertEquals(((PlayerControllerAi) p.getController()).inputContextState().nextAdmission().get("ordinal"), 2L);
            game.closeEventObservations();
        } finally { release.countDown(); worker.join(5000); }
        Assert.assertFalse(worker.isAlive()); Assert.assertNull(failure.get());
        Assert.assertEquals(result.get().get("status"), "UNAVAILABLE"); Assert.assertEquals(receipt(result.get()).get("ordinal"), 1L);
        Assert.assertEquals(receipt(capture(p)).get("status"), "BYPASSED");
    }
}
