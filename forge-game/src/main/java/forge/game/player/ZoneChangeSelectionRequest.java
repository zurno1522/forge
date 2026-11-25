package forge.game.player;

import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.List;

/** Received ordinary hidden-origin selection arguments; construction does not inspect collections. */
public record ZoneChangeSelectionRequest(SpellAbility ability, ZoneType destination, List<ZoneType> origins,
        Player sourcePlayer, Player decider, int requestedIterations) { }
