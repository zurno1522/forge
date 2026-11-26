package forge.ai;

import com.google.common.collect.ImmutableMap;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.effects.AssignGroupEffect;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
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

public class AiSpellRequestInputTest extends AITest {
    private record Fixture(Game game, Player player, Player other, PlayerControllerAi controller,
            SpellAbility ability, List<SpellAbility> spells, List<Map<String, Object>> events) { }
    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player player = game.getPlayers().get(1), other = game.getPlayers().get(0);
        final Card source = addCardToZone("Time Beetle", player, ZoneType.Battlefield);
        source.setSVar("Friend", "DB$ GainLife | Defined$ Remembered | LifeAmount$ 3");
        source.setSVar("Foe", "DB$ LoseLife | Defined$ Remembered | LifeAmount$ 2");
        final SpellAbility ability = AbilityFactory.getAbility("AB$ AssignGroup | Cost$ 0 | Defined$ Player | Choices$ Friend,Foe | AILogic$ FriendOrFoe", source);
        ability.setActivatingPlayer(player); final List<SpellAbility> spells = new ArrayList<>(ability.getAdditionalAbilityList("Choices"));
        for (var choice : spells) { choice.setActivatingPlayer(player); }
        final List<Map<String, Object>> events = new ArrayList<>(); game.setRecordAiDecisions(true);
        game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { events.add(choice.data()); } });
        return new Fixture(game, player, other, (PlayerControllerAi) player.getController(), ability, spells, events);
    }
    private Map<String, Object> affected(Object value) { final Map<String, Object> params = new HashMap<>(); params.put("Affected", value); return params; }
    private Map<?, ?> input(Map<String, Object> row) { return (Map<?, ?>) row.get("inputContext"); }
    private Map<?, ?> prompt(Map<String, Object> row) { return (Map<?, ?>) ((Map<?, ?>) input(row).get("policy")).get("prompt"); }
    private Map<?, ?> receipt(Map<String, Object> row) { return (Map<?, ?>) ((Map<?, ?>) row.get("options")).get("spellRequest"); }
    private SpellAbility record(Fixture f, Map<String, Object> params) {
        return AiSpellChoiceRecorder.single(f.player, f.ability, f.spells, params, () -> f.spells.get(0));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualFriendOrFoeControllerUsesAndRecordsTheCurrentAffectedPlayer() {
        final Fixture f = fixture();
        Assert.assertSame(f.controller.chooseSingleSpellForEffect(f.spells, f.ability, "Choose", affected(f.player)), f.spells.get(0));
        Assert.assertSame(f.controller.chooseSingleSpellForEffect(f.spells, f.ability, "Choose", affected(f.other)), f.spells.get(1));
        Assert.assertEquals(prompt(f.events.get(0)), Map.of("kind", "SPELL_ABILITY_REQUEST", "affectedPlayer", "p1"));
        Assert.assertEquals(prompt(f.events.get(2)), Map.of("kind", "SPELL_ABILITY_REQUEST", "affectedPlayer", "p0"));
        Assert.assertEquals(receipt(f.events.get(2)).get("affectedPlayerId"), f.other.getId());
        Assert.assertEquals(((Map<?, ?>) input(f.events.get(2)).get("coverage")).get("prompt"), "RECEIVED_SPELL_REQUEST_V1");
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualGroupAssignmentRecordsBothRolesBeforeApplyingTheirEffects() {
        final Fixture f = fixture(); new AssignGroupEffect().resolve(f.ability);
        Assert.assertEquals(f.player.getLife(), 23); Assert.assertEquals(f.other.getLife(), 18);
        final var starts = f.events.stream().filter(c -> "STARTED".equals(c.get("status"))).toList(); Assert.assertEquals(starts.size(), 2);
        Assert.assertEquals(starts.stream().map(c -> prompt(c).get("affectedPlayer")).collect(java.util.stream.Collectors.toSet()), Set.of("p0", "p1"));
        for (var start : starts) {
            final var players = (List<?>) ((Map<?, ?>) input(start).get("policy")).get("players");
            Assert.assertTrue(players.stream().allMatch(p -> Integer.valueOf(20).equals(((Map<?, ?>) p).get("life"))));
        }
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void auditedNullAndEmptyMapsProduceKnownNoPlayerRequests() {
        final List<Map<String, Object>> requests = new ArrayList<>(); requests.add(null); requests.add(Map.of());
        requests.add(ImmutableMap.of()); requests.add(Collections.emptyMap()); requests.add(new HashMap<>());
        for (var params : requests) {
            final Fixture f = fixture(); Assert.assertSame(record(f, params), f.spells.get(0));
            Assert.assertEquals(receipt(f.events.get(0)).get("coverage"), "COMPLETE");
            Assert.assertNull(receipt(f.events.get(0)).get("affectedPlayerId")); Assert.assertEquals(prompt(f.events.get(0)).size(), 2);
            Assert.assertEquals(prompt(f.events.get(0)).get("kind"), "SPELL_ABILITY_REQUEST"); Assert.assertNull(prompt(f.events.get(0)).get("affectedPlayer"));
            Assert.assertNull(f.game.getEventObservationFailure());
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void unsupportedMapsAndValuesStayUnknownWithoutDereferencingTheirContents() {
        final Fixture f = fixture(); final var unreadable = Mockito.spy(f.other);
        Mockito.doThrow(new AssertionError("unsupported value ID read")).when(unreadable).getId();
        final Map<String, Object> extra = affected(unreadable); extra.put("Other", "private sentinel");
        final Map<String, Object> unknownKey = new HashMap<>(); unknownKey.put("Unknown", unreadable);
        final Map<String, Object> opaque = new AbstractMap<>() {
            @Override public Set<Entry<String, Object>> entrySet() { throw new AssertionError("unknown map inspected"); }
        };
        final Map<String, Object> subclass = new HashMap<>() {
            @Override public int size() { throw new AssertionError("unknown map implementation inspected"); }
        };
        final Card card = Mockito.spy(createCard("Island", f.player)); Mockito.doThrow(new AssertionError("unsupported Card read")).when(card).getId();
        for (var params : java.util.Arrays.asList(extra, unknownKey, opaque, subclass, affected(null), affected(card), affected(f.spells.get(0)))) {
            f.events.clear(); Assert.assertSame(record(f, params), f.spells.get(0)); final var start = f.events.get(0);
            Assert.assertEquals(receipt(start).get("coverage"), "UNSUPPORTED"); Assert.assertNull(receipt(start).get("affectedPlayerId"));
            Assert.assertNull(prompt(start)); Assert.assertTrue(input(start).get("omissions").toString().contains("UNSUPPORTED"));
            Assert.assertFalse(start.toString().contains("private sentinel")); Assert.assertNull(f.game.getEventObservationFailure());
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void mapMutationAndPlayerChangesAfterEntryDoNotRewriteTheReceivedRoleOrView() {
        final Fixture f = fixture(); final Map<String, Object> params = affected(f.other);
        Assert.assertSame(AiSpellChoiceRecorder.single(f.player, f.ability, f.spells, params, () -> {
            params.clear(); params.put("Affected", f.player); f.other.setLife(7, null); return f.spells.get(0);
        }), f.spells.get(0));
        Assert.assertEquals(receipt(f.events.get(0)).get("affectedPlayerId"), f.other.getId());
        Assert.assertEquals(prompt(f.events.get(0)).get("affectedPlayer"), "p0");
        final var players = (List<?>) ((Map<?, ?>) input(f.events.get(0)).get("policy")).get("players");
        Assert.assertEquals(((Map<?, ?>) players.get(0)).get("life"), 20); Assert.assertEquals(f.other.getLife(), 7);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void foreignEqualIdPlayersDoNotAcquireACurrentRoleBinding() {
        final Fixture f = fixture(); final var foreign = Mockito.spy(initAndCreateGame().getPlayers().get(0));
        Mockito.doReturn(f.other.getId()).when(foreign).getId();
        record(f, affected(foreign)); final var start = f.events.get(0);
        Assert.assertEquals(receipt(start).get("coverage"), "COMPLETE"); Assert.assertEquals(receipt(start).get("affectedPlayerId"), f.other.getId());
        Assert.assertNull(prompt(start)); Assert.assertTrue(input(start).get("omissions").toString().contains("UNRESOLVED_BINDING"));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void planningCarrierChargesOnePromptAndDoesNotInspectArbitrarySuppliers() {
        final Fixture f = fixture(); final var state = f.controller.inputContextState(); long before = state.remainingWork();
        AiInputContext.capture(f.player, f.ability, "BEFORE_SELECTOR", "PLANNING_WORKING_STATE", () -> true);
        final long base = before - state.remainingWork(); before = state.remainingWork();
        final var carrier = new AiPromptInput.SpellRequest(new AtomicReference<>(AiSpellRequestInput.capture(affected(f.other))));
        final var context = AiInputContext.capture(f.player, f.ability, "BEFORE_SELECTOR", "PLANNING_WORKING_STATE", () -> true, carrier);
        Assert.assertEquals(before - state.remainingWork(), base + 1);
        Assert.assertEquals(((Map<?, ?>) ((Map<?, ?>) context.get("policy")).get("prompt")).get("affectedPlayer"), "p0");
        final var unknown = AiInputContext.capture(f.player, f.ability, "BEFORE_SELECTOR", "PLANNING_WORKING_STATE", () -> true,
                () -> { throw new AssertionError("unrelated planning supplier inspected"); });
        Assert.assertNull(((Map<?, ?>) unknown.get("policy")).get("prompt"));
    }

    @DataProvider public Object[][] bypass() { return new Object[][] {{"OFF"}, {"COPY"}, {"HIDDEN"}}; }
    @Test(dataProvider = "bypass", groups = {"UnitTest", "fast"})
    public void bypassedRequestsNeverReadTheAffectedPlayer(final String mode) {
        final Fixture f = fixture(); final var forbidden = Mockito.spy(f.other);
        Mockito.doThrow(new AssertionError("bypassed request read")).when(forbidden).getId();
        if (mode.equals("OFF")) { f.game.setRecordAiDecisions(false); }
        if (mode.equals("COPY")) { f.game.markAsSimulationCopy(); }
        if (mode.equals("HIDDEN")) { f.ability.getHostCard().turnFaceDown(); }
        Assert.assertSame(record(f, affected(forbidden)), f.spells.get(0)); Assert.assertNull(f.game.getEventObservationFailure());
        if (mode.equals("HIDDEN")) { Assert.assertEquals(f.events.get(0).get("choiceType"), "MASKED_CHOICE"); }
        else { Assert.assertTrue(f.events.isEmpty()); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void liveRoleGetterFailureIsVisibleWithoutChangingTheCallback() {
        final Fixture f = fixture(); final var broken = Mockito.spy(f.other); final var failure = new IllegalStateException("role getter");
        Mockito.doThrow(failure).when(broken).getId(); final AtomicInteger calls = new AtomicInteger();
        Assert.assertSame(AiSpellChoiceRecorder.single(f.player, f.ability, f.spells, affected(broken), () -> { calls.incrementAndGet(); return f.spells.get(0); }), f.spells.get(0));
        Assert.assertEquals(calls.get(), 1); Assert.assertSame(f.game.getEventObservationFailure(), failure); Assert.assertTrue(f.events.isEmpty());
    }

    @DataProvider public Object[][] closure() { return new Object[][] {{false, false}, {false, true}, {true, false}, {true, true}}; }
    @Test(dataProvider = "closure", groups = {"UnitTest", "fast"})
    @SuppressWarnings("unchecked")
    public void blockedRoleGetterCannotHoldClosureOrPublishLateValuesOrFaults(boolean parentClosure, boolean fail) throws Exception {
        final Fixture f = fixture(); final var blocked = Mockito.spy(f.other); final long id = f.other.getId();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); final AtomicInteger calls = new AtomicInteger();
        Mockito.doAnswer(call -> { entered.countDown(); Assert.assertTrue(release.await(5, TimeUnit.SECONDS));
            if (fail) { throw new IllegalStateException("obsolete role getter"); } return id; }).when(blocked).getId();
        final var parent = new AiDecisionRecorder(f.game, f.player, 1); parent.beginAssessment(f.ability);
        final var field = AiController.class.getDeclaredField("evaluationDecision"); field.setAccessible(true);
        final ThreadLocal<AiDecisionRecorder> evaluation = (ThreadLocal<AiDecisionRecorder>) field.get(f.controller.getAi());
        final var worker = Executors.newSingleThreadExecutor();
        try {
            final var result = worker.submit(() -> {
                if (parentClosure) { evaluation.set(parent); }
                try { return AiSpellChoiceRecorder.single(f.player, f.ability, f.spells, affected(blocked), () -> { calls.incrementAndGet(); return f.spells.get(0); }); }
                finally { evaluation.remove(); }
            });
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
            if (parentClosure) { Assert.assertNotNull(parent.finish(null, "AI_TIMEOUT", null)); } else { f.game.setRecordAiDecisions(false); }
            release.countDown(); Assert.assertSame(result.get(5, TimeUnit.SECONDS), f.spells.get(0));
            Assert.assertEquals(calls.get(), 1); Assert.assertTrue(f.events.isEmpty()); Assert.assertNull(f.game.getEventObservationFailure());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
}
