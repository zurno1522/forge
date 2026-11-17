package forge.view;

import com.google.gson.Gson;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

public class SimulationTargetSnapshotTest extends AITest {
    private SpellAbility targeted(final Card source, final Player player) {
        final SpellAbility sa = AbilityFactory.getAbility(
                "AB$ DealDamage | Cost$ 0 | ValidTgts$ Any | NumDmg$ 3 | SpellDescription$ Target snapshot fixture.", source);
        sa.setActivatingPlayer(player);
        return sa;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> groups(final Map<String, Object> snapshot) {
        return (List<Map<String, Object>>) snapshot.get("abilities");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> targets(final Map<String, Object> group) {
        return (List<Map<String, Object>>) group.get("targets");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void preservesMixedTargetOrderSubAbilitiesAndAllocationsWithoutIdentities() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        final Card source = addCardToZone("Island", ai, ZoneType.Battlefield);
        final Card hidden = addCardToZone("Griselbrand", opponent, ZoneType.Battlefield);
        hidden.turnFaceDown(true);
        final SpellAbility other = AbilityFactory.getAbility("AB$ Draw | Cost$ 0 | NumCards$ 1", source);
        other.setActivatingPlayer(ai);
        other.setCopied(true);
        game.getStack().add(other, (1L << 40) + 7);
        final SpellAbility sa = targeted(source, ai);
        sa.getTargets().add(opponent);
        sa.getTargets().add(hidden);
        sa.getTargets().add(other);
        sa.addDividedAllocation(opponent, 1);
        sa.addDividedAllocation(hidden, 2);
        final AbilitySub sub = (AbilitySub) AbilityFactory.getAbility(
                "DB$ DealDamage | ValidTgts$ Player | NumDmg$ 1", source);
        sub.getTargets().add(ai);
        sa.setSubAbility(sub);

        final Map<String, Object> snapshot = SimulationTargetSnapshot.capture(new SpellAbilityStackInstance(sa), game.getStack());
        Assert.assertEquals(snapshot.get("schemaVersion"), 1);
        final List<Map<String, Object>> groups = groups(snapshot);
        Assert.assertEquals(groups.size(), 2);
        Assert.assertEquals(groups.get(0).get("abilityId"), sa.getId());
        Assert.assertEquals(groups.get(1).get("abilityId"), sub.getId());
        final List<Map<String, Object>> targets = targets(groups.get(0));
        Assert.assertEquals(targets.stream().map(t -> t.get("type")).toList(), List.of("PLAYER", "CARD", "SPELL_ABILITY"));
        Assert.assertEquals(targets.get(0).get("id"), opponent.getId());
        Assert.assertEquals(targets.get(0).get("dividedAmount"), 1);
        Assert.assertEquals(targets.get(1).get("id"), hidden.getId());
        Assert.assertEquals(targets.get(1).get("gameTimestamp"), hidden.getGameTimestamp());
        Assert.assertEquals(targets.get(1).get("dividedAmount"), 2);
        Assert.assertEquals(targets.get(2).get("stackId"), (1L << 40) + 7);
        Assert.assertEquals(targets(groups.get(1)).get(0).get("id"), ai.getId());
        final String frozen = new Gson().toJson(snapshot);
        Assert.assertFalse(frozen.contains("Griselbrand"));
        Assert.assertFalse(frozen.contains("Target snapshot fixture"));
        Assert.assertFalse(frozen.contains("Island"));
        Assert.assertEquals(sa.getTargets().size(), 3, "Capture must not change engine choices");
        sa.resetTargets();
        sub.resetTargets();
        hidden.setGameTimestamp(hidden.getGameTimestamp() + 1);
        Assert.assertEquals(new Gson().toJson(snapshot), frozen, "Snapshot must not retain mutable engine objects");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void distinguishesUntargetedOptionalZeroTargetsAndUnavailableEntries() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCardToZone("Memnite", ai, ZoneType.Hand);
        final SpellAbility untargeted = source.getFirstSpellAbility();
        final Map<String, Object> noTargets = groups(SimulationTargetSnapshot.capture(
                new SpellAbilityStackInstance(untargeted), game.getStack())).get(0);
        Assert.assertEquals(noTargets.get("usesTargeting"), false);
        Assert.assertTrue(targets(noTargets).isEmpty());
        final SpellAbility optional = AbilityFactory.getAbility(
                "AB$ DealDamage | Cost$ 0 | ValidTgts$ Any | TargetMin$ 0 | NumDmg$ 1", source);
        final Map<String, Object> zeroTargets = groups(SimulationTargetSnapshot.capture(
                new SpellAbilityStackInstance(optional), game.getStack())).get(0);
        Assert.assertEquals(zeroTargets.get("usesTargeting"), true);
        Assert.assertTrue(targets(zeroTargets).isEmpty());
        Assert.assertNull(SimulationTargetSnapshot.capture(null, game.getStack()));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void preservesLongCardIdentityAndDoesNotGuessMissingStackIds() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCardToZone("Island", ai, ZoneType.Battlefield);
        final Card card = new Card((1L << 41) + 3, game);
        card.setOwner(ai);
        card.setGameTimestamp((1L << 50) + 5);
        final SpellAbility sa = targeted(source, ai);
        sa.getTargets().add(card);
        sa.getTargets().add(targeted(source, ai)); // No live stack entry for this reference.
        final List<Map<String, Object>> targets = targets(groups(SimulationTargetSnapshot.capture(
                new SpellAbilityStackInstance(sa), game.getStack())).get(0));
        Assert.assertEquals(targets.get(0).get("id"), (1L << 41) + 3);
        Assert.assertEquals(targets.get(0).get("gameTimestamp"), (1L << 50) + 5);
        Assert.assertTrue(targets.get(1).containsKey("stackId"));
        Assert.assertNull(targets.get(1).get("stackId"));
    }
}
