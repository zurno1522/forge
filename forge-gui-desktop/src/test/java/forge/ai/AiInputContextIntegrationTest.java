package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AiInputContextIntegrationTest extends AITest {
    private List<Map<String, Object>> record(final Game game) {
        game.setRecordAiDecisions(true);
        final List<Map<String, Object>> events = new ArrayList<>();
        game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { events.add(choice.data()); } });
        return events;
    }

    private Map<?, ?> context(final Map<String, Object> start) { return (Map<?, ?>) start.get("inputContext"); }
    private Map<?, ?> policy(final Map<?, ?> context) { return (Map<?, ?>) context.get("policy"); }
    private List<?> facts(final Map<?, ?> context) { return (List<?>) policy(context).get("disclosedFacts"); }
    private Object chooserLife(final Map<?, ?> context) {
        final Map<?, ?> policy = policy(context);
        return ((List<?>) policy.get("players")).stream().map(value -> (Map<?, ?>) value)
                .filter(value -> policy.get("chooser").equals(value.get("entityRef")))
                .findFirst().orElseThrow().get("life");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void optionalContextFailureKeepsBaseChoiceAndOriginalCallback() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility ability = addCardToZone("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(ai);
        final List<Map<String, Object>> events = record(game);
        final var failure = new IllegalStateException("private observation detail");
        final AtomicInteger calls = new AtomicInteger();
        final Integer value = AiChoiceRecorder.observe(ai, ability, "NUMBER_SELECTION", null, "CONTROLLER_NUMBER_RANGE",
                () -> Map.of("coverage", "OBSERVED_RANGE", "minimum", 0, "maximum", 3),
                () -> { calls.incrementAndGet(); return 2; }, result -> result, "BEFORE_SELECTOR",
                open -> { throw failure; });
        Assert.assertEquals(value, Integer.valueOf(2));
        Assert.assertEquals(calls.get(), 1);
        Assert.assertEquals(events.size(), 2);
        Assert.assertEquals(context(events.get(0)).get("status"), "UNAVAILABLE");
        Assert.assertTrue(context(events.get(0)).toString().contains("CAPTURE_FAILED"));
        Assert.assertSame(game.getEventObservationFailure(), failure);
        Assert.assertFalse(events.toString().contains("private observation detail"));
        final RuntimeException callbackError = new IllegalArgumentException("callback");
        Assert.assertSame(Assert.expectThrows(IllegalArgumentException.class, () ->
                AiChoiceRecorder.observe(ai, ability, "NUMBER_SELECTION", null, "CONTROLLER_NUMBER_RANGE",
                        () -> Map.of("coverage", "OBSERVED_RANGE", "minimum", 0, "maximum", 3),
                        () -> { throw callbackError; }, result -> result, "BEFORE_SELECTOR", open -> { throw failure; })), callbackError);
        Assert.assertEquals(events.get(3).get("status"), "ERROR");
    }

    @DataProvider public Object[][] disabled() { return new Object[][] {{false}, {true}}; }

    @Test(dataProvider = "disabled", groups = {"UnitTest", "fast"})
    public void disabledAndCopiedChoicesNeverInvokeContextCapture(final boolean copy) {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility ability = addCardToZone("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(ai);
        final List<Map<String, Object>> events = record(game);
        if (copy) { game.markAsSimulationCopy(); } else { game.setRecordAiDecisions(false); }
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertEquals(AiChoiceRecorder.observe(ai, ability, "NUMBER_SELECTION", null, "CONTROLLER_NUMBER_RANGE",
                () -> { throw new AssertionError("options"); }, () -> { calls.incrementAndGet(); return 2; },
                result -> { throw new AssertionError("selection"); }, "BEFORE_SELECTOR",
                open -> { throw new AssertionError("context"); }), Integer.valueOf(2));
        Assert.assertEquals(calls.get(), 1);
        Assert.assertTrue(events.isEmpty());
        Assert.assertNull(game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void outerOperationAndLaterSelectorHaveDistinctFrozenResourceStates() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card mountain = addCardToZone("Mountain", ai, ZoneType.Battlefield);
        final SpellAbility ability = addCardToZone("Fireball", ai, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(ai);
        final List<Map<String, Object>> events = record(game);
        final var baseline = new AiDecisionRecorder(game, ai, 1);
        AiChoiceRecorder.targeting(ai, ability, "EXECUTION", () -> {
            ai.setLife(17, null); mountain.setTapped(true);
            AiNumberChoiceRecorder.range(ai, ability, "CONTROLLER_NUMBER_RANGE", 0, 2, () -> 1);
            return true;
        });
        final Map<?, ?> parent = (Map<?, ?>) baseline.finish(null, "COMPLETED", null).data().get("inputContext");
        final Map<?, ?> outer = context(events.get(0));
        final Map<?, ?> inner = context(events.get(1));
        Assert.assertEquals(outer.get("timing"), "BEFORE_OPERATION");
        Assert.assertEquals(inner.get("timing"), "BEFORE_SELECTOR");
        Assert.assertEquals(parent.get("stateBasis"), "RULES_STATE");
        Assert.assertEquals(outer.get("stateBasis"), "RULES_STATE");
        Assert.assertNotEquals(policy(outer), policy(inner));
        Assert.assertEquals(policy(parent), policy(outer));
        Assert.assertTrue(policy(outer).toString().contains("tapped=false"));
        Assert.assertTrue(policy(inner).toString().contains("tapped=true"));
        Assert.assertEquals(chooserLife(outer), 20);
        Assert.assertEquals(chooserLife(inner), 17);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void nestedHypotheticalSelectionsArePlanningAndScopeRestoresAfterError() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility ability = addCardToZone("Fireball", ai, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(ai);
        final Card transformed = addCardToZone("Delver of Secrets", ai, ZoneType.Battlefield);
        final List<Map<String, Object>> events = record(game);
        final AtomicInteger calls = new AtomicInteger();
        final var failure = new IllegalStateException("policy");
        Assert.assertSame(Assert.expectThrows(IllegalStateException.class, () -> AiChoiceRecorder.planning(ai, () -> {
            transformed.changeToState(forge.card.CardStateName.Backside);
            AiPaymentChoiceRecorder.cards(ai, ability, "PAYMENT_SACRIFICE_POOL", new CardCollection(transformed), 1, 1,
                    () -> { calls.incrementAndGet(); return new CardCollection(transformed); });
            throw failure;
        })), failure);
        Assert.assertEquals(calls.get(), 1);
        Assert.assertEquals(context(events.get(0)).get("stateBasis"), "PLANNING_WORKING_STATE");
        Assert.assertTrue(policy(context(events.get(0))).toString().contains("Insectile Aberration"));
        Assert.assertEquals(transformed.getCurrentStateName(), forge.card.CardStateName.Backside,
                "Recording must not restore or rerun policy's temporary state");
        transformed.changeToState(forge.card.CardStateName.Original);
        AiNumberChoiceRecorder.range(ai, ability, "CONTROLLER_NUMBER_RANGE", 0, 2, () -> 1);
        Assert.assertEquals(context(events.get(2)).get("stateBasis"), "RULES_STATE");
        Assert.assertTrue(policy(context(events.get(0))).toString().contains("Insectile Aberration"));
        Assert.assertFalse(events.get(1).containsKey("inputContext"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void interruptedEffectOfferPreservesSelectionWithUnavailablePoolAndNoInventedIndex() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility ability = addCardToZone("Doom Blade", ai, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(ai);
        final Card target = addCardToZone("Grizzly Bears", game.getPlayers().get(0), ZoneType.Battlefield);
        final List<Map<String, Object>> events = record(game);
        try {
            Thread.currentThread().interrupt();
            Assert.assertSame(AiEffectChoiceRecorder.single(ai, ability, List.of(target), false, null,
                    () -> target), target);
        } finally { Thread.interrupted(); }
        Assert.assertEquals(((Map<?, ?>) events.get(0).get("options")).get("coverage"), "UNAVAILABLE");
        Assert.assertEquals(context(events.get(0)).get("status"), "UNAVAILABLE");
        final Map<?, ?> selection = (Map<?, ?>) events.get(1).get("selection");
        Assert.assertEquals(selection.get("match"), "UNAVAILABLE");
        Assert.assertNull(selection.get("selectedOptionIndex"));
        Assert.assertEquals(((Map<?, ?>) selection.get("selected")).get("id"), target.getId());
        Assert.assertNull(game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    @SuppressWarnings("unchecked")
    public void blockedContextCannotHoldTimeoutPublicationOrAttachToNextDecision() throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility ability = addCardToZone("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(ai);
        final List<Map<String, Object>> events = record(game);
        final var first = new AiDecisionRecorder(game, ai, 1);
        first.beginAssessment(ability);
        final var field = AiController.class.getDeclaredField("evaluationDecision"); field.setAccessible(true);
        final var local = (ThreadLocal<AiDecisionRecorder>) field.get(((PlayerControllerAi) ai.getController()).getAi());
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        final Thread worker = new Thread(() -> {
            local.set(first);
            try {
                AiChoiceRecorder.observe(ai, ability, "NUMBER_SELECTION", null, "CONTROLLER_NUMBER_RANGE",
                        () -> Map.of("coverage", "OBSERVED_RANGE", "minimum", 0, "maximum", 3),
                        () -> { calls.incrementAndGet(); return 2; }, result -> result, "BEFORE_SELECTOR", open -> {
                            entered.countDown();
                            try { if (!release.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("release"); } }
                            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                            return AiInputContext.capture(ai, ability, "BEFORE_SELECTOR", "PLANNING_WORKING_STATE", open);
                        });
            } catch (Throwable error) { failure.set(error); }
            finally { local.remove(); }
        });
        worker.start();
        try {
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
            Assert.assertNotNull(first.finish(null, "AI_TIMEOUT", null));
            final var next = new AiDecisionRecorder(game, ai, 2);
            Assert.assertEquals(next.finish(null, "COMPLETED", null).data().get("decisionId"), ai.getId() + ":2");
        } finally { release.countDown(); worker.join(5000); }
        Assert.assertFalse(worker.isAlive());
        Assert.assertNull(failure.get());
        Assert.assertEquals(calls.get(), 1);
        Assert.assertTrue(events.isEmpty());
    }

    @DataProvider public Object[][] intuitionCases() {
        return new Object[][] {{5, false}, {2, false}, {5, true}};
    }

    @Test(dataProvider = "intuitionCases", groups = {"UnitTest", "fast"})
    public void actualIntuitionKeepsOnlyDeliveredFactsForDelegatedNoLookingChoice(final int available, final boolean visibleTop) {
        final Game game = initAndCreateGame();
        final Player owner = game.getPlayers().get(1), chooser = game.getPlayers().get(0);
        final List<Card> library = new ArrayList<>();
        for (final String name : List.of("Island", "Forest", "Mountain", "Grizzly Bears", "Shivan Dragon").subList(0, available)) {
            library.add(addCardToZone(name, owner, ZoneType.Library));
        }
        addCardToZone("Swamp", owner, ZoneType.Hand);
        addCardToZone("Plains", chooser, ZoneType.Hand);
        if (visibleTop) { library.get(0).addMayLookTemp(chooser); }
        final SpellAbility ability = addCardToZone("Intuition", owner, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(owner);
        ability.setHostCard(game.getAction().moveToStack(ability.getHostCard(), ability));
        ability.getSubAbility().getTargets().add(chooser);
        final List<Map<String, Object>> events = record(game);
        game.getStack().addAndUnfreeze(ability);
        game.getStack().resolveStack();
        final var delegated = events.stream().filter(e -> "CONTROLLER_ZONE_CHANGE_CARD".equals(e.get("boundary"))
                && Long.valueOf(chooser.getId()).equals(e.get("chooserPlayerId"))).findFirst().orElseThrow();
        final Map<?, ?> context = context(delegated);
        Assert.assertEquals(context.get("timing"), "BEFORE_SELECTOR");
        Assert.assertEquals(context.get("stateBasis"), "RULES_STATE");
        Assert.assertEquals(((Map<?, ?>) context.get("perspective")).get("chooserPlayerId"), chooser.getId());
        Assert.assertEquals(policy(context).get("prompt"), Map.ofEntries(
                Map.entry("branch", "ORDINARY_HIDDEN_ORIGIN"), Map.entry("kind", "ZONE_PICK_PROGRESS"), Map.entry("minimumPicks", 1), Map.entry("maximumPicks", 1),
                Map.entry("optional", false), Map.entry("destination", "Hand"), Map.entry("origins", List.of("Library")),
                Map.entry("sourcePlayer", "p1"), Map.entry("decider", "p0"), Map.entry("requestedIterations", 1),
                Map.entry("attemptIndex", 0L), Map.entry("loopPosition", 0), Map.entry("priorNonNullReturnCount", 0L),
                Map.entry("retainedCount", 0), Map.entry("previousReturns", List.of())));
        Assert.assertEquals(facts(context).size(), Math.min(3, available));
        Assert.assertEquals(((List<?>) ((Map<?, ?>) delegated.get("options")).get("items")).size(), Math.min(3, available));
        Assert.assertTrue(policy(context).toString().contains("Plains"));
        Assert.assertFalse(policy(context).toString().contains("Swamp"));
        for (Object fact : facts(context)) {
            Assert.assertEquals(((Map<?, ?>) fact).get("currentObjectAssociation"), "UNRESOLVED");
        }
        Assert.assertEquals(owner.getCardsIn(ZoneType.Hand).size(), 2);
        Assert.assertEquals(owner.getCardsIn(ZoneType.Graveyard).size(), Math.min(3, available)); // Intuition plus the unchosen cards
        Assert.assertNull(game.getEventObservationFailure());
        final var after = AiInputContext.capture(chooser, null, "BEFORE_OPERATION", "RULES_STATE", () -> true);
        Assert.assertTrue(facts(after).isEmpty(), "Resolution disclosures must not persist into the next action");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void delayedDisclosureSupersetIsFrozenAndFreshNoLookingDoesNotInventKnowledge() {
        final Game game = initAndCreateGame();
        final Player owner = game.getPlayers().get(1), chooser = game.getPlayers().get(0);
        final CardCollection library = new CardCollection();
        for (String name : List.of("Island", "Forest", "Mountain", "Grizzly Bears", "Shivan Dragon")) {
            library.add(addCardToZone(name, owner, ZoneType.Library));
        }
        final Card stale = addCardToZone("Swamp", owner, ZoneType.Library);
        final CardCollection payload = new CardCollection(library); payload.add(stale);
        final DelayedReveal reveal = new DelayedReveal(payload, ZoneType.Library, PlayerView.get(owner));
        game.getAction().moveToHand(stale, null);
        final SpellAbility ability = addCardToZone("Intuition", owner, ZoneType.Hand).getFirstSpellAbility().getSubAbility();
        ability.setActivatingPlayer(owner);
        ability.setHostCard(game.getAction().moveToStack(ability.getHostCard(), ability));
        final CardCollection options = new CardCollection(library.subList(0, 2));
        final List<Map<String, Object>> events = record(game);
        chooser.getController().chooseSingleCardForZoneChange(ZoneType.Hand, List.of(ZoneType.Library), ability,
                options, reveal, "select", false, chooser);
        final Map<?, ?> first = context(events.get(0));
        Assert.assertEquals(facts(first).size(), 5);
        Assert.assertEquals(((List<?>) ((Map<?, ?>) events.get(0).get("options")).get("items")).size(), 2);
        Assert.assertFalse(facts(first).toString().contains("Swamp"));
        final String frozen = facts(first).toString();
        library.get(0).turnFaceDown();
        Assert.assertEquals(facts(first).toString(), frozen);
        chooser.getController().chooseSingleCardForZoneChange(ZoneType.Hand, List.of(ZoneType.Library), ability,
                options, null, "select", false, chooser);
        final var second = events.stream().filter(e -> "STARTED".equals(e.get("status"))).skip(1).findFirst().orElseThrow();
        Assert.assertTrue(facts(context(second)).isEmpty());
        Assert.assertFalse(((List<?>) policy(context(second)).get("cards")).toString().contains("Island"));
        Assert.assertNull(game.getEventObservationFailure());
    }
}
