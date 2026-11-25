package forge.game.player;

import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.List;

/** Received selection arguments; construction does not inspect collections.
 * Iterations describe the branch's loop, never a legal successful-return bound.
 */
public record ZoneChangeSelectionRequest(SpellAbility ability, ZoneType destination, List<ZoneType> origins,
        Player sourcePlayer, Player decider, int requestedIterations, Branch branch) {
    public enum Branch { ORDINARY_HIDDEN_ORIGIN, SAME_NAME_HIDDEN_ORIGIN, EACH_TYPE_HIDDEN_ORIGIN }

    public ZoneChangeSelectionRequest(SpellAbility ability, ZoneType destination, List<ZoneType> origins,
            Player sourcePlayer, Player decider, int requestedIterations) {
        this(ability, destination, origins, sourcePlayer, decider, requestedIterations, Branch.ORDINARY_HIDDEN_ORIGIN);
    }
}
