package forge.ai;

import forge.game.Game;
import forge.game.card.CardCollection;
import forge.game.card.CardCopyService;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AiContextBindingTest extends AITest {
    private record Fixture(Game game, Player chooser, SpellAbility ability, List<Map<String, Object>> events) { }
    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player chooser = game.getPlayers().get(1);
        final SpellAbility ability = addCardToZone("Fireball", chooser, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(chooser); game.setRecordAiDecisions(true);
        final List<Map<String, Object>> events = new ArrayList<>();
        game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { events.add(choice.data()); } });
        return new Fixture(game, chooser, ability, events);
    }
    private Map<?, ?> context(final Fixture f) { return (Map<?, ?>) f.events.get(0).get("inputContext"); }
    private Map<?, ?> links(final Map<?, ?> context) { return (Map<?, ?>) ((Map<?, ?>) context.get("diagnostics")).get("optionBindings"); }
    private Map<?, ?> policy(final Map<?, ?> context) { return (Map<?, ?>) context.get("policy"); }
    private List<?> facts(final Map<?, ?> context) { return (List<?>) policy(context).get("disclosedFacts"); }
    private Map<String, Object> capture(final Fixture f) {
        return AiInputContext.capture(f.chooser, f.ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void exactPublicOptionsLinkWithoutGrantingPoolMembershipAsFeatures() {
        final Fixture f = fixture(); final var card = addCardToZone("Grizzly Bears", f.chooser, ZoneType.Battlefield);
        final AtomicInteger traversals = new AtomicInteger();
        final Iterable<forge.game.card.Card> once = () -> {
            Assert.assertEquals(traversals.incrementAndGet(), 1); return List.of(card, card).iterator();
        };
        Assert.assertSame(AiEffectChoiceRecorder.single(f.chooser, f.ability, once, false, null, () -> card), card);
        final List<?> items = (List<?>) links(context(f)).get("items");
        Assert.assertEquals(items.size(), 2);
        Assert.assertEquals(((Map<?, ?>) items.get(0)).get("entityRef"), ((Map<?, ?>) items.get(1)).get("entityRef"));
        Assert.assertNotNull(((Map<?, ?>) items.get(0)).get("entityRef"));
        Assert.assertNull(((Map<?, ?>) f.events.get(1).get("selection")).get("selectedOptionIndex"));
        Assert.assertFalse(policy(context(f)).containsKey("optionBindings"));
        f.game.getAction().moveToHand(card, null);
        Assert.assertNotNull(((Map<?, ?>) items.get(0)).get("entityRef"));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void privateAndStaleOptionsNeverLinkByTheirNamesOrIds() {
        final Fixture f = fixture();
        final var card = addCardToZone("Grizzly Bears", f.chooser, ZoneType.Battlefield);
        final var stale = CardCopyService.getLKICopy(card);
        final var hidden = addCardToZone("Grizzly Bears", f.chooser, ZoneType.Hand);
        final var moved = f.game.getAction().moveToHand(card, null);
        f.game.getAction().moveToPlay(moved, f.chooser, null, null);
        Assert.assertFalse(card.isLKI(), "A retired ordinary object need not be marked LKI");
        AiEffectChoiceRecorder.single(f.chooser, f.ability, List.of(stale, hidden, card), false, null, () -> hidden);
        for (final Object item : (List<?>) links(context(f)).get("items")) { Assert.assertNull(((Map<?, ?>) item).get("entityRef")); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void directPublicDeliveryAssociatesOnlyUntilAnInterveningEvent() {
        final Fixture f = fixture(); final var card = addCardToZone("Grizzly Bears", f.chooser, ZoneType.Battlefield);
        final var disclosure = ((PlayerControllerAi) f.chooser.getController()).inputContextState().disclosures();
        try (var scope = disclosure.prompt()) {
            disclosure.cards(new CardCollection(card), ZoneType.Battlefield);
            final var before = capture(f);
            Assert.assertNotEquals(((Map<?, ?>) facts(before).get(0)).get("currentObjectAssociation"), "UNRESOLVED");
            AiEffectChoiceRecorder.single(f.chooser, f.ability, List.of(card), false, null, () -> card);
            Assert.assertNotEquals(((Map<?, ?>) facts(capture(f)).get(0)).get("currentObjectAssociation"), "UNRESOLVED",
                    "Diagnostic events must not invalidate a proof of unchanged engine state");
            f.game.getAction().moveToHand(card, null);
            Assert.assertEquals(((Map<?, ?>) facts(capture(f)).get(0)).get("currentObjectAssociation"), "UNRESOLVED");
            Assert.assertNotEquals(((Map<?, ?>) facts(before).get(0)).get("currentObjectAssociation"), "UNRESOLVED");
        }
        Assert.assertTrue(facts(capture(f)).isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void viewOnlyAndPrivateDeliveriesRemainUnresolvedIncludingARevealedSuperset() {
        final Fixture f = fixture(); final var publicCard = addCardToZone("Grizzly Bears", f.chooser, ZoneType.Battlefield);
        final var hidden = addCardToZone("Grizzly Bears", f.chooser, ZoneType.Library);
        final var disclosure = ((PlayerControllerAi) f.chooser.getController()).inputContextState().disclosures();
        try (var scope = disclosure.prompt()) {
            disclosure.views(List.of(publicCard.getView()), ZoneType.Battlefield);
            disclosure.cards(new CardCollection(hidden), ZoneType.Library);
            AiEffectChoiceRecorder.single(f.chooser, f.ability, List.of(publicCard), false, null, () -> publicCard);
            Assert.assertEquals(facts(context(f)).size(), 2);
            for (final Object fact : facts(context(f))) { Assert.assertEquals(((Map<?, ?>) fact).get("currentObjectAssociation"), "UNRESOLVED"); }
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void identicalPublicFacesCannotTurnAnEngineObjectIntoAPolicyTieBreaker() {
        Map<?, ?> baseline = null;
        for (final boolean alternate : List.of(false, true)) {
            final Fixture f = fixture();
            final var first = addCardToZone("Grizzly Bears", f.chooser, ZoneType.Exile);
            final var second = addCardToZone("Grizzly Bears", f.chooser, ZoneType.Exile);
            final var disclosure = ((PlayerControllerAi) f.chooser.getController()).inputContextState().disclosures();
            try (var scope = disclosure.prompt()) {
                disclosure.cards(new CardCollection(alternate ? first : second), ZoneType.Exile);
                final var context = capture(f);
                Assert.assertEquals(((Map<?, ?>) facts(context).get(0)).get("currentObjectAssociation"), "UNRESOLVED");
                if (baseline == null) { baseline = policy(context); } else { Assert.assertEquals(policy(context), baseline); }
            }
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void hiddenShuffleAndLaterPublicMovementCannotRepairPrivateDelivery() {
        final Fixture f = fixture(); final var hidden = addCardToZone("Grizzly Bears", f.chooser, ZoneType.Library);
        final var disclosure = ((PlayerControllerAi) f.chooser.getController()).inputContextState().disclosures();
        try (var scope = disclosure.prompt()) {
            disclosure.cards(new CardCollection(hidden), ZoneType.Library);
            f.chooser.shuffle(null);
            f.game.getAction().moveToPlay(hidden, f.chooser, null, null);
            Assert.assertEquals(((Map<?, ?>) facts(capture(f)).get(0)).get("currentObjectAssociation"), "UNRESOLVED");
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void publicProofGetterCannotBlockGameClosureOrPublishALateAssociation() throws Exception {
        final Fixture f = fixture(); final var source = addCardToZone("Grizzly Bears", f.chooser, ZoneType.Battlefield);
        final AtomicBoolean block = new AtomicBoolean(); final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final var card = new forge.game.card.Card(90_001, source.getPaperCard(), f.game) {
            @Override public forge.game.card.CardView getView() {
                if (block.get()) {
                    entered.countDown();
                    try { Assert.assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                }
                return super.getView();
            }
        };
        card.setOwner(f.chooser); card.setZone(f.chooser.getZone(ZoneType.Battlefield));
        final var disclosure = ((PlayerControllerAi) f.chooser.getController()).inputContextState().disclosures();
        final var executor = Executors.newFixedThreadPool(2);
        try (var scope = disclosure.prompt()) {
            disclosure.cards(new CardCollection(card), ZoneType.Battlefield);
            final var snapshot = disclosure.snapshot(); block.set(true);
            final var capture = executor.submit(() -> disclosure.currentPublicSource(snapshot, 0));
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
            executor.submit(f.game::closeEventObservations).get(2, TimeUnit.SECONDS);
            release.countDown(); Assert.assertNull(capture.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
