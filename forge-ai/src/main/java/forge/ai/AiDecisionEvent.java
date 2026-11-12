package forge.ai;

import forge.game.event.Event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Observational AI telemetry, independent of rules events and trace storage. */
public record AiDecisionEvent(Map<String, Object> data) implements Event {
    public AiDecisionEvent {
        data = freezeMap(data);
    }

    private static Map<String, Object> freezeMap(final Map<?, ?> source) {
        final Map<String, Object> result = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : source.entrySet()) {
            result.put((String) entry.getKey(), freeze(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object freeze(final Object value) {
        if (value instanceof Map<?, ?> map) {
            return freezeMap(map);
        }
        if (value instanceof List<?> list) {
            final List<Object> result = new ArrayList<>();
            for (final Object item : list) {
                result.add(freeze(item));
            }
            return Collections.unmodifiableList(result);
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException("Decision telemetry requires JSON values, got " + value.getClass());
    }
}
