package forge.view;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AiDecisionEvent;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.CardView;
import forge.game.event.GameEventCardTapped;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventShuffle;
import forge.game.player.PlayerView;
import forge.util.Localizer;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class SimulationDecisionOutputTest {
    @BeforeClass
    public void localize() {
        Localizer.getInstance().initialize("en-US", "../forge-gui/res/languages/");
    }

    @DataProvider
    public Object[][] traceModes() {
        return new Object[][] { { false }, { true } };
    }

    @Test(dataProvider = "traceModes", groups = { "UnitTest", "fast" })
    public void decisionStreamWorksWithAndWithoutFullTrace(final boolean fullTrace) throws Exception {
        final Path dir = Files.createTempDirectory("forge-decision-output-");
        final Path full = dir.resolve("trace.jsonl");
        final Path decisions = dir.resolve("decisions.jsonl");
        try {
            final GameRules rules = new GameRules(GameType.Constructed);
            final Game game = new Game(List.of(), rules, new Match(rules, List.of(), "Decisions"));
            Assert.assertFalse(game.isRecordingAiDecisions());
            try (SimulationTraceRun run = SimulationTraceRun.open(fullTrace ? full.toString() : null,
                    decisions.toString(), rules, List.of(), "GAMES", 1, null, null, null, 7L, "HEURISTIC")) {
                final SimulationTraceCollector collector = run.beginGame(game, null, null, null);
                Assert.assertTrue(game.isRecordingAiDecisions());
                game.fireEvent(new GameEventShuffle((PlayerView) null));
                game.fireEvent(new GameEventGameStarted(GameType.Constructed, new PlayerView(0, game.getTracker()), List.of()));
                game.fireEvent(new AiDecisionEvent(Map.of("decisionId", "0:1")));
                game.fireEvent(new GameEventCardTapped((CardView) null, false));
                run.finishGame(collector, "NORMAL", null);
                run.finishNormal();
            }
            final List<JsonObject> data = read(decisions);
            Assert.assertEquals(data.stream().map(r -> r.get("type").getAsString()).toList(),
                    List.of("RUN_STARTED", "GAME_STARTED", "AI_DECISION", "GAME_EXECUTION_FINISHED", "RUN_FINISHED"));
            Assert.assertEquals(data.get(0).get("streamKind").getAsString(), "AI_DECISIONS");
            Assert.assertEquals(data.get(0).get("pilotMode").getAsString(), "HEURISTIC");
            Assert.assertEquals(data.get(0).get("seed").getAsLong(), 7L);
            Assert.assertEquals(data.get(1).get("eventIndex").getAsInt(), fullTrace ? 2 : 1);
            if (fullTrace) {
                final List<JsonObject> complete = read(full);
                Assert.assertEquals(complete.size(), 7);
                Assert.assertEquals(complete.get(1).get("type").getAsString(), "SHUFFLE");
                Assert.assertEquals(complete.get(0).get("runId"), data.get(0).get("runId"));
                Assert.assertEquals(data.get(0).get("eventIndexMode").getAsString(), "SHARED_WITH_GAME_TRACE");
            } else {
                Assert.assertFalse(Files.exists(full));
                Assert.assertEquals(data.get(0).get("eventIndexMode").getAsString(), "CONTIGUOUS");
            }
        } finally {
            Files.deleteIfExists(full);
            Files.deleteIfExists(decisions);
            Files.deleteIfExists(dir);
        }
    }

    @Test(groups = { "UnitTest", "fast" })
    public void rejectsIdenticalOutputPathsBeforeTruncating() throws Exception {
        final Path file = Files.createTempFile("forge-same-output-", ".jsonl");
        try {
            Files.writeString(file, "sentinel");
            Assert.expectThrows(SimulationTraceRun.TraceFailure.class,
                    () -> SimulationTraceRun.open(file.toString(), file.toString(), new GameRules(GameType.Constructed),
                            List.of(), "GAMES", 1, null, null, null, 1L, "HEURISTIC"));
            Assert.assertEquals(Files.readString(file), "sentinel");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test(groups = { "UnitTest", "fast" })
    public void secondarySinkFailureCannotReportHealthy() throws Exception {
        final Path full = Files.createTempFile("forge-full-", ".jsonl");
        final Path decisions = Files.createTempFile("forge-decisions-", ".jsonl");
        final SimulationTraceWriter writer = SimulationTraceWriter.open(full.toString());
        final SimulationTraceWriter sink = SimulationTraceWriter.open(decisions.toString());
        try {
            final GameRules rules = new GameRules(GameType.Constructed);
            final Game game = new Game(List.of(), rules, new Match(rules, List.of(), "Failure"));
            final SimulationTraceCollector collector = new SimulationTraceCollector(game, writer, sink, 1,
                    Map.of(), null, null, null);
            game.subscribeToEvents(collector);
            sink.close();
            game.fireEvent(new AiDecisionEvent(Map.of("decisionId", "0:1")));
            final IOException error = Assert.expectThrows(IOException.class, collector::checkHealthy);
            Assert.assertTrue(error.getMessage().contains("AiDecisionEvent"));
            Assert.expectThrows(IOException.class, () -> collector.executionFinished("NORMAL", null));
        } finally {
            writer.close();
            try { sink.close(); } catch (IOException ignored) { /* Deliberately closed. */ }
            Files.deleteIfExists(full);
            Files.deleteIfExists(decisions);
        }
    }

    private static List<JsonObject> read(final Path path) throws Exception {
        return Files.readAllLines(path).stream().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    }
}
