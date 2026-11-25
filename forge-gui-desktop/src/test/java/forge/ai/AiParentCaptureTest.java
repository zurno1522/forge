package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.AITimeoutTracker;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AiParentCaptureTest extends AITest {
    private record Fixture(Game game, Player ai, SpellAbility spell, Card land, SpellAbility landAbility, SpellAbility otherLandAbility,
                           AiDecisionRecorder recorder) { }

    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player ai = game.getPlayers().get(1);
        final SpellAbility spell = addCardToZone("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(ai);
        final Card land = addCardToZone("Island", ai, ZoneType.Hand);
        final SpellAbility landAbility = new forge.game.spellability.LandAbility(land, land.getCurrentState());
        landAbility.setActivatingPlayer(ai);
        final Card otherLand = addCardToZone("Forest", ai, ZoneType.Hand);
        final SpellAbility otherLandAbility = new forge.game.spellability.LandAbility(otherLand, otherLand.getCurrentState());
        otherLandAbility.setActivatingPlayer(ai);
        game.setRecordAiDecisions(true);
        final var recorder = new AiDecisionRecorder(game, ai, 33); recorder.beginAssessment(spell);
        return new Fixture(game, ai, spell, land, landAbility, otherLandAbility, recorder);
    }

    @DataProvider public Object[][] boundaries() {
        return new Object[][] {{"ASSESSMENT"}, {"DETAIL"}, {"EVALUATED"}, {"LAND_ITERATOR"}, {"LAND_CARD"}, {"LAND_SEARCH"}};
    }
    @DataProvider public Object[][] blockedBoundaries() {
        final var result = new java.util.ArrayList<Object[]>();
        for (final Object[] boundary : boundaries()) {
            result.add(new Object[] {boundary[0], false}); result.add(new Object[] {boundary[0], true});
        }
        return result.toArray(Object[][]::new);
    }

    private Runnable capture(final Fixture f, final String boundary, final Runnable getter) {
        final var spell = org.mockito.Mockito.spy(f.spell);
        if ("ASSESSMENT".equals(boundary) || "DETAIL".equals(boundary)) {
            org.mockito.Mockito.doAnswer(ignored -> { getter.run(); return f.spell.getRootAbility(); }).when(spell).getRootAbility();
            return "ASSESSMENT".equals(boundary) ? () -> f.recorder.beginAssessment(spell)
                    : () -> f.recorder.detail(spell, Map.of("reason", "CARD_AI_DECLINED"));
        }
        if ("EVALUATED".equals(boundary)) {
            org.mockito.Mockito.doAnswer(ignored -> { getter.run(); return f.spell.getDescription(); }).when(spell).getDescription();
            return () -> f.recorder.evaluated(spell, "WillPlay");
        }
        if ("LAND_ITERATOR".equals(boundary)) {
            final Iterable<Card> lands = () -> { getter.run(); return List.of(f.land).iterator(); };
            return () -> f.recorder.lands(lands, f.landAbility);
        }
        if ("LAND_CARD".equals(boundary)) {
            final Card land = org.mockito.Mockito.spy(f.land);
            org.mockito.Mockito.doAnswer(ignored -> { getter.run(); return f.land.getName(); }).when(land).getName();
            return () -> f.recorder.lands(List.of(land), f.otherLandAbility);
        }
        f.recorder.lands(List.of(f.land), f.landAbility);
        final var offered = org.mockito.Mockito.spy(f.landAbility);
        org.mockito.Mockito.doAnswer(ignored -> { getter.run(); return f.land; }).when(offered).getHostCard();
        return () -> f.recorder.landSearchCandidates(List.of(offered));
    }

    @Test(dataProvider = "blockedBoundaries", groups = {"UnitTest", "fast"})
    public void parentCaptureCannotBlockTimeoutOrMutateItsFrozenResult(final String boundary, final boolean fail) throws Exception {
        final Fixture f = fixture();
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final Runnable operation = capture(f, boundary, () -> {
            entered.countDown();
            try { Assert.assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new IllegalStateException(failure); }
            if (fail) { throw new IllegalStateException("late parent capture failure"); }
        });
        final var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            final var work = threads.submit(operation);
            Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            final var event = threads.submit(() -> f.recorder.finish(null, "AI_TIMEOUT", null)).get(2, java.util.concurrent.TimeUnit.SECONDS);
            final String frozen = event.data().toString(); release.countDown(); work.get(5, java.util.concurrent.TimeUnit.SECONDS);
            Assert.assertEquals(event.data().toString(), frozen); Assert.assertNull(f.game.getEventObservationFailure());
            final var candidates = (List<?>) event.data().get("candidates");
            Assert.assertEquals(candidates.size(), "LAND_SEARCH".equals(boundary) ? 1 : 0);
            if ("LAND_SEARCH".equals(boundary)) { Assert.assertEquals(((Map<?, ?>) candidates.get(0)).get("searchOffered"), false); }
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test(dataProvider = "boundaries", groups = {"UnitTest", "fast"})
    public void activeMetadataFailureIsVisibleAndDoesNotEscapeIntoPolicy(final String boundary) {
        final Fixture f = fixture(); final var failure = new IllegalStateException("private observation diagnostic");
        capture(f, boundary, () -> { throw failure; }).run();
        Assert.assertSame(f.game.getEventObservationFailure(), failure);
        final var event = f.recorder.finish(null, "COMPLETED", null);
        Assert.assertFalse(event.data().toString().contains("private observation diagnostic"));
    }

    @Test(dataProvider = "boundaries", groups = {"UnitTest", "fast"})
    public void genuineAiDeadlineStillPropagatesWithoutBecomingATraceFailure(final String boundary) {
        final Fixture f = fixture();
        final Runnable operation = capture(f, boundary, AITimeoutTracker::check);
        final Object limit = AITimeoutTracker.arm(System.nanoTime() - 1, false);
        try { Assert.expectThrows(AITimeoutTracker.Expired.class, operation::run); }
        finally { AITimeoutTracker.disarm(limit); }
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] assessmentMetadata() { return new Object[][] {{"DETAIL"}, {"EVALUATED"}}; }

    @Test(dataProvider = "assessmentMetadata", groups = {"UnitTest", "fast"})
    public void staleMetadataCannotAttachToARepeatedAssessmentOfTheSameAbility(final String boundary) throws Exception {
        final Fixture f = fixture();
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final Runnable operation = capture(f, boundary, () -> {
            entered.countDown();
            try { Assert.assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new IllegalStateException(failure); }
        });
        final var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            final var work = threads.submit(operation);
            Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            threads.submit(() -> f.recorder.beginAssessment(f.spell)).get(2, java.util.concurrent.TimeUnit.SECONDS);
            release.countDown(); work.get(5, java.util.concurrent.TimeUnit.SECONDS);
            f.recorder.evaluated(f.spell, "CantPlayAi");
            final var rows = (List<?>) f.recorder.finish(null, "COMPLETED", null).data().get("candidates");
            Assert.assertEquals(rows.size(), 1);
            Assert.assertFalse(((Map<?, ?>) rows.get(0)).containsKey("assessmentDetail"));
            Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void landCaptureTraversesOnceAndPreservesOrderingAndLastMatchingOffer() {
        final Fixture f = fixture(); final Card other = f.otherLandAbility.getHostCard();
        final AtomicInteger traversals = new AtomicInteger();
        final Iterable<Card> pool = () -> { traversals.incrementAndGet(); return List.of(other, f.land, other).iterator(); };
        f.recorder.lands(pool, f.landAbility);
        final SpellAbility last = new forge.game.spellability.LandAbility(other, other.getCurrentState());
        last.setActivatingPlayer(f.ai);
        f.recorder.landSearchCandidates(List.of(f.otherLandAbility, f.landAbility, last));
        final var rows = (List<?>) f.recorder.finish(null, "COMPLETED", null).data().get("candidates");
        Assert.assertEquals(traversals.get(), 1); Assert.assertEquals(rows.size(), 3);
        Assert.assertEquals(rows.stream().map(row -> ((Map<?, ?>) row).get("sourceId")).toList(), List.of(other.getId(), f.land.getId(), other.getId()));
        Assert.assertEquals(rows.stream().map(row -> ((Map<?, ?>) row).get("abilityId")).toList(), List.of(last.getId(), f.landAbility.getId(), last.getId()));
        Assert.assertEquals(rows.stream().map(row -> ((Map<?, ?>) row).get("heuristicDefault")).toList(), List.of(false, true, false));
        Assert.assertTrue(rows.stream().allMatch(row -> Boolean.TRUE.equals(((Map<?, ?>) row).get("searchOffered"))));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void failureInALaterLandDoesNotAdmitAPartiallyBuiltBatch() {
        final Fixture f = fixture(); final Card broken = org.mockito.Mockito.spy(f.otherLandAbility.getHostCard());
        final var failure = new IllegalStateException("later land snapshot failure");
        org.mockito.Mockito.doThrow(failure).when(broken).getName();
        f.recorder.lands(List.of(f.land, broken), f.landAbility);
        Assert.assertSame(f.game.getEventObservationFailure(), failure);
        Assert.assertTrue(((List<?>) f.recorder.finish(null, "COMPLETED", null).data().get("candidates")).isEmpty());
    }
}
