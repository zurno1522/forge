package forge.game.event;

import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.PlayerView;

import java.util.List;

public record GameEventCardsDrawn(PlayerView player, List<CardView> cards) implements GameEvent {
    public GameEventCardsDrawn(Player player, Iterable<Card> cards) {
        this(PlayerView.get(player), List.copyOf(CardView.getCollection(cards)));
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
