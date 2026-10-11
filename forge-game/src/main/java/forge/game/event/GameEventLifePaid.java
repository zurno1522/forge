package forge.game.event;

import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;

public record GameEventLifePaid(PlayerView player, int amount,
        SpellAbilityView cause) implements GameEvent {
    public GameEventLifePaid(Player player, int amount, SpellAbility cause) {
        this(PlayerView.get(player), amount, SpellAbilityView.get(cause));
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
