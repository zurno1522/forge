package forge.view;

import com.google.gson.GsonBuilder;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Development process-resource envelope only; never installed by ordinary simulation commands. */
public final class NativeGameCostLauncher {
    private NativeGameCostLauncher() { }
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static long allocation() {
        try { return THREADS.isThreadAllocatedMemorySupported() ? THREADS.getTotalThreadAllocatedBytes() : -1; }
        catch (UnsupportedOperationException unsupported) { return -1; }
    }
    private static long cpu() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os ? os.getProcessCpuTime() : -1;
    }
    private static Long delta(long end, long start) { return end < 0 || start < 0 ? null : end-start; }
    private static Long peakRss() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmHWM:")) { return Long.parseLong(line.trim().split("\\s+")[1])*1024; }
            }
        } catch (Exception unavailable) { /* Non-Linux or unsupported process metrics stay unknown. */ }
        return null;
    }
    public static void main(String[] args) {
        if (THREADS.isThreadAllocatedMemorySupported()) { THREADS.setThreadAllocatedMemoryEnabled(true); }
        final long wallStart = System.nanoTime(), cpuStart = cpu(), allocationStart = allocation();
        final Path output = Path.of(System.getProperty("forge.cost.sidecar"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                final long wallEnd = System.nanoTime(), cpuEnd = cpu(), allocationEnd = allocation();
                final Map<String, Object> result = new LinkedHashMap<>();
                result.put("schemaVersion", 1); result.put("scope", "MAIN_COUNTER_SETUP_TO_JVM_SHUTDOWN_HOOK");
                result.put("wallNanos", wallEnd-wallStart); result.put("processCpuNanos", delta(cpuEnd,cpuStart));
                result.put("allThreadsJavaAllocatedBytes", delta(allocationEnd,allocationStart));
                result.put("peakResidentBytesSinceJvmStart", peakRss());
                result.put("heapUsedAtShutdown", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                final Map<String, Object> pools = new LinkedHashMap<>(), gc = new LinkedHashMap<>();
                for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
                    if (pool.getType() == MemoryType.HEAP) { var usage = pool.getPeakUsage(); pools.put(pool.getName(), usage == null ? null : usage.getUsed()); }
                }
                for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                    final Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("collections", bean.getCollectionCount() < 0 ? null : bean.getCollectionCount());
                    entry.put("milliseconds", bean.getCollectionTime() < 0 ? null : bean.getCollectionTime()); gc.put(bean.getName(),entry);
                }
                result.put("heapPoolPeaksSinceJvmStart", pools); result.put("gcSinceJvmStart", gc);
                result.put("commandArguments", java.util.List.of(args)); result.put("trainingReady", false);
                Files.writeString(output, new GsonBuilder().serializeNulls().create().toJson(result)+"\n");
            } catch (Throwable failure) { System.err.println("Native resource sidecar failed"); failure.printStackTrace(System.err); }
        }, "native-resource-cutoff"));
        Main.main(args);
    }
}
