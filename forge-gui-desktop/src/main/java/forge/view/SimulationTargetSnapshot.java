package forge.view;

import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.MagicStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only, identity-only snapshot of committed targets; no AI or file-writing dependency. */
final class SimulationTargetSnapshot {
    private SimulationTargetSnapshot() { }

    static Map<String, Object> capture(final SpellAbilityStackInstance root, final MagicStack stack) {
        if (root == null) { return null; }
        final List<Map<String, Object>> abilities = new ArrayList<>();
        for (SpellAbilityStackInstance current = root; current != null; current = current.getSubInstance()) {
            final List<Map<String, Object>> targets = new ArrayList<>();
            for (final GameObject target : current.getTargetChoices()) {
                final Map<String, Object> ref = new LinkedHashMap<>();
                if (target instanceof Card card) {
                    ref.put("type", "CARD");
                    ref.put("id", card.getId());
                    ref.put("gameTimestamp", card.getGameTimestamp());
                } else if (target instanceof Player player) {
                    ref.put("type", "PLAYER");
                    ref.put("id", player.getId());
                } else if (target instanceof SpellAbility ability) {
                    ref.put("type", "SPELL_ABILITY");
                    ref.put("id", ability.getId());
                    final SpellAbilityStackInstance targeted = stack.getInstanceMatchingSpellAbilityID(ability);
                    ref.put("stackId", targeted == null ? null : targeted.getId());
                } else {
                    throw new IllegalArgumentException("Unsupported committed target type: " + target.getClass().getName());
                }
                final Integer divided = current.getTargetChoices().getDividedValue(target);
                if (divided != null) { ref.put("dividedAmount", divided); }
                targets.add(ref);
            }
            final Map<String, Object> group = new LinkedHashMap<>();
            group.put("abilityId", current.getSpellAbility().getId());
            group.put("usesTargeting", current.getSpellAbility().usesTargeting());
            group.put("targets", targets);
            abilities.add(group);
        }
        return Map.of("schemaVersion", 1, "abilities", abilities);
    }
}
