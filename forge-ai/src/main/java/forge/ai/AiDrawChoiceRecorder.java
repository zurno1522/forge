package forge.ai;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Entry of the native Draw amount/target helper, distinct from overall playability and numeric application. */
public final class AiDrawChoiceRecorder {
    private AiDrawChoiceRecorder() { }

    public static boolean policy(final Player chooser, final SpellAbility ability, final boolean mandatory,
            final BooleanSupplier callback) {
        final var parameters = new AtomicReference<AiReceivedParameters.Input>();
        return AiChoiceRecorder.observeParametersAt(chooser, ability, "DRAW_POLICY_RESULT", null,
                "DRAW_AMOUNT_TARGET_POLICY", "BEFORE_OPERATION", () -> {
                    final var received = AiReceivedParameters.drawPolicy(mandatory); parameters.set(received);
                    return Map.of("coverage", "DRAW_POLICY_NOT_LEGAL_DOMAIN", "callbackParameters", received.diagnostics());
                }, callback::getAsBoolean, result -> Map.of("result", result), null, null,
                new AiReceivedParameters.Carrier(parameters));
    }
}
