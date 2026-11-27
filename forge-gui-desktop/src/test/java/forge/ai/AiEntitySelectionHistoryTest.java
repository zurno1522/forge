package forge.ai;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AiEntitySelectionHistoryTest extends AITest {
    private record Fixture(Game game, Player chooser, SpellAbility ability, Card padding, FCollection<Card> options,
            List<Map<String, Object>> events) { }

    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player chooser = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, chooser);
        final Card source = addCardToZone("Time Beetle", chooser, ZoneType.Battlefield);
        final Card padding = addCardToZone("Island", chooser, ZoneType.Battlefield);
        final SpellAbility ability = AbilityFactory.getAbility("AB$ TimeTravel | Cost$ 0", source);
        ability.setActivatingPlayer(chooser);
        final FCollection<Card> options = new FCollection<>(List.of(
                addCardToZone("Grizzly Bears", chooser, ZoneType.Battlefield),
                addCardToZone("Shivan Dragon", chooser, ZoneType.Battlefield)));
        final List<Map<String, Object>> events = new ArrayList<>();
        game.setRecordAiDecisions(true);
        game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { events.add(choice.data()); } });
        return new Fixture(game, chooser, ability, padding, options, events);
    }

    private List<Card> choose(final Fixture f, final int min, final int max) {
        return f.chooser.getController().chooseEntitiesForEffect(f.options, min, max, null, f.ability,
                "unrecorded", f.game.getPlayers().get(0), null);
    }
    private List<Map<String, Object>> picks(final Fixture f) {
        return f.events.stream().filter(e -> "CONTROLLER_EFFECT_ENTITY".equals(e.get("boundary"))).toList();
    }
    private Map<?, ?> input(final Map<String, Object> choice) { return (Map<?, ?>) choice.get("inputContext"); }
    private Map<?, ?> policy(final Map<String, Object> choice) { return (Map<?, ?>) input(choice).get("policy"); }
    private Map<?, ?> prompt(final Map<String, Object> choice) { return (Map<?, ?>) policy(choice).get("prompt"); }
    private Map<?, ?> sequence(final Map<String, Object> choice) { return (Map<?, ?>) ((Map<?, ?>) choice.get("options")).get("selectionSequence"); }
    private String ref(final Map<String, Object> choice, final Card card) {
        final var bindings = (List<?>) ((Map<?, ?>) input(choice).get("diagnostics")).get("cardBindings");
        return (String) bindings.stream().map(v -> (Map<?, ?>) v).filter(v -> Long.valueOf(card.getId()).equals(v.get("id")))
                .findFirst().orElseThrow().get("entityRef");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void realControllerUsesActualEarlierReturnsAsOrderedHistoryWithoutMutatingEarlierMessages() {
        final Fixture f = fixture(); Assert.assertEquals(choose(f, 1, 2), new ArrayList<>(f.options));
        final var picks = picks(f); Assert.assertEquals(picks.size(), 2);
        Assert.assertEquals(prompt(picks.get(0)).get("kind"), "ENTITY_PICK_SEQUENCE");
        Assert.assertEquals(input(picks.get(0)).get("schemaVersion"), 29);
        Assert.assertEquals(sequence(picks.get(0)).get("coverage"), "COMPLETE");
        Assert.assertEquals(sequence(picks.get(0)).get("priorSelectionCount"), 0);
        Assert.assertTrue(((List<?>) prompt(picks.get(0)).get("previousSelections")).isEmpty());
        final var second = prompt(picks.get(1));
        Assert.assertEquals(second.get("minimumTotal"), 1); Assert.assertEquals(second.get("maximumTotal"), 2);
        Assert.assertEquals(second.get("priorSelectionCount"), 1); Assert.assertEquals(second.get("optional"), true);
        Assert.assertEquals(second.get("previousSelections"), List.of(ref(picks.get(1), f.options.get(0))));
        Assert.expectThrows(UnsupportedOperationException.class, () -> ((List<?>) second.get("previousSelections")).clear());
        Assert.assertTrue(((List<?>) sequence(picks.get(0)).get("priorSelections")).isEmpty());
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void previousObjectsAreAssociatedWithTheNextSelectorsFreshViewRatherThanOuterLocalReferences() {
        final Fixture f = fixture(); final AtomicBoolean changed = new AtomicBoolean();
        f.game.observeEventEmission(event -> {
            if (event instanceof AiChoiceEvent choice && "COMPLETED".equals(choice.data().get("status")) && changed.compareAndSet(false, true)) {
                f.game.getAction().moveToGraveyard(f.padding, f.ability);
                f.chooser.setLife(7, null);
            }
        });
        choose(f, 1, 2); final var picks = picks(f);
        Assert.assertEquals(prompt(picks.get(1)).get("kind"), "ENTITY_PICK_SEQUENCE");
        final var outer = f.events.get(0);
        Assert.assertNotEquals(ref(outer, f.options.get(0)), ref(picks.get(1), f.options.get(0)));
        Assert.assertEquals(prompt(picks.get(1)).get("previousSelections"), List.of(ref(picks.get(1), f.options.get(0))));
        Assert.assertEquals(((Map<?, ?>) ((List<?>) policy(picks.get(1)).get("players")).get(1)).get("life"), 7);
        Assert.assertEquals(((Map<?, ?>) ((List<?>) policy(outer).get("players")).get(1)).get("life"), 20);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aPrivateEarlierSelectionPreservesKnownCountButCannotAcquireAPublicAssociationOrName() {
        final Fixture f = fixture(); final Card hidden = addCardToZone("Black Lotus", f.game.getPlayers().get(0), ZoneType.Hand);
        f.options.set(0, hidden); choose(f, 0, 2); final var second = picks(f).get(1);
        Assert.assertEquals(prompt(second).get("kind"), "ENTITY_PICK_SEQUENCE");
        Assert.assertEquals(sequence(second).get("coverage"), "COMPLETE");
        final List<?> refs = (List<?>) prompt(second).get("previousSelections");
        Assert.assertEquals(refs.size(), 1); Assert.assertNull(refs.get(0));
        Assert.assertFalse(policy(second).toString().contains("Black Lotus"));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void theReceiptCapDoesNotLimitEngineSelectionsOrPretendTheRemainingHistoryIsComplete() {
        final Fixture f = fixture(); f.options.clear();
        for (int i = 0; i < 67; i++) { f.options.add(createCard("Memnite", f.chooser)); }
        Assert.assertEquals(choose(f, 0, 67).size(), 67);
        final var picks = picks(f); Assert.assertEquals(picks.size(), 67);
        Assert.assertEquals(prompt(picks.get(64)).get("kind"), "ENTITY_PICK_SEQUENCE");
        Assert.assertEquals(((List<?>) prompt(picks.get(64)).get("previousSelections")).size(), 64);
        final var later = picks.get(65);
        Assert.assertEquals(sequence(later).get("coverage"), "PREFIX");
        Assert.assertEquals(sequence(later).get("priorSelectionCount"), 65);
        Assert.assertEquals(((List<?>) sequence(later).get("priorSelections")).size(), 64);
        Assert.assertNull(policy(later).get("prompt"));
        Assert.assertTrue(((List<?>) input(later).get("omissions")).contains(Map.of("component", "PROMPT", "reason", "OUTSIDE_CAPTURED_PREFIX")));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void zeroBoundDeclineAndSubsequentDirectSelectorDoNotLeakASequenceScope() {
        final Fixture f = fixture(); f.options.clear(); Assert.assertTrue(choose(f, 0, 0).isEmpty());
        Assert.assertEquals(prompt(picks(f).get(0)).get("kind"), "ENTITY_PICK_SEQUENCE");
        Assert.assertEquals(prompt(picks(f).get(0)).get("priorSelectionCount"), 0);
        f.options.add(addCardToZone("Grizzly Bears", f.chooser, ZoneType.Battlefield));
        f.chooser.getController().chooseSingleEntityForEffect(f.options, null, f.ability, "unrecorded", false, null, null);
        final var direct = picks(f).get(1);
        Assert.assertFalse(((Map<?, ?>) direct.get("options")).containsKey("selectionSequence"));
        Assert.assertEquals(prompt(direct).get("kind"), "SINGLE_ENTITY");
        Assert.assertNull(direct.get("parentChoiceId"));
    }

    @Test(groups = {"UnitTest", "fast"})
    @SuppressWarnings("unchecked")
    public void unauditedOverrideReturnsArePreservedWithoutClaimingAuditedSelectorHistory() {
        final Fixture f = fixture(); final AtomicInteger calls = new AtomicInteger();
        final var controller = new PlayerControllerAi(f.game, f.chooser, f.chooser.getLobbyPlayer()) {
            @Override public <T extends GameEntity> T chooseSingleEntityForEffect(final FCollectionView<T> options,
                    final DelayedReveal reveal, final SpellAbility ability, final String title, final boolean optional,
                    final Player related, final Map<String, Object> params) {
                calls.incrementAndGet(); return (T) f.options.get(0);
            }
        };
        f.chooser.dangerouslySetController(controller);
        Assert.assertEquals(choose(f, 0, 2), List.of(f.options.get(0), f.options.get(0)));
        Assert.assertEquals(calls.get(), 2);
        Assert.assertTrue(picks(f).isEmpty());
        Assert.assertEquals(f.events.size(), 2);
        Assert.assertEquals(((Map<?, ?>) f.events.get(1).get("selection")).get("selectedCount"), 2);
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aMovedEarlierObjectCannotBindToItsNewIncarnation() {
        final Fixture f = fixture(); final Card earlier = f.options.get(0); final AtomicBoolean moved = new AtomicBoolean();
        f.game.observeEventEmission(event -> {
            if (event instanceof AiChoiceEvent choice && "COMPLETED".equals(choice.data().get("status")) && moved.compareAndSet(false, true)) {
                f.game.getAction().moveToGraveyard(earlier, f.ability);
            }
        });
        choose(f, 0, 2); final var second = picks(f).get(1);
        Assert.assertEquals(sequence(second).get("coverage"), "COMPLETE");
        final var previous = (List<?>) prompt(second).get("previousSelections");
        Assert.assertEquals(previous.size(), 1); Assert.assertNull(previous.get(0));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void nestedOperationHasItsOwnAdmissionAndRestoresTheOuterReceiptSequence() {
        final Fixture f = fixture(); final AtomicBoolean nested = new AtomicBoolean();
        final List<Map<String, Object>> nestedEvents = new ArrayList<>();
        f.game.observeEventEmission(event -> {
            if (event instanceof AiChoiceEvent choice && "CONTROLLER_EFFECT_ENTITY".equals(choice.data().get("boundary")) && nested.compareAndSet(false, true)) {
                final int from = f.events.size();
                Assert.assertEquals(choose(f, 0, 1), List.of(f.options.get(0)));
                nestedEvents.addAll(f.events.subList(from, f.events.size()));
            }
        });
        choose(f, 1, 2);
        Assert.assertEquals(nestedEvents.size(), 4);
        final var nestedPick = nestedEvents.get(1);
        Assert.assertEquals(nestedPick.get("parentChoiceId"), nestedEvents.get(0).get("choiceId"));
        Assert.assertEquals(prompt(nestedPick).get("priorSelectionCount"), 0);
        Assert.assertEquals(prompt(nestedPick).get("minimumTotal"), 0);
        final var outerLast = picks(f).get(2);
        Assert.assertEquals(prompt(outerLast).get("minimumTotal"), 1);
        Assert.assertEquals(prompt(outerLast).get("priorSelectionCount"), 1);
        Assert.assertEquals(prompt(outerLast).get("previousSelections"), List.of(ref(outerLast, f.options.get(0))));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aFailedIntermediateCaptureCannotBorrowTheEarlierOuterAdmission() {
        final Fixture f = fixture(); final var failure = new IllegalStateException("missing nested parent capture");
        final FCollection<Card> nestedOffer = Mockito.spy(new FCollection<>(f.options));
        final AtomicInteger iterations = new AtomicInteger();
        Mockito.doAnswer(call -> { if (iterations.getAndIncrement() == 0) { throw failure; } return call.callRealMethod(); }).when(nestedOffer).iterator();
        final AtomicBoolean nested = new AtomicBoolean(); final List<Map<String, Object>> nestedEvents = new ArrayList<>();
        f.game.observeEventEmission(event -> {
            if (event instanceof AiChoiceEvent choice && "CONTROLLER_EFFECT_ENTITY".equals(choice.data().get("boundary")) && nested.compareAndSet(false, true)) {
                final int from = f.events.size();
                Assert.assertEquals(f.chooser.getController().chooseEntitiesForEffect(nestedOffer, 0, 1, null, f.ability,
                        "nested", f.game.getPlayers().get(0), null), List.of(f.options.get(0)));
                nestedEvents.addAll(f.events.subList(from, f.events.size()));
            }
        });
        Assert.assertEquals(choose(f, 1, 2), new ArrayList<>(f.options));
        Assert.assertEquals(nestedEvents.size(), 2);
        Assert.assertFalse(((Map<?, ?>) nestedEvents.get(0).get("options")).containsKey("selectionSequence"));
        Assert.assertEquals(prompt(nestedEvents.get(0)).get("kind"), "SINGLE_ENTITY");
        Assert.assertEquals(prompt(picks(f).get(2)).get("priorSelectionCount"), 1);
        Assert.assertSame(f.game.getEventObservationFailure(), failure);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aNestedProbeCannotClaimTheSameVirtualDispatchTwice() {
        final Fixture f = fixture(); final var controller = Mockito.spy((PlayerControllerAi) f.chooser.getController());
        f.chooser.dangerouslySetController(controller);
        final AtomicReference<FCollectionView<Card>> actualOffer = new AtomicReference<>();
        Mockito.doAnswer(call -> { actualOffer.set(call.getArgument(0)); return call.callRealMethod(); }).when(controller)
                .chooseSingleEntityForEffect(Mockito.any(), Mockito.isNull(), Mockito.same(f.ability), Mockito.anyString(),
                        Mockito.anyBoolean(), Mockito.any(), Mockito.any());
        final AtomicBoolean probed = new AtomicBoolean();
        f.game.observeEventEmission(event -> {
            if (event instanceof AiChoiceEvent choice && "CONTROLLER_EFFECT_ENTITY".equals(choice.data().get("boundary")) && probed.compareAndSet(false, true)) {
                controller.chooseSingleEntityForEffect(actualOffer.get(), null, f.ability, "probe", false, f.game.getPlayers().get(0), null);
            }
        });
        Assert.assertEquals(choose(f, 1, 2), new ArrayList<>(f.options));
        final var picks = picks(f); Assert.assertEquals(picks.size(), 3);
        Assert.assertEquals(prompt(picks.get(0)).get("kind"), "ENTITY_PICK_SEQUENCE");
        Assert.assertFalse(((Map<?, ?>) picks.get(1).get("options")).containsKey("selectionSequence"));
        Assert.assertEquals(prompt(picks.get(1)).get("kind"), "SINGLE_ENTITY");
        Assert.assertEquals(prompt(picks.get(2)).get("priorSelectionCount"), 1);
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] lateReceipt() { return new Object[][] {{false, false}, {false, true}, {true, false}, {true, true}}; }
    @Test(dataProvider = "lateReceipt", groups = {"UnitTest", "fast"})
    @SuppressWarnings("unchecked")
    public void blockedReceiptCannotHoldPublicationAndLateCompletionCannotPoisonHealth(final boolean parentClosure, final boolean fail) throws Exception {
        final Fixture f = fixture(); final Card selected = Mockito.spy(f.options.get(0)); f.options.set(0, selected);
        final long timestamp = selected.getGameTimestamp();
        final AtomicBoolean gate = new AtomicBoolean(); final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Mockito.doAnswer(call -> {
            if (gate.get()) {
                entered.countDown(); Assert.assertTrue(release.await(5, TimeUnit.SECONDS));
                if (fail) { throw new IllegalStateException("obsolete receipt"); }
            }
            return timestamp;
        }).when(selected).getGameTimestamp();
        f.game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice && "COMPLETED".equals(choice.data().get("status"))) { gate.set(true); } });
        final var parent = new AiDecisionRecorder(f.game, f.chooser, 1); parent.beginAssessment(f.ability);
        final var field = AiController.class.getDeclaredField("evaluationDecision"); field.setAccessible(true);
        final ThreadLocal<AiDecisionRecorder> evaluation = (ThreadLocal<AiDecisionRecorder>) field.get(((PlayerControllerAi) f.chooser.getController()).getAi());
        final var worker = Executors.newSingleThreadExecutor();
        try {
            final var result = worker.submit(() -> {
                if (parentClosure) { evaluation.set(parent); }
                try { return choose(f, 1, 2); } finally { evaluation.remove(); }
            });
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
            if (parentClosure) { Assert.assertNotNull(parent.finish(null, "AI_TIMEOUT", null)); }
            else { f.game.setRecordAiDecisions(false); }
            release.countDown();
            Assert.assertEquals(result.get(5, TimeUnit.SECONDS), new ArrayList<>(f.options));
            Assert.assertEquals(f.events.size(), 3, "Only the outer start and first selector's pair precede closure");
            Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void liveReceiptFailureIsVisibleAndLeavesSubsequentHistoryUnavailableWithoutLosingReturns() {
        final Fixture f = fixture(); final var ledger = ((PlayerControllerAi) f.chooser.getController()).configureObservationAccounting();
        final Card selected = Mockito.spy(f.options.get(0)); f.options.set(0, selected);
        final long timestamp = selected.getGameTimestamp(); final AtomicBoolean gate = new AtomicBoolean();
        final AtomicBoolean failed = new AtomicBoolean(); final var failure = new IllegalStateException("live receipt failure");
        Mockito.doAnswer(call -> { if (gate.get() && failed.compareAndSet(false, true)) { throw failure; } return timestamp; }).when(selected).getGameTimestamp();
        f.game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice && "COMPLETED".equals(choice.data().get("status"))) { gate.set(true); } });
        Assert.assertEquals(choose(f, 1, 2), new ArrayList<>(f.options));
        final var second = picks(f).get(1);
        Assert.assertEquals(sequence(second).get("coverage"), "UNAVAILABLE");
        Assert.assertEquals(sequence(second).get("priorSelectionCount"), 1);
        Assert.assertNull(policy(second).get("prompt"));
        Assert.assertTrue(((List<?>) input(second).get("omissions")).contains(Map.of("component", "PROMPT", "reason", "CAPTURE_FAILED")));
        Assert.assertSame(f.game.getEventObservationFailure(), failure);
        ledger.close();
        Assert.assertEquals(((Map<?, ?>) ((Map<?, ?>) ledger.snapshot().get("boundaries")).get("CONTROLLER_EFFECT_ENTITIES")).get("observationFailures"), 1L);
        Assert.assertEquals(f.events.get(5).get("status"), "COMPLETED");
    }

    @DataProvider public Object[][] invisible() { return new Object[][] {{"HIDDEN"}, {"DISABLED"}, {"COPY"}}; }
    @Test(dataProvider = "invisible", groups = {"UnitTest", "fast"})
    public void unrecordedOrHiddenRealControllerLoopDoesNotReadReceiptGetters(final String mode) {
        final Fixture f = fixture();
        for (int i = 0; i < f.options.size(); i++) {
            final Card card = Mockito.spy(f.options.get(i)); f.options.set(i, card);
            Mockito.doThrow(new AssertionError("receipt must not be inspected")).when(card).getGameTimestamp();
        }
        if ("HIDDEN".equals(mode)) { f.ability.getHostCard().turnFaceDown(); }
        if ("DISABLED".equals(mode)) { f.game.setRecordAiDecisions(false); }
        if ("COPY".equals(mode)) { f.game.markAsSimulationCopy(); }
        Assert.assertEquals(choose(f, 1, 2), new ArrayList<>(f.options));
        if ("HIDDEN".equals(mode)) {
            Assert.assertEquals(f.events.size(), 6);
            for (final var event : f.events) {
                if ("STARTED".equals(event.get("status"))) {
                    Assert.assertEquals(event.get("boundary"), "MASKED_CHOICE");
                    Assert.assertEquals(event.get("options"), Map.of("coverage", "MASKED"));
                }
            }
        } else { Assert.assertTrue(f.events.isEmpty()); }
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void delegatedChooserOwnsItsHistoryWhileAnotherChoosersNestedProbeStaysUnsequenced() {
        final Game game = initAndCreateThreePlayerGame();
        final Player activator = game.getPlayers().get(0), chooser = game.getPlayers().get(1), other = game.getPlayers().get(2);
        final SpellAbility ability = AbilityFactory.getAbility("AB$ TimeTravel | Cost$ 0", addCardToZone("Time Beetle", activator, ZoneType.Battlefield));
        ability.setActivatingPlayer(activator);
        final FCollection<Card> offered = new FCollection<>(List.of(addCardToZone("Grizzly Bears", chooser, ZoneType.Battlefield),
                addCardToZone("Shivan Dragon", chooser, ZoneType.Battlefield)));
        final List<Map<String, Object>> events = new ArrayList<>(); final AtomicBoolean probed = new AtomicBoolean();
        game.setRecordAiDecisions(true);
        game.observeEventEmission(event -> {
            if (event instanceof AiChoiceEvent choice) {
                events.add(choice.data());
                if ("CONTROLLER_EFFECT_ENTITY".equals(choice.data().get("boundary")) && probed.compareAndSet(false, true)) {
                    other.getController().chooseSingleEntityForEffect(offered, null, ability, "probe", false, null, null);
                }
            }
        });
        Assert.assertEquals(chooser.getController().chooseEntitiesForEffect(offered, 0, 2, null, ability, "choose", other, null), new ArrayList<>(offered));
        final var own = events.stream().filter(e -> "CONTROLLER_EFFECT_ENTITY".equals(e.get("boundary")) && Long.valueOf(chooser.getId()).equals(e.get("chooserPlayerId"))).toList();
        Assert.assertEquals(own.size(), 2);
        Assert.assertEquals(prompt(own.get(1)).get("priorSelectionCount"), 1);
        Assert.assertEquals(prompt(own.get(1)).get("relatedPlayer"), "p2");
        final var foreign = events.stream().filter(e -> Long.valueOf(other.getId()).equals(e.get("chooserPlayerId"))).findFirst().orElseThrow();
        Assert.assertFalse(((Map<?, ?>) foreign.get("options")).containsKey("selectionSequence"));
        Assert.assertEquals(prompt(foreign).get("kind"), "SINGLE_ENTITY");
        Assert.assertNull(game.getEventObservationFailure());
    }
    @Test(groups = {"UnitTest", "fast"})
    public void aDisabledNestedOperationMasksAnOlderPendingEntityDispatchThenRestoresIt() {
        final Fixture f = fixture(); final var receiver = (PlayerControllerAi) f.chooser.getController();
        final var result = AiEffectChoiceRecorder.multiple(f.chooser, f.ability, f.options, 0, 2, null, receiver, () -> {
            AiEntitySelectionHistory.beforePick(f.chooser, f.ability, f.options, 0, true, null);
            f.game.setRecordAiDecisions(false);
            AiEffectChoiceRecorder.multiple(f.chooser, f.ability, f.options, 0, 1, null, receiver, () -> {
                f.game.setRecordAiDecisions(true);
                return List.of(AiEffectChoiceRecorder.single(f.chooser, f.ability, f.options, true, null, () -> f.options.get(1)));
            });
            final Card first = AiEffectChoiceRecorder.single(f.chooser, f.ability, f.options, true, null, () -> f.options.get(0));
            AiEntitySelectionHistory.returned(f.chooser, f.ability, first, 1);
            AiEntitySelectionHistory.beforePick(f.chooser, f.ability, f.options, 1, true, null);
            final Card second = AiEffectChoiceRecorder.single(f.chooser, f.ability, f.options, true, null, () -> f.options.get(1));
            AiEntitySelectionHistory.returned(f.chooser, f.ability, second, 2); return List.of(first, second);
        });
        Assert.assertEquals(result, new ArrayList<>(f.options)); final var picks = picks(f); Assert.assertEquals(picks.size(), 3);
        Assert.assertNull(sequence(picks.get(0)), "disabled nested operation borrowed an older dispatch");
        Assert.assertEquals(prompt(picks.get(0)).get("kind"), "SINGLE_ENTITY");
        Assert.assertEquals(sequence(picks.get(1)).get("priorSelectionCount"), 0);
        Assert.assertEquals(sequence(picks.get(2)).get("priorSelectionCount"), 1);
        Assert.assertEquals(prompt(picks.get(2)).get("previousSelections"), List.of(ref(picks.get(2), f.options.get(0))));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

}
