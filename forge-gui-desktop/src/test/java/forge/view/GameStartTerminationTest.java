package forge.view;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventPlayerLivesChanged;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.RegisteredPlayer;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class GameStartTerminationTest extends AITest {
    @DataProvider public Object[][] checkpoints() { return new Object[][] { { false }, { true } }; }

    @Test(dataProvider = "checkpoints", groups = { "UnitTest", "fast" })
    public void completedDrawCannotBeRevivedByTheNextStartupStage(boolean duringStartScript) {
        final List<RegisteredPlayer> registered = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final Deck deck = new Deck("Startup fixture"); deck.getMain().add("Forest", 40);
            registered.add(new RegisteredPlayer(deck).setPlayer(new LobbyPlayerAi("p" + i, null)));
        }
        if (duringStartScript) {
            registered.get(0).addStartGameScript(List.of("DB$ GainLife | Defined$ You | LifeAmount$ 1"));
        }
        // Puzzle skips mulligan policy, isolating the two native startup transitions.
        final GameRules rules = new GameRules(GameType.Puzzle);
        final Match match = new Match(rules, registered, "Startup termination");
        final Game game = match.createGame(); game.setNoGUIUser();
        final AtomicInteger outcomes = new AtomicInteger(), turns = new AtomicInteger();
        final AtomicReference<Object> terminal = new AtomicReference<>();
        game.observeEventEmission(event -> {
            if (event instanceof GameEventGameOutcome) { outcomes.incrementAndGet(); }
            if (!duringStartScript && event instanceof GameEventGameStarted
                    || duringStartScript && event instanceof GameEventPlayerLivesChanged && game.getAge() == GameStage.Mulligan) {
                SimulateMatch.finishAsDraw(game); terminal.compareAndSet(null, game.getOutcome());
            }
            if (event instanceof GameEventTurnBegan) {
                turns.incrementAndGet();
                // Stop an unfixed engine if it incorrectly begins playing after the terminal outcome.
                SimulateMatch.finishAsDraw(game);
            }
        });

        match.startGame(game);

        Assert.assertNotNull(terminal.get(), "Native startup checkpoint was not exercised");
        Assert.assertTrue(game.isGameOver());
        Assert.assertSame(game.getOutcome(), terminal.get(), "Startup replaced the completed outcome");
        Assert.assertEquals(outcomes.get(), 1, "A second outcome was emitted");
        Assert.assertEquals(turns.get(), 0, "A completed game began its first turn");
        Assert.assertNull(game.getEventObservationFailure());
    }
}
