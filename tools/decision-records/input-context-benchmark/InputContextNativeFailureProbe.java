package forge.ai;

import forge.game.Game;
import forge.game.event.Event;
import forge.game.player.Player;
import java.util.Map;

/** Native boundary check of agent exception handlers; not a cost sample or a played game. */
public final class InputContextNativeFailureProbe extends AITest {
    private static void check(boolean ok, String message) { if (!ok) { throw new AssertionError(message); } }
    private static long calls(Map<String, Object> spans, String key) { return (Long) ((Map<?, ?>) spans.get(key)).get("calls"); }
    public static void main(String[] args) {
        try { new InputContextNativeFailureProbe().run(); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(System.err); System.exit(1); }
    }
    private void run() {
        initializeModel(); final Game game = initAndCreateGame(); final Player ai = game.getPlayers().get(1);
        game.setRecordAiDecisions(true);
        final var original = new IllegalStateException("original capture");
        InputContextPhaseMetrics.begin(false);
        try {
            AiInputContext.capture(ai, null, "BEFORE_SELECTOR", "RULES_STATE", () -> true, () -> { throw original; });
            throw new AssertionError("Capture error was swallowed");
        } catch (IllegalStateException actual) { check(actual == original, "Original capture exception changed"); }
        final var context = AiInputContext.capture(ai, null, "BEFORE_SELECTOR", "RULES_STATE", () -> true);
        check(((Map<?, ?>) context.get("admission")).get("ordinal").equals(2L), "Capture scope/counter did not recover");
        try {
            AiDecisionEvent.freezeMap(Map.of("unsupported", new Object()));
            throw new AssertionError("Invalid JSON object was accepted");
        } catch (IllegalArgumentException diagnostic) {
            check(diagnostic.getMessage().startsWith("Decision telemetry requires JSON values"), "Freeze diagnostic changed");
        }
        check(AiDecisionEvent.freezeMap(Map.of("valid", 3)).get("valid").equals(3), "Healthy freeze changed");
        final var observerError = new IllegalStateException("original observer");
        game.observeEventEmission(event -> { throw observerError; });
        game.fireEvent(new Event() { });
        check(game.getEventObservationFailure() == observerError, "Native subscriber failure disappeared");
        final var spans = InputContextPhaseMetrics.finish();
        check(calls(spans, "capture") == 2, "Native capture spans did not unwind");
        check(calls(spans, "contextFreeze") == 1, "Native context freeze was misattributed after failure");
        check(calls(spans, "eventFreeze") == 2, "Native outside-capture freeze spans did not unwind");
        check(calls(spans, "publication") == 1, "Native emission span changed");
        check(InputContextPhaseAgent.transformed == 4, "Native classes were not instrumented");
        System.out.println("PASS: native capture/JSON failures unwind; healthy capture keeps ordinal 2; observer failure stays visible");
    }
}
