package forge.view;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.RecordedSelectionTestSupport;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEventGameStarted;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class TraceFailureProbe extends AITest {
    public static void main(final String[] args) throws Exception {
        final TraceFailureProbe probe = new TraceFailureProbe();
        final Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        probe.initializeModel();
        probe.failAfterDecision(output);
        probe.failFlush();
    }

    private void failAfterDecision(final Path output) {
        final GameRules rules = new GameRules(GameType.Constructed);
        final var players = List.of(
                new RegisteredPlayer(new Deck("A")).setPlayer(new LobbyPlayerAi("A", null)),
                new RegisteredPlayer(new Deck("B")).setPlayer(new LobbyPlayerAi("B", null)));
        final Match match = new Match(rules, players, "Forced game exception") {
            @Override public void startGame(final Game game) {
                final Player ai = game.getPlayers().get(1);
                game.setAge(GameStage.Play);
                game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
                game.getPhaseHandler().onStackResolved();
                game.fireEvent(new GameEventGameStarted(GameType.Constructed, ai, game.getPlayers()));
                final var selected = addCardToZone("Memnite", ai, ZoneType.Hand).getFirstSpellAbility();
                RecordedSelectionTestSupport.select(ai, selected);
                throw new IllegalStateException("intentional failure after a recorded decision");
            }
        };
        try (final var run = SimulationTraceRun.open(output.resolve("exception-full.jsonl").toString(),
                output.resolve("exception-decisions.jsonl").toString(), rules, players, "GAMES",
                1, null, null, null, 7L, "HEURISTIC")) {
            SimulateMatch.simulateSingleMatch(match, 0, false, run, null, null);
            run.finishNormal();
        }
    }

    private void failFlush() throws Exception {
        final class Delegate extends Writer {
            private boolean closed;
            @Override public void write(final char[] chars, final int offset, final int length) { }
            @Override public void flush() throws IOException { throw new IOException("intentional flush failure"); }
            @Override public void close() { closed = true; }
        }
        final Delegate delegate = new Delegate();
        final var constructor = SimulationTraceWriter.class.getDeclaredConstructor(BufferedWriter.class);
        constructor.setAccessible(true);
        final var writer = constructor.newInstance(new BufferedWriter(delegate));
        int failures = 0;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                writer.close();
            } catch (IOException expected) {
                if (!expected.getMessage().equals("intentional flush failure")) { throw expected; }
                failures++;
            }
        }
        if (failures != 2 || delegate.closed) {
            throw new IllegalStateException("flush failures did not leave the delegate open");
        }
        System.out.printf("Flush failures=%d, delegate closed=%s%n", failures, delegate.closed);
    }
}
