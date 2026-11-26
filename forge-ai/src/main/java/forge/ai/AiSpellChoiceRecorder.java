package forge.ai;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Actual effect-ability callbacks; references are diagnostic and opaque arguments remain uninspected. */
final class AiSpellChoiceRecorder {
    private AiSpellChoiceRecorder() { }

    static SpellAbility single(final Player chooser, final SpellAbility ability, final List<SpellAbility> options,
            final Supplier<SpellAbility> callback) {
        if (!chooser.getGame().isRecordingAiDecisions()) { return callback.get(); }
        final AtomicReference<List<Map<String, Object>>> offered = new AtomicReference<>();
        return AiChoiceRecorder.observeInput(chooser, ability, "EFFECT_SPELL_ABILITY_SELECTION", null,
                "CONTROLLER_EFFECT_SPELL_ABILITY", "BEFORE_SELECTOR", () -> pool(options, offered), callback,
                value -> AiEffectChoiceRecorder.selected(value, offered.get()));
    }

    static List<SpellAbility> multiple(final Player chooser, final SpellAbility ability, final List<SpellAbility> options,
            final int requestedSelections, final Supplier<List<SpellAbility>> callback) {
        if (!chooser.getGame().isRecordingAiDecisions()) { return callback.get(); }
        return AiChoiceRecorder.observeInput(chooser, ability, "EFFECT_MULTI_SPELL_ABILITY_SELECTION", null,
                "CONTROLLER_EFFECT_SPELL_ABILITIES", "BEFORE_OPERATION", () -> {
                    final Map<String, Object> result = pool(options, new AtomicReference<>());
                    result.put("requestedSelections", requestedSelections); return result;
                }, callback, values -> {
                    final int count = values.size();
                    final var captured = AiTargetChoiceRecorder.references(values, () -> !Thread.currentThread().isInterrupted());
                    final List<Map<String, Object>> refs = captured == null ? List.of() : captured.values();
                    if (refs.size() > count) { throw new IllegalStateException("Returned spell-ability list changed during capture"); }
                    return Map.of("coverage", captured == null ? "UNAVAILABLE" : "OBSERVED_RETURNED_SPELL_ABILITY_PREFIX",
                            "items", refs, "selectedCount", count,
                            "truncated", captured == null || captured.truncated() || count > refs.size());
                }, new AiPromptInput.SpellOperation(requestedSelections));
    }

    private static Map<String, Object> pool(final List<SpellAbility> options,
            final AtomicReference<List<Map<String, Object>>> offered) {
        final var captured = AiTargetChoiceRecorder.references(options, () -> !Thread.currentThread().isInterrupted());
        offered.set(captured == null ? null : captured.values());
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("coverage", captured == null ? "UNAVAILABLE" : "OBSERVED_SPELL_ABILITY_OPTIONS_DIAGNOSTIC_ONLY");
        result.put("items", captured == null ? List.of() : captured.values());
        result.put("truncated", captured == null || captured.truncated());
        return result;
    }
}
