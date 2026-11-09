package forge.view;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventShuffle;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.player.PlayerOutcome;
import forge.game.player.RegisteredPlayer;
import forge.util.Lang;
import forge.util.Localizer;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimulateMatchTest {
    @BeforeClass
    public void initializeLocalizer() {
        Localizer.getInstance().initialize("en-US", "../forge-gui/res/languages/");
        Lang.createInstance("en-US");
    }

    @Test(groups = { "UnitTest", "fast" })
    public void forcingUnfinishedGameToDrawDoesNotAwardPlayersWins() {
        final Game game = match(2).createGame();

        SimulateMatch.finishAsDraw(game);

        assertDrawWithoutWinners(game);
        for (final Player player : game.getRegisteredPlayers()) {
            Assert.assertEquals(player.getOutcome().lossState, GameLossReason.IntentionalDraw);
        }
    }

    @Test(groups = { "UnitTest", "fast" })
    public void forcedDrawPreservesAnEliminatedPlayersLoss() {
        final Game game = match(3).createGame();
        final Player eliminated = game.getPlayers().get(0);
        eliminated.concede();
        final PlayerOutcome loss = eliminated.getOutcome();
        game.onPlayerLost(eliminated);
        Assert.assertFalse(game.getPlayers().contains(eliminated));

        SimulateMatch.finishAsDraw(game);

        assertDrawWithoutWinners(game);
        Assert.assertSame(eliminated.getOutcome(), loss);
        Assert.assertEquals(eliminated.getOutcome().lossState, GameLossReason.Conceded);
        for (final Player player : game.getPlayers()) {
            Assert.assertEquals(player.getOutcome().lossState, GameLossReason.IntentionalDraw);
        }
    }

    @Test(groups = { "UnitTest", "fast" })
    public void forcedDrawReplacesPendingAlternativeWinBeforeGameIsFinalized() {
        final Game game = match(2).createGame();
        final Player pendingWinner = game.getPlayers().get(0);
        pendingWinner.getStats().setOutcome(PlayerOutcome.altWin("Test alternative win"));
        Assert.assertFalse(game.isGameOver());
        Assert.assertTrue(pendingWinner.getOutcome().hasWon());

        SimulateMatch.finishAsDraw(game);

        assertDrawWithoutWinners(game);
        Assert.assertEquals(pendingWinner.getOutcome().lossState, GameLossReason.IntentionalDraw);
    }

    @Test(groups = { "UnitTest", "fast" })
    public void completedNormalResultSurvivesDrawCleanup() {
        final Game game = match(2).createGame();
        final Player winner = game.getPlayers().get(0);
        final Player loser = game.getPlayers().get(1);
        loser.concede();
        game.setGameOver(GameEndReason.AllOpponentsLost);
        final GameOutcome completed = game.getOutcome();
        final PlayerOutcome win = winner.getOutcome();
        final PlayerOutcome loss = loser.getOutcome();

        SimulateMatch.finishAsDraw(game);

        Assert.assertSame(game.getOutcome(), completed);
        Assert.assertFalse(game.getOutcome().isDraw());
        Assert.assertSame(game.getOutcome().getWinningLobbyPlayer(), winner.getLobbyPlayer());
        Assert.assertSame(winner.getOutcome(), win);
        Assert.assertTrue(winner.getOutcome().hasWon());
        Assert.assertSame(loser.getOutcome(), loss);
    }

    @Test(groups = { "UnitTest", "fast" })
    public void timeoutWaitsForGameCleanupBeforeFinalizingItsTrace() throws Exception {
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setSimTimeout(1);
        final List<RegisteredPlayer> players = players(2);
        final BlockingMatch match = new BlockingMatch(rules, players);
        final Path file = Files.createTempFile("forge-sim-timeout-", ".jsonl");
        final ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            try (SimulationTraceRun run = SimulationTraceRun.open(file.toString(), rules, players,
                    "GAMES", 1, null, null, null, 12345L)) {
                final Future<?> execution = caller.submit(
                        () -> SimulateMatch.simulateSingleMatch(match, 0, false, run, null, null));
                try {
                    Assert.assertTrue(match.started.await(5, TimeUnit.SECONDS), "Game worker did not start");
                    Assert.assertTrue(match.cancellationSeen.await(5, TimeUnit.SECONDS), "Game worker was not cancelled");
                    Assert.assertTrue(match.gameOverAtCancellation.get(), "The game must be ended before interrupting its worker");
                    Assert.expectThrows(TimeoutException.class, () -> execution.get(100, TimeUnit.MILLISECONDS));
                } finally {
                    match.releaseCleanup.countDown();
                }
                execution.get(5, TimeUnit.SECONDS);
                Assert.assertEquals(match.workerExited.getCount(), 0L);
                assertDrawWithoutWinners(match.game);
                for (final Player player : match.game.getRegisteredPlayers()) {
                    Assert.assertEquals(player.getOutcome().lossState, GameLossReason.IntentionalDraw);
                }
                run.finishNormal();
            }

            final List<JsonObject> records = records(file);
            final List<String> types = List.of("RUN_STARTED", "SHUFFLE", "SHUFFLE", "GAME_STARTED",
                    "GAME_OUTCOME", "GAME_FINISHED", "GAME_EXECUTION_FINISHED", "RUN_FINISHED");
            Assert.assertEquals(records.size(), types.size());
            for (int i = 0; i < records.size(); i++) {
                Assert.assertEquals(records.get(i).get("type").getAsString(), types.get(i));
                if (records.get(i).has("game")) {
                    Assert.assertEquals(records.get(i).get("game").getAsInt(), 1);
                    Assert.assertEquals(records.get(i).get("eventIndex").getAsInt(), i);
                }
            }
            final JsonObject outcome = records.get(4);
            Assert.assertEquals(outcome.get("endReason").getAsString(), "Draw");
            Assert.assertTrue(outcome.get("draw").getAsBoolean());
            Assert.assertEquals(outcome.getAsJsonArray("winners").size(), 0);
            Assert.assertEquals(records.get(6).get("status").getAsString(), "TIMEOUT");
            final JsonObject finished = records.get(7);
            Assert.assertEquals(finished.get("status").getAsString(), "TIMEOUT");
            Assert.assertEquals(finished.get("gamesAttempted").getAsInt(), 1);
            Assert.assertEquals(finished.get("gamesNormal").getAsInt(), 0);
            Assert.assertEquals(finished.get("gamesTimedOut").getAsInt(), 1);
            Assert.assertEquals(finished.get("gamesFailed").getAsInt(), 0);
        } finally {
            match.releaseCleanup.countDown();
            caller.shutdownNow();
            caller.awaitTermination(5, TimeUnit.SECONDS);
            Files.deleteIfExists(file);
        }
    }

    private static void assertDrawWithoutWinners(final Game game) {
        Assert.assertTrue(game.isGameOver());
        Assert.assertEquals(game.getOutcome().getWinCondition(), GameEndReason.Draw);
        Assert.assertTrue(game.getOutcome().isDraw());
        Assert.assertNull(game.getOutcome().getWinningLobbyPlayer());
        for (final Player player : game.getRegisteredPlayers()) {
            Assert.assertNotNull(player.getOutcome());
            Assert.assertFalse(player.getOutcome().hasWon(), player.getName() + " was awarded a win");
        }
    }

    private static Match match(final int playerCount) {
        return new Match(new GameRules(GameType.Constructed), players(playerCount), "Simulation test");
    }

    private static List<RegisteredPlayer> players(final int count) {
        final List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            players.add(new RegisteredPlayer(new Deck("Empty deck " + i))
                    .setPlayer(new LobbyPlayerAi("Player " + i, null)));
        }
        return players;
    }

    private static List<JsonObject> records(final Path file) throws Exception {
        final List<JsonObject> records = new ArrayList<>();
        for (final String line : Files.readAllLines(file)) {
            records.add(JsonParser.parseString(line).getAsJsonObject());
        }
        return records;
    }

    private static final class BlockingMatch extends Match {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch cancellationSeen = new CountDownLatch(1);
        private final CountDownLatch releaseCleanup = new CountDownLatch(1);
        private final CountDownLatch workerExited = new CountDownLatch(1);
        private final AtomicBoolean gameOverAtCancellation = new AtomicBoolean();
        private Game game;

        private BlockingMatch(final GameRules rules, final List<RegisteredPlayer> players) {
            super(rules, players, "Timed out simulation");
        }

        @Override
        public Game createGame() {
            game = super.createGame();
            return game;
        }

        @Override
        public void startGame(final Game game) {
            for (final Player player : game.getPlayers()) {
                game.fireEvent(new GameEventShuffle(player));
            }
            game.fireEvent(new GameEventGameStarted(GameType.Constructed, game.getPlayers().get(0), game.getPlayers()));
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                gameOverAtCancellation.set(game.isGameOver());
                cancellationSeen.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        releaseCleanup.await();
                        break;
                    } catch (InterruptedException retry) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                game.fireEvent(new GameEventGameFinished());
                workerExited.countDown();
            }
        }
    }
}
