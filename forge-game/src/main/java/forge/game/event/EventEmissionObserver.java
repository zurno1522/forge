package forge.game.event;

/**
 * Synchronous, observational capture before EventBus delivery can be deferred.
 * Implementations must not mutate rules state or emit events. Failures are retained by Game.
 */
@FunctionalInterface
public interface EventEmissionObserver {
    void capture(Event event);

    /** Release observational state at authoritative execution termination. */
    default void close() { }

    @FunctionalInterface
    interface Subscription extends AutoCloseable {
        @Override void close();
    }
}
