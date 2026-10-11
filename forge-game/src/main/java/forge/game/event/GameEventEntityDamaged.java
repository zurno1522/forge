package forge.game.event;

import forge.game.card.Card;
import forge.game.card.CardView;

public record GameEventEntityDamaged(CardView target, CardView source, int amount,
        boolean combat) implements GameEvent {
    public GameEventEntityDamaged(Card target, Card source, int amount, boolean combat) {
        this(CardView.get(target), CardView.get(source), amount, combat);
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
