package forge.view;

import com.google.gson.JsonParser;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEventShuffle;
import forge.game.player.PlayerView;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.BeforeClass;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class SimulationLongIdentifierTest {
    @BeforeClass
    public void localize() {
        forge.util.Localizer.getInstance().initialize("en-US", "../forge-gui/res/languages/");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void eventIndicesKeepIncreasingPastTheIntBoundary() throws Exception {
        final Path file = Files.createTempFile("forge-long-events-", ".jsonl");
        try (SimulationTraceWriter writer = SimulationTraceWriter.open(file.toString())) {
            final GameRules rules = new GameRules(GameType.Constructed);
            final Game game = new Game(List.of(), rules, new Match(rules, List.of(), "Long IDs"));
            final SimulationTraceCollector collector = new SimulationTraceCollector(game, writer, 1, Map.of(), null, null, null);
            final var index = SimulationTraceCollector.class.getDeclaredField("eventIndex");
            index.setAccessible(true);
            index.setLong(collector, Integer.MAX_VALUE - 1L);
            collector.onGameEvent(new GameEventShuffle((PlayerView) null));
            collector.onGameEvent(new GameEventShuffle((PlayerView) null));
            collector.checkHealthy();
            writer.flush();
            final List<String> lines = Files.readAllLines(file);
            Assert.assertEquals(JsonParser.parseString(lines.get(0)).getAsJsonObject().get("eventIndex").getAsLong(), Integer.MAX_VALUE);
            Assert.assertEquals(JsonParser.parseString(lines.get(1)).getAsJsonObject().get("eventIndex").getAsLong(), Integer.MAX_VALUE + 1L);
            Assert.assertEquals(collector.nextEventIndex(), Integer.MAX_VALUE + 2L);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
