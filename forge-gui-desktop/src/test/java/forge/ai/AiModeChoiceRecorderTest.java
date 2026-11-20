package forge.ai;

import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AiModeChoiceRecorderTest extends AITest {
    private record Fixture(Game game, Player ai, SpellAbility ability, List<AbilitySub> options, List<Map<String, Object>> events) { }

    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility sa = addCardToZone("Mystic Confluence", ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        game.setRecordAiDecisions(true);
        final List<Map<String, Object>> events = new ArrayList<>();
        game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { events.add(choice.data()); } });
        return new Fixture(game, ai, sa, new ArrayList<>(sa.getAdditionalAbilityList("Choices")), events);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void repeatedChoicesAndMutableOffersArePreservedWithoutRerunningOrDeduplicating() {
        final Fixture f = fixture();
        final AbilitySub mode = f.options.get(0);
        final List<AbilitySub> selected = new ArrayList<>(List.of(mode, mode));
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertSame(AiModeChoiceRecorder.choose(f.ai, f.ability, f.options, 0, 3, true, () -> {
            calls.incrementAndGet(); f.options.clear(); return selected;
        }), selected);
        Assert.assertEquals(calls.get(), 1);
        Assert.assertEquals(f.events.size(), 2);
        final var offered = (Map<?, ?>) f.events.get(0).get("options");
        Assert.assertTrue(((List<?>) offered.get("items")).size() > 1);
        final var choice = (Map<?, ?>) f.events.get(1).get("selection");
        Assert.assertEquals(choice.get("selectedCount"), 2);
        Assert.assertEquals(((List<?>) choice.get("modes")).size(), 2);
        for (final Object ref : (List<?>) choice.get("modes")) {
            Assert.assertEquals(((Map<?, ?>) ref).get("optionIndex"), 0);
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void filteredOfferIndicesRemainDistinctFromOriginalDefinitionIndices() {
        final Fixture f = fixture();
        f.options.remove(0);
        final AbilitySub selected = f.options.get(0);
        AiModeChoiceRecorder.choose(f.ai, f.ability, f.options, 1, 1, false, () -> List.of(selected));
        final var choice = (Map<?, ?>) f.events.get(1).get("selection");
        final var reference = (Map<?, ?>) ((List<?>) choice.get("modes")).get(0);
        Assert.assertEquals(reference.get("optionIndex"), 0);
        Assert.assertEquals(reference.get("definitionIndex"), 1);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void copiedModesStayUnmappedInsteadOfMatchingTextOrTheMutableCharmOrderTag() {
        final Fixture f = fixture();
        f.options.get(0).setSVar("CharmOrder", "1");
        final AbilitySub copy = (AbilitySub) f.options.get(0).copy(f.ai);
        final List<AbilitySub> result = List.of(copy);
        Assert.assertSame(AiModeChoiceRecorder.choose(f.ai, f.ability, f.options, 1, 1, false, () -> result), result);
        final var value = (Map<?, ?>) f.events.get(1).get("selection");
        final var ref = (Map<?, ?>) ((List<?>) value.get("modes")).get(0);
        Assert.assertNull(ref.get("optionIndex"));
        Assert.assertNull(ref.get("definitionIndex"));
        Assert.assertEquals(ref.get("abilityId"), copy.getId());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void largeRepeatedModeListsDeclareBothCapturedPrefixesAndActualCounts() {
        final Fixture f = fixture();
        final List<AbilitySub> many = Collections.nCopies(300, f.options.get(0));
        Assert.assertSame(AiModeChoiceRecorder.choose(f.ai, f.ability, many, 0, 300, true, () -> many), many);
        Assert.assertEquals(((Map<?, ?>) f.events.get(0).get("options")).get("truncated"), true);
        final var value = (Map<?, ?>) f.events.get(1).get("selection");
        Assert.assertEquals(value.get("selectedCount"), 300);
        Assert.assertEquals(((List<?>) value.get("modes")).size(), 256);
        Assert.assertEquals(value.get("truncated"), true);
        Assert.assertNull(((Map<?, ?>) ((List<?>) value.get("modes")).get(0)).get("optionIndex"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void decliningDiffersFromAnAvailableEmptyModePlan() {
        final Fixture f = fixture();
        Assert.assertNull(AiModeChoiceRecorder.choose(f.ai, f.ability, f.options, 0, 1, false, () -> null));
        Assert.assertEquals(f.events.get(1).get("status"), "DECLINED");
        Assert.assertNull(f.events.get(1).get("selection"));
        Assert.assertTrue(AiModeChoiceRecorder.choose(f.ai, f.ability, f.options, 0, 1, false, List::of).isEmpty());
        final var value = (Map<?, ?>) f.events.get(3).get("selection");
        Assert.assertEquals(value.get("available"), true);
        Assert.assertEquals(value.get("selectedCount"), 0);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void realAzoriusCharmEvaluationRecordsItsActualModeOptionsAndStoredPlan() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        for (final Player p : game.getPlayers()) { fillLibrary(p, 20); }
        addCardToZone("Plains", ai, ZoneType.Battlefield);
        addCardToZone("Island", ai, ZoneType.Battlefield);
        addCardToZone("Azorius Charm", ai, ZoneType.Hand);
        final List<Map<String, Object>> choices = new ArrayList<>();
        final List<AiDecisionEvent> decisions = new ArrayList<>();
        game.observeEventEmission(event -> {
            if (event instanceof AiChoiceEvent choice) { choices.add(choice.data()); }
            if (event instanceof AiDecisionEvent decision) { decisions.add(decision); }
        });
        game.setRecordAiDecisions(true);
        final var selected = ai.getController().chooseSpellAbilityToPlay();
        Assert.assertNotNull(selected);
        Assert.assertFalse(selected.isEmpty());
        final var start = choices.stream().filter(c -> "MODE_SELECTION".equals(c.get("choiceType"))).findFirst().orElseThrow();
        final var end = choices.stream().filter(c -> start.get("choiceId").equals(c.get("choiceId")) && !"STARTED".equals(c.get("status"))).findFirst().orElseThrow();
        Assert.assertEquals(start.get("stage"), "CANDIDATE_EVALUATION");
        Assert.assertEquals(start.get("decisionId"), decisions.get(0).data().get("decisionId"));
        Assert.assertNotNull(start.get("assessmentId"));
        Assert.assertTrue(((List<?>) ((Map<?, ?>) start.get("options")).get("items")).size() >= 1);
        final var plan = (Map<?, ?>) end.get("selection");
        Assert.assertEquals(plan.get("available"), true);
        Assert.assertEquals(plan.get("accepted"), true);
        Assert.assertTrue((int) plan.get("selectedCount") > 0);
    }
}
