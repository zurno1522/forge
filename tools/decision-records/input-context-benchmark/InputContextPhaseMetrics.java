package forge.ai;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Inclusive outermost spans; recursion is counted once, and overlapping phases stay explicit. */
public final class InputContextPhaseMetrics {
    static final String[] PHASES = {"capture", "canonicalOrdering", "contextFreeze", "eventFreeze", "publication", "serialization", "disclosureDelivery"};
    static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final ThreadLocal<State> LOCAL = ThreadLocal.withInitial(State::new);
    private static final class State {
        boolean enabled, memory;
        final int[] depth = new int[PHASES.length];
        final long[] cpuStart = new long[PHASES.length], allocationStart = new long[PHASES.length];
        final long[] cpu = new long[PHASES.length], allocated = new long[PHASES.length], calls = new long[PHASES.length];
        final Object[] freezeSource = new Object[2];
        long peakSampledPayloadBytes;
    }
    static void initialize() {
        if (!THREADS.isThreadCpuTimeSupported() || !THREADS.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("CPU/allocation counters unavailable");
        }
        THREADS.setThreadCpuTimeEnabled(true);
        THREADS.setThreadAllocatedMemoryEnabled(true);
        LOCAL.get();
    }
    static void begin(boolean memory) {
        State s = new State(); s.enabled = Boolean.getBoolean("forge.context.profile"); s.memory = memory; LOCAL.set(s);
    }
    public static void enter(int phase) {
        State s = LOCAL.get();
        if (!s.enabled || s.depth[phase]++ > 0) { return; }
        s.calls[phase]++;
        if (!s.memory) {
            s.cpuStart[phase] = THREADS.getCurrentThreadCpuTime();
            s.allocationStart[phase] = THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
    }
    public static void exit(int phase) {
        State s = LOCAL.get();
        if (!s.enabled || --s.depth[phase] > 0) { return; }
        if (s.depth[phase] < 0) { throw new IllegalStateException("Unbalanced phase " + phase); }
        if (!s.memory) {
            s.allocated[phase] += THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()) - s.allocationStart[phase];
            s.cpu[phase] += THREADS.getCurrentThreadCpuTime() - s.cpuStart[phase];
        }
    }
    public static void freezeEnter(Object source) {
        State s = LOCAL.get(); if (!s.enabled) { return; }
        int phase = s.depth[0] > 0 ? 2 : 3;
        if (s.memory && s.depth[phase] == 0) { s.freezeSource[phase - 2] = source; }
        enter(phase);
    }
    public static void freezeExit(Object result) {
        State s = LOCAL.get(); if (!s.enabled) { return; }
        int phase = s.depth[0] > 0 ? 2 : 3;
        boolean outer = s.depth[phase] == 1;
        exit(phase);
        if (outer && s.memory) {
            sample(s.freezeSource[phase - 2], result);
            s.freezeSource[phase - 2] = null;
        }
    }
    static void sample(Object... roots) {
        State s = LOCAL.get();
        if (s.enabled && s.memory) { s.peakSampledPayloadBytes = Math.max(s.peakSampledPayloadBytes, reachable(roots)); }
    }
    static Map<String, Object> finish() {
        State s = LOCAL.get(); s.enabled = false;
        if (Arrays.stream(s.depth).anyMatch(value -> value != 0)) { throw new IllegalStateException("Unbalanced instrumentation"); }
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < PHASES.length; i++) {
            result.put(PHASES[i], Map.of("calls", s.calls[i], "cpuNanos", s.cpu[i], "allocatedBytes", s.allocated[i]));
        }
        result.put("peakSampledPayloadBytes", s.peakSampledPayloadBytes);
        return result;
    }
    static long reachable(Object... roots) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object> pending = new ArrayDeque<>();
        for (Object root : roots) { if (root != null) { pending.add(root); } }
        long bytes = 0;
        while (!pending.isEmpty()) {
            Object value = pending.removeFirst(); if (!seen.add(value)) { continue; }
            bytes += InputContextPhaseAgent.instrumentation.getObjectSize(value);
            Class<?> type = value.getClass();
            if (type.isArray()) {
                if (!type.getComponentType().isPrimitive()) {
                    for (int i = 0; i < Array.getLength(value); i++) {
                        Object item = Array.get(value, i); if (item != null) { pending.add(item); }
                    }
                }
            } else {
                for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                    for (Field field : current.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) { continue; }
                        if (!field.trySetAccessible()) { throw new IllegalStateException("Inaccessible payload field " + field); }
                        try { Object item = field.get(value); if (item != null) { pending.add(item); } }
                        catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
                    }
                }
            }
        }
        return bytes;
    }
}
