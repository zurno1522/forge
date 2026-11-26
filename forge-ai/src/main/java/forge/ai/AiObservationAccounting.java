package forge.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded, source-side callback accounting. No Game, callback, result or invocation registry is retained. */
public final class AiObservationAccounting {
    public static final List<String> FIELDS = List.of("entered", "prepared", "omitted", "callbackStarted", "callbackReturned",
            "callbackThrew", "inputUnavailable", "startAttempts", "startReturned", "terminalAttempts", "terminalReturned", "finished", "observationFailures",
            "omittedUnsupported", "omittedClosed", "omittedFailure");
    private static final Set<String> BOUNDARIES = Set.of("TRIGGER_PREPARATION", "COST_VISITOR", "PAYMENT_SACRIFICE_POOL",
            "PAYMENT_DISCARD_POOL", "PAYMENT_EXILE_POOL", "AUTO_MANA_PAYMENT", "MANA_POOL_OPTIONS", "MANA_SOURCE_OPTIONS", "CONTROLLER_TARGETS",
            "COPY_TARGETS", "CONTROLLER_RETARGET", "CHARM_MODE_HEURISTIC", "CONTROLLER_MODES", "CONTROLLER_NUMBER_RANGE",
            "CONTROLLER_NUMBER_OPTIONS", "CONTROLLER_ANNOUNCEMENT", "MAX_X_HEURISTIC", "X_ANNOUNCEMENT_REFINEMENT",
            "MASKED_CHOICE", "CONTROLLER_EFFECT_ENTITY", "CONTROLLER_EFFECT_ENTITIES", "CONTROLLER_EFFECT_SPELL_ABILITIES", "CONTROLLER_EFFECT_SPELL_ABILITY", "CONTROLLER_ZONE_CHANGE_SELECTIONS", "CONTROLLER_ZONE_CHANGE_CARD", "DESTROY_REMOVAL_POOL",
            "DESTROY_LAND_POOL", "DESTROY_POLYMORPH_POOL", "DAMAGE_OPPONENT_POOL", "DAMAGE_KILLABLES",
            "DAMAGE_MANDATORY_OPPONENT_POOL", "DAMAGE_MANDATORY_FALLBACK_POOL", "DAMAGE_KILLABLE_PLANESWALKERS",
            "DAMAGE_PLANESWALKER_POOL", "DRAW_PLAYER_POOL", "DAMAGE_PLAYER_POOL", "ATTACH_CARD_POOL", "ATTACH_PLAYER_POOL", "MASKED_TARGET_POOL");
    private final Map<String, long[]> cells = new LinkedHashMap<>();
    private boolean closed;
    private boolean overflowed;
    private boolean scopeComplete = true;
    private long collectorStarts;
    private long collectorTerminals;

    public AiObservationAccounting() {
        cells.put("UNCLASSIFIED", new long[FIELDS.size()]);
        for (final String name : new java.util.TreeSet<>(BOUNDARIES)) { cells.put(name, new long[FIELDS.size()]); }
    }

    private void increment(final long[] cell, final int index) {
        if (cell[index] == Long.MAX_VALUE) { overflowed = true; } else { cell[index]++; }
    }

    synchronized Scope enter() {
        if (closed) { return null; }
        final Scope scope = new Scope(); increment(scope.cell, 0); return scope;
    }

    public synchronized void incomplete() { if (!closed) { scopeComplete = false; } }
    public synchronized void close() { closed = true; }
    public synchronized void reconcileReceipts() {
        long starts = 0, terminals = 0;
        for (final long[] row : cells.values()) { starts = sum(starts, row[7]); terminals = sum(terminals, row[9]); }
        if (collectorStarts > starts || collectorTerminals > terminals) { scopeComplete = false; }
    }
    public synchronized void collectorWrite(final boolean started) {
        if (closed) { return; }
        if (started) { collectorStarts = sum(collectorStarts, 1); }
        else { collectorTerminals = sum(collectorTerminals, 1); }
    }

    private long sum(final long left, final long right) {
        if (Long.MAX_VALUE - left < right) { overflowed = true; return Long.MAX_VALUE; }
        return left + right;
    }

    /** Merge already closed game/controller snapshots into a fixed-size run aggregate. */
    public synchronized void merge(final Map<String, Object> snapshot) {
        if (closed) { throw new IllegalStateException("Accounting aggregate is closed"); }
        scopeComplete &= Boolean.TRUE.equals(snapshot.get("closed")) && Boolean.TRUE.equals(snapshot.get("scopeComplete"));
        overflowed |= Boolean.TRUE.equals(snapshot.get("overflowed"));
        collectorStarts = sum(collectorStarts, ((Number) snapshot.get("collectorStarts")).longValue());
        collectorTerminals = sum(collectorTerminals, ((Number) snapshot.get("collectorTerminals")).longValue());
        for (final var entry : ((Map<?, ?>) snapshot.get("boundaries")).entrySet()) {
            final long[] cell = cells.get(entry.getKey());
            if (cell == null) { scopeComplete = false; continue; }
            final Map<?, ?> row = (Map<?, ?>) entry.getValue();
            for (int i = 0; i < FIELDS.size(); i++) { cell[i] = sum(cell[i], ((Number) row.get(FIELDS.get(i))).longValue()); }
        }
    }

    public synchronized Map<String, Object> snapshot() {
        final Map<String, Object> values = new LinkedHashMap<>();
        for (final var entry : cells.entrySet()) {
            if (entry.getValue()[0] == 0) { continue; }
            final Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < FIELDS.size(); i++) { row.put(FIELDS.get(i), entry.getValue()[i]); }
            values.put(entry.getKey(), row);
        }
        return AiDecisionEvent.freezeMap(Map.of("closed", closed, "overflowed", overflowed, "scopeComplete", scopeComplete,
                "boundaries", values, "collectorStarts", collectorStarts, "collectorTerminals", collectorTerminals));
    }

    final class Scope {
        private long[] cell = cells.get("UNCLASSIFIED");
        private boolean resolved;
        private boolean exited;
        private int omissionReason = 15;

        void unsupported() { omissionReason = 13; }
        void admissionClosed() { omissionReason = 14; }

        void classify(final String name) {
            synchronized (AiObservationAccounting.this) {
                if (closed || resolved || exited) { return; }
                if (name == null || name.length() > 64) { scopeComplete = false; return; }
                final long[] next = cells.get(name);
                if (next == null) { scopeComplete = false; return; }
                if (next != cell) {
                    if (cell[0] > 0) { cell[0]--; } else { overflowed = true; }
                    increment(next, 0); cell = next;
                }
            }
        }

        void prepared() {
            synchronized (AiObservationAccounting.this) {
                if (closed || resolved || exited) { return; }
                resolved = true; increment(cell, 1);
            }
        }

        void failure() { add(12); }
        void unavailable() { add(6); }
        void callbackStarted() { add(3); }
        void callbackReturned() { add(4); }
        void callbackThrew() { add(5); }
        void startAttempt() { add(7); }
        void startReturned() { add(8); }
        void terminalAttempt() { add(9); }
        void terminalReturned() { add(10); }

        private void add(final int index) {
            synchronized (AiObservationAccounting.this) { if (!closed && !exited) { increment(cell, index); } }
        }

        void exit() {
            synchronized (AiObservationAccounting.this) {
                if (closed || exited) { return; }
                exited = true;
                if (!resolved) { resolved = true; increment(cell, 2); increment(cell, omissionReason); }
                increment(cell, 11);
            }
        }
    }
}
