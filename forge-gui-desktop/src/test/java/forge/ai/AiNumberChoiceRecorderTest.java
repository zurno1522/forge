package forge.ai;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AiNumberChoiceRecorderTest extends AITest {
    private record Fixture(Game game, Player ai, SpellAbility ability, List<Map<String, Object>> events) { }

    private Fixture fixture(final String name, final int mountains) {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (int i = 0; i < mountains; i++) { addCardToZone("Mountain", ai, ZoneType.Battlefield); }
        final SpellAbility sa = addCardToZone(name, ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        game.setRecordAiDecisions(true);
        final List<Map<String, Object>> events = new ArrayList<>();
        game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { events.add(choice.data()); } });
        return new Fixture(game, ai, sa, events);
    }

    @DataProvider public Object[][] xCosts() {
        return new Object[][] {{"Fireball", 4, 3}, {"Hangarback Walker", 4, 2}, {"Fireball", 1, 0}};
    }

    @Test(dataProvider = "xCosts", groups = {"UnitTest", "fast"})
    public void realXHeuristicRecordsItsBoundAndActualValueIncludingDoubleXAndZero(final String card, final int mana, final int expected) {
        final Fixture f = fixture(card, mana);
        final int chosen = ComputerUtilCost.setMaxXValue(f.ability, f.ai, false);
        Assert.assertEquals(chosen, expected);
        Assert.assertEquals(f.ability.getXManaCostPaid(), Integer.valueOf(expected));
        final var start = f.events.stream().filter(e -> "MAX_X_HEURISTIC".equals(e.get("boundary"))).findFirst().orElseThrow();
        final var terminal = f.events.stream().filter(e -> start.get("choiceId").equals(e.get("choiceId")) && !"STARTED".equals(e.get("status"))).findFirst().orElseThrow();
        Assert.assertEquals(((Map<?, ?>) terminal.get("selection")).get("value"), expected);
        Assert.assertEquals(((Map<?, ?>) start.get("options")).get("maximum"), expected);
        Assert.assertEquals(((Map<?, ?>) start.get("options")).get("coverage"), "HEURISTIC_BOUND_NOT_LEGAL_SET");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void unknownRefinementAlternativesAndFinalCandidateOutcomesAreNotInferredFromMana() {
        final Fixture f = fixture("Fireball", 4);
        f.ability.setXManaCostPaid(3);
        AiNumberChoiceRecorder.refineX(f.ai, f.ability, 1);
        final var options = (Map<?, ?>) f.events.get(0).get("options");
        Assert.assertEquals(options.get("coverage"), "OBSERVED_SELECTION_ONLY");
        Assert.assertNull(options.get("maximum"));
        Assert.assertEquals(options.get("previousValue"), 3);
        Assert.assertEquals(f.ability.getXManaCostPaid(), Integer.valueOf(1));
        final var recorder = new AiDecisionRecorder(f.game, f.ai, 1);
        recorder.beginAssessment(f.ability); recorder.evaluated(f.ability, "WillPlay");
        final var event = recorder.finish(List.of(f.ability), "COMPLETED", null);
        Assert.assertEquals(((Map<?, ?>) ((List<?>) event.data().get("candidates")).get(0)).get("evaluatedX"), 1);
        Assert.assertEquals(((Map<?, ?>) ((List<?>) event.data().get("selectedActions")).get(0)).get("evaluatedX"), 1);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void rangeRecordingDoesNotClampAnUnexpectedReturnOrInspectADeclinedSelection() {
        final Fixture f = fixture("Fireball", 1);
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertEquals(AiNumberChoiceRecorder.range(f.ai, f.ability, "CONTROLLER_NUMBER_RANGE", 0, 2,
                () -> { calls.incrementAndGet(); return 3; }), Integer.valueOf(3));
        Assert.assertEquals(calls.get(), 1);
        Assert.assertEquals(((Map<?, ?>) f.events.get(1).get("selection")).get("domainMatch"), "OUTSIDE_OBSERVED_DOMAIN");
        Assert.assertNull(AiNumberChoiceRecorder.range(f.ai, f.ability, "CONTROLLER_ANNOUNCEMENT", 0, 2, () -> null));
        Assert.assertEquals(f.events.get(3).get("status"), "DECLINED");
        Assert.assertNull(f.events.get(3).get("selection"));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void discretePrefixesAndDuplicateValuesNeverInventAnIndex() {
        final Fixture f = fixture("Fireball", 1);
        final List<Integer> many = new ArrayList<>();
        for (int i = 0; i < 300; i++) { many.add(i); }
        Assert.assertEquals(AiNumberChoiceRecorder.discrete(f.ai, f.ability, many, () -> 299), 299);
        Assert.assertEquals(((Map<?, ?>) f.events.get(0).get("options")).get("truncated"), true);
        Assert.assertEquals(((Map<?, ?>) f.events.get(1).get("selection")).get("domainMatch"), "OUTSIDE_CAPTURED_OPTIONS");
        Assert.assertNull(((Map<?, ?>) f.events.get(1).get("selection")).get("optionIndex"));
        Assert.assertEquals(AiNumberChoiceRecorder.discrete(f.ai, f.ability, List.of(1, 1, 2), () -> 1), 1);
        Assert.assertEquals(((Map<?, ?>) f.events.get(3).get("selection")).get("domainMatch"), "AMBIGUOUS_CAPTURED_OPTIONS");
        Assert.assertNull(((Map<?, ?>) f.events.get(3).get("selection")).get("optionIndex"));
    }
}
