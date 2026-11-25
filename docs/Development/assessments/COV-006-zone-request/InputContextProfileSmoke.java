package forge.ai;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Exercises one real workload trial; this is a protocol smoke check, not a cost assessment. */
public final class InputContextProfileSmoke {
    public static void main(String[] args) throws Exception {
        System.setProperty("forge.context.iterations", "1");
        System.setProperty("forge.context.warmup", "0");
        var probe = new InputContextWorkloadProbe();
        probe.initializeModel();
        var fixtures = InputContextWorkloadProbe.class.getDeclaredField("WORKLOADS"); fixtures.setAccessible(true);
        var workload = ((List<?>) fixtures.get(null)).get(0);
        var trial = InputContextWorkloadProbe.class.getDeclaredMethod("trial", workload.getClass(), String.class, int.class, boolean.class, boolean.class);
        trial.setAccessible(true);
        var row = (Map<?, ?>) trial.invoke(probe, workload, "NUMERIC", 0, false, false);
        if (!"PERMITTED_CHOICE_VIEW_V8".equals(row.get("inputContextProfile"))
                || !Long.valueOf(1).equals(row.get("callbacks")) || !Long.valueOf(2).equals(row.get("events"))) {
            throw new AssertionError("Profile/callback/event smoke check failed");
        }
        Files.writeString(Path.of(args[0]), new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(row) + "\n");
        System.exit(0);
    }
}
