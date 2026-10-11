package forge.game.event;

import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.player.PlayerView;

public record GameEventPlayerLost(PlayerView player, GameLossReason lossReason,
        String lossSource) implements GameEvent {
    public GameEventPlayerLost(Player player, GameLossReason lossReason, String lossSource) {
        this(PlayerView.get(player), lossReason, lossSource);
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
