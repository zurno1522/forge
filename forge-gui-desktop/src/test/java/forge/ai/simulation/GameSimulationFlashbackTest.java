package forge.ai.simulation;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

public class GameSimulationFlashbackTest extends AITest {
    @Test(groups = { "UnitTest", "fast" })
    public void snapcasterGrantedFlashbackCanBePlayedInCopyWithoutMutatingOriginal() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCards("Forest", 4, ai);
        addCards("Island", 2, ai);
        final Card snapcaster = addCard("Snapcaster Mage", ai);
        final Card claim = addCardToZone("Skyshroud Claim", ai, ZoneType.Graveyard);
        for (final Player p : game.getPlayers()) { fillLibrary(p, 20); }
        addCardToZone("Forest", ai, ZoneType.Library);
        addCardToZone("Forest", ai, ZoneType.Library);
        final SpellAbility grant = AbilityFactory.getAbility(snapcaster.getSVar("TrigFlashback"), snapcaster);
        grant.setActivatingPlayer(ai);
        grant.getTargets().add(claim);
        AbilityUtils.resolve(grant);
        Assert.assertTrue(claim.hasKeyword(Keyword.FLASHBACK));
        final SpellAbility flashback = claim.getAllPossibleAbilities(ai, true).stream()
                .filter(SpellAbility::isFlashback).findFirst().orElseThrow();
        flashback.setActivatingPlayer(ai);
        final GameSimulator simulator = GameSimulator.forRollout(game, ai, false);
        final Card copy = simulator.getGameCopier().find(claim);
        Assert.assertTrue(copy.hasKeyword(Keyword.FLASHBACK), "Granted flashback must survive copying a graveyard card");
        Assert.assertTrue(simulator.applySpellAbility(flashback), "The same flashback play must work in the simulation");
        Assert.assertEquals(simulator.getSimulatedGameState().findById(claim.getId()).getZone().getZoneType(), ZoneType.Exile);
        Assert.assertEquals(simulator.getSimulatedAiPlayer().getCardsIn(ZoneType.Battlefield).stream().filter(Card::isLand).count(), 8L);
        Assert.assertEquals(claim.getZone().getZoneType(), ZoneType.Graveyard);
        Assert.assertEquals(ai.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isLand).count(), 6L);
        Assert.assertFalse(claim.isTapped());
    }
    @Test(groups = { "UnitTest", "fast" })
    public void copiedGrantExpiresWithoutExpiringSourceOrPrintedFlashback() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCard("Snapcaster Mage", ai);
        final Card claim = addCardToZone("Skyshroud Claim", ai, ZoneType.Graveyard);
        final Card nativeFlashback = addCardToZone("Think Twice", ai, ZoneType.Graveyard);
        final SpellAbility grant = AbilityFactory.getAbility(source.getSVar("TrigFlashback"), source);
        grant.setActivatingPlayer(ai);
        grant.getTargets().add(claim);
        AbilityUtils.resolve(grant);
        final GameCopier copier = new GameCopier(game);
        final Game copy = copier.makeCopy(null, ai);
        Assert.assertTrue(copier.find(claim).hasKeyword(Keyword.FLASHBACK));
        copy.getEndOfTurn().executeUntil();
        Assert.assertFalse(copier.find(claim).hasKeyword(Keyword.FLASHBACK));
        Assert.assertTrue(copier.find(nativeFlashback).hasKeyword(Keyword.FLASHBACK));
        Assert.assertTrue(claim.hasKeyword(Keyword.FLASHBACK), "Copy expiration must not mutate the original grant");
        game.getEndOfTurn().executeUntil();
        Assert.assertFalse(claim.hasKeyword(Keyword.FLASHBACK));
        Assert.assertTrue(nativeFlashback.hasKeyword(Keyword.FLASHBACK));
    }

    @Test(groups = { "UnitTest", "fast" })
    public void mappedPumpExpirationPreservesOriginalCreatureStats() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card bear = addCard("Runeclaw Bear", ai);
        final Card growth = addCardToZone("Giant Growth", ai, ZoneType.Hand);
        final SpellAbility pump = growth.getFirstSpellAbility();
        pump.setActivatingPlayer(ai);
        pump.getTargets().add(bear);
        AbilityUtils.resolve(pump);
        Assert.assertEquals(bear.getNetPower(), 5);
        final GameCopier copier = new GameCopier(game);
        final Game copy = copier.makeCopy(null, ai);
        Assert.assertEquals(copier.find(bear).getNetPower(), 5);
        copy.getEndOfTurn().executeUntil();
        Assert.assertEquals(copier.find(bear).getNetPower(), 2);
        Assert.assertEquals(bear.getNetPower(), 5);
        game.getEndOfTurn().executeUntil();
        Assert.assertEquals(bear.getNetPower(), 2);
    }

    @Test(groups = { "UnitTest", "fast" })
    public void grantedFlashbackKeepsItsOwnCostBesidePrintedFlashback() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCards("Island", 2, ai);
        for (final Player p : game.getPlayers()) { fillLibrary(p, 20); }
        final Card source = addCard("Snapcaster Mage", ai);
        final Card think = addCardToZone("Think Twice", ai, ZoneType.Graveyard);
        final SpellAbility grant = AbilityFactory.getAbility(source.getSVar("TrigFlashback"), source);
        grant.setActivatingPlayer(ai);
        grant.getTargets().add(think);
        AbilityUtils.resolve(grant);
        final SpellAbility cheaper = think.getAllPossibleAbilities(ai, true).stream()
                .filter(sa -> sa.isFlashback() && sa.getPayCosts().getTotalMana().getCMC() == 2)
                .findFirst().orElseThrow();
        cheaper.setActivatingPlayer(ai);
        final GameSimulator simulator = GameSimulator.forRollout(game, ai, false);
        Assert.assertTrue(simulator.applySpellAbility(cheaper), "Two mana must pay the granted cost, not the printed three-mana flashback");
        Assert.assertEquals(simulator.getSimulatedGameState().findById(think.getId()).getZone().getZoneType(), ZoneType.Exile);
        Assert.assertEquals(think.getZone().getZoneType(), ZoneType.Graveyard);
    }

    @Test(groups = { "UnitTest", "fast" })
    public void playerSpecificExpirationUsesTheCopiedPlayer() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCard("Snapcaster Mage", ai);
        final Card target = addCardToZone("Skyshroud Claim", ai, ZoneType.Graveyard);
        final SpellAbility grant = AbilityFactory.getAbility(source.getSVar("TrigFlashback") + " | Duration$ UntilYourNextUpkeep", source);
        grant.setActivatingPlayer(ai);
        grant.getTargets().add(target);
        AbilityUtils.resolve(grant);
        final GameCopier copier = new GameCopier(game);
        final Game copy = copier.makeCopy(null, ai);
        Assert.assertTrue(copier.find(target).hasKeyword(Keyword.FLASHBACK));
        copy.getUpkeep().executeUntil(copier.find(ai));
        Assert.assertFalse(copier.find(target).hasKeyword(Keyword.FLASHBACK));
        Assert.assertTrue(target.hasKeyword(Keyword.FLASHBACK));
    }

    @Test(groups = { "UnitTest", "fast" })
    public void zoneChangeDoesNotRegrantExpiredPermissionInCopy() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCard("Snapcaster Mage", ai);
        final Card target = addCardToZone("Skyshroud Claim", ai, ZoneType.Graveyard);
        final SpellAbility grant = AbilityFactory.getAbility(source.getSVar("TrigFlashback"), source);
        grant.setActivatingPlayer(ai);
        grant.getTargets().add(target);
        AbilityUtils.resolve(grant);
        final Card moved = game.getAction().moveToHand(target, null);
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy(null, ai);
        Assert.assertFalse(copier.find(moved).hasKeyword(Keyword.FLASHBACK));
    }

}
