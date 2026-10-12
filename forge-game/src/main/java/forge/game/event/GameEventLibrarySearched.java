package forge.game.event;

import forge.game.player.Player;
import forge.game.player.PlayerView;

public record GameEventLibrarySearched(PlayerView searcher, PlayerView libraryOwner) implements GameEvent {
    public GameEventLibrarySearched(Player searcher, Player libraryOwner) {
        this(PlayerView.get(searcher), PlayerView.get(libraryOwner));
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
