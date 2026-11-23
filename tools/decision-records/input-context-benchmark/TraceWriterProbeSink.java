package forge.view;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

/** Development bridge to the actual production writer; buffered character output, no filesystem I/O. */
public final class TraceWriterProbeSink implements AutoCloseable {
    private static volatile Object consumed;
    private final CountingWriter sink = new CountingWriter();
    private final SimulationTraceWriter writer;

    public TraceWriterProbeSink() {
        try {
            Constructor<SimulationTraceWriter> constructor = SimulationTraceWriter.class.getDeclaredConstructor(BufferedWriter.class);
            constructor.setAccessible(true);
            writer = constructor.newInstance(new BufferedWriter(sink));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    public void write(Object value) {
        if (!(value instanceof Map<?,?> map)) { throw new IllegalArgumentException("Expected record map"); }
        @SuppressWarnings("unchecked") Map<String,?> record = (Map<String,?>) map;
        try { writer.write(record); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    @Override public void close() {
        try { writer.close(); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    public long characters() { return sink.characters; }

    /** Outside timing: obtain bytes from the same encoder for size/round-trip validation. */
    public static String encode(Object value) {
        try {
            Method append = SimulationTraceWriter.class.getDeclaredMethod("appendJson", Appendable.class, Object.class);
            append.setAccessible(true);
            StringWriter out = new StringWriter(); append.invoke(null, out, value); return out.toString();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private static final class CountingWriter extends Writer {
        long characters;
        @Override public void write(char[] value, int offset, int length) {
            characters += length;
            consumed = value; // Escape the actual buffer so encoded character stores remain observable.
        }
        @Override public void flush() { }
        @Override public void close() { }
    }
}
