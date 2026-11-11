package forge.view;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SimulationTraceRunTest {
    @DataProvider
    public Object[][] completedRunStatuses() {
        return new Object[][] {
                { List.of("NORMAL"), "NORMAL", 1, 0, 0 },
                { List.of("TIMEOUT"), "TIMEOUT", 0, 1, 0 },
                { List.of("TIMEOUT", "NORMAL"), "TIMEOUT", 1, 1, 0 },
                { List.of("EXCEPTION"), "EXCEPTION", 0, 0, 1 },
                { List.of("TIMEOUT", "EXCEPTION", "NORMAL"), "EXCEPTION", 1, 1, 1 },
                { List.of("EXCEPTION", "TIMEOUT"), "EXCEPTION", 0, 1, 1 }
        };
    }

    @Test(dataProvider = "completedRunStatuses", groups = { "UnitTest", "fast" })
    public void completedRunReportsWorstGameStatus(final List<String> statuses,
            final String expectedStatus, final int normal, final int timedOut, final int failed) throws Exception {
        final Path file = Files.createTempFile("forge-sim-run-", ".jsonl");
        try {
            try (SimulationTraceRun run = open(file)) {
                for (final String status : statuses) {
                    final SimulationTraceCollector collector = run.beginGame(Mockito.mock(Game.class), null, null, null);
                    run.finishGame(collector, status,
                            "EXCEPTION".equals(status) ? new IllegalStateException("game failed") : null);
                }
                run.finishNormal();
            }

            final List<JsonObject> records = records(file);
            Assert.assertEquals(records.size(), statuses.size() + 2);
            for (int i = 0; i < statuses.size(); i++) {
                final JsonObject game = records.get(i + 1);
                Assert.assertEquals(game.get("type").getAsString(), "GAME_EXECUTION_FINISHED");
                Assert.assertEquals(game.get("status").getAsString(), statuses.get(i));
            }
            assertFinished(records.get(records.size() - 1), expectedStatus, statuses.size(), normal, timedOut, failed);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test(groups = { "UnitTest", "fast" })
    public void abortOverridesTimeoutAndFinalizationIsIdempotent() throws Exception {
        final Path file = Files.createTempFile("forge-sim-run-", ".jsonl");
        try {
            try (SimulationTraceRun run = open(file)) {
                final SimulationTraceCollector collector = run.beginGame(Mockito.mock(Game.class), null, null, null);
                run.finishGame(collector, "TIMEOUT", null);
                run.finishAborted();
                run.finishNormal();
            }

            final List<JsonObject> records = records(file);
            Assert.assertEquals(records.size(), 3);
            assertFinished(records.get(2), "ABORTED", 1, 0, 1, 0);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test(groups = { "UnitTest", "fast" })
    public void traceFailureCountsFailedAttemptAndAbortsRun() throws Exception {
        final Path file = Files.createTempFile("forge-sim-run-", ".jsonl");
        try {
            final IOException failure = new IOException("subscriber failed", new NullPointerException("card type"));
            try (SimulationTraceRun run = open(file)) {
                run.beginGame(Mockito.mock(Game.class), null, null, null);
                final SimulationTraceCollector collector = Mockito.mock(SimulationTraceCollector.class);
                Mockito.doThrow(failure).when(collector).executionFinished("NORMAL", null);

                final SimulationTraceRun.TraceFailure thrown = Assert.expectThrows(
                        SimulationTraceRun.TraceFailure.class, () -> run.finishGame(collector, "NORMAL", null));
                Assert.assertSame(thrown.getCause(), failure);
            }

            final List<JsonObject> records = records(file);
            Assert.assertEquals(records.size(), 2);
            assertFinished(records.get(1), "ABORTED", 1, 0, 0, 1);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static SimulationTraceRun open(final Path file) {
        return SimulationTraceRun.open(file.toString(), new GameRules(GameType.Constructed), List.of(),
                "GAMES", null, null, null, null, 12345L);
    }

    private static List<JsonObject> records(final Path file) throws IOException {
        final List<JsonObject> records = new ArrayList<>();
        for (final String line : Files.readAllLines(file)) {
            records.add(JsonParser.parseString(line).getAsJsonObject());
        }
        return records;
    }

    private static void assertFinished(final JsonObject record, final String status,
            final int attempted, final int normal, final int timedOut, final int failed) {
        Assert.assertEquals(record.get("type").getAsString(), "RUN_FINISHED");
        Assert.assertEquals(record.get("status").getAsString(), status);
        Assert.assertEquals(record.get("gamesAttempted").getAsInt(), attempted);
        Assert.assertEquals(record.get("gamesNormal").getAsInt(), normal);
        Assert.assertEquals(record.get("gamesTimedOut").getAsInt(), timedOut);
        Assert.assertEquals(record.get("gamesFailed").getAsInt(), failed);
    }
}
