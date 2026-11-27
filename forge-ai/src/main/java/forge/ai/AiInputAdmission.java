package forge.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** One bounded capture counter per original player/Game; receipts contain detached scalar metadata. */
final class AiInputAdmission {
    static final Map<String, Object> BYPASSED = receipt("BYPASSED", false, null);
    static final Map<String, Object> UNKNOWN = receipt("UNESTABLISHED", false, null);
    private static final Map<String, Object> OVERFLOW = receipt("COUNTER_OVERFLOW", true, null);
    private final AtomicLong ordinal = new AtomicLong();

    Map<String, Object> next() {
        long previous = ordinal.get();
        while (previous < Long.MAX_VALUE) {
            if (ordinal.compareAndSet(previous, previous + 1)) {
                return receipt("ADMITTED", true, previous + 1);
            }
            previous = ordinal.get();
        }
        return OVERFLOW;
    }

    private static Map<String, Object> receipt(final String status, final boolean known, final Long ordinal) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("policy", known ? "FIXED_SHARE_PREFIX_V1" : null);
        result.put("denominator", known ? 1 : null);
        result.put("observationSeed", null);
        result.put("ordinal", ordinal);
        result.put("status", status);
        return Collections.unmodifiableMap(result);
    }
}
