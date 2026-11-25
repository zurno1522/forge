package forge.ai;

import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** Observes offered mode identities and actual stored/returned plans without rerunning the selector. */
public final class AiModeChoiceRecorder {
    private AiModeChoiceRecorder() { }

    public static AiAbilityDecision evaluate(final Player chooser, final SpellAbility ability, final List<AbilitySub> possible,
            final int min, final int max, final boolean repeat, final Supplier<AiAbilityDecision> callback) {
        return record(chooser, ability, possible, min, max, repeat, "CHARM_MODE_HEURISTIC", "BEFORE_SELECTOR", callback,
                ignored -> ability.getChosenList(), AiAbilityDecision::willingToPlay);
    }

    public static List<AbilitySub> choose(final Player chooser, final SpellAbility ability, final List<AbilitySub> possible,
            final int min, final int max, final boolean repeat, final Supplier<List<AbilitySub>> callback) {
        return record(chooser, ability, possible, min, max, repeat, "CONTROLLER_MODES", "BEFORE_OPERATION", callback, Function.identity(), ignored -> null);
    }

    private static <T> T record(final Player chooser, final SpellAbility ability, final List<AbilitySub> possible,
            final int min, final int max, final boolean repeat, final String boundary, final String timing, final Supplier<T> callback,
            final Function<T, List<AbilitySub>> selected, final Function<T, Boolean> accepted) {
        if (!chooser.getGame().isRecordingAiDecisions()) { return callback.get(); }
        final IdentityHashMap<SpellAbility, Integer> indices = new IdentityHashMap<>();
        final IdentityHashMap<SpellAbility, Integer> definitions = new IdentityHashMap<>();
        // Both the heuristic and controller can consume hypothetical, prepared plans/resources.
        // The controller may reuse a plan; wrapping it does not make it a fresh selector.
        return AiChoiceRecorder.planning(chooser, () -> AiChoiceRecorder.observeInput(chooser, ability, "MODE_SELECTION", null, boundary, timing, () -> {
            final List<AbilitySub> declared = ability.getAdditionalAbilityList("Choices");
            for (int i = 0; declared != null && i < declared.size() && i < AiTargetChoiceRecorder.MAX_REFERENCES; i++) {
                final AbilitySub mode = declared.get(i);
                definitions.put(mode, definitions.containsKey(mode) ? -1 : i);
            }
            final List<Map<String, Object>> options = new ArrayList<>();
            for (int i = 0; i < possible.size() && i < AiTargetChoiceRecorder.MAX_REFERENCES; i++) {
                final AbilitySub mode = possible.get(i);
                indices.put(mode, indices.containsKey(mode) ? -1 : i);
                final Map<String, Object> option = new LinkedHashMap<>();
                option.put("abilityId", mode.getId()); option.put("optionIndex", i);
                final Integer definition = definitions.get(mode);
                option.put("definitionIndex", definition == null || definition < 0 ? null : definition);
                options.add(option);
            }
            return Map.of("coverage", "OBSERVED_MODE_OPTIONS", "items", options, "truncated", possible.size() > options.size(),
                    "min", min, "max", max, "allowRepeat", repeat);
        }, callback, result -> {
            if (result == null) { return null; }
            final List<AbilitySub> chosen = selected.apply(result);
            final List<Map<String, Object>> modes = new ArrayList<>();
            for (int i = 0; chosen != null && i < chosen.size() && i < AiTargetChoiceRecorder.MAX_REFERENCES; i++) {
                final AbilitySub mode = chosen.get(i);
                final Integer index = indices.get(mode);
                final Map<String, Object> reference = new LinkedHashMap<>();
                reference.put("abilityId", mode.getId());
                reference.put("optionIndex", index == null || index < 0 ? null : index);
                final Integer definition = definitions.get(mode);
                reference.put("definitionIndex", definition == null || definition < 0 ? null : definition);
                modes.add(reference);
            }
            final Map<String, Object> value = new LinkedHashMap<>();
            value.put("available", chosen != null);
            value.put("modes", modes);
            value.put("selectedCount", chosen == null ? null : chosen.size());
            value.put("truncated", chosen != null && chosen.size() > modes.size());
            value.put("accepted", accepted.apply(result));
            return value;
        }));
    }
}
