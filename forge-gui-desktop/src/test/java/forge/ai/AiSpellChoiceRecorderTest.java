package forge.ai;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.effects.ChooseGenericEffect;
import forge.game.ability.effects.VillainousChoiceEffect;
import forge.game.card.Card;
import forge.game.event.GameEventCardModeChosen;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class AiSpellChoiceRecorderTest extends AITest {
    private record Fixture(Game game, Player player, PlayerControllerAi controller, SpellAbility ability,
            List<SpellAbility> spells, List<Map<String, Object>> events, List<String> order) { }
    private Fixture fixture(String api) {
        final Game game = initAndCreateGame(); final Player player = game.getPlayers().get(1);
        final Card source = addCardToZone("Time Beetle", player, ZoneType.Battlefield);
        source.setSVar("ChoiceLife", "DB$ GainLife | LifeAmount$ 3"); source.setSVar("ChoiceDraw", "DB$ Draw | NumCards$ 1");
        final SpellAbility ability = AbilityFactory.getAbility("AB$ " + api + " | Cost$ 0 | Defined$ You | Choices$ ChoiceLife,ChoiceDraw | ChoiceAmount$ 2", source);
        ability.setActivatingPlayer(player);
        final List<SpellAbility> spells = new ArrayList<>(ability.getAdditionalAbilityList("Choices"));
        for (var spell : spells) { spell.setActivatingPlayer(player); }
        final List<Map<String, Object>> events = new ArrayList<>(); final List<String> order = new ArrayList<>();
        game.setRecordAiDecisions(true);
        game.observeEventEmission(event -> {
            if (event instanceof AiChoiceEvent choice) { final var row = choice.data(); events.add(row); order.add(row.get("choiceId") + ":" + row.get("status")); }
            if (event instanceof GameEventCardModeChosen) { order.add("MODE_APPLIED"); }
        });
        return new Fixture(game, player, (PlayerControllerAi) player.getController(), ability, spells, events, order);
    }
    private List<Map<String, Object>> starts(Fixture f, String type) {
        return f.events.stream().filter(c -> "STARTED".equals(c.get("status")) && type.equals(c.get("choiceType"))).toList();
    }
    private Map<?, ?> input(Map<String, Object> row) { return (Map<?, ?>) row.get("inputContext"); }
    private Map<?, ?> prompt(Map<String, Object> row) { return (Map<?, ?>) ((Map<?, ?>) input(row).get("policy")).get("prompt"); }

    @Test(groups = {"UnitTest", "fast"})
    public void actualControllerLoopCapturesEachSelectionBeforeApplyingTheEngineEffects() {
        final Fixture f = fixture("GenericChoice"); addCardToZone("Forest", f.player, ZoneType.Library);
        new ChooseGenericEffect().resolve(f.ability);
        Assert.assertEquals(f.player.getLife(), 23); Assert.assertEquals(f.player.getCardsIn(ZoneType.Hand).size(), 1);
        final var outer = starts(f, "EFFECT_MULTI_SPELL_ABILITY_SELECTION").get(0);
        final var children = starts(f, "EFFECT_SPELL_ABILITY_SELECTION"); Assert.assertEquals(children.size(), 2);
        Assert.assertEquals(prompt(outer), Map.of("kind", "SPELL_ABILITY_OPERATION_REQUEST", "requestedSelections", 2));
        Assert.assertEquals(((Map<?, ?>) outer.get("entryLocation")).get("kind"), "OPERATION");
        for (var child : children) {
            Assert.assertEquals(child.get("parentChoiceId"), outer.get("choiceId"));
            Assert.assertEquals(((Map<?, ?>) child.get("entryLocation")).get("kind"), "SELECTOR");
            Assert.assertEquals(prompt(child).get("kind"), "SPELL_ABILITY_REQUEST"); Assert.assertNull(prompt(child).get("affectedPlayer"));
            Assert.assertEquals(((Map<?, ?>) input(child).get("coverage")).get("prompt"), "RECEIVED_SPELL_REQUEST_V1");
            Assert.assertEquals(((Map<?, ?>) ((Map<?, ?>) input(child).get("diagnostics")).get("optionBindings")).get("coverage"), "UNSUPPORTED");
        }
        Assert.assertTrue(f.order.indexOf(outer.get("choiceId") + ":COMPLETED") < f.order.indexOf("MODE_APPLIED"));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] requestCounts() { return new Object[][] {{-1}, {0}, {1}, {2}}; }
    @Test(dataProvider = "requestCounts", groups = {"UnitTest", "fast"})
    public void receivedCountsDoNotRepairTheOriginalDoWhile(final int requested) {
        final Fixture f = fixture("GenericChoice"); final var ledger = f.controller.configureObservationAccounting();
        final var result = f.controller.chooseSpellAbilitiesForEffect(f.spells, f.ability, "Choose", requested, Map.of());
        Assert.assertEquals(result.size(), Math.max(1, requested)); Assert.assertSame(result.get(0), f.spells.get(0));
        Assert.assertEquals(prompt(starts(f, "EFFECT_MULTI_SPELL_ABILITY_SELECTION").get(0)).get("requestedSelections"), requested);
        Assert.assertEquals(f.spells.size(), 2);
        final var cells = (Map<?, ?>) ledger.snapshot().get("boundaries"); Assert.assertEquals(cells.size(), 2);
        Assert.assertEquals(((Map<?, ?>) cells.get("CONTROLLER_EFFECT_SPELL_ABILITIES")).get("entered"), 1L);
        Assert.assertEquals(((Map<?, ?>) cells.get("CONTROLLER_EFFECT_SPELL_ABILITY")).get("entered"), (long) result.size());
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void duplicateReturnsAndAmbiguousReferenceIndicesRemainDistinctFacts() {
        final Fixture f = fixture("GenericChoice"); final var original = f.spells.get(0);
        f.spells.add(0, original);
        final var result = f.controller.chooseSpellAbilitiesForEffect(f.spells, f.ability, "Choose", 2, Map.of());
        Assert.assertEquals(result, List.of(original, original)); Assert.assertEquals(f.spells.size(), 3);
        final var children = starts(f, "EFFECT_SPELL_ABILITY_SELECTION");
        final var terminal = f.events.stream().filter(c -> children.get(0).get("choiceId").equals(c.get("choiceId")) && "COMPLETED".equals(c.get("status"))).findFirst().orElseThrow();
        Assert.assertEquals(((Map<?, ?>) terminal.get("selection")).get("match"), "AMBIGUOUS");
        Assert.assertNull(((Map<?, ?>) terminal.get("selection")).get("selectedOptionIndex"));
        Assert.assertEquals(((Map<?, ?>) f.events.get(f.events.size() - 1).get("selection")).get("selectedCount"), 2);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void originalVirtualDispatchReceivesTheSameOpaqueMapAndTitle() {
        final Fixture f = fixture("GenericChoice"); final var controller = Mockito.spy(f.controller); f.player.dangerouslySetController(controller);
        final Map<String, Object> params = new AbstractMap<>() {
            @Override public Set<Entry<String, Object>> entrySet() { throw new AssertionError("opaque map inspected by observation"); }
        };
        final String title = new String("private title sentinel"); final List<Integer> remaining = new ArrayList<>();
        Mockito.doAnswer(call -> {
            Assert.assertSame(call.getArgument(1), f.ability); Assert.assertSame(call.getArgument(2), title); Assert.assertSame(call.getArgument(3), params);
            remaining.add(((List<?>) call.getArgument(0)).size()); return call.callRealMethod();
        }).when(controller).chooseSingleSpellForEffect(Mockito.anyList(), Mockito.any(), Mockito.anyString(), Mockito.any());
        Assert.assertEquals(controller.chooseSpellAbilitiesForEffect(f.spells, f.ability, title, 2, params), f.spells);
        Assert.assertEquals(remaining, List.of(2, 1)); Assert.assertFalse(f.events.toString().contains(title)); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void naturalDeclinesAndOriginalEmptyListErrorsKeepTheirOutcomes() {
        final Fixture decline = fixture("AssignGroup");
        Assert.assertTrue(decline.controller.chooseSpellAbilitiesForEffect(List.of(), decline.ability, "Choose", 2, Map.of()).isEmpty());
        Assert.assertEquals(decline.events.get(2).get("status"), "DECLINED");
        Assert.assertEquals(((Map<?, ?>) decline.events.get(3).get("selection")).get("selectedCount"), 0);
        final Fixture failure = fixture("GenericChoice");
        Assert.expectThrows(IndexOutOfBoundsException.class, () -> failure.controller.chooseSpellAbilitiesForEffect(List.of(), failure.ability, "Choose", 1, Map.of()));
        Assert.assertEquals(failure.events.get(2).get("status"), "ERROR"); Assert.assertEquals(failure.events.get(3).get("status"), "ERROR");
        Assert.assertNull(failure.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void villainousChoiceKeepsItsOriginalEffectAndOwnOperation() {
        final Fixture f = fixture("VillainousChoice"); new VillainousChoiceEffect().resolve(f.ability);
        Assert.assertEquals(f.player.getLife(), 23); Assert.assertEquals(starts(f, "EFFECT_MULTI_SPELL_ABILITY_SELECTION").size(), 1);
        Assert.assertEquals(starts(f, "EFFECT_SPELL_ABILITY_SELECTION").size(), 1); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void prefixCaptureDoesNotReadThe257thAbilityOrClampTheReturnedList() {
        final Fixture f = fixture("GenericChoice"); final var last = Mockito.spy(f.spells.get(1));
        Mockito.doThrow(new AssertionError("ability beyond captured prefix inspected")).when(last).getId();
        final List<SpellAbility> values = new ArrayList<>(Collections.nCopies(256, f.spells.get(0))); values.add(last);
        Assert.assertSame(AiSpellChoiceRecorder.multiple(f.player, f.ability, values, 257, () -> values), values);
        final var options = (Map<?, ?>) f.events.get(0).get("options");
        final var selection = (Map<?, ?>) f.events.get(1).get("selection");
        Assert.assertEquals(((List<?>) options.get("items")).size(), 256); Assert.assertEquals(options.get("truncated"), true);
        Assert.assertEquals(((List<?>) selection.get("items")).size(), 256); Assert.assertEquals(selection.get("selectedCount"), 257);
        Assert.assertEquals(selection.get("truncated"), true); Assert.assertEquals(values.size(), 257); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void optionSnapshotsPrecedeOriginalMutationAndCallbackErrorsStayOriginal() {
        final Fixture f = fixture("GenericChoice"); final var selected = f.spells.get(1); final AtomicInteger calls = new AtomicInteger();
        Assert.assertSame(AiSpellChoiceRecorder.single(f.player, f.ability, f.spells, () -> { calls.incrementAndGet(); f.spells.clear(); return selected; }), selected);
        Assert.assertEquals(calls.get(), 1); Assert.assertEquals(((List<?>) ((Map<?, ?>) f.events.get(0).get("options")).get("items")).size(), 2);
        Assert.assertEquals(((Map<?, ?>) f.events.get(1).get("selection")).get("selectedOptionIndex"), 1);
        final var original = new IllegalStateException("original spell callback");
        Assert.assertSame(Assert.expectThrows(IllegalStateException.class, () -> AiSpellChoiceRecorder.single(f.player, f.ability, f.spells, () -> { throw original; })), original);
        Assert.assertEquals(f.events.get(3).get("status"), "ERROR"); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] bypass() { return new Object[][] {{"OFF"}, {"COPY"}, {"HIDDEN"}}; }
    @Test(dataProvider = "bypass", groups = {"UnitTest", "fast"})
    public void bypassedCallbacksDoNotInspectNewOptionsOrResults(final String mode) {
        final Fixture f = fixture("GenericChoice");
        if (mode.equals("OFF")) { f.game.setRecordAiDecisions(false); }
        if (mode.equals("COPY")) { f.game.markAsSimulationCopy(); }
        if (mode.equals("HIDDEN")) { f.ability.getHostCard().turnFaceDown(); }
        final List<SpellAbility> forbidden = new AbstractList<>() {
            @Override public int size() { throw new AssertionError("spell options/results inspected"); }
            @Override public SpellAbility get(int i) { throw new AssertionError("spell options/results inspected"); }
        };
        final var result = Mockito.spy(f.spells.get(0)); Mockito.doThrow(new AssertionError("spell result inspected")).when(result).getId();
        Assert.assertSame(AiSpellChoiceRecorder.single(f.player, f.ability, forbidden, () -> result), result);
        Assert.assertSame(AiSpellChoiceRecorder.multiple(f.player, f.ability, forbidden, 1, () -> forbidden), forbidden);
        if (mode.equals("HIDDEN")) { Assert.assertEquals(f.events.size(), 4); Assert.assertTrue(f.events.stream().filter(c -> "STARTED".equals(c.get("status"))).allMatch(c -> "MASKED_CHOICE".equals(c.get("choiceType")))); }
        else { Assert.assertTrue(f.events.isEmpty()); }
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void liveOptionFaultIsVisibleAndStillRunsTheOriginalCallback() {
        final Fixture f = fixture("GenericChoice"); final var failure = new IllegalStateException("spell option getter");
        final List<SpellAbility> broken = new AbstractList<>() {
            @Override public int size() { throw failure; }
            @Override public SpellAbility get(int i) { throw failure; }
        };
        final AtomicInteger calls = new AtomicInteger();
        Assert.assertSame(AiSpellChoiceRecorder.single(f.player, f.ability, broken, () -> { calls.incrementAndGet(); return f.spells.get(0); }), f.spells.get(0));
        Assert.assertEquals(calls.get(), 1); Assert.assertSame(f.game.getEventObservationFailure(), failure); Assert.assertTrue(f.events.isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void planningCountCarrierChargesWorkWithoutGrantingOtherSuppliers() {
        final Fixture f = fixture("GenericChoice"); final var state = f.controller.inputContextState(); long before = state.remainingWork();
        AiInputContext.capture(f.player, f.ability, "BEFORE_OPERATION", "PLANNING_WORKING_STATE", () -> true);
        final long base = before - state.remainingWork(); before = state.remainingWork();
        final var input = AiInputContext.capture(f.player, f.ability, "BEFORE_OPERATION", "PLANNING_WORKING_STATE", () -> true, new AiPromptInput.SpellOperation(0));
        Assert.assertEquals(before - state.remainingWork(), base + 1);
        Assert.assertEquals(((Map<?, ?>) ((Map<?, ?>) input.get("policy")).get("prompt")).get("requestedSelections"), 0);
        final var refused = AiInputContext.capture(f.player, f.ability, "BEFORE_OPERATION", "PLANNING_WORKING_STATE", () -> true,
                () -> { throw new AssertionError("unaudited hypothetical supplier inspected"); });
        Assert.assertNull(((Map<?, ?>) refused.get("policy")).get("prompt"));
    }
}
