package forge.ai;

import java.util.Map;

/** Exercises both local and propagated throws at the exact instrumented method boundary. */
final class AiDecisionEvent {
    static RuntimeException implicit;
    static Map<?, ?> freezeMap(Map<?, ?> source, RuntimeException explicit) {
        if (explicit != null) { throw explicit; }
        mayFail();
        return Map.copyOf(source);
    }
    private static void mayFail() { if (implicit != null) { throw implicit; } }
}
