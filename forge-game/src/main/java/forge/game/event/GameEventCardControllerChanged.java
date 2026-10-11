package forge.game.event;

import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.PlayerView;

public record GameEventCardControllerChanged(CardView card, PlayerView oldController,
        PlayerView newController) implements GameEvent {
    public GameEventCardControllerChanged(Card card, Player oldController, Player newController) {
        this(CardView.get(card), PlayerView.get(oldController), PlayerView.get(newController));
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
