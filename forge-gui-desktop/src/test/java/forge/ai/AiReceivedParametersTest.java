package forge.ai;

import com.google.common.collect.ImmutableMap;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CounterCustomType;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollection;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AiReceivedParametersTest extends AITest {
    private record Fixture(Game game, Player player, Player other, PlayerControllerAi controller, SpellAbility ability,
            Card card, List<Map<String, Object>> events) { }
    private Fixture fixture(String api) {
        final Game game = initAndCreateGame(); final Player player = game.getPlayers().get(1);
        final Card host = addCardToZone("Time Beetle", player, ZoneType.Battlefield);
        final var ability = AbilityFactory.getAbility("AB$ " + api + " | Cost$ 0", host); ability.setActivatingPlayer(player);
        final Card card = addCardToZone("Grizzly Bears", player, ZoneType.Battlefield);
        final var events = new ArrayList<Map<String, Object>>(); game.setRecordAiDecisions(true);
        game.observeEventEmission(e -> { if (e instanceof AiChoiceEvent c) { events.add(c.data()); } });
        return new Fixture(game, player, game.getPlayers().get(0), (PlayerControllerAi) player.getController(), ability, card, events);
    }
    private Map<String, Object> map(Object... entries) {
        final Map<String, Object> map = new HashMap<>(); for (int i = 0; i < entries.length; i += 2) { map.put((String) entries[i], entries[i + 1]); } return map;
    }
    private Map<?, ?> context(Map<String, Object> row) { return (Map<?, ?>) row.get("inputContext"); }
    private Map<?, ?> received(Map<String, Object> row) { return (Map<?, ?>) ((Map<?, ?>) context(row).get("policy")).get("receivedParameters"); }
    private Map<?, ?> receipt(Map<String, Object> row) { return (Map<?, ?>) ((Map<?, ?>) row.get("options")).get("callbackParameters"); }
    private int number(Fixture f, Map<String, Object> params) { return AiNumberChoiceRecorder.controllerRange(f.player, f.ability, 0, 3, params, () -> 2); }
    private Card entity(Fixture f, Map<String, Object> params) {
        return AiEffectChoiceRecorder.single(f.player, f.ability, List.of(f.card), true, null, params, () -> f.card);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualVoterControllerSelectsOppositeBoundsAndRecordsItsReceivedRole() {
        final Fixture f = fixture("Vote");
        Assert.assertEquals(f.controller.chooseNumber(f.ability, "Choose", 0, 3, map("Voter", f.player)), 3);
        Assert.assertEquals(f.controller.chooseNumber(f.ability, "Choose", 0, 3, map("Voter", f.other)), 0);
        Assert.assertEquals(received(f.events.get(0)), Map.of("kind", "VOTER", "voter", "p1"));
        Assert.assertEquals(received(f.events.get(2)), Map.of("kind", "VOTER", "voter", "p0"));
        Assert.assertEquals(((Map<?, ?>) context(f.events.get(2)).get("policy")).get("prompt"), Map.of("kind", "NUMBER_RANGE", "minimum", 0, "maximum", 3));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualCounterRemovalKeepsPlayerAndCardCounterRolesDistinct() {
        final Fixture f = fixture("RemoveCounter"); f.other.setCounters(CounterEnumType.POISON, 3); f.card.setCounters(CounterEnumType.M1M1, 1);
        Assert.assertEquals(f.controller.chooseNumber(f.ability, "Choose", 0, 3, map("Target", f.other, "CounterType", CounterEnumType.POISON)), 0);
        Assert.assertEquals(received(f.events.get(0)), Map.of("kind", "COUNTER_AMOUNT", "target", "p0", "counterType", CounterEnumType.POISON.getName()));
        Assert.assertEquals(f.controller.chooseNumber(f.ability, "Choose", 0, 1, map("Target", f.card, "CounterType", CounterEnumType.M1M1)), 1);
        final var value = received(f.events.get(2)); Assert.assertEquals(value.get("kind"), "COUNTER_AMOUNT");
        Assert.assertTrue(value.get("target").toString().startsWith("c")); Assert.assertEquals(value.get("counterType"), CounterEnumType.M1M1.getName());
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualEntityControllerForwardsItsParameterMapWithoutChangingTheSelection() {
        final Fixture f = fixture("TimeTravel"); final var params = map("CMCLimit", 7);
        Assert.assertSame(f.controller.chooseSingleEntityForEffect(new FCollection<>(List.of(f.card)), null, f.ability,
                "Choose", false, null, params), f.card);
        Assert.assertEquals(received(f.events.get(0)), Map.of("kind", "ENTITY_MANA_VALUE_LIMIT", "manaValueLimit", 7));
        Assert.assertEquals(params, map("CMCLimit", 7)); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void transferRolesAndAllAuditedEntityScalarsRetainExactMeanings() {
        final Fixture f = fixture("Vote");
        number(f, map("Source", f.card, "Target", f.other, "CounterType", CounterEnumType.CHARGE));
        Assert.assertEquals(received(f.events.get(0)).get("kind"), "COUNTER_TRANSFER_AMOUNT"); Assert.assertEquals(received(f.events.get(0)).get("target"), "p0");
        for (String key : List.of("Attach", "Attacker", "Partner")) {
            f.events.clear(); Assert.assertSame(entity(f, map(key, f.card)), f.card);
            Assert.assertEquals(received(f.events.get(0)).get("kind"), "ENTITY_CARD_ROLE"); Assert.assertEquals(received(f.events.get(0)).get("role"), key);
        }
        for (int value : List.of(Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE)) {
            f.events.clear(); entity(f, map("CMCLimit", value)); Assert.assertEquals(received(f.events.get(0)), Map.of("kind", "ENTITY_MANA_VALUE_LIMIT", "manaValueLimit", value));
        }
        f.events.clear(); entity(f, map("Target", f.other)); Assert.assertEquals(received(f.events.get(0)), Map.of("kind", "ENTITY_TARGET_ROLE", "target", "p0"));
        for (boolean amount : List.of(false, true)) {
            f.events.clear(); final var params = map("CounterType", CounterEnumType.LOYALTY); if (amount) { params.put("Amount", -2); params.put("Target", f.card); }
            entity(f, params); final var value = received(f.events.get(0)); Assert.assertEquals(value.get("kind"), "ENTITY_COUNTER_REQUEST");
            Assert.assertEquals(value.get("amount"), amount ? -2 : null); Assert.assertEquals(value.get("target") == null, !amount);
        }
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void knownNoParametersAndUnknownShapesAreNeverConflated() {
        final Fixture f = fixture("Vote"); final List<Map<String, Object>> empty = new ArrayList<>(); empty.add(null);
        empty.add(Map.of()); empty.add(Collections.emptyMap()); empty.add(ImmutableMap.of()); empty.add(new HashMap<>());
        for (var params : empty) { f.events.clear(); number(f, params); Assert.assertEquals(received(f.events.get(0)), Map.of("kind", "NONE")); }
        final var unreadable = Mockito.spy(f.other); Mockito.doThrow(new AssertionError("unknown shape value read")).when(unreadable).getId();
        final Map<String, Object> opaque = new AbstractMap<>() { @Override public Set<Entry<String, Object>> entrySet() { throw new AssertionError("unknown map enumerated"); } };
        final Map<String, Object> subclass = new HashMap<>() { @Override public int size() { throw new AssertionError("subclass inspected"); } };
        for (var params : List.of(opaque, subclass, map("Voter", unreadable, "Unknown", 1), map("Voter", null), map("Voter", f.card), map("Voter", unreadable, "Unknown1", 1, "Unknown2", 2, "Unknown3", 3))) {
            f.events.clear(); Assert.assertEquals(number(f, params), 2); Assert.assertNull(received(f.events.get(0)));
            Assert.assertEquals(receipt(f.events.get(0)).get("coverage"), "UNSUPPORTED");
            Assert.assertNotNull(((Map<?, ?>) context(f.events.get(0)).get("policy")).get("prompt"));
        }
        f.events.clear(); entity(f, map("Attachments", List.of(f.card))); Assert.assertNull(received(f.events.get(0)));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void arbitraryCounterImplementationsAndOversizedNativeNamesAreRefusedWhole() {
        final Fixture f = fixture("Vote"); final CounterType custom = new CounterType() {
            @Override public String getName() { throw new AssertionError("unknown CounterType read"); }
        };
        final var target = Mockito.spy(f.card); Mockito.doThrow(new AssertionError("rejected shape role read")).when(target).getId();
        number(f, map("Target", target, "CounterType", custom)); Assert.assertEquals(receipt(f.events.get(0)).get("coverage"), "UNSUPPORTED");
        f.events.clear(); number(f, map("Target", target, "CounterType", new CounterCustomType("x".repeat(257))));
        Assert.assertEquals(receipt(f.events.get(0)).get("coverage"), "UNAVAILABLE"); Assert.assertNull(receipt(f.events.get(0)).get("values"));
        Assert.assertNull(received(f.events.get(0))); Assert.assertTrue(context(f.events.get(0)).get("omissions").toString().contains("OUTSIDE_CAPTURED_PREFIX"));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void privateAndForeignEqualIdRolesStayUnresolved() {
        final Fixture f = fixture("Vote"); final Card hidden = addCardToZone("Black Lotus", f.other, ZoneType.Hand);
        final Card foreign = Mockito.spy(createCard("Grizzly Bears", f.other)); Mockito.doReturn(f.card.getId()).when(foreign).getId();
        Mockito.doReturn(f.card.getGameTimestamp()).when(foreign).getGameTimestamp();
        for (Card card : List.of(hidden, foreign)) {
            f.events.clear(); entity(f, map("Attach", card)); Assert.assertEquals(receipt(f.events.get(0)).get("coverage"), "COMPLETE");
            Assert.assertNull(received(f.events.get(0))); Assert.assertTrue(context(f.events.get(0)).get("omissions").toString().contains("UNRESOLVED_BINDING"));
        }
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void entryReceiptsAndFeaturesSurviveOriginalMapAndPlayerMutation() {
        final Fixture f = fixture("Vote"); final var params = map("Voter", f.other);
        Assert.assertEquals(AiNumberChoiceRecorder.controllerRange(f.player, f.ability, 0, 3, params, () -> { params.clear(); f.other.setLife(7, null); return 2; }), 2);
        Assert.assertEquals(received(f.events.get(0)), Map.of("kind", "VOTER", "voter", "p0"));
        Assert.assertEquals(((Map<?, ?>) receipt(f.events.get(0)).get("values")).get("voter"), Map.of("type", "PLAYER", "id", f.other.getId()));
        final var players = (List<?>) ((Map<?, ?>) context(f.events.get(0)).get("policy")).get("players"); Assert.assertEquals(((Map<?, ?>) players.get(0)).get("life"), 20);
        f.events.clear(); final var original = new IllegalStateException("original callback");
        Assert.assertSame(Assert.expectThrows(IllegalStateException.class, () -> AiNumberChoiceRecorder.controllerRange(f.player, f.ability, 0, 3,
                map("Voter", f.other), () -> { throw original; })), original);
        Assert.assertEquals(f.events.get(1).get("status"), "ERROR");
        Assert.assertEquals(received(f.events.get(0)), Map.of("kind", "VOTER", "voter", "p0"));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void aRoleMovedAfterReceiptCannotAcquireAnotherIncarnationsBinding() {
        final Fixture f = fixture("Vote"); final var input = AiReceivedParameters.capture(AiReceivedParameters.Family.ENTITY, map("Attach", f.card));
        f.game.getAction().moveToGraveyard(f.card, f.ability);
        final var context = AiInputContext.capture(f.player, f.ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true, null, null, new AiReceivedParameters.Carrier(new AtomicReference<>(input)));
        Assert.assertNull(((Map<?, ?>) context.get("policy")).get("receivedParameters")); Assert.assertTrue(context.get("omissions").toString().contains("UNRESOLVED_BINDING"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void parametersAreIndependentOfPlanningPromptPermissionAndChargeTheirFields() {
        final Fixture f = fixture("Vote"); final var state = f.controller.inputContextState(); long before = state.remainingWork();
        AiInputContext.capture(f.player, f.ability, "BEFORE_SELECTOR", "PLANNING_WORKING_STATE", () -> true); final long base = before - state.remainingWork(); before = state.remainingWork();
        final var carrier = new AiReceivedParameters.Carrier(new AtomicReference<>(AiReceivedParameters.capture(AiReceivedParameters.Family.NUMBER, map("Voter", f.other))));
        final var context = AiInputContext.capture(f.player, f.ability, "BEFORE_SELECTOR", "PLANNING_WORKING_STATE", () -> true,
                () -> { throw new AssertionError("unrelated planning prompt inspected"); }, null, carrier);
        Assert.assertEquals(before - state.remainingWork(), base + 2);
        Assert.assertEquals(((Map<?, ?>) context.get("policy")).get("receivedParameters"), Map.of("kind", "VOTER", "voter", "p0"));
        Assert.assertNull(((Map<?, ?>) context.get("policy")).get("prompt"));
    }

    @DataProvider public Object[][] bypass() { return new Object[][] {{"OFF"}, {"COPY"}, {"HIDDEN"}}; }
    @Test(dataProvider = "bypass", groups = {"UnitTest", "fast"})
    public void bypassNeverReadsReceivedParameterValues(String mode) {
        final Fixture f = fixture("Vote"); final var blocked = Mockito.spy(f.other); Mockito.doThrow(new AssertionError("bypassed role read")).when(blocked).getId();
        if (mode.equals("OFF")) { f.game.setRecordAiDecisions(false); }
        if (mode.equals("COPY")) { f.game.markAsSimulationCopy(); }
        if (mode.equals("HIDDEN")) { f.ability.getHostCard().turnFaceDown(); }
        Assert.assertEquals(number(f, map("Voter", blocked)), 2); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void liveGetterFailureIsVisibleAndOriginalCallbackStillRunsOnce() {
        final Fixture f = fixture("Vote"); final var broken = Mockito.spy(f.other); final var failure = new IllegalStateException("parameter ID");
        Mockito.doThrow(failure).when(broken).getId(); final var calls = new AtomicInteger();
        Assert.assertEquals(AiNumberChoiceRecorder.controllerRange(f.player, f.ability, 0, 3, map("Voter", broken), () -> { calls.incrementAndGet(); return 2; }), 2);
        Assert.assertEquals(calls.get(), 1); Assert.assertSame(f.game.getEventObservationFailure(), failure); Assert.assertTrue(f.events.isEmpty());
    }

    @DataProvider public Object[][] closure() { return new Object[][] {{false, false}, {false, true}, {true, false}, {true, true}}; }
    @Test(dataProvider = "closure", groups = {"UnitTest", "fast"})
    @SuppressWarnings("unchecked")
    public void blockedGetterCannotHoldClosureOrPoisonHealthWithLateValuesOrFaults(boolean parentClosure, boolean fail) throws Exception {
        final Fixture f = fixture("Vote"); final var blocked = Mockito.spy(f.other); final long id = f.other.getId();
        final var entered = new CountDownLatch(1); final var release = new CountDownLatch(1); final var calls = new AtomicInteger();
        Mockito.doAnswer(call -> { entered.countDown(); Assert.assertTrue(release.await(5, TimeUnit.SECONDS)); if (fail) { throw new IllegalStateException("obsolete parameters"); } return id; }).when(blocked).getId();
        final var parent = new AiDecisionRecorder(f.game, f.player, 1); parent.beginAssessment(f.ability);
        final var field = AiController.class.getDeclaredField("evaluationDecision"); field.setAccessible(true); final ThreadLocal<AiDecisionRecorder> evaluation = (ThreadLocal<AiDecisionRecorder>) field.get(f.controller.getAi());
        final var worker = Executors.newSingleThreadExecutor();
        try {
            final var result = worker.submit(() -> { if (parentClosure) { evaluation.set(parent); } try {
                return AiNumberChoiceRecorder.controllerRange(f.player, f.ability, 0, 3, map("Voter", blocked), () -> { calls.incrementAndGet(); return 2; });
            } finally { evaluation.remove(); } });
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS)); if (parentClosure) { Assert.assertNotNull(parent.finish(null, "AI_TIMEOUT", null)); } else { f.game.setRecordAiDecisions(false); }
            release.countDown(); Assert.assertEquals(result.get(5, TimeUnit.SECONDS), 2); Assert.assertEquals(calls.get(), 1); Assert.assertTrue(f.events.isEmpty()); Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
}
