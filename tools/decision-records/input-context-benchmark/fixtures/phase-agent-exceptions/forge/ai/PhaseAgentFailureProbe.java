package forge.ai;

import forge.game.Game;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/** Executable unit of the actual agent/metrics, including implicit-exception bytecode paths. */
public final class PhaseAgentFailureProbe {
    private static void check(boolean ok, String message) { if (!ok) { throw new AssertionError(message); } }
    private static void same(Throwable expected, Runnable body) {
        try { body.run(); } catch (Throwable actual) { check(actual == expected, "Changed exception identity"); return; }
        throw new AssertionError("Original exception was swallowed");
    }
    private static long calls(Map<String, Object> result, String phase) { return (Long) ((Map<?, ?>) result.get(phase)).get("calls"); }

    public static void main(String[] args) throws Exception {
        final var error = new IllegalStateException("original");
        InputContextPhaseMetrics.begin(false);
        same(error, () -> AiInputContext.capture(() -> { throw error; }, null));
        final var originalError = new AssertionError("original error");
        same(originalError, () -> AiInputContext.capture(() -> { throw originalError; }, null));
        same(error, () -> AiInputContext.capture(() -> "unreachable", error));
        check(AiInputContext.capture(() -> "healthy", null).equals("healthy"), "Changed capture result");
        AiDecisionEvent.implicit = error;
        same(error, () -> AiDecisionEvent.freezeMap(Map.of(), null));
        AiDecisionEvent.implicit = null;
        same(error, () -> AiDecisionEvent.freezeMap(Map.of(), error));
        check(AiDecisionEvent.freezeMap(Map.of("value", 3), null).get("value").equals(3), "Changed freeze result");
        final var game = new Game();
        same(error, () -> game.fireEvent(() -> { throw error; }, null));
        same(error, () -> game.fireEvent(() -> { }, error));
        game.fireEvent(() -> { }, null);
        same(error, () -> AiInputContext.capture(() -> new AiInputContext.Capture().build(error), null));
        check(AiInputContext.capture(() -> new AiInputContext.Capture().build(null), null).equals(List.of(1, 2)), "Changed ordering result");
        AiDecisionEvent.implicit = error;
        same(error, () -> AiInputContext.capture(() -> AiDecisionEvent.freezeMap(Map.of(), null), null));
        AiDecisionEvent.implicit = null;
        AiInputContext.capture(() -> AiDecisionEvent.freezeMap(Map.of("nested", 3), null), null);
        final var spans = InputContextPhaseMetrics.finish();
        check(calls(spans, "capture") == 8, "Lost/doubled capture spans");
        check(calls(spans, "contextFreeze") == 2, "Lost/doubled nested context freezes");
        check(calls(spans, "eventFreeze") == 3, "Lost/doubled outside-capture freeze spans");
        check(calls(spans, "publication") == 3, "Lost/doubled publication spans");
        check(calls(spans, "canonicalOrdering") == 2, "Lost/doubled sort spans");
        InputContextPhaseMetrics.begin(true);
        AiDecisionEvent.implicit = error;
        same(error, () -> AiDecisionEvent.freezeMap(Map.of(), null));
        AiDecisionEvent.implicit = null;
        Field local = InputContextPhaseMetrics.class.getDeclaredField("LOCAL"); local.setAccessible(true);
        Object state = ((ThreadLocal<?>) local.get(null)).get();
        Field roots = state.getClass().getDeclaredField("freezeSource"); roots.setAccessible(true);
        for (Object root : (Object[]) roots.get(state)) { check(root == null, "Failed freeze retained its sampled source"); }
        AiDecisionEvent.freezeMap(Map.of("value", 3), null);
        check(calls(InputContextPhaseMetrics.finish(), "eventFreeze") == 2, "Memory scope failed to recover");
        for (boolean nested : List.of(false, true)) {
            InputContextPhaseMetrics.begin(true);
            try {
                // This module is deliberately not opened: a sampler failure must remain visible.
                final var opaque = Map.of("opaque", new java.util.concurrent.atomic.AtomicReference<>("value"));
                if (nested) { AiInputContext.capture(() -> AiDecisionEvent.freezeMap(opaque, null), null); }
                else { AiDecisionEvent.freezeMap(opaque, null); }
                throw new AssertionError("Sampling failure was hidden");
            } catch (IllegalStateException diagnostic) {
                check(diagnostic.getMessage().startsWith("Inaccessible payload field"), "Sampler diagnostic was replaced: " + diagnostic);
            }
            state = ((ThreadLocal<?>) local.get(null)).get();
            for (Object root : (Object[]) roots.get(state)) { check(root == null, "Sampler failure retained a source root"); }
            check(calls(InputContextPhaseMetrics.finish(), nested ? "contextFreeze" : "eventFreeze") == 1, "Sampler failure double-retired its span");
        }
        check(InputContextPhaseAgent.transformed == 4, "Missing instrumented fixture classes");
        System.out.println("PASS: nine timing failures preserve original throwables; capture/freeze/publication/sort unwound; failed memory roots cleared; nested sampler diagnostics preserved");
    }
}
