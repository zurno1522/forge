package forge.game.player;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.zone.ZoneType;

import java.util.List;

/** Passive facts from actual selector dispatches, distinct from retained cards and eventual movement.
 * Implementations must contain observation failures so they cannot change selection behavior.
 */
public interface ZoneChangeSelectionObserver {
    ZoneChangeSelectionObserver NONE = new ZoneChangeSelectionObserver() { };

    default void beforeSelector(CardCollection offered, boolean optional, int loopPosition, int retainedCount) { }
    /** Actual per-call origins, preserving fresh call argument objects. */
    default void beforeSelector(CardCollection offered, boolean optional, int loopPosition, int retainedCount,
            List<ZoneType> origins) { beforeSelector(offered, optional, loopPosition, retainedCount); }
    default void returned(Card card, int retainedCount) { }
}
