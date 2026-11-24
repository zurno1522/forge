package forge.ai;

import forge.game.player.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Public entry clock, independent of parent publication and expensive input projection. */
final class AiEntryLocation {
    private AiEntryLocation() { }

    static Map<String, Object> capture(final Player chooser, final String timing, final String basis,
            final boolean visible, final BooleanSupplier open) {
        if (!visible) { return unavailable("HIDDEN"); }
        try {
            if (!open.getAsBoolean() || Thread.currentThread().isInterrupted()) { return unavailable("INTERRUPTED"); }
            final String kind = switch (timing) {
                case "BEFORE_SELECTOR" -> "SELECTOR";
                case "BEFORE_OPERATION" -> "OPERATION";
                case "AFTER_SELECTION_BEFORE_APPLICATION" -> "APPLICATION";
                default -> null;
            };
            if (kind == null || !"RULES_STATE".equals(basis) && !"PLANNING_WORKING_STATE".equals(basis)) {
                return unavailable("UNSUPPORTED");
            }
            final var phase = chooser.getGame().getPhaseHandler();
            final Map<String, Object> value = envelope("CAPTURED", kind, timing, basis, null);
            value.put("turn", phase.getTurn());
            value.put("phase", phase.getPhase() == null ? "UNESTABLISHED" : phase.getPhase().name());
            final Player turnPlayer = phase.getPlayerTurn();
            value.put("turnPlayerId", turnPlayer == null ? null : turnPlayer.getId());
            if (!open.getAsBoolean() || Thread.currentThread().isInterrupted()) { return unavailable("INTERRUPTED"); }
            return AiDecisionEvent.freezeMap(value);
        } catch (RuntimeException failure) {
            chooser.getGame().recordEventObservationFailure(failure);
            return unavailable("CAPTURE_FAILED");
        }
    }

    static Map<String, Object> unavailable(final String reason) {
        return AiDecisionEvent.freezeMap(envelope("UNAVAILABLE", "UNESTABLISHED", "UNESTABLISHED", "UNESTABLISHED", reason));
    }

    private static Map<String, Object> envelope(final String status, final String kind, final String timing,
            final String basis, final String reason) {
        final Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1);
        value.put("status", status);
        value.put("kind", kind);
        value.put("timing", timing);
        value.put("stateBasis", basis);
        value.put("turn", null);
        value.put("phase", null);
        value.put("turnPlayerId", null);
        value.put("reason", reason);
        return value;
    }
}
