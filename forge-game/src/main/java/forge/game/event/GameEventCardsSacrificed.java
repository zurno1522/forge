package forge.game.event;

import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;

import java.util.List;

public record GameEventCardsSacrificed(PlayerView player, List<CardView> cards,
        SpellAbilityView cause) implements GameEvent {
    public GameEventCardsSacrificed(Player player, Iterable<Card> cards, SpellAbility cause) {
        this(PlayerView.get(player), List.copyOf(CardView.getCollection(cards)),
                cause == null ? null : SpellAbilityView.get(cause));
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
