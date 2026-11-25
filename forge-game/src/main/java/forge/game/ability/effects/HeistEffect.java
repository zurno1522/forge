package forge.game.ability.effects;

import java.util.List;
import java.util.Map;

import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CardZoneTable;
import forge.game.player.Player;
import forge.game.player.ZoneChangeSelectionObserver;
import forge.game.player.ZoneChangeSelectionRequest;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.Aggregates;
import forge.util.Localizer;

public class HeistEffect extends SpellAbilityEffect {

    @Override
    public boolean movesCardToOrFromLibrary(final SpellAbility sa) {
        return true;
    }

    @Override
    public void resolve(SpellAbility sa) {
        Map<AbilityKey, Object> moveParams = AbilityKey.newMap();
        moveParams.put(AbilityKey.LastStateBattlefield, sa.getLastStateBattlefield());
        moveParams.put(AbilityKey.LastStateGraveyard, sa.getLastStateGraveyard());
        final Card source = sa.getHostCard();
        final Player player = AbilityUtils.getDefinedPlayers(source, sa.getParam("Defined"), sa).get(0);
        final Game game = player.getGame();
        final Player target = getTargetPlayers(sa).get(0);
        final CardZoneTable triggerList = new CardZoneTable();
        final int num = AbilityUtils.calculateAmount(source, sa.getParamOrDefault("Num", "1"), sa);
        CardCollection heisted = new CardCollection();

        final Selection selection = new Selection(sa, player, target, game, num, moveParams, triggerList, heisted);
        final ZoneChangeSelectionRequest request = new ZoneChangeSelectionRequest(sa, ZoneType.Exile,
                List.of(ZoneType.Library), target, player, num, ZoneChangeSelectionRequest.Branch.HEIST);
        heisted = player.getController().withZoneChangeSelections(request, observer -> chooseCards(selection, observer));

        if (!heisted.isEmpty()) {
            final Card eff = createEffect(sa, player, source + "'s Heist Effect", source.getImageKey());
            eff.addRemembered(heisted);
            String mayPlay = "Mode$ Continuous | MayPlay$ True | MayPlayIgnoreType$ True | EffectZone$ Command | " +
            "Affected$ Card.IsRemembered | AffectedZone$ Exile | Description$ You may play the heisted card for as " +
            "long as it remains exiled, and mana of any type can be spent to cast it.";
            eff.addStaticAbility(mayPlay);
            addForgetOnMovedTrigger(eff, "Exile");
            addForgetOnCastTrigger(eff, "Card.IsRemembered");
            game.getAction().moveToCommand(eff, sa);
        }

        triggerList.triggerChangesZoneAll(game, sa);
    }
    private record Selection(SpellAbility ability, Player player, Player target, Game game, int iterations,
            Map<AbilityKey, Object> moveParams, CardZoneTable triggerList, CardCollection chosen) { }

    private CardCollection chooseCards(final Selection selection, final ZoneChangeSelectionObserver observer) {
        final SpellAbility sa = selection.ability(); final Player player = selection.player();
        final Player target = selection.target(); final Game game = selection.game(); final int num = selection.iterations();
        final Map<AbilityKey, Object> moveParams = selection.moveParams(); final CardZoneTable triggerList = selection.triggerList();
        final CardCollection heisted = selection.chosen();
        for (int i = 0; i < num; i++) {
            List<Card> choices = Aggregates.random(CardLists.getNotType(target.getCardsIn(ZoneType.Library), 
                "Land"), 3);
            if (choices.isEmpty()) continue; //nothing to heist
            final List<ZoneType> origins = List.of(ZoneType.Library);
            final CardCollection offered = new CardCollection(choices);
            observer.beforeSelector(offered, false, i, heisted.size(), origins);
            Card chosenCard = player.getController().chooseSingleCardForZoneChange(ZoneType.Exile,
                origins, sa, offered,
                null, Localizer.getInstance().getMessage("lblChooseCardHeist"), false, 
                player);
            observer.returned(chosenCard, heisted.size());
            if (!chosenCard.canExiledBy(sa, true)) {
                continue;
            }
            Card exiled = game.getAction().moveTo(ZoneType.Exile, chosenCard, sa, moveParams);
            exiled.turnFaceDown(true);
            exiled.addMayLookFaceDownExile(player);
            handleExiledWith(exiled, sa);
            heisted.add(exiled);
            triggerList.put(ZoneType.Library, exiled.getZone().getZoneType(), exiled);
        }
        return heisted;
    }

}
