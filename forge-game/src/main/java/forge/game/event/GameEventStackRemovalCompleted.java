package forge.game.event;

/** A removal operation has finished, including paths that could not announce a counter. */
public record GameEventStackRemovalCompleted(long stackId) implements GameEvent {
    @Override public <T> T visit(final IGameEventVisitor<T> visitor) { return visitor.visit(this); }
}
