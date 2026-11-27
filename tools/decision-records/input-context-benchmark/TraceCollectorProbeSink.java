package forge.view;

import forge.game.Game;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Actual collector and UTF-8 writer for synthetic operations, never a played-game trace. */
public final class TraceCollectorProbeSink implements AutoCloseable {
    private final SimulationTraceWriter writer;
    private final SimulationTraceCollector collector;

    public TraceCollectorProbeSink(Game game, Path path) throws IOException {
        writer = SimulationTraceWriter.open(path.toString());
        collector = new SimulationTraceCollector(game, writer, 1, Map.of(), null, null, null);
        collector.attach(); game.setRecordAiDecisions(true);
    }
    public void flush() throws IOException { writer.flush(); }
    public void breakWriter() throws IOException { writer.close(); }
    public boolean failed() {
        try { collector.checkHealthy(); return false; }
        catch (IOException failure) { return true; }
    }
    @Override public void close() throws IOException {
        try { collector.executionFinished("NORMAL", null); }
        finally { writer.close(); }
    }
}
