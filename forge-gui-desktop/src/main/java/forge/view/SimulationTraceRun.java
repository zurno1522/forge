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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
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
    private final SimulationTraceWriter decisionWriter;
    private final String runId = UUID.randomUUID().toString();
    private String pilotMode;
    private final Map<LobbyPlayer, Integer> participantIds = new IdentityHashMap<>();
    private int gamesAttempted;
    private int gamesNormal;
    private int gamesTimedOut;
    private int gamesFailed;
    private boolean finished;

    private SimulationTraceRun(final SimulationTraceWriter writer, final SimulationTraceWriter decisionWriter) {
        this.writer = writer;
        this.decisionWriter = decisionWriter;
    }

    static SimulationTraceRun open(final String filename, final GameRules rules,
            final List<RegisteredPlayer> players, final String runMode,
            final Integer requestedGames, final Integer gamesPerMatch,
            final String tournamentType, final Integer playersPerMatch,
            final Long seed) {
        return open(filename, null, rules, players, runMode, requestedGames, gamesPerMatch,
                tournamentType, playersPerMatch, seed, "UNKNOWN");
    }

    static SimulationTraceRun open(final String filename, final String decisionFilename, final GameRules rules,
            final List<RegisteredPlayer> players, final String runMode,
            final Integer requestedGames, final Integer gamesPerMatch,
            final String tournamentType, final Integer playersPerMatch, final Long seed, final String pilotMode) {
        SimulationTraceWriter writer = null;
        SimulationTraceWriter decisionWriter = null;
        try {
            if (filename == null && decisionFilename == null) {
                throw new IOException("At least one simulation output is required");
            }
            if (filename != null && decisionFilename != null) {
                final Path trace = Path.of(filename).toAbsolutePath().normalize();
                final Path decisions = Path.of(decisionFilename).toAbsolutePath().normalize();
                if (trace.equals(decisions) || (Files.exists(trace) && Files.exists(decisions)
                        && Files.isSameFile(trace, decisions))) {
                    throw new IOException("Game trace and decision output must use different files");
                }
            }
            writer = filename == null ? null : SimulationTraceWriter.open(filename);
            decisionWriter = decisionFilename == null ? null : SimulationTraceWriter.open(decisionFilename);
            final SimulationTraceRun run = new SimulationTraceRun(writer, decisionWriter);
            run.pilotMode = pilotMode;
            run.writeStarted(rules, players, runMode, requestedGames, gamesPerMatch,
                    tournamentType, playersPerMatch, seed);
            if (writer != null) { writer.flush(); }
            if (decisionWriter != null) { decisionWriter.flush(); }
            return run;
        } catch (IOException e) {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // Preserve the initialization failure as the primary diagnostic.
                }
            }
            if (decisionWriter != null) {
                try { decisionWriter.close(); } catch (IOException ignored) { /* Retain original failure. */ }
            }
            throw new TraceFailure("Unable to initialize simulation outputs", e);
        }
    }

    private void writeStarted(final GameRules rules, final List<RegisteredPlayer> players,
            final String runMode, final Integer requestedGames, final Integer gamesPerMatch,
            final String tournamentType, final Integer playersPerMatch, final Long seed) throws IOException {
        final Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "RUN_STARTED");
        record.put("schemaVersion", 1);
        record.put("runId", runId);
        record.put("pilotMode", pilotMode);
        record.put("engineRevision", System.getProperty("forge.simulation.revision", "UNKNOWN"));
        record.put("engineDirty", System.getProperty("forge.simulation.dirty", "UNKNOWN"));
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
        if (writer != null) {
            record.put("streamKind", "FULL_GAME_TRACE");
            record.put("eventIndexMode", "CONTIGUOUS");
            writer.write(record);
        }
        if (decisionWriter != null) {
            record.put("streamKind", "AI_DECISIONS");
            record.put("eventIndexMode", writer == null ? "CONTIGUOUS" : "SHARED_WITH_GAME_TRACE");
            decisionWriter.write(record);
        }
    }

    SimulationTraceCollector beginGame(final Game game, final Integer matchId,
            final Integer gameInMatch, final Integer tournamentRound) {
        gamesAttempted++;
        final SimulationTraceCollector collector =
                new SimulationTraceCollector(game, writer, decisionWriter, gamesAttempted, participantIds,
                        matchId, gameInMatch, tournamentRound);
        game.subscribeToEvents(collector);
        game.setRecordAiDecisions(true);
        return collector;
    }

    void finishGame(final SimulationTraceCollector collector, final String status, final Throwable error) {
        try {
            collector.executionFinished(status, error);
        } catch (IOException e) {
            gamesFailed++;
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
        // Completing the requested run does not mean that every game completed normally.
        finishRun(gamesFailed > 0 ? "EXCEPTION" : gamesTimedOut > 0 ? "TIMEOUT" : "NORMAL");
    }

    void finishAborted() {
        finishRun("ABORTED");
    }

    private void finishRun(final String status) {
        if (finished) {
            return;
        }
        try {
            final Map<String, Object> record = new LinkedHashMap<>();
            record.put("type", "RUN_FINISHED");
            record.put("status", status);
            record.put("gamesAttempted", gamesAttempted);
            record.put("gamesNormal", gamesNormal);
            record.put("gamesTimedOut", gamesTimedOut);
            record.put("gamesFailed", gamesFailed);
            if (writer != null) { writer.write(record); }
            if (decisionWriter != null) { decisionWriter.write(record); }
            closeWriters();
            finished = true;
        } catch (IOException e) {
            try {
                closeWriters();
            } catch (IOException ignored) {
                // The sink is already failing; best-effort close only.
            }
            throw new TraceFailure("Unable to finalize simulation trace", e);
        }
    }

    private void closeWriters() throws IOException {
        IOException failure = null;
        for (final SimulationTraceWriter sink : new SimulationTraceWriter[] { writer, decisionWriter }) {
            if (sink == null) { continue; }
            try {
                sink.close();
            } catch (IOException e) {
                if (failure == null) { failure = e; } else { failure.addSuppressed(e); }
            }
        }
        if (failure != null) { throw failure; }
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
