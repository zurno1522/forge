package forge.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Isolated bytecode fixture for the agent, never an engine or gameplay substitute. */
final class AiInputContext {
    static Object capture(Supplier<Object> body, RuntimeException explicit) {
        if (explicit != null) { throw explicit; }
        return body.get();
    }
    static final class Capture {
        Object build(RuntimeException failure) {
            List<Integer> values = new ArrayList<>(List.of(2, 1));
            values.sort((a, b) -> { if (failure != null) { throw failure; } return a.compareTo(b); });
            return values;
        }
    }
}
