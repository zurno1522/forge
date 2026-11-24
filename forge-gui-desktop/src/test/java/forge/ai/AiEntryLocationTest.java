package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AiEntryLocationTest extends AITest {
    private record Fixture(Game game, Player chooser, SpellAbility ability, Card target, List<Map<String, Object>> starts) { }
    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player chooser = game.getPlayers().get(1);
        final SpellAbility ability = addCardToZone("Doom Blade", chooser, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(chooser);
        final Card target = addCardToZone("Grizzly Bears", game.getPlayers().get(0), ZoneType.Battlefield);
        game.setRecordAiDecisions(true);
        final List<Map<String, Object>> starts = new ArrayList<>();
        game.observeEventEmission(event -> {
            if (event instanceof AiChoiceEvent choice && "STARTED".equals(choice.data().get("status"))) { starts.add(choice.data()); }
        });
        return new Fixture(game, chooser, ability, target, starts);
    }
    private Map<?, ?> entry(Fixture f, int index) { return (Map<?, ?>) f.starts.get(index).get("entryLocation"); }
    private Map<?, ?> context(Fixture f, int index) { return (Map<?, ?>) f.starts.get(index).get("inputContext"); }

    @Test(groups = {"UnitTest", "fast"})
    public void standaloneTargetSelectionFreezesItsOwnInputAndPublicDiagnosticLinks() {
        final Fixture f = fixture(); final AtomicInteger calls = new AtomicInteger();
        Assert.assertSame(AiTargetChoiceRecorder.choose(f.chooser, f.ability, List.of(f.target), "DESTROY_REMOVAL_POOL", pool -> {
            calls.incrementAndGet(); f.chooser.setLife(7, null);
            f.game.getPhaseHandler().devModeSet(PhaseType.MAIN2, f.chooser);
            return f.target;
        }), f.target);
        Assert.assertEquals(calls.get(), 1);
        Assert.assertEquals(entry(f, 0).get("kind"), "SELECTOR");
        Assert.assertEquals(entry(f, 0).get("phase"), "MAIN1");
        final Map<?, ?> policy = (Map<?, ?>) context(f, 0).get("policy");
        Assert.assertEquals(policy.get("phase"), "MAIN1");
        Assert.assertEquals(((Map<?, ?>) ((List<?>) policy.get("players")).get(1)).get("life"), 20);
        final Map<?, ?> links = (Map<?, ?>) ((Map<?, ?>) context(f, 0).get("diagnostics")).get("optionBindings");
        Assert.assertNotNull(((Map<?, ?>) ((List<?>) links.get("items")).get(0)).get("entityRef"));
        Assert.assertTrue(f.ability.getTargets().isEmpty()); // A recommendation is not an application.
    }

    @Test(groups = {"UnitTest", "fast"})
    public void precomputedTargetAdditionHasApplicationInputAndKeepsItsLegacyTiming() {
        final Fixture f = fixture();
        Assert.assertTrue(AiTargetChoiceRecorder.addSelected(f.chooser, f.ability, List.of(f.target), "DESTROY_REMOVAL_POOL", f.target));
        Assert.assertEquals(entry(f, 0).get("kind"), "APPLICATION");
        Assert.assertEquals(entry(f, 0).get("timing"), "AFTER_SELECTION_BEFORE_APPLICATION");
        Assert.assertEquals(context(f, 0).get("timing"), "AFTER_SELECTION_BEFORE_APPLICATION");
        Assert.assertEquals(((Map<?, ?>) f.starts.get(0).get("options")).get("poolSnapshotTiming"), "AT_TARGET_ADDITION");
        Assert.assertEquals(f.ability.getTargets().size(), 1);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void entryClockPrecedesTheSingleDiagnosticPoolTraversal() {
        final Fixture f = fixture(); final AtomicInteger iterations = new AtomicInteger();
        final Iterable<Card> options = () -> {
            iterations.incrementAndGet(); f.game.getPhaseHandler().devModeSet(PhaseType.MAIN2, f.chooser);
            return List.of(f.target).iterator();
        };
        Assert.assertSame(AiTargetChoiceRecorder.choose(f.chooser, f.ability, options, "DESTROY_REMOVAL_POOL", ignored -> f.target), f.target);
        Assert.assertEquals(iterations.get(), 1);
        Assert.assertEquals(entry(f, 0).get("phase"), "MAIN1");
        Assert.assertEquals(((Map<?, ?>) context(f, 0).get("policy")).get("phase"), "MAIN2");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void operationEntryAndPlanningScopeSurviveCallbackFailureWithoutLeakingIntoTheNextChoice() {
        final Fixture f = fixture(); final var failure = new IllegalStateException("original policy error");
        Assert.assertSame(Assert.expectThrows(IllegalStateException.class, () -> AiChoiceRecorder.planning(f.chooser,
                () -> AiChoiceRecorder.targeting(f.chooser, f.ability, null, () -> { throw failure; }))), failure);
        Assert.assertEquals(entry(f, 0).get("kind"), "OPERATION");
        Assert.assertEquals(entry(f, 0).get("stateBasis"), "PLANNING_WORKING_STATE");
        AiTargetChoiceRecorder.choose(f.chooser, f.ability, List.of(f.target), "DESTROY_REMOVAL_POOL", ignored -> f.target);
        Assert.assertEquals(entry(f, 1).get("stateBasis"), "RULES_STATE");
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void hiddenAndClosedEntriesDoNotReadTheClockOrInventAPublicLocation() {
        final Player chooser = Mockito.mock(Player.class);
        final var hidden = AiEntryLocation.capture(chooser, "BEFORE_SELECTOR", "RULES_STATE", false,
                () -> { throw new AssertionError("hidden admission"); });
        Assert.assertEquals(hidden.get("reason"), "HIDDEN"); Assert.assertNull(hidden.get("turn"));
        Assert.assertEquals(AiEntryLocation.capture(chooser, "BEFORE_SELECTOR", "RULES_STATE", true, () -> false).get("reason"), "INTERRUPTED");
        Mockito.verifyNoInteractions(chooser);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void clockGetterFailureIsVisibleWithoutPublishingItsPrivateExceptionMessage() {
        final Player chooser = Mockito.mock(Player.class); final Game game = Mockito.mock(Game.class);
        final var failure = new IllegalStateException("private clock diagnostic");
        Mockito.when(chooser.getGame()).thenReturn(game); Mockito.when(game.getPhaseHandler()).thenThrow(failure);
        final var entry = AiEntryLocation.capture(chooser, "BEFORE_SELECTOR", "RULES_STATE", true, () -> true);
        Assert.assertEquals(entry.get("reason"), "CAPTURE_FAILED"); Assert.assertNull(entry.get("phase"));
        Assert.assertFalse(entry.toString().contains("private clock diagnostic"));
        Mockito.verify(game).recordEventObservationFailure(failure);
    }

    @DataProvider public Object[][] off() { return new Object[][] {{false}, {true}}; }
    @Test(dataProvider = "off", groups = {"UnitTest", "fast"})
    public void disabledOrCopiedTargetCallbacksDoNotTraverseOrCapture(final boolean copy) {
        final Fixture f = fixture();
        if (copy) { f.game.markAsSimulationCopy(); } else { f.game.setRecordAiDecisions(false); }
        final Iterable<Card> options = () -> { throw new AssertionError("disabled traversal"); };
        Assert.assertSame(AiTargetChoiceRecorder.choose(f.chooser, f.ability, options, "DESTROY_REMOVAL_POOL", ignored -> f.target), f.target);
        Assert.assertTrue(f.starts.isEmpty());
    }
}
