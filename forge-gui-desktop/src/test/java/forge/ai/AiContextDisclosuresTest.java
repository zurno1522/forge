package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCopyService;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AiContextDisclosuresTest extends AITest {
    private record Fixture(Game game, Player recipient, AiContextDisclosures disclosures) { }

    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player recipient = game.getPlayers().get(1);
        game.setRecordAiDecisions(true);
        return new Fixture(game, recipient, new AiContextDisclosures(recipient, () -> true));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void directPromptFreezesDeliveredFaceAndDoesNotBorrowPreviousPromptKnowledge() {
        final Fixture f = fixture();
        final Card delivered = addCardToZone("Lightning Bolt", f.game.getPlayers().get(0), ZoneType.Hand);
        final AiContextDisclosures.Snapshot beforeMutation;
        try (var prompt = f.disclosures.prompt()) {
            f.disclosures.cards(new CardCollection(delivered), ZoneType.Hand);
            beforeMutation = f.disclosures.snapshot();
            Assert.assertEquals(beforeMutation.coverage(), "CURRENT_PROMPT");
            Assert.assertEquals(beforeMutation.facts().get(0).get("name"), "Lightning Bolt");
            Assert.assertEquals(beforeMutation.facts().get(0).get("currentObjectAssociation"), "UNRESOLVED");
            delivered.turnFaceDown(true);
            Assert.assertEquals(beforeMutation.facts().get(0).get("presentedFace"), "Original");
            Assert.assertEquals(beforeMutation.facts().get(0).get("name"), "Lightning Bolt");
            Assert.expectThrows(UnsupportedOperationException.class,
                    () -> beforeMutation.facts().get(0).put("name", "later"));
        }
        Assert.assertTrue(f.disclosures.snapshot().facts().isEmpty());
        Assert.assertEquals(f.disclosures.snapshot().coverage(), "UNSUPPORTED_PRIOR_SCOPE");
        try (var next = f.disclosures.prompt()) {
            Assert.assertTrue(f.disclosures.snapshot().facts().isEmpty());
            Assert.assertEquals(f.disclosures.snapshot().coverage(), "CURRENT_PROMPT");
        }
        Assert.assertEquals(beforeMutation.facts().get(0).get("name"), "Lightning Bolt");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void nestedPromptKeepsTheOuterDisclosedSupersetUntilTheOuterCallEnds() {
        final Fixture f = fixture();
        final CardCollection delivered = new CardCollection();
        for (final String name : List.of("Lightning Bolt", "Grizzly Bears", "Island", "Forest", "Sol Ring")) {
            delivered.add(addCardToZone(name, f.game.getPlayers().get(0), ZoneType.Library));
        }
        try (var outer = f.disclosures.prompt()) {
            f.disclosures.cards(delivered, ZoneType.Library);
            try (var inner = f.disclosures.prompt()) {
                Assert.assertEquals(f.disclosures.snapshot().facts().size(), 5,
                        "Nested selectors must retain the delivered superset, independent of their own candidate count");
            }
            Assert.assertEquals(f.disclosures.snapshot().facts().size(), 5);
        }
        Assert.assertTrue(f.disclosures.snapshot().facts().isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualStackIntervalRetainsFactsAcrossSubchoicesAndClearsBeforeReusingAnAbility() {
        final Fixture f = fixture();
        final Card host = addCardToZone("Grizzly Bears", f.recipient, ZoneType.Battlefield);
        final Card disclosed = addCardToZone("Lightning Bolt", f.game.getPlayers().get(0), ZoneType.Library);
        final AtomicInteger resolutions = new AtomicInteger();
        final List<AiContextDisclosures.Snapshot> snapshots = new ArrayList<>();
        final SpellAbility root = new SpellAbility.EmptySa(host) {
            @Override public void resolve() {
                if (resolutions.getAndIncrement() == 0) {
                    f.disclosures.cards(new CardCollection(disclosed), ZoneType.Library);
                    try (var first = f.disclosures.prompt()) { snapshots.add(f.disclosures.snapshot()); }
                    // A hidden shuffle does not erase the historical fact, or create current-object continuity.
                    disclosed.getOwner().shuffle(this);
                    try (var next = f.disclosures.prompt()) { snapshots.add(f.disclosures.snapshot()); }
                } else {
                    snapshots.add(f.disclosures.snapshot());
                }
            }
        };
        f.game.getStack().add(root);
        f.game.getStack().resolveStack();
        Assert.assertEquals(snapshots.size(), 2);
        for (final var snapshot : snapshots) {
            Assert.assertEquals(snapshot.coverage(), "STACK_RESOLUTION");
            Assert.assertEquals(snapshot.facts().get(0).get("name"), "Lightning Bolt");
            Assert.assertEquals(snapshot.facts().get(0).get("currentObjectAssociation"), "UNRESOLVED");
        }
        Assert.assertTrue(f.disclosures.snapshot().facts().isEmpty());
        f.game.getStack().add(root);
        f.game.getStack().resolveStack();
        Assert.assertTrue(snapshots.get(2).facts().isEmpty(), "The same ability's next stack instance is a new interval");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void abortedStackScopeCannotReopenUntilANewInstanceStarts() {
        final Fixture f = fixture();
        final Card host = addCardToZone("Grizzly Bears", f.recipient, ZoneType.Battlefield);
        final Card disclosed = addCardToZone("Lightning Bolt", f.recipient, ZoneType.Library);
        final AtomicInteger resolutions = new AtomicInteger();
        final List<AiContextDisclosures.Snapshot> snapshots = new ArrayList<>();
        final SpellAbility root = new SpellAbility.EmptySa(host) {
            @Override public void resolve() {
                f.disclosures.cards(new CardCollection(disclosed), ZoneType.Library);
                if (resolutions.getAndIncrement() == 0) {
                    f.disclosures.abort();
                    f.disclosures.cards(new CardCollection(disclosed), ZoneType.Library);
                }
                snapshots.add(f.disclosures.snapshot());
            }
        };
        f.game.getStack().add(root);
        f.game.getStack().resolveStack();
        Assert.assertEquals(snapshots.get(0).coverage(), "UNSUPPORTED_PRIOR_SCOPE");
        Assert.assertTrue(snapshots.get(0).facts().isEmpty());
        f.game.getStack().add(root);
        f.game.getStack().resolveStack();
        Assert.assertEquals(snapshots.get(1).coverage(), "STACK_RESOLUTION");
        Assert.assertEquals(snapshots.get(1).facts().size(), 1);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void lkiDeliveryUsesThePresentedViewWithoutLookingUpTheCurrentHiddenObject() {
        final Fixture f = fixture();
        final Card actual = addCardToZone("Lightning Bolt", f.game.getPlayers().get(0), ZoneType.Hand);
        final Card lki = CardCopyService.getLKICopy(actual);
        actual.turnFaceDown(true);
        Assert.assertTrue(actual.isFaceDown());
        try (var prompt = f.disclosures.prompt()) {
            f.disclosures.views(List.of(lki.getView()), ZoneType.Hand);
            final Map<String, Object> fact = f.disclosures.snapshot().facts().get(0);
            Assert.assertEquals(fact.get("source"), "CONTROLLER_VIEW_REVEAL");
            Assert.assertEquals(fact.get("name"), "Lightning Bolt");
            Assert.assertEquals(fact.get("presentedFace"), "Original");
            Assert.assertEquals(fact.get("currentObjectAssociation"), "UNRESOLVED");
            Assert.assertFalse(fact.containsKey("id"));
            Assert.assertFalse(fact.containsKey("gameTimestamp"));
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void ambiguousFaceDownDeliveryDoesNotInferTheAlternateIdentity() {
        final Fixture f = fixture();
        final Card hidden = addCardToZone("Shivan Dragon", f.game.getPlayers().get(0), ZoneType.Battlefield);
        hidden.turnFaceDown(true);
        try (var prompt = f.disclosures.prompt()) {
            f.disclosures.views(List.of(hidden.getView()), ZoneType.Battlefield);
            final var snapshot = f.disclosures.snapshot();
            Assert.assertEquals(snapshot.facts().get(0).get("presentedFace"), "FaceDown");
            Assert.assertFalse(snapshot.facts().toString().contains("Shivan Dragon"));
            Assert.assertTrue(hasOmission(snapshot, "UNSUPPORTED"));
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void deliveryOutsideAnyAuditedIntervalDoesNotSeedTheNextPrompt() {
        final Fixture f = fixture();
        final Card disclosed = addCardToZone("Lightning Bolt", f.recipient, ZoneType.Library);
        f.disclosures.cards(new CardCollection(disclosed), ZoneType.Library);
        try (var prompt = f.disclosures.prompt()) {
            Assert.assertTrue(f.disclosures.snapshot().facts().isEmpty());
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void failedDeliveryPoisonsNestedSelectorsUntilTheOuterPromptCloses() {
        final Fixture f = fixture();
        final Card delivered = addCardToZone("Lightning Bolt", f.recipient, ZoneType.Library);
        try (var outer = f.disclosures.prompt()) {
            f.disclosures.cards(new CardCollection(delivered), ZoneType.Library);
            f.disclosures.failed();
            try (var inner = f.disclosures.prompt()) {
                f.disclosures.cards(new CardCollection(delivered), ZoneType.Library);
                Assert.assertTrue(f.disclosures.snapshot().facts().isEmpty());
                Assert.assertEquals(f.disclosures.snapshot().coverage(), "UNSUPPORTED_PRIOR_SCOPE");
                Assert.assertTrue(hasOmission(f.disclosures.snapshot(), "CAPTURE_FAILED"));
            }
            Assert.assertTrue(hasOmission(f.disclosures.snapshot(), "CAPTURE_FAILED"));
        }
        try (var next = f.disclosures.prompt()) {
            f.disclosures.cards(new CardCollection(delivered), ZoneType.Library);
            Assert.assertEquals(f.disclosures.snapshot().facts().size(), 1);
            Assert.assertFalse(hasOmission(f.disclosures.snapshot(), "CAPTURE_FAILED"));
        }
    }

    @DataProvider public Object[][] disabled() { return new Object[][] {{false}, {true}}; }

    @Test(dataProvider = "disabled", groups = {"UnitTest", "fast"})
    public void disabledAndCopiedGamesNeverTraverseOrSpendDisclosureWork(final boolean copy) {
        final Fixture f = fixture();
        if (copy) { f.game.markAsSimulationCopy(); } else { f.game.setRecordAiDecisions(false); }
        final AiContextDisclosures disclosures = new AiContextDisclosures(f.recipient,
                () -> { throw new AssertionError("disabled disclosure budget used"); });
        final List<CardView> views = new AbstractList<>() {
            @Override public CardView get(final int index) { throw new AssertionError("disabled disclosure read"); }
            @Override public int size() { throw new AssertionError("disabled disclosure traversal"); }
        };
        try (var prompt = disclosures.prompt()) { disclosures.views(views, ZoneType.Hand); }
        Assert.assertTrue(disclosures.snapshot().facts().isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void prefixAndSharedWorkBudgetStopTraversalAndExposeOmissions() {
        final Fixture f = fixture();
        final CardView view = addCardToZone("Lightning Bolt", f.recipient, ZoneType.Library).getView();
        final AtomicInteger read = new AtomicInteger();
        final AtomicInteger work = new AtomicInteger();
        final List<CardView> views = new AbstractList<>() {
            @Override public CardView get(final int index) { read.incrementAndGet(); return view; }
            @Override public int size() { return 1000; }
        };
        final AiContextDisclosures bounded = new AiContextDisclosures(f.recipient, () -> { work.incrementAndGet(); return true; });
        try (var prompt = bounded.prompt()) {
            bounded.views(views, ZoneType.Library);
            Assert.assertEquals(bounded.snapshot().facts().size(), AiContextDisclosures.MAX_FACTS);
            Assert.assertEquals(read.get(), AiContextDisclosures.MAX_FACTS);
            Assert.assertEquals(work.get(), AiContextDisclosures.MAX_FACTS);
            Assert.assertTrue(hasOmission(bounded.snapshot(), "OUTSIDE_CAPTURED_PREFIX"));
        }
        read.set(0);
        final AiContextDisclosures exhausted = new AiContextDisclosures(f.recipient, () -> false);
        try (var prompt = exhausted.prompt()) {
            exhausted.views(views, ZoneType.Library);
            Assert.assertTrue(exhausted.snapshot().facts().isEmpty());
            Assert.assertEquals(read.get(), 0);
            Assert.assertTrue(hasOmission(exhausted.snapshot(), "BUDGET_EXHAUSTED"));
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void interruptionDoesNotReadTheNextPresentedFace() {
        final Fixture f = fixture();
        final CardView view = addCardToZone("Lightning Bolt", f.recipient, ZoneType.Library).getView();
        final AtomicInteger work = new AtomicInteger();
        final AiContextDisclosures disclosures = new AiContextDisclosures(f.recipient, () -> { work.incrementAndGet(); return true; });
        try (var prompt = disclosures.prompt()) {
            Thread.currentThread().interrupt();
            try { disclosures.views(List.of(view), ZoneType.Library); }
            finally { Thread.interrupted(); }
            Assert.assertEquals(work.get(), 0);
            Assert.assertTrue(disclosures.snapshot().facts().isEmpty());
            Assert.assertTrue(hasOmission(disclosures.snapshot(), "INTERRUPTED"));
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void authoritativeCloseDoesNotWaitForCaptureWorkOrAcceptItsLateResult() throws Exception {
        final Fixture f = fixture();
        final CardView view = addCardToZone("Lightning Bolt", f.recipient, ZoneType.Library).getView();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AiContextDisclosures disclosures = new AiContextDisclosures(f.recipient, () -> {
            entered.countDown();
            try { return release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        });
        final var executor = Executors.newSingleThreadExecutor();
        try (var prompt = disclosures.prompt()) {
            final var capture = executor.submit(() -> disclosures.views(List.of(view), ZoneType.Library));
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
            // A held capture may not own the short publication monitor used by authoritative closure.
            f.game.closeEventObservations();
            release.countDown();
            capture.get(5, TimeUnit.SECONDS);
            Assert.assertTrue(disclosures.snapshot().facts().isEmpty());
            Assert.assertEquals(disclosures.snapshot().coverage(), "UNSUPPORTED_PRIOR_SCOPE");
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    private static boolean hasOmission(final AiContextDisclosures.Snapshot snapshot, final String reason) {
        return snapshot.omissions().stream().anyMatch(item -> reason.equals(item.get("reason")));
    }
}
