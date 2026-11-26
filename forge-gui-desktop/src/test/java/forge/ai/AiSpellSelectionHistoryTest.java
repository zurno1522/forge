package forge.ai;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class AiSpellSelectionHistoryTest extends AITest {
    private record Fixture(Game game, Player player, PlayerControllerAi controller, SpellAbility ability,
            List<SpellAbility> spells, List<Map<String, Object>> events, Map<String, Object> params) { }
    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player player = game.getPlayers().get(1);
        final Card source = addCardToZone("Time Beetle", player, ZoneType.Battlefield);
        source.setSVar("Life", "DB$ GainLife | LifeAmount$ 3"); source.setSVar("Draw", "DB$ Draw | NumCards$ 1");
        final SpellAbility ability = AbilityFactory.getAbility("AB$ GenericChoice | Cost$ 0 | Choices$ Life,Draw", source);
        ability.setActivatingPlayer(player); final List<SpellAbility> spells = new ArrayList<>(ability.getAdditionalAbilityList("Choices"));
        for (var spell : spells) { spell.setActivatingPlayer(player); }
        game.setRecordAiDecisions(true); final List<Map<String, Object>> events = new ArrayList<>();
        game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent c) { events.add(c.data()); } });
        return new Fixture(game, player, (PlayerControllerAi) player.getController(), ability, spells, events, new HashMap<>());
    }
    private List<Map<String, Object>> picks(Fixture f) { return f.events.stream().filter(c -> "CONTROLLER_EFFECT_SPELL_ABILITY".equals(c.get("boundary"))).toList(); }
    private Map<?, ?> progress(Map<String, Object> c) { return (Map<?, ?>) ((Map<?, ?>) c.get("options")).get("spellSelectionProgress"); }
    private Map<?, ?> input(Map<String, Object> c) { return (Map<?, ?>) c.get("inputContext"); }
    private Map<?, ?> prompt(Map<String, Object> c) { return (Map<?, ?>) ((Map<?, ?>) input(c).get("policy")).get("prompt"); }
    private List<SpellAbility> operation(Fixture f, Supplier<List<SpellAbility>> callback) {
        return AiSpellChoiceRecorder.multiple(f.player, f.ability, f.spells, 100, f.params, f.controller, callback);
    }
    private SpellAbility pick(Fixture f, int before, SpellAbility result) {
        AiSpellSelectionHistory.beforePick(f.player, f.ability, f.controller, f.spells, f.params, before);
        final var returned = AiSpellChoiceRecorder.single(f.player, f.ability, f.spells, f.params, f.controller, () -> result);
        AiSpellSelectionHistory.returned(f.player, f.ability, f.controller, returned, before + (result == null ? 0 : 1));
        return returned;
    }

    @Test(groups = {"UnitTest", "fast"})
    public void realLoopRetainsDuplicateReturnOrderAndFrozenUnresolvedHistory() {
        final Fixture f = fixture(); final var original = f.spells.get(0); f.spells.add(0, original);
        final var result = f.controller.chooseSpellAbilitiesForEffect(f.spells, f.ability, "Choose", 3, f.params);
        Assert.assertEquals(result, List.of(original, original, f.spells.get(2))); final var picks = picks(f);
        for (int i = 0; i < 3; i++) {
            Assert.assertEquals(prompt(picks.get(i)).get("attemptIndex"), (long) i);
            Assert.assertEquals(prompt(picks.get(i)).get("priorNonNullReturnCount"), (long) i);
            Assert.assertEquals(prompt(picks.get(i)).get("retainedCount"), i);
            Assert.assertEquals(prompt(picks.get(i)).get("requestedSelections"), 3);
            final var previous = (List<?>) prompt(picks.get(i)).get("previousReturns");
            Assert.assertEquals(previous.size(), i); Assert.assertTrue(previous.stream().allMatch(v -> v == null));
        }
        final var receipts = (List<?>) progress(picks.get(2)).get("priorReturns"); Assert.assertEquals(receipts.size(), 2); Assert.assertEquals(receipts.get(0), receipts.get(1));
        Assert.expectThrows(UnsupportedOperationException.class, () -> receipts.clear());
        Assert.assertTrue(((List<?>) progress(picks.get(0)).get("priorReturns")).isEmpty()); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void nonmemberReturnsRemainActualReturnsWithoutRepairingRemoval() {
        final Fixture f = fixture(); final var controller = Mockito.spy(f.controller); f.player.dangerouslySetController(controller);
        final var outsider = AbilityFactory.getAbility("DB$ GainLife | LifeAmount$ 1", f.ability.getHostCard());
        Mockito.doAnswer(call -> AiSpellChoiceRecorder.single(f.player, f.ability, call.getArgument(0), call.getArgument(3), controller, () -> outsider))
                .when(controller).chooseSingleSpellForEffect(Mockito.anyList(), Mockito.any(), Mockito.anyString(), Mockito.any());
        Assert.assertEquals(controller.chooseSpellAbilitiesForEffect(f.spells, f.ability, "Choose", 3, f.params), List.of(outsider, outsider, outsider));
        Assert.assertEquals(f.spells.size(), 2); Assert.assertEquals(((List<?>) progress(picks(f).get(2)).get("priorReturns")).size(), 2);
        Assert.assertEquals(prompt(picks(f).get(2)).get("retainedCount"), 2); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void directAndPostOperationSelectorsKeepUnknownHistoryAndV14RolePermission() {
        final Fixture f = fixture(); f.controller.chooseSingleSpellForEffect(f.spells, f.ability, "Choose", f.params);
        f.controller.chooseSpellAbilitiesForEffect(f.spells, f.ability, "Choose", 1, f.params);
        f.controller.chooseSingleSpellForEffect(f.spells, f.ability, "Choose", f.params);
        final var picks = picks(f); Assert.assertEquals(picks.size(), 3);
        for (int i : List.of(0, 2)) { Assert.assertNull(progress(picks.get(i))); Assert.assertEquals(prompt(picks.get(i)).get("kind"), "SPELL_ABILITY_REQUEST"); }
        Assert.assertEquals(prompt(picks.get(1)).get("kind"), "SPELL_ABILITY_PICK_PROGRESS");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void nestedOperationsRestoreOuterReceiptsAndPlanningProbeCannotClaimTwice() {
        final Fixture f = fixture(); operation(f, () -> {
            pick(f, 0, f.spells.get(0)); operation(f, () -> { pick(f, 0, f.spells.get(1)); return List.of(f.spells.get(1)); });
            AiSpellSelectionHistory.beforePick(f.player, f.ability, f.controller, f.spells, f.params, 1);
            final var selected = AiSpellChoiceRecorder.single(f.player, f.ability, f.spells, f.params, f.controller, () -> {
                AiChoiceRecorder.planning(f.player, () -> AiSpellChoiceRecorder.single(f.player, f.ability, f.spells, f.params, f.controller, () -> f.spells.get(0)));
                return f.spells.get(1);
            }); AiSpellSelectionHistory.returned(f.player, f.ability, f.controller, selected, 2); return f.spells;
        });
        final var picks = picks(f); Assert.assertEquals(picks.stream().limit(3).map(c -> prompt(c).get("attemptIndex")).toList(), List.of(0L, 0L, 1L));
        Assert.assertNotEquals(picks.get(0).get("parentChoiceId"), picks.get(1).get("parentChoiceId"));
        Assert.assertEquals(((Map<?, ?>) ((List<?>) progress(picks.get(2)).get("priorReturns")).get(0)).get("id"), f.spells.get(0).getId());
        Assert.assertNull(progress(picks.get(3))); Assert.assertEquals(prompt(picks.get(3)).get("kind"), "SPELL_ABILITY_REQUEST");
        Assert.assertEquals(input(picks.get(3)).get("stateBasis"), "PLANNING_WORKING_STATE"); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void equalButDifferentArgumentsAndReceiversCannotClaimTheAnnouncedAttempt() {
        final Fixture f = fixture(); operation(f, () -> {
            AiSpellSelectionHistory.beforePick(f.player, f.ability, f.controller, f.spells, f.params, 0);
            AiSpellChoiceRecorder.single(f.player, f.ability, new ArrayList<>(f.spells), f.params, f.controller, () -> f.spells.get(0));
            AiSpellChoiceRecorder.single(f.player, f.ability, f.spells, new HashMap<>(f.params), f.controller, () -> f.spells.get(0));
            AiSpellChoiceRecorder.single(f.player, f.ability, f.spells, f.params, null, () -> f.spells.get(0));
            final var value = AiSpellChoiceRecorder.single(f.player, f.ability, f.spells, f.params, f.controller, () -> f.spells.get(0));
            AiSpellSelectionHistory.returned(f.player, f.ability, f.controller, value, 1); return List.of(value);
        });
        final var picks = picks(f); for (int i = 0; i < 3; i++) { Assert.assertNull(progress(picks.get(i))); }
        Assert.assertEquals(prompt(picks.get(3)).get("attemptIndex"), 0L); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aFailedNestedAdmissionMasksTheOuterFrameAndUnwitnessedHistoryStaysUnavailable() {
        final Fixture f = fixture(); final var broken = new java.util.AbstractList<SpellAbility>() {
            @Override public int size() { return 1; }
            @Override public SpellAbility get(int index) { throw new IllegalStateException("nested options"); }
        };
        operation(f, () -> {
            pick(f, 0, f.spells.get(0));
            AiSpellChoiceRecorder.multiple(f.player, f.ability, broken, 1, f.params, f.controller, () -> { pick(f, 0, f.spells.get(1)); return List.of(f.spells.get(1)); });
            // This return was not announced/claimed; later history must remain explicitly unavailable.
            AiSpellSelectionHistory.beforePick(f.player, f.ability, f.controller, f.spells, f.params, 1);
            AiSpellSelectionHistory.returned(f.player, f.ability, f.controller, f.spells.get(0), 2);
            pick(f, 2, f.spells.get(1)); return List.of(f.spells.get(0), f.spells.get(0), f.spells.get(1));
        });
        final var picks = picks(f); Assert.assertNull(progress(picks.get(1)));
        final var last = picks.get(2); Assert.assertEquals(progress(last).get("receiptReason"), "UNSUPPORTED"); Assert.assertNull(prompt(last));
        Assert.assertNotNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void failedSelectorMetadataConsumesItsDispatchBeforeANestedProbeCanBorrowIt() {
        final Fixture f = fixture(); final AtomicBoolean first = new AtomicBoolean(true);
        final List<SpellAbility> offered = new java.util.AbstractList<>() {
            @Override public int size() { return 2; }
            @Override public SpellAbility get(int index) {
                if (first.getAndSet(false)) { throw new IllegalStateException("first selector metadata"); }
                return f.spells.get(index);
            }
        };
        operation(f, () -> {
            AiSpellSelectionHistory.beforePick(f.player, f.ability, f.controller, offered, f.params, 0);
            final var result = AiSpellChoiceRecorder.single(f.player, f.ability, offered, f.params, f.controller, () -> {
                AiSpellChoiceRecorder.single(f.player, f.ability, offered, f.params, f.controller, () -> f.spells.get(1));
                return f.spells.get(0);
            });
            AiSpellSelectionHistory.returned(f.player, f.ability, f.controller, result, 1);
            pick(f, 1, f.spells.get(1)); return f.spells;
        });
        final var picks = picks(f); Assert.assertEquals(picks.size(), 2);
        Assert.assertNull(progress(picks.get(0))); Assert.assertEquals(prompt(picks.get(0)).get("kind"), "SPELL_ABILITY_REQUEST");
        Assert.assertEquals(prompt(picks.get(1)).get("attemptIndex"), 1L);
        Assert.assertEquals(((Map<?, ?>) ((List<?>) progress(picks.get(1)).get("priorReturns")).get(0)).get("id"), f.spells.get(0).getId());
        Assert.assertNotNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aDispatchAfterDeclineCannotClaimCompleteBaseLoopProgress() {
        final Fixture f = fixture(); operation(f, () -> { pick(f, 0, null); pick(f, 0, f.spells.get(0)); return List.of(f.spells.get(0)); });
        final var second = picks(f).get(1); Assert.assertEquals(progress(second).get("progressReason"), "UNSUPPORTED");
        Assert.assertEquals(progress(second).get("attemptIndex"), 1L); Assert.assertEquals(progress(second).get("priorNonNullReturnCount"), 0L);
        Assert.assertNull(prompt(second)); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void receiptPrefixDoesNotReadThe65thIdentityOrClampEngineResults() {
        final Fixture f = fixture(); final var selected = Mockito.spy(f.spells.get(0)); final long id = selected.getId();
        final List<SpellAbility> result = new ArrayList<>();
        operation(f, () -> {
            for (int i = 0; i < 67; i++) {
                AiSpellSelectionHistory.beforePick(f.player, f.ability, f.controller, f.spells, f.params, i);
                final var returned = AiSpellChoiceRecorder.single(f.player, f.ability, f.spells, f.params, f.controller, () -> selected);
                if (i >= 64) { Mockito.doThrow(new AssertionError("receipt beyond prefix read")).when(selected).getId(); }
                try { AiSpellSelectionHistory.returned(f.player, f.ability, f.controller, returned, i + 1); }
                finally { Mockito.doReturn(id).when(selected).getId(); }
                result.add(returned);
            } return result;
        });
        Assert.assertEquals(result.size(), 67); final var picks = picks(f);
        Assert.assertEquals(((List<?>) prompt(picks.get(64)).get("previousReturns")).size(), 64);
        final var last = picks.get(66); Assert.assertEquals(progress(last).get("attemptIndex"), 66L); Assert.assertEquals(progress(last).get("receiptCoverage"), "PREFIX");
        Assert.assertEquals(((List<?>) progress(last).get("priorReturns")).size(), 64); Assert.assertNull(prompt(last)); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void liveReceiptFailureIsOwnedVisibleAndDoesNotChangeTheActualReturns() {
        final Fixture f = fixture(); final var selected = Mockito.spy(f.spells.get(0)); final long id = selected.getId();
        final var failure = new IllegalStateException("live receipt"); final AtomicBoolean fail = new AtomicBoolean();
        Mockito.doAnswer(call -> { if (fail.getAndSet(false)) { throw failure; } return id; }).when(selected).getId();
        operation(f, () -> {
            AiSpellSelectionHistory.beforePick(f.player, f.ability, f.controller, f.spells, f.params, 0);
            final var value = AiSpellChoiceRecorder.single(f.player, f.ability, f.spells, f.params, f.controller, () -> selected);
            fail.set(true); AiSpellSelectionHistory.returned(f.player, f.ability, f.controller, value, 1);
            pick(f, 1, f.spells.get(1)); return List.of(selected, f.spells.get(1));
        });
        final var last = picks(f).get(1); Assert.assertEquals(progress(last).get("priorNonNullReturnCount"), 1L);
        Assert.assertEquals(progress(last).get("receiptReason"), "CAPTURE_FAILED"); Assert.assertNull(prompt(last)); Assert.assertSame(f.game.getEventObservationFailure(), failure);
    }

    @DataProvider public Object[][] closure() { return new Object[][] {{false, false}, {false, true}, {true, false}, {true, true}}; }
    @Test(dataProvider = "closure", groups = {"UnitTest", "fast"})
    @SuppressWarnings("unchecked")
    public void blockedReceiptCannotHoldClosureOrPublishLateValuesOrFaults(boolean parentClosure, boolean fail) throws Exception {
        final Fixture f = fixture(); final var selected = Mockito.spy(f.spells.get(0)); final long id = selected.getId();
        final AtomicBoolean gate = new AtomicBoolean(); final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Mockito.doAnswer(call -> { if (gate.get()) { entered.countDown(); Assert.assertTrue(release.await(5, TimeUnit.SECONDS));
            if (fail) { throw new IllegalStateException("obsolete receipt"); } } return id; }).when(selected).getId();
        final var parent = new AiDecisionRecorder(f.game, f.player, 1); parent.beginAssessment(f.ability);
        final var field = AiController.class.getDeclaredField("evaluationDecision"); field.setAccessible(true);
        final ThreadLocal<AiDecisionRecorder> evaluation = (ThreadLocal<AiDecisionRecorder>) field.get(f.controller.getAi());
        final var worker = Executors.newSingleThreadExecutor();
        try {
            final var result = worker.submit(() -> { if (parentClosure) { evaluation.set(parent); } try { return operation(f, () -> {
                AiSpellSelectionHistory.beforePick(f.player, f.ability, f.controller, f.spells, f.params, 0);
                final var value = AiSpellChoiceRecorder.single(f.player, f.ability, f.spells, f.params, f.controller, () -> selected);
                gate.set(true); AiSpellSelectionHistory.returned(f.player, f.ability, f.controller, value, 1); return List.of(value);
            }); } finally { evaluation.remove(); } });
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
            if (parentClosure) { Assert.assertNotNull(parent.finish(null, "AI_TIMEOUT", null)); } else { f.game.setRecordAiDecisions(false); }
            release.countDown(); Assert.assertSame(result.get(5, TimeUnit.SECONDS).get(0), selected);
            Assert.assertEquals(f.events.size(), 3); Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void longCountersRefuseOverflowRatherThanWrapping() throws Exception {
        final Fixture f = fixture(); operation(f, () -> {
            try {
                final var active = AiSpellSelectionHistory.class.getDeclaredField("ACTIVE"); active.setAccessible(true);
                final var scope = ((ThreadLocal<?>) active.get(null)).get();
                for (String name : List.of("attemptIndex", "nonNullReturns")) {
                    final var field = scope.getClass().getDeclaredField(name); field.setAccessible(true); field.setLong(scope, Long.MAX_VALUE);
                }
            } catch (Exception error) { throw new AssertionError(error); }
            pick(f, 0, f.spells.get(0)); pick(f, 1, f.spells.get(1)); return f.spells;
        });
        final var first = picks(f).get(0); Assert.assertNull(progress(first).get("attemptIndex")); Assert.assertEquals(progress(first).get("progressReason"), "COUNTER_OVERFLOW");
        final var second = picks(f).get(1); Assert.assertNull(progress(second).get("priorNonNullReturnCount")); Assert.assertNull(prompt(second)); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void changedAndUnauditedControllersRetireOrRefuseTheCapability() {
        final Fixture f = fixture(); operation(f, () -> {
            pick(f, 0, f.spells.get(0));
            f.player.dangerouslySetController(new PlayerControllerAi(f.game, f.player, f.player.getLobbyPlayer()));
            pick(f, 1, f.spells.get(1)); return f.spells;
        }); Assert.assertNull(progress(picks(f).get(1)));
        final Fixture g = fixture(); final var subclass = new PlayerControllerAi(g.game, g.player, g.player.getLobbyPlayer()) { };
        g.player.dangerouslySetController(subclass); subclass.chooseSpellAbilitiesForEffect(g.spells, g.ability, "Choose", 2, g.params);
        Assert.assertTrue(picks(g).stream().allMatch(c -> progress(c) == null));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void missingCollectedRowsDoNotEraseActualReceiptsAndNestedOffScopesMaskThenRestore() {
        final Fixture f = fixture(); operation(f, () -> {
            pick(f, 0, f.spells.get(0)); final Object id = picks(f).get(0).get("choiceId"); f.events.removeIf(c -> id.equals(c.get("choiceId")));
            f.game.setRecordAiDecisions(false);
            operation(f, () -> { f.game.setRecordAiDecisions(true); pick(f, 0, f.spells.get(1)); return List.of(f.spells.get(1)); });
            pick(f, 1, f.spells.get(1)); return f.spells;
        });
        final var picks = picks(f); Assert.assertNull(progress(picks.get(0))); Assert.assertEquals(prompt(picks.get(1)).get("attemptIndex"), 1L);
        Assert.assertEquals(((Map<?, ?>) ((List<?>) progress(picks.get(1)).get("priorReturns")).get(0)).get("id"), f.spells.get(0).getId());
    }

    @DataProvider public Object[][] bypass() { return new Object[][] {{"OFF"}, {"COPY"}, {"HIDDEN"}}; }
    @Test(dataProvider = "bypass", groups = {"UnitTest", "fast"})
    public void bypassedOwnedOperationsCannotReadReceiptIdentities(String mode) {
        final Fixture f = fixture(); final var selected = Mockito.spy(f.spells.get(0));
        Mockito.doThrow(new AssertionError("bypassed spell identity read")).when(selected).getId();
        final List<SpellAbility> forbidden = new java.util.AbstractList<>() {
            @Override public int size() { throw new AssertionError("bypassed options read"); }
            @Override public SpellAbility get(int index) { throw new AssertionError("bypassed options read"); }
        };
        if (mode.equals("OFF")) { f.game.setRecordAiDecisions(false); }
        if (mode.equals("COPY")) { f.game.markAsSimulationCopy(); }
        if (mode.equals("HIDDEN")) { f.ability.getHostCard().turnFaceDown(); }
        final var result = AiSpellChoiceRecorder.multiple(f.player, f.ability, forbidden, 2, f.params, f.controller, () -> {
            AiSpellSelectionHistory.beforePick(f.player, f.ability, f.controller, forbidden, f.params, 0);
            final var value = AiSpellChoiceRecorder.single(f.player, f.ability, forbidden, f.params, f.controller, () -> selected);
            AiSpellSelectionHistory.returned(f.player, f.ability, f.controller, value, 1); return List.of(value);
        });
        Assert.assertSame(result.get(0), selected); Assert.assertNull(f.game.getEventObservationFailure());
        Assert.assertTrue(f.events.stream().filter(c -> "STARTED".equals(c.get("status"))).allMatch(c -> "MASKED_CHOICE".equals(c.get("choiceType"))));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void planningProgressChargesEachReceiptAndUnrelatedSuppliersRemainUninspected() {
        final Fixture f = fixture(); final var state = f.controller.inputContextState();
        final var receipt = AiTargetChoiceRecorder.reference(f.spells.get(0));
        final var request = new AiPromptInput.SpellRequest(new AtomicReference<>(AiSpellRequestInput.capture(f.params)));
        final var empty = new AiSpellSelectionHistory.Prompt(new AtomicReference<>(new AiSpellSelectionHistory.Input(2, 0L, 0L, 0, null, "COMPLETE", null, List.of())), request);
        long before = state.remainingWork(); AiInputContext.capture(f.player, f.ability, "BEFORE_SELECTOR", "PLANNING_WORKING_STATE", () -> true, empty);
        final long base = before - state.remainingWork();
        final var one = new AiSpellSelectionHistory.Prompt(new AtomicReference<>(new AiSpellSelectionHistory.Input(2, 1L, 1L, 1, null, "COMPLETE", null, List.of(receipt))), request);
        before = state.remainingWork(); final var context = AiInputContext.capture(f.player, f.ability, "BEFORE_SELECTOR", "PLANNING_WORKING_STATE", () -> true, one);
        Assert.assertEquals(before - state.remainingWork(), base + 1); Assert.assertEquals(((Map<?, ?>) ((Map<?, ?>) context.get("policy")).get("prompt")).get("kind"), "SPELL_ABILITY_PICK_PROGRESS");
        AiInputContext.capture(f.player, f.ability, "BEFORE_SELECTOR", "PLANNING_WORKING_STATE", () -> true, () -> { throw new AssertionError("unrelated supplier inspected"); });
        Assert.assertNull(f.game.getEventObservationFailure());
    }
}
