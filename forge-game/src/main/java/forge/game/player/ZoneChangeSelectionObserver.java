package forge.game.player;

import forge.game.card.Card;
import forge.game.card.CardCollection;

/** Passive facts from actual selector dispatches, distinct from retained cards and eventual movement.
 * Implementations must contain observation failures so they cannot change selection behavior.
 */
public interface ZoneChangeSelectionObserver {
    ZoneChangeSelectionObserver NONE = new ZoneChangeSelectionObserver() { };

    default void beforeSelector(CardCollection offered, boolean optional, int loopPosition, int retainedCount) { }
    default void returned(Card card, int retainedCount) { }
}
