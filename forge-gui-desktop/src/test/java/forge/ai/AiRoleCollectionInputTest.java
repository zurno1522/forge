package forge.ai;

import com.google.common.collect.ImmutableList;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.effects.AttachEffect;
import forge.game.ability.effects.MustBlockEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollection;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class AiRoleCollectionInputTest extends AITest {
    private record Fixture(Game game, Player ai, Player other, SpellAbility ability, Card card, List<Map<String, Object>> events) { }
    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player ai = game.getPlayers().get(1), other = game.getPlayers().get(0);
        final Card host = addCardToZone("Lightning Greaves", ai, ZoneType.Battlefield);
        final SpellAbility ability = AbilityFactory.getAbility("AB$ Attach | Cost$ 0 | ValidTgts$ Creature.YouCtrl", host); ability.setActivatingPlayer(ai);
        final Card card = addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        final var events = new ArrayList<Map<String, Object>>(); game.setRecordAiDecisions(true);
        game.observeEventEmission(e -> { if (e instanceof AiChoiceEvent c) { events.add(c.data()); } });
        return new Fixture(game, ai, other, ability, card, events);
    }
    private Map<String, Object> params(String key, Object value) { final var map = new HashMap<String, Object>(); map.put(key, value); return map; }
    private Map<?, ?> context(Fixture f) { return (Map<?, ?>) f.events.get(0).get("inputContext"); }
    private Map<?, ?> received(Fixture f) { return (Map<?, ?>) ((Map<?, ?>) context(f).get("policy")).get("receivedParameters"); }
    private Map<?, ?> receipt(Fixture f) { return (Map<?, ?>) ((Map<?, ?>) f.events.get(0).get("options")).get("callbackParameters"); }
    private Card select(Fixture f, Map<String, Object> params) {
        return AiEffectChoiceRecorder.single(f.ai, f.ability, new FCollection<>(List.of(f.card)), false, null, params, () -> f.card);
    }
    private Map<?, ?> project(Fixture f, AiReceivedParameters.Input input) {
        return AiInputContext.capture(f.ai, f.ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true, null, null,
                new AiReceivedParameters.Carrier(new AtomicReference<>(input)));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualAttachmentEffectRecordsItsSourceRoleAndStillAttachesTheEquipment() {
        final Fixture f = fixture(); f.ability.getTargets().add(f.card); new AttachEffect().resolve(f.ability);
        Assert.assertSame(f.ability.getHostCard().getAttachedTo(), f.card); Assert.assertEquals(received(f).get("kind"), "ENTITY_ATTACHMENT_ROLES");
        Assert.assertEquals(((List<?>) received(f).get("entities")).size(), 1);
        final var values = (Map<?, ?>) receipt(f).get("values"); final var raw = (Map<?, ?>) ((List<?>) values.get("entities")).get(0);
        Assert.assertEquals(raw.get("id"), f.ability.getHostCard().getId()); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualMustBlockEffectRecordsItsAttackerAndKeepsItsBlockingRequirement() {
        final Fixture f = fixture(); final Card attacker = addCardToZone("Shivan Dragon", f.ai, ZoneType.Battlefield);
        final Card blocker = addCardToZone("Hill Giant", f.other, ZoneType.Battlefield);
        final var ability = AbilityFactory.getAbility("AB$ MustBlock | Cost$ 0 | Choices$ Creature.OppCtrl", attacker); ability.setActivatingPlayer(f.ai);
        new MustBlockEffect().resolve(ability); Assert.assertTrue(blocker.getMustBlockCards().contains(attacker));
        Assert.assertEquals(received(f).get("kind"), "ENTITY_ATTACKER_ROLES"); Assert.assertEquals(((List<?>) received(f).get("entities")).size(), 1);
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void nativeRequestsKeepOriginalOrderDuplicatesAndDetachedPreCallbackValues() {
        final Fixture f = fixture(); final var second = addCardToZone("Hill Giant", f.ai, ZoneType.Battlefield);
        final var roles = new ArrayList<>(List.of(f.card, second, f.card)); final var map = params("Attachments", roles);
        final Card result = AiChoiceRecorder.planning(f.ai, () -> AiEffectChoiceRecorder.single(f.ai, f.ability,
                new FCollection<>(List.of(f.card)), false, null, map, () -> { roles.clear(); map.clear(); return f.card; }));
        Assert.assertSame(result, f.card); Assert.assertTrue(roles.isEmpty()); Assert.assertTrue(map.isEmpty());
        final var locals = (List<?>) received(f).get("entities"); Assert.assertEquals(locals.size(), 3); Assert.assertEquals(locals.get(0), locals.get(2)); Assert.assertNotEquals(locals.get(0), locals.get(1));
        final var raw = (List<?>) ((Map<?, ?>) receipt(f).get("values")).get("entities"); Assert.assertEquals(raw.size(), 3); Assert.assertEquals(raw.get(0), raw.get(2));
        Assert.assertEquals(context(f).get("stateBasis"), "PLANNING_WORKING_STATE"); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void canonicalAndNativeEmptyListsAreTypedEmptyRolesRatherThanNoParameters() {
        final Fixture f = fixture();
        for (final var empty : List.of(CardCollection.EMPTY, List.of(), Collections.emptyList(), ImmutableList.of(), new CardCollection(), new ArrayList<>())) {
            for (String key : List.of("Attachments", "Attackers")) {
                f.events.clear(); Assert.assertSame(select(f, params(key, empty)), f.card);
                Assert.assertEquals(received(f), Map.of("kind", key.equals("Attachments") ? "ENTITY_ATTACHMENT_ROLES" : "ENTITY_ATTACKER_ROLES", "entities", List.of()));
            }
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void sixtyFourRolesAreCompleteAndSixtyFiveAreRefusedBeforeAnyIdentityRead() {
        final Fixture f = fixture(); select(f, params("Attachments", new ArrayList<>(Collections.nCopies(64, f.card))));
        Assert.assertEquals(((List<?>) received(f).get("entities")).size(), 64);
        final Card unreadable = Mockito.spy(f.card); Mockito.doThrow(new AssertionError("oversized identity read")).when(unreadable).getId();
        f.events.clear(); select(f, params("Attackers", new ArrayList<>(Collections.nCopies(65, unreadable))));
        Assert.assertEquals(receipt(f).get("coverage"), "UNAVAILABLE"); Assert.assertNull(receipt(f).get("values")); Assert.assertNull(received(f));
        Assert.assertTrue(context(f).get("omissions").toString().contains("OUTSIDE_CAPTURED_PREFIX")); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void unknownCollectionsAndWrongWholeShapesNeverReadCollectionOrEntityGetters() {
        final Fixture f = fixture(); final Card unreadable = Mockito.spy(f.card); Mockito.doThrow(new AssertionError("invalid request identity read")).when(unreadable).getId();
        final var opaque = new AbstractCollection<Card>() {
            @Override public int size() { throw new AssertionError("opaque size"); }
            @Override public Iterator<Card> iterator() { throw new AssertionError("opaque iterator"); }
        };
        final var subclass = new ArrayList<Card>() { @Override public int size() { throw new AssertionError("subclass size"); } };
        final var cardSubclass = new CardCollection() { @Override public int size() { throw new AssertionError("card subclass size"); } };
        final var wrong = new ArrayList<Object>(); wrong.add(unreadable); wrong.add(null);
        for (Object value : List.of(opaque, subclass, cardSubclass, wrong, List.of(unreadable), new ArrayList<>(List.of(f.other)))) {
            f.events.clear(); select(f, params("Attachments", value)); Assert.assertEquals(receipt(f).get("coverage"), "UNSUPPORTED"); Assert.assertNull(received(f));
        }
        f.events.clear(); select(f, params("Attackers", null)); Assert.assertEquals(receipt(f).get("coverage"), "UNSUPPORTED");
        final var extra = params("Attachments", new ArrayList<>(List.of(unreadable))); extra.put("unknown", 1);
        f.events.clear(); select(f, extra); Assert.assertEquals(receipt(f).get("coverage"), "UNSUPPORTED"); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void onePrivateForeignOrStaleMemberRefusesTheWholeFeatureList() {
        final Fixture f = fixture(); final Card hidden = addCardToZone("Black Lotus", f.other, ZoneType.Hand);
        final Card foreign = Mockito.spy(createCard("Grizzly Bears", f.ai)); Mockito.doReturn(f.card.getId()).when(foreign).getId();
        Mockito.doReturn(f.card.getGameTimestamp()).when(foreign).getGameTimestamp();
        for (Card bad : List.of(hidden, foreign)) {
            f.events.clear(); select(f, params("Attachments", new ArrayList<>(List.of(f.card, bad))));
            Assert.assertNull(received(f)); Assert.assertEquals(receipt(f).get("coverage"), "COMPLETE"); Assert.assertTrue(context(f).get("omissions").toString().contains("UNRESOLVED_BINDING"));
        }
        final var input = AiReceivedParameters.capture(AiReceivedParameters.Family.ENTITY, params("Attackers", new CardCollection(f.card)));
        f.game.getAction().moveToGraveyard(f.card, null); final var ctx = project(f, input);
        Assert.assertNull(((Map<?, ?>) ctx.get("policy")).get("receivedParameters")); Assert.assertTrue(ctx.get("omissions").toString().contains("UNRESOLVED_BINDING"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void roleWorkIsChargedPerOccurrenceAndNumericPermissionNeverExpands() {
        final Fixture f = fixture(); final var state = ((PlayerControllerAi) f.ai.getController()).inputContextState();
        long before = state.remainingWork(); project(f, AiReceivedParameters.capture(AiReceivedParameters.Family.ENTITY, params("Attachments", new ArrayList<>()))); final long empty = before - state.remainingWork();
        before = state.remainingWork(); project(f, AiReceivedParameters.capture(AiReceivedParameters.Family.ENTITY, params("Attachments", new ArrayList<>(Collections.nCopies(3, f.card)))));
        Assert.assertEquals(before - state.remainingWork(), empty + 3);
        final var input = AiReceivedParameters.capture(AiReceivedParameters.Family.NUMBER, params("Attachments", new CardCollection(f.card)));
        Assert.assertEquals(input.coverage(), "UNSUPPORTED"); Assert.assertNull(input.values());
    }
}
