package forge.ai;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollection;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AiPromptInputContextTest extends AITest {
    private record Fixture(Game game, Player chooser, SpellAbility ability, List<Map<String, Object>> events) { }

    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player chooser = game.getPlayers().get(1);
        final SpellAbility ability = addCardToZone("Fireball", chooser, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(chooser); game.setRecordAiDecisions(true);
        final List<Map<String, Object>> events = new ArrayList<>();
        game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { events.add(choice.data()); } });
        return new Fixture(game, chooser, ability, events);
    }

    private Map<?, ?> context(final Fixture f, final int index) { return (Map<?, ?>) f.events.get(index).get("inputContext"); }
    private Map<?, ?> policy(final Map<?, ?> context) { return (Map<?, ?>) context.get("policy"); }
    private Map<?, ?> prompt(final Fixture f, final int index) { return (Map<?, ?>) policy(context(f, index)).get("prompt"); }

    @Test(groups = {"UnitTest", "fast"})
    public void actualControllerRangesRetainArgumentsWithoutExpandingOrNormalizingThem() {
        final Fixture f = fixture();
        Assert.assertEquals(f.chooser.getController().chooseNumber(f.ability, "unrecorded title", Integer.MIN_VALUE, Integer.MAX_VALUE), Integer.MAX_VALUE);
        Assert.assertEquals(prompt(f, 0), Map.of("kind", "NUMBER_RANGE", "minimum", Integer.MIN_VALUE, "maximum", Integer.MAX_VALUE));
        Assert.assertEquals(context(f, 0).get("timing"), "BEFORE_SELECTOR");
        f.chooser.getController().chooseNumber(f.ability, "another title", 9, 3, Map.of("unrecorded", "private detail"));
        Assert.assertEquals(prompt(f, 2), Map.of("kind", "NUMBER_RANGE", "minimum", 9, "maximum", 3));
        Assert.assertFalse(f.events.toString().contains("unrecorded"));
        Assert.assertFalse(f.events.toString().contains("private detail"));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualDiscreteControllerPreservesOrderDuplicatesAndTheSuppliedPlayerBeforeMutation() {
        final Fixture f = fixture(); final List<Integer> values = new ArrayList<>(List.of(-2, 7, 7, 0));
        Assert.assertEquals(f.chooser.getController().chooseNumber(f.ability, "not a feature", values, f.game.getPlayers().get(0)), -2);
        final Map<?, ?> prompt = prompt(f, 0);
        Assert.assertEquals(prompt.get("values"), List.of(-2, 7, 7, 0));
        Assert.assertEquals(prompt.get("relatedPlayer"), "p0");
        values.clear();
        Assert.assertEquals(prompt.get("values"), List.of(-2, 7, 7, 0));
        Assert.expectThrows(UnsupportedOperationException.class, () -> ((List<?>) prompt.get("values")).clear());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualSingleEntityControllerRetainsLocalOptionalityAndRelatedPlayer() {
        final Fixture f = fixture(); final Player opponent = f.game.getPlayers().get(0);
        Assert.assertSame(f.chooser.getController().chooseSingleEntityForEffect(new FCollection<>(List.of(opponent)), null,
                f.ability, "private rendered title", true, opponent, Map.of("unrecorded", "private detail")), opponent);
        Assert.assertEquals(prompt(f, 0), Map.of("kind", "SINGLE_ENTITY", "minimumPicks", 0, "maximumPicks", 1,
                "optional", true, "relatedPlayer", "p0"));
        Assert.assertFalse(policy(context(f, 0)).toString().contains("private detail"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void hiddenEntityPoolIdentityMembershipAndOrderCannotChangeThePolicyProjection() {
        Map<?, ?> baseline = null;
        for (final boolean alternate : List.of(false, true)) {
            final Fixture f = fixture(); final Player opponent = f.game.getPlayers().get(0);
            final var first = addCardToZone(alternate ? "Shivan Dragon" : "Black Lotus", opponent, ZoneType.Hand);
            final var second = addCardToZone(alternate ? "Black Lotus" : "Shivan Dragon", opponent, ZoneType.Hand);
            final var options = alternate ? List.of(second, first) : List.of(first);
            AiEffectChoiceRecorder.single(f.chooser, f.ability, options, false, null, () -> first);
            final Map<?, ?> policy = policy(context(f, 0));
            Assert.assertEquals(prompt(f, 0).get("minimumPicks"), 1);
            Assert.assertNull(prompt(f, 0).get("relatedPlayer"));
            if (baseline == null) { baseline = policy; } else { Assert.assertEquals(policy, baseline); }
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void anOversizedNumericDomainIsWhollyUnavailableAndDoesNotChangeTheCallback() {
        final Fixture f = fixture(); final List<Integer> values = new ArrayList<>();
        for (int i = 0; i <= AiInputContext.MAX_PROMPT_VALUES; i++) { values.add(i); }
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertEquals(AiNumberChoiceRecorder.discrete(f.chooser, f.ability, values,
                () -> { calls.incrementAndGet(); return 64; }), 64);
        Assert.assertEquals(calls.get(), 1);
        Assert.assertNull(prompt(f, 0));
        Assert.assertTrue(context(f, 0).get("omissions").toString().contains("OUTSIDE_CAPTURED_PREFIX"));
        Assert.assertEquals(((List<?>) ((Map<?, ?>) f.events.get(0).get("options")).get("items")).size(), 65);
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void planningDoesNotInspectHypotheticalPromptArguments() {
        final Fixture f = fixture();
        final Map<String, Object> captured = AiInputContext.capture(f.chooser, f.ability, "BEFORE_SELECTOR",
                "PLANNING_WORKING_STATE", () -> true, () -> { throw new AssertionError("hypothetical domain inspected"); });
        Assert.assertNull(policy(captured).get("prompt"));
        Assert.assertTrue(captured.get("omissions").toString().contains("PROMPT"));
        Assert.assertEquals(((Map<?, ?>) captured.get("coverage")).get("prompt"), "UNAVAILABLE");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aForeignPlayerCannotBindByAnEqualEngineId() {
        final Fixture f = fixture(); final Game other = initAndCreateGame();
        final Player foreign = other.getPlayers().get(0);
        Assert.assertEquals(foreign.getId(), f.game.getPlayers().get(0).getId());
        AiNumberChoiceRecorder.discrete(f.chooser, f.ability, List.of(2), foreign, () -> 2);
        Assert.assertNull(prompt(f, 0));
        Assert.assertTrue(context(f, 0).get("omissions").toString().contains("UNRESOLVED_BINDING"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void callbackMutationAndExceptionCannotRewriteTheEntryPrompt() {
        final Fixture f = fixture(); final List<Integer> values = new ArrayList<>(List.of(3, 1));
        final var failure = new IllegalStateException("original callback"); final AtomicInteger calls = new AtomicInteger();
        Assert.assertSame(Assert.expectThrows(IllegalStateException.class, () ->
                AiNumberChoiceRecorder.discrete(f.chooser, f.ability, values, () -> {
                    calls.incrementAndGet(); values.clear(); throw failure;
                })), failure);
        Assert.assertEquals(calls.get(), 1);
        Assert.assertEquals(prompt(f, 0).get("values"), List.of(3, 1));
        Assert.assertEquals(f.events.get(1).get("status"), "ERROR");
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] disabled() { return new Object[][] {{false}, {true}}; }

    @Test(dataProvider = "disabled", groups = {"UnitTest", "fast"})
    public void disabledAndCopiedCallbacksNeverInspectPromptInputs(final boolean copy) {
        final Fixture f = fixture();
        if (copy) { f.game.markAsSimulationCopy(); } else { f.game.setRecordAiDecisions(false); }
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertEquals(AiChoiceRecorder.observeInput(f.chooser, f.ability, "NUMBER_SELECTION", null,
                "CONTROLLER_NUMBER_RANGE", "BEFORE_SELECTOR", () -> { throw new AssertionError("options"); },
                () -> { calls.incrementAndGet(); return 1; }, value -> value,
                () -> { throw new AssertionError("prompt"); }), Integer.valueOf(1));
        Assert.assertEquals(calls.get(), 1); Assert.assertTrue(f.events.isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void promptFailureRemainsVisibleWithoutSuppressingTheBaseChoice() {
        final Fixture f = fixture(); final var failure = new IllegalStateException("private prompt detail");
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertEquals(AiChoiceRecorder.observeInput(f.chooser, f.ability, "NUMBER_SELECTION", null,
                "CONTROLLER_NUMBER_RANGE", "BEFORE_SELECTOR", () -> Map.of("coverage", "OBSERVED_RANGE", "minimum", 0, "maximum", 2),
                () -> { calls.incrementAndGet(); return 1; }, value -> value, () -> { throw failure; }), Integer.valueOf(1));
        Assert.assertEquals(calls.get(), 1); Assert.assertEquals(f.events.size(), 2);
        Assert.assertEquals(context(f, 0).get("status"), "UNAVAILABLE");
        Assert.assertNull(context(f, 0).get("policy"));
        Assert.assertSame(f.game.getEventObservationFailure(), failure);
        Assert.assertFalse(f.events.toString().contains("private prompt detail"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void eachNumericValueConsumesTheExistingTraversalAllowance() {
        final Fixture f = fixture(); final var state = ((PlayerControllerAi) f.chooser.getController()).inputContextState();
        final long before = state.remainingWork();
        AiInputContext.capture(f.chooser, f.ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true, () -> AiPromptInput.range(0, 63));
        final long rangeWork = before - state.remainingWork(); final long afterRange = state.remainingWork();
        final List<Integer> values = new ArrayList<>();
        for (int i = 0; i < AiInputContext.MAX_PROMPT_VALUES; i++) { values.add(i); }
        final var captured = AiInputContext.capture(f.chooser, f.ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true,
                () -> AiPromptInput.numbers(values, null));
        Assert.assertEquals(((List<?>) ((Map<?, ?>) policy(captured).get("prompt")).get("values")).size(), 64);
        Assert.assertEquals(afterRange - state.remainingWork() - rangeWork, 64L);
    }
}
