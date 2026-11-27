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

public class AiCopyTargetInputTest extends AITest {
    private record Fixture(Game game, Player ai, Player other, SpellAbility copy, Card prior, Card alternative,
                           List<Map<String, Object>> events) { }
    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player ai = game.getPlayers().get(1), other = game.getPlayers().get(0);
        final Card giant = addCardToZone("Hill Giant", other, ZoneType.Battlefield);
        final Card bears = addCardToZone("Grizzly Bears", other, ZoneType.Battlefield);
        final Card host = addCardToZone("Unsummon", ai, ZoneType.Hand);
        final SpellAbility original = host.getFirstSpellAbility(); original.setActivatingPlayer(ai); original.getTargets().add(giant);
        original.setHostCard(game.getAction().moveToStack(host, original)); game.getStack().add(original);
        final SpellAbility copy = original.copy(ai); copy.resetTargets(); copy.getTargets().add(giant);
        final var events = new ArrayList<Map<String, Object>>(); game.setRecordAiDecisions(true);
        game.observeEventEmission(e -> { if (e instanceof AiChoiceEvent c) { events.add(c.data()); } });
        return new Fixture(game, ai, other, copy, giant, bears, events);
    }
    private void choose(Fixture f, SpellAbility copy) { ((PlayerControllerAi) f.ai.getController()).chooseNewTargetsForCopy(copy); }
    private Map<String, Object> start(Fixture f) { return f.events.stream().filter(c -> "COPY_TARGETS".equals(c.get("boundary"))).findFirst().orElseThrow(); }
    private Map<?, ?> context(Map<?, ?> start) { return (Map<?, ?>) start.get("inputContext"); }
    private List<?> targets(Map<?, ?> plan) { return (List<?>) ((Map<?, ?>) ((List<?>) plan.get("abilities")).get(0)).get("targets"); }

    @Test(groups = {"UnitTest", "fast"})
    public void actualCopyReselectsWhileItsOwnEntryAndPriorPlanStayBeforeMutation() {
        final Fixture f = fixture(); final var copy = Mockito.spy(f.copy); final var clears = new AtomicInteger();
        Mockito.doAnswer(call -> {
            final var start = start(f); Assert.assertEquals(start.get("status"), "STARTED");
            Assert.assertEquals(((Map<?, ?>) start.get("entryLocation")).get("phase"), "MAIN1");
            f.ai.setLife(7, null); f.game.getPhaseHandler().devModeSet(PhaseType.MAIN2, f.ai); clears.incrementAndGet();
            return call.callRealMethod();
        }).when(copy).clearTargets();
        choose(f, copy); Assert.assertEquals(clears.get(), 1); Assert.assertSame(copy.getTargets().getFirstTargetedCard(), f.alternative);
        final var start = start(f); final var ctx = context(start); final var entry = (Map<?, ?>) start.get("entryLocation");
        Assert.assertEquals(start.get("stage"), "EFFECT_COPY_TARGET_SELECTION"); Assert.assertEquals(entry.get("kind"), "OPERATION");
        Assert.assertEquals(entry.get("timing"), "BEFORE_OPERATION"); Assert.assertEquals(entry.get("stateBasis"), "RULES_STATE");
        Assert.assertEquals(ctx.get("schemaVersion"), 22); Assert.assertEquals(((Map<?, ?>) ctx.get("coverage")).get("receivedParameters"), "UNSUPPORTED");
        final var policy = (Map<?, ?>) ctx.get("policy"); Assert.assertNull(policy.get("receivedParameters")); Assert.assertNull(policy.get("prompt"));
        Assert.assertEquals(((Map<?, ?>) ((List<?>) policy.get("players")).get(1)).get("life"), 20);
        final var options = (Map<?, ?>) start.get("options"); Assert.assertEquals(options.size(), 2);
        Assert.assertEquals(((Map<?, ?>) targets((Map<?, ?>) options.get("priorTargets")).get(0)).get("id"), f.prior.getId());
        final var terminal = f.events.stream().filter(c -> start.get("choiceId").equals(c.get("choiceId")) && "COMPLETED".equals(c.get("status"))).findFirst().orElseThrow();
        final var selection = (Map<?, ?>) terminal.get("selection"); Assert.assertEquals(selection.size(), 1);
        Assert.assertEquals(((Map<?, ?>) targets((Map<?, ?>) selection.get("targets")).get(0)).get("id"), f.alternative.getId());
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualNoLegalAlternativeKeepsTheOldIllegalTargetWithoutClaimingAcceptance() {
        final Fixture f = fixture(); f.game.getAction().moveToGraveyard(f.prior, null); f.game.getAction().moveToGraveyard(f.alternative, null);
        final var prior = f.copy.getTargets(); choose(f, f.copy); Assert.assertSame(f.copy.getTargets(), prior);
        Assert.assertSame(f.copy.getTargets().getFirstTargetedCard(), f.prior); Assert.assertFalse(f.copy.canTarget(f.prior));
        final var terminal = f.events.stream().filter(c -> start(f).get("choiceId").equals(c.get("choiceId")) && "COMPLETED".equals(c.get("status"))).findFirst().orElseThrow();
        Assert.assertEquals(((Map<?, ?>) terminal.get("selection")).keySet(), java.util.Set.of("targets"));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualDelegationKeepsItsOwnChooserAndNestedPlanningAttribution() {
        final Fixture f = fixture(); f.copy.getMapParams().put("TargetingPlayer", "Opponent");
        final Card own = addCardToZone("Shivan Dragon", f.ai, ZoneType.Battlefield);
        AiChoiceRecorder.planning(f.ai, () -> { choose(f, f.copy); return true; });
        final var outer = start(f); Assert.assertEquals(context(outer).get("stateBasis"), "PLANNING_WORKING_STATE");
        final var target = f.events.stream().filter(c -> "CONTROLLER_TARGETS".equals(c.get("boundary")) && "STARTED".equals(c.get("status"))).findFirst().orElseThrow();
        Assert.assertEquals(target.get("chooserPlayerId"), f.other.getId()); Assert.assertEquals(target.get("parentChoiceId"), outer.get("choiceId"));
        Assert.assertEquals(target.get("stage"), "DELEGATED_TARGET_SELECTION"); Assert.assertEquals(context(target).get("stateBasis"), "PLANNING_WORKING_STATE");
        Assert.assertSame(f.copy.getTargetingPlayer(), f.other); Assert.assertSame(f.copy.getTargets().getFirstTargetedCard(), own);
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void originalErrorFromActualCopyWorkIsRethrownOnceWithErrorTerminal() {
        final Fixture f = fixture(); final var copy = Mockito.spy(f.copy); final var original = new IllegalStateException("private original error"); final var calls = new AtomicInteger();
        Mockito.doAnswer(call -> { calls.incrementAndGet(); throw original; }).when(copy).clearTargets();
        Assert.assertSame(Assert.expectThrows(IllegalStateException.class, () -> choose(f, copy)), original);
        Assert.assertEquals(calls.get(), 1); Assert.assertEquals(f.events.get(f.events.size() - 1).get("status"), "ERROR");
        Assert.assertFalse(f.events.toString().contains(original.getMessage())); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] bypass() { return new Object[][] {{"OFF"}, {"COPY"}, {"HIDDEN"}}; }
    @Test(dataProvider = "bypass", groups = {"UnitTest", "fast"})
    public void actualCopyStillChoosesNormallyWhenCaptureIsBypassed(String mode) {
        final Fixture f = fixture();
        if (mode.equals("OFF")) { f.game.setRecordAiDecisions(false); }
        if (mode.equals("COPY")) { f.game.markAsSimulationCopy(); }
        if (mode.equals("HIDDEN")) { f.copy.getHostCard().turnFaceDown(); }
        choose(f, f.copy); Assert.assertSame(f.copy.getTargets().getFirstTargetedCard(), f.alternative);
        Assert.assertTrue(f.events.stream().filter(c -> "STARTED".equals(c.get("status"))).allMatch(c -> "MASKED_CHOICE".equals(c.get("choiceType"))));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void copyAccountingStaysInItsExistingCell() {
        final Fixture f = fixture(); final var ledger = ((PlayerControllerAi) f.ai.getController()).configureObservationAccounting(); choose(f, f.copy);
        final var cells = (Map<?, ?>) ledger.snapshot().get("boundaries"); final var cell = (Map<?, ?>) cells.get("COPY_TARGETS");
        Assert.assertEquals(cell.get("entered"), 1L); Assert.assertEquals(cell.get("finished"), 1L); Assert.assertEquals(cell.get("observationFailures"), 0L);
    }
}
