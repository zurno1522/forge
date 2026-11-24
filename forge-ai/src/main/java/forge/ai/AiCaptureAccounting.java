package forge.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed scalar source counts for capture calls within one retained parent. No invocation registry. */
final class AiCaptureAccounting {
    enum Family { TARGET, SEARCH }
    private final Family family;
    AiCaptureAccounting() { this(Family.TARGET); }
    AiCaptureAccounting(final Family family) { this.family = family; }

    static final List<String> FIELDS = List.of("entered", "captured", "unsupported", "budget", "interrupted", "failed", "finished", "inputUnavailable");
    private final long[] counts = new long[FIELDS.size()];
    private boolean closed;
    private boolean overflowed;

    synchronized Scope enter() {
        if (closed) { return null; }
        increment(0); return new Scope();
    }

    private void increment(final int field) {
        if (counts[field] == Long.MAX_VALUE) { overflowed = true; } else { counts[field]++; }
    }

    synchronized Map<String, Object> closeAndSnapshot() {
        closed = true;
        final Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < FIELDS.size(); i++) { values.put(FIELDS.get(i), counts[i]); }
        return AiDecisionEvent.freezeMap(Map.of("schemaVersion", 1, "scope", "RECORDING_PARENT_" + family.name() + "_CAPTURE_CALLS",
                "cutoff", "PARENT_CAPTURE_CLOSED", "closed", true, "overflowed", overflowed, "counts", values));
    }

    final class Scope {
        private boolean resolved;
        private boolean exited;
        void unsupported() { outcome(2); }
        void budget() { outcome(3); }
        void interrupted() { outcome(4); }
        void failed() { outcome(5); }

        private void outcome(final int field) {
            synchronized (AiCaptureAccounting.this) {
                if (closed || resolved || exited) { return; }
                resolved = true; increment(field);
            }
        }

        void captured(final boolean inputUnavailable) {
            synchronized (AiCaptureAccounting.this) {
                if (closed || resolved || exited) { return; }
                resolved = true; increment(1);
                if (inputUnavailable) { increment(7); }
            }
        }

        void exit() {
            synchronized (AiCaptureAccounting.this) {
                if (closed || exited) { return; }
                if (!resolved) { resolved = true; increment(5); }
                exited = true; increment(6);
            }
        }
    }
}
