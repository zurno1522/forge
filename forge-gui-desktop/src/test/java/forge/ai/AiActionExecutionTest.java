package forge.ai;

import com.google.common.eventbus.Subscribe;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEventGameFinished;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AiActionExecutionTest extends AITest {
    public static final class Events {
        final List<AiDecisionEvent> decisions = new ArrayList<>();
        final List<Map<String, Object>> executions = new ArrayList<>();
        @Subscribe public void decision(final AiDecisionEvent e) { decisions.add(e); }
        @Subscribe public void execution(final AiActionExecutionEvent e) { executions.add(e.data()); }
    }

    private Events record(final Game game, final Player ai, final SpellAbility... abilities) {
        game.setRecordAiDecisions(true);
        final Events events = new Events();
        game.subscribeToEvents(events);
        for (final SpellAbility sa : abilities) { sa.setActivatingPlayer(ai); }
        ((PlayerControllerAi) ai.getController()).getAi().publishDecision(
                new AiDecisionRecorder(game, ai, game.nextAiDecisionSequence()), List.of(abilities), "COMPLETED", null);
        return events;
    }

    private void assertResult(final Events events, final String status, final String reason) {
        Assert.assertEquals(events.executions.size(), 2);
        Assert.assertEquals(events.executions.get(0).get("status"), "STARTED");
        final Map<String, Object> result = events.executions.get(1);
        Assert.assertEquals(result.get("status"), status);
        Assert.assertEquals(result.get("reason"), reason);
        final Map<?, ?> selected = (Map<?, ?>) ((List<?>) events.decisions.get(0).data().get("selectedActions")).get(0);
        Assert.assertEquals(result.get("actionId"), selected.get("actionId"));
        Assert.assertEquals(result.get("decisionId"), events.decisions.get(0).data().get("decisionId"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void realLandSelectionAndExecutionAreLinked() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCardToZone("Island", ai, ZoneType.Hand);
        for (final Player p : game.getPlayers()) { fillLibrary(p, 10); }
        final Events events = new Events();
        game.subscribeToEvents(events);
        game.setRecordAiDecisions(true);
        final PlayerControllerAi controller = (PlayerControllerAi) ai.getController();
        final SpellAbility selected = controller.chooseSpellAbilityToPlay().get(0);
        Assert.assertTrue(selected.isLandAbility());
        Assert.assertTrue(controller.playChosenSpellAbility(selected));
        assertResult(events, "SUCCEEDED", "LAND_PLAYED");
        Assert.assertEquals(ai.getLandsPlayedThisTurn(), 1);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void spellReachingStackIsNotReportedAsResolved() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card card = addCardToZone("Memnite", ai, ZoneType.Hand);
        final SpellAbility sa = card.getFirstSpellAbility();
        final Events events = record(game, ai, sa);
        ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(sa);
        assertResult(events, "SUCCEEDED", "STACKED");
        Assert.assertTrue(card.getGame().getCardState(card).isInZone(ZoneType.Stack));
        Assert.assertEquals(events.executions.get(1).get("stackId"), game.getStack().iterator().next().getId());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void activatedAbilityCopyKeepsTheSelectedActionIdentity() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card card = addCardToZone("Archivist", ai, ZoneType.Battlefield);
        card.setSickness(false);
        fillLibrary(ai, 10);
        final SpellAbility sa = card.getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility).findFirst().orElseThrow();
        final Events events = record(game, ai, sa);
        ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(sa);
        assertResult(events, "SUCCEEDED", "STACKED");
        final Map<String, Object> result = events.executions.get(1);
        Assert.assertNotEquals(result.get("executedAbilityId"), result.get("abilityId"));
        Assert.assertEquals(result.get("executedAbilityId"), game.getStack().iterator().next().getSpellAbility().getId());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void illegalTargetFailureDoesNotUseTheControllerReturnValue() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility sa = addCardToZone("Swords to Plowshares", ai, ZoneType.Hand).getFirstSpellAbility();
        final Events events = record(game, ai, sa);
        Assert.assertTrue(((PlayerControllerAi) ai.getController()).playChosenSpellAbility(sa));
        assertResult(events, "FAILED", "CAST_OR_ACTIVATION_FAILED");
        Assert.assertTrue(game.getStack().isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void unplayableLandFailsAndUnattemptedChoiceIsAbandonedAtNextSelection() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility sa = new forge.game.spellability.LandAbility(addCardToZone("Island", ai, ZoneType.Hand), null);
        final Events events = record(game, ai, sa);
        ai.setLandsPlayedThisTurn(100);
        ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(sa);
        assertResult(events, "FAILED", "LAND_NOT_PLAYED");
        // Selecting the same ability again gets a new action ID; it is not a second attempt at the old action.
        final Events pending = record(game, ai, sa);
        game.fireEvent(new GameEventGameFinished());
        Assert.assertTrue(pending.executions.isEmpty(), "Game finish can precede an in-flight attempt");
        ((PlayerControllerAi) ai.getController()).chooseSpellAbilityToPlay();
        Assert.assertEquals(pending.executions.size(), 1);
        Assert.assertEquals(pending.executions.get(0).get("status"), "ABANDONED");
        Assert.assertNotEquals(pending.executions.get(0).get("actionId"), events.executions.get(0).get("actionId"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void exceptionsAreRecordedAndPropagated() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card card = addCardToZone("Island", ai, ZoneType.Hand);
        final SpellAbility broken = new SpellAbility.EmptySa(card) {
            @Override public boolean isLandAbility() { return true; }
            @Override public boolean canPlay() { throw new IllegalStateException("test failure"); }
        };
        final Events events = record(game, ai, broken);
        Assert.expectThrows(IllegalStateException.class,
                () -> ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(broken));
        assertResult(events, "ERROR", "EXECUTION_EXCEPTION");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void repeatedAbilityInOneSelectionGetsSeparateExecutionIds() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility sa = addCardToZone("Swords to Plowshares", ai, ZoneType.Hand).getFirstSpellAbility();
        final Events events = record(game, ai, sa, sa);
        final PlayerControllerAi controller = (PlayerControllerAi) ai.getController();
        controller.playChosenSpellAbility(sa);
        controller.playChosenSpellAbility(sa);
        Assert.assertEquals(events.executions.size(), 4);
        Assert.assertEquals(events.executions.get(1).get("status"), "FAILED");
        Assert.assertEquals(events.executions.get(3).get("status"), "FAILED");
        Assert.assertNotEquals(events.executions.get(1).get("actionId"), events.executions.get(3).get("actionId"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void selectedManaAbilityIsImmediateAndUnselectedPlaysAreNotAttributed() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card elf = addCardToZone("Llanowar Elves", ai, ZoneType.Battlefield);
        elf.setSickness(false);
        final SpellAbility mana = elf.getManaAbilities().get(0);
        final Events events = record(game, ai, mana);
        final PlayerControllerAi controller = (PlayerControllerAi) ai.getController();
        controller.playChosenSpellAbility(mana);
        assertResult(events, "SUCCEEDED", "RESOLVED_WITHOUT_STACK");
        final SpellAbility other = addCardToZone("Memnite", ai, ZoneType.Hand).getFirstSpellAbility();
        other.setActivatingPlayer(ai);
        controller.playChosenSpellAbility(other);
        Assert.assertEquals(events.executions.size(), 2);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void unrecordedGamesAndSimulationCopiesEmitNoExecutionEvents() {
        for (final boolean copy : List.of(false, true)) {
            final Game game = initAndCreateGame();
            final Player ai = game.getPlayers().get(1);
            addCardToZone("Island", ai, ZoneType.Hand);
            for (final Player p : game.getPlayers()) { fillLibrary(p, 10); }
            if (copy) {
                game.setRecordAiDecisions(true);
                game.markAsSimulationCopy();
            }
            final Events events = new Events();
            game.subscribeToEvents(events);
            final PlayerControllerAi controller = (PlayerControllerAi) ai.getController();
            final List<SpellAbility> selected = controller.chooseSpellAbilityToPlay();
            Assert.assertNotNull(selected);
            controller.playChosenSpellAbility(selected.get(0));
            Assert.assertTrue(events.decisions.isEmpty());
            Assert.assertTrue(events.executions.isEmpty());
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void helperSuccessWithoutAnEngineExecutionIsUnobserved() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card elf = addCardToZone("Llanowar Elves", ai, ZoneType.Battlefield);
        final SpellAbility mana = elf.getManaAbilities().get(0);
        final Events events = record(game, ai, mana);
        final AiActionTracker tracker = ((PlayerControllerAi) ai.getController()).getAi().actionTracker();
        final AiActionTracker.Attempt attempt = tracker.begin(mana);
        tracker.played(attempt, mana, true);
        assertResult(events, "UNOBSERVED", "NO_STACK_ENTRY_OBSERVED");
        Assert.assertTrue(ai.getManaPool().isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void referenceLookupUsesActiveAliasesAndExcludesDerivedEffects() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility selected = addCardToZone("Memnite", ai, ZoneType.Hand).getFirstSpellAbility();
        final Events events = record(game, ai, selected);
        final AiController brain = ((PlayerControllerAi) ai.getController()).getAi();
        Assert.assertNull(brain.getActionReference(selected), "Selection alone does not prove execution started");
        final AiActionTracker.Attempt attempt = brain.actionTracker().begin(selected);
        final AiActionReference reference = brain.getActionReference(selected);
        Assert.assertEquals(reference.actionId(), events.executions.get(0).get("actionId"));
        final SpellAbility transformed = selected.copy();
        brain.aliasSelectedAction(selected, transformed);
        Assert.assertEquals(brain.getActionReference(transformed), reference);
        final SpellAbility engineCopy = transformed.copy();
        engineCopy.setOriginalAbility(transformed);
        Assert.assertEquals(brain.getActionReference(engineCopy), reference);
        engineCopy.setCopied(true);
        Assert.assertNull(brain.getActionReference(engineCopy));
        transformed.setTrigger(new forge.game.trigger.TriggerAlways(Map.of(), selected.getHostCard(), true));
        Assert.assertNull(brain.getActionReference(transformed));
        game.markAsSimulationCopy();
        Assert.assertNull(brain.getActionReference(selected));
        brain.actionTracker().finish(attempt, "FAILED", "TEST_FINISHED");
        Assert.assertNull(brain.getActionReference(selected));
    }
}
