package forge.game.event;

import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;

public record GameEventStackCountered(int stackId, SpellAbilityView counteredAbility,
        SpellAbilityView cause) implements GameEvent {
    public GameEventStackCountered(int stackId, SpellAbility counteredAbility, SpellAbility cause) {
        this(stackId, SpellAbilityView.get(counteredAbility), SpellAbilityView.get(cause));
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
