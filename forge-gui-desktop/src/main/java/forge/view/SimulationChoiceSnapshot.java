package forge.view;

import forge.game.ability.ApiType;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Copies committed action parameters without consulting the AI's evaluation caches. */
final class SimulationChoiceSnapshot {
    private SimulationChoiceSnapshot() { }

    static Map<String, Object> modes(final SpellAbilityStackInstance entry) {
        if (entry == null) { return null; }
        final SpellAbility root = entry.getSpellAbility();
        final boolean modal = root.getApi() == ApiType.Charm;
        final List<Map<String, Object>> modes = new ArrayList<>();
        if (modal) {
            // CharmEffect appends copies of each chosen option's entire ability chain. Match the
            // original script parameters of that chain, not rendered text or the cached chosenList.
            for (SpellAbilityStackInstance current = entry.getSubInstance(); current != null;) {
                SpellAbilityStackInstance next = null;
                boolean matched = false;
                Integer optionIndex = null;
                final var options = root.getAdditionalAbilityList("Choices");
                for (int index = 0; index < options.size(); index++) {
                    SpellAbility template = options.get(index);
                    SpellAbilityStackInstance actual = current;
                    while (template != null && actual != null && template.getOriginalMapParams()
                            .equals(actual.getSpellAbility().getOriginalMapParams())) {
                        template = template.getSubAbility();
                        actual = actual.getSubInstance();
                    }
                    if (template != null) { continue; }
                    if (matched && next != actual) { return null; } // Ambiguous chain boundary.
                    optionIndex = matched ? null : index;
                    next = actual;
                    matched = true;
                }
                if (!matched) { return null; }
                final Map<String, Object> mode = new LinkedHashMap<>();
                mode.put("abilityId", current.getSpellAbility().getId());
                mode.put("optionIndex", optionIndex);
                modes.add(mode);
                current = next;
            }
        }
        return Map.of("schemaVersion", 1, "modal", modal, "modes", modes);
    }
}
