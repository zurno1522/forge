package forge.view;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.card.GamePieceType;
import forge.game.Game;
import forge.game.card.CardView;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.phase.PhaseHandler;
import forge.game.player.PlayerView;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SimulationTraceBlockersTest {
    @DataProvider(name = "blockerCounts")
    public Object[][] blockerCounts() {
        return new Object[][] { { 0 }, { 1 }, { 2 } };
    }

    @Test(dataProvider = "blockerCounts", groups = { "UnitTest", "fast" })
    public void writesActualBlockersWithoutUnblockedPlaceholder(final int blockerCount) throws Exception {
        final Game game = mock(Game.class);
        when(game.getPhaseHandler()).thenReturn(mock(PhaseHandler.class));
        final PlayerView defender = new PlayerView(1, null);
        final CardView attacker = cardView(8, "Runeclaw Bear");
        final Multimap<CardView, CardView> blocks = ArrayListMultimap.create();
        if (blockerCount == 0) {
            // Use a separate view with the same ID to verify card identity, not object identity.
            blocks.put(attacker, cardView(8, "Runeclaw Bear"));
        } else {
            for (int i = 0; i < blockerCount; i++) {
                blocks.put(attacker, cardView(90 + i, "Hill Giant"));
            }
        }

        final Path file = Files.createTempFile("forge-blockers-trace-", ".jsonl");
        try {
            try (SimulationTraceWriter writer = SimulationTraceWriter.open(file.toString())) {
                final SimulationTraceCollector collector = new SimulationTraceCollector(
                        game, writer, 1, Map.of(), null, null, null);
                collector.onGameEvent(new GameEventBlockersDeclared(defender, Map.of(defender, blocks)));
                collector.checkHealthy();
            }
            final JsonObject record = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            Assert.assertEquals(record.get("type").getAsString(), "BLOCKERS_DECLARED");
            final JsonArray entries = record.getAsJsonArray("attackedEntities").get(0)
                    .getAsJsonObject().getAsJsonArray("blocks");
            Assert.assertEquals(entries.size(), 1);
            final JsonObject block = entries.get(0).getAsJsonObject();
            Assert.assertEquals(block.getAsJsonObject("attacker").get("id").getAsInt(), 8);
            final JsonArray actualBlockers = block.getAsJsonArray("blockers");
            Assert.assertEquals(actualBlockers.size(), blockerCount);
            for (int i = 0; i < blockerCount; i++) {
                Assert.assertEquals(actualBlockers.get(i).getAsJsonObject().get("id").getAsInt(), 90 + i);
            }
            Assert.assertEquals(blocks.size(), Math.max(1, blockerCount));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static CardView cardView(final int id, final String name) {
        final CardView card = mock(CardView.class);
        when(card.getId()).thenReturn(id);
        when(card.getName()).thenReturn(name);
        when(card.getGamePieceType()).thenReturn(GamePieceType.CARD);
        return card;
    }
}
