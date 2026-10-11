package forge.view;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.player.RegisteredPlayer;
import forge.item.PaperCard;
import forge.util.BuildInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Run-scoped owner of the simulation trace sink and participant identity map.
 */
final class SimulationTraceRun implements AutoCloseable {
    static final class TraceFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TraceFailure(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    private final SimulationTraceWriter writer;
    private final Map<LobbyPlayer, Integer> participantIds = new IdentityHashMap<>();
    private int gamesAttempted;
    private int gamesNormal;
    private int gamesTimedOut;
    private int gamesFailed;
    private boolean finished;

    private SimulationTraceRun(final SimulationTraceWriter writer) {
        this.writer = writer;
    }

    static SimulationTraceRun open(final String filename, final GameRules rules,
            final List<RegisteredPlayer> players, final String runMode,
            final Integer requestedGames, final Integer gamesPerMatch,
            final String tournamentType, final Integer playersPerMatch,
            final Long seed) {
        try {
            final SimulationTraceWriter writer = SimulationTraceWriter.open(filename);
            final SimulationTraceRun run = new SimulationTraceRun(writer);
            run.writeStarted(rules, players, runMode, requestedGames, gamesPerMatch,
                    tournamentType, playersPerMatch, seed);
            writer.flush();
            return run;
        } catch (IOException e) {
            throw new TraceFailure("Unable to initialize simulation trace: " + filename, e);
        }
    }

    private void writeStarted(final GameRules rules, final List<RegisteredPlayer> players,
            final String runMode, final Integer requestedGames, final Integer gamesPerMatch,
            final String tournamentType, final Integer playersPerMatch, final Long seed) throws IOException {
        final Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "RUN_STARTED");
        record.put("schemaVersion", 1);
        record.put("forgeVersion", BuildInfo.getVersionString());
        record.put("runMode", runMode);
        record.put("gameType", rules.getGameType().name());
        if (requestedGames != null) {
            record.put("requestedGames", requestedGames);
        }
        if (gamesPerMatch != null) {
            record.put("gamesPerMatch", gamesPerMatch);
        }
        if (tournamentType != null) {
            record.put("tournamentType", tournamentType);
        }
        if (playersPerMatch != null) {
            record.put("playersPerMatch", playersPerMatch);
        }
        if (seed != null) {
            record.put("seed", seed);
        }

        final List<Map<String, Object>> participants = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            final RegisteredPlayer player = players.get(i);
            final int participantId = i + 1;
            participantIds.put(player.getPlayer(), participantId);

            final Map<String, Object> participant = new LinkedHashMap<>();
            participant.put("participantId", participantId);
            participant.put("deckName", player.getDeck().getName());
            participant.put("aiProfile", player.getPlayer() instanceof LobbyPlayerAi ai
                    ? ai.getAiProfile() : "");
            participant.put("deck", deckSnapshot(player.getDeck()));
            participants.add(participant);
        }
        record.put("participants", participants);
        writer.write(record);
    }

    SimulationTraceCollector beginGame(final Game game, final Integer matchId,
            final Integer gameInMatch, final Integer tournamentRound) {
        gamesAttempted++;
        final SimulationTraceCollector collector =
                new SimulationTraceCollector(game, writer, gamesAttempted, participantIds,
                        matchId, gameInMatch, tournamentRound);
        game.subscribeToEvents(collector);
        return collector;
    }

    void finishGame(final SimulationTraceCollector collector, final String status, final Throwable error) {
        try {
            collector.executionFinished(status, error);
        } catch (IOException e) {
            throw new TraceFailure("Simulation trace failed while finishing game " + gamesAttempted, e);
        }

        switch (status) {
            case "NORMAL" -> gamesNormal++;
            case "TIMEOUT" -> gamesTimedOut++;
            case "EXCEPTION" -> gamesFailed++;
            default -> throw new IllegalArgumentException("Unknown trace game status: " + status);
        }
    }

    void finishNormal() {
        finishRun("NORMAL");
    }

    void finishAborted() {
        finishRun("ABORTED");
    }

    private void finishRun(final String status) {
        if (finished) {
            return;
        }
        finished = true;
        try {
            final Map<String, Object> record = new LinkedHashMap<>();
            record.put("type", "RUN_FINISHED");
            record.put("status", status);
            record.put("gamesAttempted", gamesAttempted);
            record.put("gamesNormal", gamesNormal);
            record.put("gamesTimedOut", gamesTimedOut);
            record.put("gamesFailed", gamesFailed);
            writer.write(record);
            writer.close();
        } catch (IOException e) {
            throw new TraceFailure("Unable to finalize simulation trace", e);
        }
    }

    @Override
    public void close() {
        if (!finished) {
            finishAborted();
        }
    }

    private static Map<String, Object> deckSnapshot(final Deck deck) {
        final Map<String, Object> snapshot = new LinkedHashMap<>();
        for (final Map.Entry<DeckSection, CardPool> section : deck) {
            if (section.getValue() == null || section.getValue().isEmpty()) {
                continue;
            }

            final Map<String, Integer> counts = new TreeMap<>();
            for (final Map.Entry<PaperCard, Integer> card : section.getValue()) {
                counts.merge(card.getKey().getName(), card.getValue(), Integer::sum);
            }

            final List<Map<String, Object>> cards = new ArrayList<>();
            for (final Map.Entry<String, Integer> card : counts.entrySet()) {
                final Map<String, Object> cardEntry = new LinkedHashMap<>();
                cardEntry.put("name", card.getKey());
                cardEntry.put("count", card.getValue());
                cards.add(cardEntry);
            }
            snapshot.put(section.getKey().name(), cards);
        }
        return snapshot;
    }
}
