package forge.ai;

import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;

/** Exact bounded diagnostic arguments. Refuse an unrepresentable request rather than truncate it. */
final class AiZoneOrigins {
    private static final int MAX_ORIGINS = ZoneType.values().length;
    private AiZoneOrigins() { }

    static List<String> capture(final List<ZoneType> origins) {
        if (origins == null) { throw AiChoiceRecorder.unsupportedOptions(); }
        final int count = origins.size();
        if (count < 0 || count > MAX_ORIGINS) { throw AiChoiceRecorder.unsupportedOptions(); }
        final List<String> values = new ArrayList<>();
        final var iterator = origins.iterator();
        while (iterator.hasNext()) {
            if (values.size() == MAX_ORIGINS) { throw AiChoiceRecorder.unsupportedOptions(); }
            final ZoneType value = iterator.next();
            if (value == null) { throw AiChoiceRecorder.unsupportedOptions(); }
            values.add(value.name());
        }
        if (values.size() != count) { throw AiChoiceRecorder.unsupportedOptions(); }
        return List.copyOf(values);
    }
}
