package forge.game.event;

import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;

public record GameEventCardDestroyedObserved(CardView card, PlayerView owner,
        PlayerView controller, SpellAbilityView cause) implements GameEvent {
    public GameEventCardDestroyedObserved(Card card, SpellAbility cause) {
        this(CardView.get(card), PlayerView.get(card.getOwner()), PlayerView.get(card.getController()),
                cause == null ? null : SpellAbilityView.get(cause));
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
