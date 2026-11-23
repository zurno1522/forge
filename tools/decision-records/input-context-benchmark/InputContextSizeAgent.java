package forge.ai;
import java.lang.instrument.Instrumentation;
public final class InputContextSizeAgent {
    static Instrumentation instrumentation;
    public static void premain(String ignored, Instrumentation value) { instrumentation = value; }
}
