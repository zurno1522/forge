package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AiModeInputContextTest extends AITest {
    private record Fixture(Game game, Player ai, SpellAbility ability, List<AbilitySub> options,
            List<Map<String, Object>> events) { }

    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility ability = addCardToZone("Mystic Confluence", ai, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(ai);
        game.setRecordAiDecisions(true);
        final List<Map<String, Object>> events = new ArrayList<>();
        game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { events.add(choice.data()); } });
        return new Fixture(game, ai, ability, new ArrayList<>(ability.getAdditionalAbilityList("Choices")), events);
    }

    private Map<?, ?> entry(final Map<String, Object> start) { return (Map<?, ?>) start.get("entryLocation"); }
    private Map<?, ?> input(final Map<String, Object> start) { return (Map<?, ?>) start.get("inputContext"); }

    @Test(groups = {"UnitTest", "fast"})
    public void operationFreezesItsOwnPlanningViewBeforeTheCallbackMutatesRulesState() {
        final Fixture f = fixture();
        final List<AbilitySub> result = List.of(f.options.get(0));
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertSame(AiModeChoiceRecorder.choose(f.ai, f.ability, f.options, 1, 2, true, () -> {
            calls.incrementAndGet();
            f.ai.setLife(7, null);
            f.game.getPhaseHandler().devModeSet(PhaseType.MAIN2, f.ai);
            return result;
        }), result);
        Assert.assertEquals(calls.get(), 1);
        final var start = f.events.get(0);
        Assert.assertEquals(entry(start).get("kind"), "OPERATION");
        Assert.assertEquals(entry(start).get("phase"), "MAIN1");
        Assert.assertEquals(input(start).get("stateBasis"), "PLANNING_WORKING_STATE");
        final Map<?, ?> policy = (Map<?, ?>) input(start).get("policy");
        Assert.assertEquals(policy.get("phase"), "MAIN1");
        Assert.assertEquals(((Map<?, ?>) ((List<?>) policy.get("players")).get(1)).get("life"), 20);
        Assert.assertEquals(policy.get("prompt"), Map.of("kind", "MODE_BOUNDS", "minimumPicks", 1, "maximumPicks", 2, "allowRepeat", true));
        Assert.assertEquals(((Map<?, ?>) ((Map<?, ?>) input(start).get("diagnostics")).get("optionBindings")).get("coverage"), "UNSUPPORTED");
        Assert.expectThrows(UnsupportedOperationException.class, () -> ((List<?>) policy.get("players")).clear());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualCachedControllerReturnDoesNotRunASecondSelector() {
        final Fixture f = fixture();
        final List<AbilitySub> cached = List.of(f.options.get(1), f.options.get(1));
        f.ability.setChosenList(cached);
        Assert.assertSame(f.ai.getController().chooseModeForAbility(f.ability, f.options, 0, 3, true), cached);
        Assert.assertEquals(f.events.size(), 2);
        Assert.assertEquals(f.events.get(0).get("boundary"), "CONTROLLER_MODES");
        Assert.assertEquals(entry(f.events.get(0)).get("kind"), "OPERATION");
        Assert.assertEquals(((Map<?, ?>) f.events.get(1).get("selection")).get("selectedCount"), 2);
        Assert.assertSame(f.ability.getChosenList(), cached);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void interceptedControllerPlanIsAnOperationAndDoesNotReadOrReplaceTheCachedPlan() {
        final Fixture f = fixture();
        final List<AbilitySub> cached = List.of(f.options.get(0));
        final List<AbilitySub> intercepted = List.of(f.options.get(1));
        f.ability.setChosenList(cached);
        final AtomicInteger calls = new AtomicInteger();
        final var picker = ((PlayerControllerAi) f.ai.getController()).getAi().getSimulationPicker();
        picker.setInterceptor(new forge.ai.simulation.SpellAbilityChoicesIterator(null) {
            @Override public List<AbilitySub> chooseModesForAbility(final SpellAbility ability, final List<AbilitySub> choices,
                    final int min, final int max, final boolean repeat) {
                calls.incrementAndGet();
                Assert.assertSame(ability, f.ability); Assert.assertSame(choices, f.options);
                Assert.assertEquals(min, 0); Assert.assertEquals(max, 2); Assert.assertTrue(repeat);
                return intercepted;
            }
        });
        try {
            Assert.assertSame(f.ai.getController().chooseModeForAbility(f.ability, f.options, 0, 2, true), intercepted);
        } finally { picker.setInterceptor(null); }
        Assert.assertEquals(calls.get(), 1);
        Assert.assertSame(f.ability.getChosenList(), cached);
        Assert.assertEquals(f.events.size(), 2);
        Assert.assertEquals(entry(f.events.get(0)).get("kind"), "OPERATION");
        Assert.assertEquals(((Map<?, ?>) ((List<?>) ((Map<?, ?>) f.events.get(1).get("selection")).get("modes")).get(0)).get("optionIndex"), 1);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void delegatedModeControllerKeepsTheActualChooserSeparateFromTheActivator() {
        final Game game = initAndCreateThreePlayerGame();
        final Player activator = game.getPlayers().get(0), chooser = game.getPlayers().get(1);
        final SpellAbility ability = addCardToZone("Mystic Confluence", activator, ZoneType.Battlefield).getFirstSpellAbility();
        ability.setActivatingPlayer(activator);
        final List<AbilitySub> options = ability.getAdditionalAbilityList("Choices");
        final List<AbilitySub> cached = List.of(options.get(0)); ability.setChosenList(cached);
        final List<Map<String, Object>> starts = new ArrayList<>();
        game.setRecordAiDecisions(true);
        game.observeEventEmission(event -> {
            if (event instanceof AiChoiceEvent choice && "STARTED".equals(choice.data().get("status"))) { starts.add(choice.data()); }
        });
        Assert.assertSame(chooser.getController().chooseModeForAbility(ability, options, 1, 1, false), cached);
        Assert.assertEquals(starts.size(), 1);
        Assert.assertEquals(starts.get(0).get("chooserPlayerId"), chooser.getId());
        Assert.assertEquals(((Map<?, ?>) input(starts.get(0)).get("perspective")).get("chooserPlayerId"), chooser.getId());
        Assert.assertEquals(input(starts.get(0)).get("stateBasis"), "PLANNING_WORKING_STATE");
        Assert.assertSame(ability.getActivatingPlayer(), activator);
        Assert.assertNull(game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void nestedHeuristicAndTargetsHaveIndependentEntriesAndInheritPlanningUntilAnErrorUnwinds() {
        final Fixture f = fixture();
        final Card target = addCardToZone("Grizzly Bears", f.game.getPlayers().get(0), ZoneType.Battlefield);
        final SpellAbility targeting = addCardToZone("Doom Blade", f.ai, ZoneType.Hand).getFirstSpellAbility();
        targeting.setActivatingPlayer(f.ai);
        final var failure = new IllegalArgumentException("original mode failure");
        Assert.assertSame(Assert.expectThrows(IllegalArgumentException.class, () ->
                AiModeChoiceRecorder.choose(f.ai, f.ability, f.options, 1, 1, false, () -> {
                    f.ai.setLife(11, null);
                    AiModeChoiceRecorder.evaluate(f.ai, f.ability, f.options, 1, 1, false, () -> {
                        AiTargetChoiceRecorder.choose(f.ai, targeting, List.of(target), "DESTROY_REMOVAL_POOL", ignored -> target);
                        throw failure;
                    });
                    throw new AssertionError("unreachable");
                })), failure);
        final var outer = f.events.get(0);
        final var inner = f.events.get(1);
        final var nested = f.events.get(2);
        Assert.assertEquals(entry(outer).get("kind"), "OPERATION");
        Assert.assertEquals(entry(inner).get("kind"), "SELECTOR");
        Assert.assertEquals(inner.get("parentChoiceId"), outer.get("choiceId"));
        Assert.assertEquals(nested.get("parentChoiceId"), inner.get("choiceId"));
        Assert.assertEquals(((Map<?, ?>) ((List<?>) ((Map<?, ?>) input(inner).get("policy")).get("players")).get(1)).get("life"), 11);
        Assert.assertEquals(input(nested).get("stateBasis"), "PLANNING_WORKING_STATE");
        Assert.assertEquals(f.events.get(4).get("status"), "ERROR");
        Assert.assertEquals(f.events.get(5).get("status"), "ERROR");
        AiTargetChoiceRecorder.choose(f.ai, targeting, List.of(target), "DESTROY_REMOVAL_POOL", ignored -> target);
        Assert.assertEquals(input(f.events.get(6)).get("stateBasis"), "RULES_STATE");
        Assert.assertNull(f.events.get(6).get("parentChoiceId"));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void modeOperationDuringActualStackResolutionStillDeclaresPlanning() {
        final Fixture f = fixture();
        f.ability.setChosenList(List.of(f.options.get(0)));
        final SpellAbility resolving = new SpellAbility.EmptySa(f.ability.getHostCard()) {
            @Override public void resolve() {
                Assert.assertTrue(f.game.getStack().isResolving());
                f.ai.getController().chooseModeForAbility(f.ability, f.options, 1, 1, false);
            }
        };
        f.game.getStack().add(resolving);
        f.game.getStack().resolveStack();
        Assert.assertEquals(f.events.get(0).get("stage"), "RESOLUTION");
        Assert.assertEquals(input(f.events.get(0)).get("stateBasis"), "PLANNING_WORKING_STATE");
        Assert.assertTrue((boolean) ((Map<?, ?>) ((Map<?, ?>) input(f.events.get(0)).get("policy")).get("stack")).get("isResolving"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void exhaustedViewDoesNotLoseTheModeOperationOrItsReturnedPlan() throws Exception {
        final Fixture f = fixture();
        final var state = ((PlayerControllerAi) f.ai.getController()).inputContextState();
        final var remaining = AiInputContext.State.class.getDeclaredField("remaining"); remaining.setAccessible(true);
        ((AtomicLong) remaining.get(state)).set(0);
        final List<AbilitySub> result = List.of(f.options.get(0));
        Assert.assertSame(AiModeChoiceRecorder.choose(f.ai, f.ability, f.options, 1, 1, false, () -> result), result);
        Assert.assertEquals(entry(f.events.get(0)).get("kind"), "OPERATION");
        Assert.assertEquals(input(f.events.get(0)).get("status"), "UNAVAILABLE");
        Assert.assertTrue(((List<?>) input(f.events.get(0)).get("omissions")).contains(Map.of("component", "CONTEXT", "reason", "BUDGET_EXHAUSTED")));
        Assert.assertEquals(f.events.get(1).get("status"), "COMPLETED");
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] bypass() { return new Object[][] {{"HIDDEN"}, {"DISABLED"}, {"COPY"}}; }

    @Test(dataProvider = "bypass", groups = {"UnitTest", "fast"})
    public void unavailableModesNeverInspectTheOfferOrRerunTheCallback(final String reason) {
        final Fixture f = fixture();
        if ("HIDDEN".equals(reason)) { f.ability.getHostCard().turnFaceDown(); }
        if ("DISABLED".equals(reason)) { f.game.setRecordAiDecisions(false); }
        if ("COPY".equals(reason)) { f.game.markAsSimulationCopy(); }
        final List<AbilitySub> privateOffer = new AbstractList<>() {
            @Override public AbilitySub get(final int index) { throw new AssertionError("private offer"); }
            @Override public int size() { throw new AssertionError("private offer"); }
        };
        final AtomicInteger calls = new AtomicInteger();
        final List<AbilitySub> result = List.of(f.options.get(0));
        Assert.assertSame(AiModeChoiceRecorder.choose(f.ai, f.ability, privateOffer, 1, 1, false,
                () -> { calls.incrementAndGet(); return result; }), result);
        Assert.assertEquals(calls.get(), 1);
        if ("HIDDEN".equals(reason)) {
            Assert.assertEquals(f.events.size(), 2);
            Assert.assertEquals(f.events.get(0).get("boundary"), "MASKED_CHOICE");
            Assert.assertEquals(entry(f.events.get(0)).get("reason"), "HIDDEN");
            Assert.assertEquals(input(f.events.get(0)).get("timing"), "UNESTABLISHED");
            Assert.assertEquals(input(f.events.get(0)).get("profile"), "PERMITTED_CHOICE_VIEW_V21");
            Assert.assertNull(f.events.get(1).get("selection"));
        } else { Assert.assertTrue(f.events.isEmpty()); }
        Assert.assertNull(f.game.getEventObservationFailure());
    }
}
