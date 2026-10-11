package forge.game.event;

import forge.game.player.Player;
import forge.game.player.PlayerView;

public record GameEventStartingHandFinalized(PlayerView player, int handSize) implements GameEvent {
    public GameEventStartingHandFinalized(Player player, int handSize) {
        this(PlayerView.get(player), handSize);
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
