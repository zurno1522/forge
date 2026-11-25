package forge.ai;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AiModePromptInputTest extends AITest {
    private record Fixture(Game game, Player ai, SpellAbility ability, List<AbilitySub> options,
            List<Map<String, Object>> starts) { }

    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility ability = addCardToZone("Mystic Confluence", ai, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(ai);
        final List<Map<String, Object>> starts = new ArrayList<>();
        game.setRecordAiDecisions(true);
        game.observeEventEmission(event -> {
            if (event instanceof AiChoiceEvent choice && "STARTED".equals(choice.data().get("status"))) { starts.add(choice.data()); }
        });
        return new Fixture(game, ai, ability, ability.getAdditionalAbilityList("Choices"), starts);
    }

    private Map<?, ?> input(final Fixture f, final int index) { return (Map<?, ?>) f.starts.get(index).get("inputContext"); }
    private Map<?, ?> policy(final Fixture f, final int index) { return (Map<?, ?>) input(f, index).get("policy"); }

    @DataProvider public Object[][] bounds() {
        return new Object[][] {{0, 3, true}, {2, 1, false}, {Integer.MIN_VALUE, Integer.MAX_VALUE, true}};
    }

    @Test(dataProvider = "bounds", groups = {"UnitTest", "fast"})
    public void controllerRetainsTheExactReceivedBoundsWithoutNormalizingOrSelectingAgain(final int min, final int max, final boolean repeat) {
        final Fixture f = fixture();
        final List<AbilitySub> cached = List.of(f.options.get(0), f.options.get(0)); f.ability.setChosenList(cached);
        Assert.assertSame(f.ai.getController().chooseModeForAbility(f.ability, f.options, min, max, repeat), cached);
        Assert.assertEquals(f.starts.size(), 1);
        Assert.assertEquals(input(f, 0).get("schemaVersion"), 9);
        Assert.assertEquals(input(f, 0).get("profile"), "PERMITTED_CHOICE_VIEW_V9");
        Assert.assertEquals(input(f, 0).get("stateBasis"), "PLANNING_WORKING_STATE");
        Assert.assertEquals(((Map<?, ?>) input(f, 0).get("coverage")).get("prompt"), "RECEIVED_MODE_BOUNDS_V1");
        Assert.assertEquals(policy(f, 0).get("prompt"), Map.of("kind", "MODE_BOUNDS", "minimumPicks", min,
                "maximumPicks", max, "allowRepeat", repeat));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void heuristicProjectsOnlyItsReceivedBoundsBeforeMutatingTheStoredPlan() {
        final Fixture f = fixture(); final AtomicInteger calls = new AtomicInteger();
        final AiAbilityDecision result = new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        final List<AbilitySub> selected = new ArrayList<>(List.of(f.options.get(0)));
        Assert.assertSame(AiModeChoiceRecorder.evaluate(f.ai, f.ability, f.options, 1, 2, false, () -> {
            calls.incrementAndGet(); f.ability.setChosenList(selected); return result;
        }), result);
        Assert.assertEquals(calls.get(), 1);
        Assert.assertEquals(f.starts.get(0).get("boundary"), "CHARM_MODE_HEURISTIC");
        Assert.assertEquals(input(f, 0).get("timing"), "BEFORE_SELECTOR");
        final Map<?, ?> prompt = (Map<?, ?>) policy(f, 0).get("prompt");
        Assert.assertEquals(prompt, Map.of("kind", "MODE_BOUNDS", "minimumPicks", 1, "maximumPicks", 2, "allowRepeat", false));
        selected.clear();
        Assert.assertEquals(prompt.get("minimumPicks"), 1);
        Assert.expectThrows(UnsupportedOperationException.class, () -> prompt.clear());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void differentOfferedModeMembershipAndOrderDoNotBecomePolicyFeatures() {
        final Fixture f = fixture();
        AiModeChoiceRecorder.choose(f.ai, f.ability, f.options, 0, 3, true, List::of);
        AiModeChoiceRecorder.choose(f.ai, f.ability, List.of(f.options.get(1), f.options.get(0), f.options.get(0)), 0, 3, true, List::of);
        Assert.assertEquals(policy(f, 0), policy(f, 1));
        Assert.assertNotEquals(f.starts.get(0).get("options"), f.starts.get(1).get("options"));
        Assert.assertEquals(((Map<?, ?>) ((Map<?, ?>) input(f, 0).get("diagnostics")).get("optionBindings")).get("coverage"), "UNSUPPORTED");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void receivedBoundsUseTheExistingWorkAllowanceAndDoNotAuthorizeOtherPlanningSuppliers() {
        final Fixture f = fixture();
        final var state = ((PlayerControllerAi) f.ai.getController()).inputContextState();
        final long before = state.remainingWork();
        AiInputContext.capture(f.ai, f.ability, "BEFORE_OPERATION", "PLANNING_WORKING_STATE", () -> true);
        final long baseWork = before - state.remainingWork();
        final long modeBefore = state.remainingWork();
        final var mode = AiInputContext.capture(f.ai, f.ability, "BEFORE_OPERATION", "PLANNING_WORKING_STATE", () -> true,
                new AiPromptInput.ModeBounds(0, 3, true));
        Assert.assertEquals(modeBefore - state.remainingWork(), baseWork + 1);
        Assert.assertEquals(((Map<?, ?>) mode.get("coverage")).get("prompt"), "RECEIVED_MODE_BOUNDS_V1");
        final var refused = AiInputContext.capture(f.ai, f.ability, "BEFORE_SELECTOR", "PLANNING_WORKING_STATE", () -> true,
                () -> { throw new AssertionError("Other hypothetical suppliers remain uninspected"); });
        Assert.assertNull(((Map<?, ?>) refused.get("policy")).get("prompt"));
        Assert.assertEquals(((Map<?, ?>) refused.get("coverage")).get("prompt"), "UNAVAILABLE");
        Assert.assertTrue(((List<?>) refused.get("omissions")).contains(Map.of("component", "PROMPT", "reason", "UNSUPPORTED")));
        Assert.assertNull(f.game.getEventObservationFailure());
    }
}
