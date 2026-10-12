package forge.game.event;

import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.PlayerView;

import java.util.ArrayList;
import java.util.List;

public record GameEventCardsShown(
        List<CardView> cards,
        PlayerView cardOwner,
        List<PlayerView> viewers,
        CardVisibilityType visibilityType) implements GameEvent {

    public GameEventCardsShown(CardCollectionView cards, Player cardOwner,
            Iterable<Player> viewers, CardVisibilityType visibilityType) {
        this(cardViews(cards), PlayerView.get(cardOwner), playerViews(viewers), visibilityType);
    }

    private static List<CardView> cardViews(final Iterable<Card> cards) {
        final List<CardView> views = new ArrayList<>();
        for (final Card card : cards) {
            views.add(CardView.get(card));
        }
        return List.copyOf(views);
    }

    private static List<PlayerView> playerViews(final Iterable<Player> players) {
        final List<PlayerView> views = new ArrayList<>();
        for (final Player player : players) {
            views.add(PlayerView.get(player));
        }
        return List.copyOf(views);
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
