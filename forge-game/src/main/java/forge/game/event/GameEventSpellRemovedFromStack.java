package forge.game.event;

import forge.game.spellability.SpellAbilityView;

public record GameEventSpellRemovedFromStack(SpellAbilityView sa, Long stackId,
        Disposition disposition) implements GameEvent {
    public enum Disposition { UNKNOWN, FINAL, COUNTER_PENDING }

    /** Legacy producers do not establish whether a later counter diagnostic is pending. */
    public GameEventSpellRemovedFromStack(final SpellAbilityView sa) {
        this(sa, null, Disposition.UNKNOWN);
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "Stack removed " + sa;
    }
}
