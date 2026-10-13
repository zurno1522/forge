package forge.view;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Map;

/**
 * Streaming JSONL sink for simulation traces.
 *
 * <p>This deliberately has no dependency on a third-party JSON library so the
 * headless simulator keeps the same dependency surface. Records are written
 * immediately and not retained in memory.</p>
 */
final class SimulationTraceWriter implements AutoCloseable {
    private final BufferedWriter writer;

    private SimulationTraceWriter(final BufferedWriter writer) {
        this.writer = writer;
    }

    static SimulationTraceWriter open(final String filename) throws IOException {
        final Path path = Path.of(filename);
        final Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return new SimulationTraceWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
    }

    synchronized void write(final Map<String, ?> record) throws IOException {
        appendJson(writer, record);
        writer.newLine();
    }

    synchronized void flush() throws IOException {
        writer.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        writer.flush();
        writer.close();
    }

    private static void appendJson(final Appendable out, final Object value) throws IOException {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            appendString(out, s);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value.toString());
        } else if (value instanceof Enum<?> e) {
            appendString(out, e.name());
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            final Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                final Map.Entry<?, ?> entry = iterator.next();
                appendString(out, String.valueOf(entry.getKey()));
                out.append(':');
                appendJson(out, entry.getValue());
                if (iterator.hasNext()) {
                    out.append(',');
                }
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            final Iterator<?> iterator = iterable.iterator();
            while (iterator.hasNext()) {
                appendJson(out, iterator.next());
                if (iterator.hasNext()) {
                    out.append(',');
                }
            }
            out.append(']');
        } else {
            appendString(out, value.toString());
        }
    }

    private static void appendString(final Appendable out, final String value) throws IOException {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int)c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
