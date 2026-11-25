package forge.game.ability.effects;

import forge.StaticData;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardZoneTable;
import forge.game.player.Player;
import forge.game.player.ZoneChangeSelectionObserver;
import forge.game.player.ZoneChangeSelectionRequest;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.Localizer;
import forge.util.MyRandom;

import java.util.*;

public class DraftEffect extends SpellAbilityEffect {
    @Override
    protected String getStackDescription(SpellAbility sa) {
        final Card source = sa.getHostCard();
        final Player player = AbilityUtils.getDefinedPlayers(source, sa.getParam("Defined"), sa).get(0);

        final StringBuilder sb = new StringBuilder();

        sb.append(player).append(" drafts a card from ").append(source.getDisplayName()).append("'s spellbook.");

        return sb.toString();
    }

    @Override
    public void resolve(SpellAbility sa) {
        final Card source = sa.getHostCard();
        final Player player = AbilityUtils.getDefinedPlayers(source, sa.getParam("Defined"), sa).get(0);
        final Game game = player.getGame();
        List<String> spellbook = Arrays.asList(sa.getParam("Spellbook").split(","));
        final int numToDraft = AbilityUtils.calculateAmount(source, sa.getParamOrDefault("DraftNum", "1"), sa);
        Map<AbilityKey, Object> moveParams = AbilityKey.newMap();
        moveParams.put(AbilityKey.LastStateBattlefield, sa.getLastStateBattlefield());
        moveParams.put(AbilityKey.LastStateGraveyard, sa.getLastStateGraveyard());
        CardCollection drafted = new CardCollection();

        final Selection selection = new Selection(sa, player, game, spellbook, numToDraft, moveParams, drafted);
        final ZoneChangeSelectionRequest request = new ZoneChangeSelectionRequest(sa, ZoneType.None,
                List.of(), player, player, numToDraft, ZoneChangeSelectionRequest.Branch.DRAFT);
        drafted = player.getController().withZoneChangeSelections(request, observer -> chooseCards(selection, observer));

        final CardZoneTable triggerList = new CardZoneTable();
        for (final Card c : drafted) {
            Card made = game.getAction().moveToHand(c, sa, moveParams);
            if (c != null) {
                triggerList.put(ZoneType.None, made.getZone().getZoneType(), made);
            }
            if (sa.hasParam("RememberDrafted")) {
                source.addRemembered(made);
            }
        }
        triggerList.triggerChangesZoneAll(game, sa);
    }
    private record Selection(SpellAbility ability, Player player, Game game, List<String> spellbook, int iterations,
            Map<AbilityKey, Object> moveParams, CardCollection chosen) { }

    private CardCollection chooseCards(final Selection selection, final ZoneChangeSelectionObserver observer) {
        final SpellAbility sa = selection.ability(); final Player player = selection.player(); final Game game = selection.game();
        final List<String> spellbook = selection.spellbook(); final int numToDraft = selection.iterations();
        final Map<AbilityKey, Object> moveParams = selection.moveParams(); final CardCollection drafted = selection.chosen();
        for (int i = 0; i < numToDraft; i++) {
            Collections.shuffle(spellbook, MyRandom.getRandom());
            List<Card> draftOptions = new ArrayList<>();
            for (String name : spellbook.subList(0, 3)) {
                // Cardnames that include "," must use ";" instead in Spellbook$ (i.e. Tovolar; Dire Overlord)
                name = name.replace(";", ",");

                PaperCard pc = StaticData.instance().getCommonCards().getUniqueByName(name);
                // Take the balanced version of the card if available.
                if (pc.isUnRebalanced()) {
                    pc = StaticData.instance().getCommonCards().getUniqueByName("A-" + name);
                }

                Card cardOption = Card.fromPaperCard(pc, player);
                draftOptions.add(cardOption);
            }

            final List<ZoneType> origins = new ArrayList<ZoneType>();
            final CardCollection offered = new CardCollection(draftOptions);
            observer.beforeSelector(offered, false, i, drafted.size(), origins);
            Card chosenCard = player.getController().chooseSingleCardForZoneChange(ZoneType.None, origins, sa, offered, null, Localizer.getInstance().getMessage("lblChooseCardDraft"), false, player);
            observer.returned(chosenCard, drafted.size());
            game.getAction().moveTo(ZoneType.None, chosenCard, sa, moveParams);
            drafted.add(chosenCard);
        }
        return drafted;
    }

}
