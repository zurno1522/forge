package forge.ai;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Native Damage assessment/calculation scopes, distinct from committed actions and numeric application. */
public final class AiDamageChoiceRecorder {
    enum Operation { CAN_PLAY, TRIGGER, LIFE_DRAIN }
    private AiDamageChoiceRecorder() { }

    public static AiAbilityDecision canPlay(final Player chooser, final SpellAbility ability,
            final Supplier<AiAbilityDecision> callback) {
        return observe(chooser, ability, Operation.CAN_PLAY, null, callback, AiDamageChoiceRecorder::decision);
    }

    public static AiAbilityDecision trigger(final Player chooser, final SpellAbility ability, final boolean mandatory,
            final Supplier<AiAbilityDecision> callback) {
        return observe(chooser, ability, Operation.TRIGGER, mandatory, callback, AiDamageChoiceRecorder::decision);
    }

    public static boolean lifeDrain(final Player chooser, final SpellAbility ability, final BooleanSupplier callback) {
        return observe(chooser, ability, Operation.LIFE_DRAIN, null, callback::getAsBoolean, result -> Map.of("result", result));
    }

    private static Map<String, Object> decision(final AiAbilityDecision result) {
        return Map.of("rating", result.rating(), "decision", result.decision().name());
    }

    private static <T> T observe(final Player chooser, final SpellAbility ability, final Operation operation,
            final Boolean mandatory, final Supplier<T> callback, final Function<T, Object> selection) {
        final var parameters = new AtomicReference<AiReceivedParameters.Input>();
        return AiChoiceRecorder.observeParametersAt(chooser, ability, "DAMAGE_POLICY_RESULT", null,
                "DAMAGE_" + operation.name() + "_POLICY", "BEFORE_OPERATION", () -> {
                    final var received = AiReceivedParameters.damagePolicy(operation, mandatory); parameters.set(received);
                    return Map.of("coverage", "DAMAGE_POLICY_NOT_LEGAL_DOMAIN", "operation", operation.name(), "callbackParameters", received.diagnostics());
                }, callback, selection, null, null, new AiReceivedParameters.Carrier(parameters));
    }
}
