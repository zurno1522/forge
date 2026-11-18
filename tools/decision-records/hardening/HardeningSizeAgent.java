package forge.view;

import java.lang.instrument.Instrumentation;

/** Development probe only; not packaged into Forge. */
public final class HardeningSizeAgent {
    static Instrumentation instrumentation;

    public static void premain(String options, Instrumentation value) {
        instrumentation = value;
    }
}
