package forge.ai;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Numeric callback inputs and actual X proposals. Bounds are observations, never inferred legal masks. */
public final class AiNumberChoiceRecorder {
    private AiNumberChoiceRecorder() { }

    public static Integer range(final Player chooser, final SpellAbility ability, final String boundary,
            final int min, final int max, final Supplier<Integer> callback) {
        return range(chooser, ability, boundary, min, max, callback, null);
    }

    static int controllerRange(final Player chooser, final SpellAbility ability,
            final int min, final int max, final Supplier<Integer> callback) {
        return controllerRange(chooser, ability, min, max, null, callback);
    }

    static int controllerRange(final Player chooser, final SpellAbility ability, final int min, final int max,
            final Map<String, Object> params, final Supplier<Integer> callback) {
        final AtomicReference<AiReceivedParameters.Input> parameters = new AtomicReference<>();
        return AiChoiceRecorder.observeParameters(chooser, ability, "NUMBER_SELECTION", "CONTROLLER_NUMBER_RANGE", () -> {
            final var received = AiReceivedParameters.capture(AiReceivedParameters.Family.NUMBER, params); parameters.set(received);
            return Map.of("coverage", "OBSERVED_RANGE", "minimum", min, "maximum", max, "callbackParameters", received.diagnostics());
        }, callback, value -> numeric(value, null, value >= min && value <= max ? "WITHIN_OBSERVED_DOMAIN" : "OUTSIDE_OBSERVED_DOMAIN"),
                () -> AiPromptInput.range(min, max), null, new AiReceivedParameters.Carrier(parameters));
    }

    private static Integer range(final Player chooser, final SpellAbility ability, final String boundary,
            final int min, final int max, final Supplier<Integer> callback, final Supplier<AiPromptInput> prompt) {
        return AiChoiceRecorder.observeInput(chooser, ability, "NUMBER_SELECTION", null, boundary,
                "CONTROLLER_ANNOUNCEMENT".equals(boundary) ? "BEFORE_OPERATION" : "BEFORE_SELECTOR",
                () -> Map.of("coverage", "OBSERVED_RANGE", "minimum", min, "maximum", max), callback,
                value -> numeric(value, null, value >= min && value <= max ? "WITHIN_OBSERVED_DOMAIN" : "OUTSIDE_OBSERVED_DOMAIN"), prompt);
    }

    public static int discrete(final Player chooser, final SpellAbility ability, final List<Integer> possible,
            final Supplier<Integer> callback) {
        return discrete(chooser, ability, possible, null, callback);
    }

    public static int discrete(final Player chooser, final SpellAbility ability, final List<Integer> possible,
            final Player relatedPlayer, final Supplier<Integer> callback) {
        final AtomicReference<List<Integer>> captured = new AtomicReference<>();
        return AiChoiceRecorder.observeInput(chooser, ability, "NUMBER_SELECTION", null, "CONTROLLER_NUMBER_OPTIONS", "BEFORE_SELECTOR", () -> {
            final List<Integer> values = new ArrayList<>();
            for (int i = 0; i < possible.size() && i < AiTargetChoiceRecorder.MAX_REFERENCES; i++) { values.add(possible.get(i)); }
            captured.set(values);
            return Map.of("coverage", "OBSERVED_DISCRETE_OPTIONS", "items", values, "truncated", values.size() < possible.size());
        }, callback, value -> {
            if (value == null) { return null; }
            final List<Integer> values = captured.get();
            Integer index = null; int count = 0;
            for (int i = 0; values != null && i < values.size(); i++) {
                if (value.equals(values.get(i))) { index = i; count++; }
            }
            return numeric(value, count == 1 ? index : null, count == 1 ? "UNIQUE_CAPTURED_OPTION"
                    : count == 0 ? "OUTSIDE_CAPTURED_OPTIONS" : "AMBIGUOUS_CAPTURED_OPTIONS");
        }, () -> AiPromptInput.numbers(possible, relatedPlayer));
    }

    public static int announceX(final Player chooser, final SpellAbility ability, final Integer bound,
            final int selected, final String boundary) {
        return AiChoiceRecorder.observe(chooser, ability, "X_SELECTION", null, boundary, () -> {
            final Map<String, Object> options = new LinkedHashMap<>();
            options.put("coverage", bound == null ? "OBSERVED_SELECTION_ONLY" : "HEURISTIC_BOUND_NOT_LEGAL_SET");
            options.put("minimum", bound == null ? null : 0);
            options.put("maximum", bound);
            options.put("previousValue", ability.getXManaCostPaid());
            return options;
        }, () -> { ability.setXManaCostPaid(selected); return selected; },
                value -> numeric(value, null, bound == null ? "UNAVAILABLE" : value >= 0 && value <= bound
                        ? "WITHIN_OBSERVED_DOMAIN" : "OUTSIDE_OBSERVED_DOMAIN"));
    }

    public static void refineX(final Player chooser, final SpellAbility ability, final int selected) {
        announceX(chooser, ability, null, selected, "X_ANNOUNCEMENT_REFINEMENT");
    }

    private static Map<String, Object> numeric(final Integer value, final Integer index, final String match) {
        if (value == null) { return null; }
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", value); result.put("optionIndex", index); result.put("domainMatch", match);
        return result;
    }
}
