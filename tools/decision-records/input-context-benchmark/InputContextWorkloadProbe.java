package forge.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.view.TraceWriterProbeSink;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Development-only V4 workloads, including real source accounting and populated public proofs. */
public final class InputContextWorkloadProbe extends AITest {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static volatile Object consumed;
    private record Workload(String name, int players, int board, int stack, String delivery) { }
    private static final List<Workload> WORKLOADS = List.of(
            new Workload("SMALL", 2, 4, 0, "NONE"),
            new Workload("PRIVATE32", 2, 30, 4, "PRIVATE32"),
            new Workload("PUBLIC32", 2, 30, 4, "PUBLIC32"),
            new Workload("MULTIPLAYER", 4, 18, 8, "PUBLIC32"),
            new Workload("CAPPED", 4, 40, 12, "PUBLIC32"));
    private static final List<String> MODES = List.of("DISABLED", "COPY", "UNACCOUNTED_BASE", "ACCOUNTED_BASE", "NUMERIC", "ENTITY", "CAPTURE");

    private Game fixture(Workload w) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < w.players; i++) {
            players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("p" + i, null)));
        }
        GameRules rules = new GameRules(GameType.Commander);
        Game game = new Game(players, rules, new Match(rules, players, "V4 workload"));
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(1));
        game.getPhaseHandler().onStackResolved();
        for (int p = 0; p < w.players; p++) {
            Player player = game.getPlayers().get(p);
            fillLibrary(player, 32);
            for (int i = 0; i < 7; i++) { addCardToZone("Island", player, ZoneType.Hand); }
            for (int i = 0; i < w.board; i++) {
                Card card = addCardToZone(i % 2 == 0 ? "Memnite" : "Grizzly Bears", player, ZoneType.Battlefield);
                // Distinct synthetic public presentations exercise the full uniqueness scan.
                card.setName("Fixture public " + p + " " + i);
            }
        }
        for (int i = 0; i < w.stack; i++) {
            Player player = game.getPlayers().get(i % w.players);
            Card card = createCard("Divination", player);
            card.setGameTimestamp(game.getNextTimestamp());
            game.getStackZone().add(card);
            card.setName("Fixture stack " + i);
            SpellAbility ability = card.getFirstSpellAbility();
            ability.setActivatingPlayer(player); ability.setCopied(true);
            game.getStack().add(ability, 1000L + i);
        }
        if (game.getStack().size() != w.stack) { throw new IllegalStateException("Stack fixture was not installed"); }
        return game;
    }

    private static Map<String, Long> totals(AiObservationAccounting ledger) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String field : AiObservationAccounting.FIELDS) { result.put(field, 0L); }
        if (ledger != null) {
            for (Object row : ((Map<?, ?>) ledger.snapshot().get("boundaries")).values()) {
                for (String field : AiObservationAccounting.FIELDS) {
                    result.merge(field, ((Number) ((Map<?, ?>) row).get(field)).longValue(), Long::sum);
                }
            }
        }
        return result;
    }
    private static String policyHash(Map<String, Object> value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                GSON.toJson(value.get("policy")).getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> trial(Workload w, String mode, int round, boolean memory, boolean exhaustion) throws Exception {
        Game game = fixture(w); Player ai = game.getPlayers().get(1);
        PlayerControllerAi controller = (PlayerControllerAi) ai.getController();
        SpellAbility ability = addCardToZone("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(ai);
        boolean off = mode.equals("DISABLED") || mode.equals("COPY");
        game.setRecordAiDecisions(!mode.equals("DISABLED"));
        if (mode.equals("COPY")) { game.markAsSimulationCopy(); }
        List<AiObservationAccounting> ledgers = new ArrayList<>();
        if (!off && !mode.equals("UNACCOUNTED_BASE")) {
            for (Player player : game.getPlayers()) {
                ledgers.add(((PlayerControllerAi) player.getController()).configureObservationAccounting());
            }
        }
        AiObservationAccounting ledger = ledgers.isEmpty() ? null : controller.observationAccounting();
        AiContextDisclosures disclosure = off ? null : controller.inputContextState().disclosures();
        AiContextDisclosures.Prompt prompt = off || w.delivery.equals("NONE") ? null : disclosure.prompt();
        CardCollection offered = new CardCollection();
        for (Player player : game.getPlayers()) { offered.addAll(player.getCardsIn(ZoneType.Battlefield)); }
        if (prompt != null) {
            CardCollection shown = new CardCollection();
            Iterable<Card> source = w.delivery.equals("PRIVATE32")
                    ? game.getPlayers().get(0).getCardsIn(ZoneType.Library) : offered;
            for (Card card : source) { if (shown.size() == 32) { break; } shown.add(card); }
            disclosure.cards(shown, w.delivery.equals("PRIVATE32") ? ZoneType.Library : ZoneType.Battlefield);
        }
        final Map<String, Object>[] retained = new Map[]{Map.of()};
        Map<String, Long> statuses = new LinkedHashMap<>(), omissions = new LinkedHashMap<>();
        long[] counts = new long[5]; // callbacks, events, linked disclosures, option entries, linked options
        TraceWriterProbeSink sink = new TraceWriterProbeSink();
        java.util.function.Consumer<Map<String, Object>> receive = context -> {
            retained[0] = context;
            statuses.merge((String) context.get("status"), 1L, Long::sum);
            for (Object omission : (List<?>) context.get("omissions")) {
                Map<?, ?> item = (Map<?, ?>) omission;
                omissions.merge(item.get("component") + ":" + item.get("reason"), 1L, Long::sum);
            }
            if (context.get("policy") instanceof Map<?, ?> policy) {
                for (Object fact : (List<?>) policy.get("disclosedFacts")) {
                    if (!"UNRESOLVED".equals(((Map<?, ?>) fact).get("currentObjectAssociation"))) { counts[2]++; }
                }
            }
            Map<?, ?> bindings = (Map<?, ?>) ((Map<?, ?>) context.get("diagnostics")).get("optionBindings");
            for (Object item : (List<?>) bindings.get("items")) {
                counts[3]++;
                if (((Map<?, ?>) item).get("entityRef") != null) { counts[4]++; }
            }
        };
        game.observeEventEmission(event -> {
            if (event instanceof AiChoiceEvent choice) {
                counts[1]++;
                if (choice.data().get("inputContext") instanceof Map<?, ?> context) { receive.accept((Map<String, Object>) context); }
                InputContextPhaseMetrics.enter(5);
                try { sink.write(choice.data()); InputContextPhaseMetrics.sample(choice.data()); }
                finally { InputContextPhaseMetrics.exit(5); }
            }
        });
        Map<String, Object> options = Map.of("coverage", "OBSERVED_RANGE", "minimum", 0, "maximum", 7);
        Runnable operation = () -> {
            switch (mode) {
                case "UNACCOUNTED_BASE", "ACCOUNTED_BASE" -> {
                    Integer selected = AiChoiceRecorder.observe(ai, ability, "NUMBER_SELECTION", null,
                            "CONTROLLER_NUMBER_RANGE", () -> options, () -> { counts[0]++; return 3; }, value -> value);
                    if (selected != 3) { throw new IllegalStateException("Changed selection"); }
                }
                case "ENTITY" -> {
                    Card selected = AiEffectChoiceRecorder.single(ai, ability, offered, false, null,
                            () -> { counts[0]++; return offered.get(0); });
                    if (selected != offered.get(0)) { throw new IllegalStateException("Changed selection"); }
                }
                case "CAPTURE" -> {
                    Map<String, Object> context = AiInputContext.capture(ai, ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true);
                    receive.accept(context); consumed = context;
                }
                default -> {
                    Integer selected = AiNumberChoiceRecorder.range(ai, ability, "CONTROLLER_NUMBER_RANGE", 0, 7,
                            () -> { counts[0]++; return 3; });
                    if (selected != 3) { throw new IllegalStateException("Changed selection"); }
                }
            }
        };
        int warmup = memory || exhaustion ? 0 : Integer.getInteger("forge.context.warmup", 20);
        int iterations = memory ? 1 : exhaustion ? 1200 : Integer.getInteger("forge.context.iterations", 80);
        for (int i = 0; i < warmup; i++) { operation.run(); }
        statuses.clear(); omissions.clear(); java.util.Arrays.fill(counts, 0L);
        Map<String, Long> accountingBefore = totals(ledger);
        long workBefore = off ? -1 : controller.inputContextState().remainingWork();
        InputContextPhaseMetrics.begin(memory);
        long tid = Thread.currentThread().threadId();
        long allocated = InputContextPhaseMetrics.THREADS.getThreadAllocatedBytes(tid);
        long cpu = InputContextPhaseMetrics.THREADS.getCurrentThreadCpuTime(), wall = System.nanoTime();
        // Exhaustion measures available/unavailable batches separately rather than treating lost input as a saving.
        List<Map<String, Object>> windows = new ArrayList<>();
        int batch = exhaustion ? 40 : iterations;
        for (int offset = 0; offset < iterations; offset += batch) {
            long batchCpu = InputContextPhaseMetrics.THREADS.getCurrentThreadCpuTime();
            long batchAllocation = InputContextPhaseMetrics.THREADS.getThreadAllocatedBytes(tid);
            long unavailableBefore = statuses.getOrDefault("UNAVAILABLE", 0L);
            long availableBefore = statuses.values().stream().mapToLong(Long::longValue).sum() - unavailableBefore;
            for (int i = 0; i < Math.min(batch, iterations - offset); i++) { operation.run(); }
            if (exhaustion) {
                long unavailable = statuses.getOrDefault("UNAVAILABLE", 0L) - unavailableBefore;
                long available = statuses.values().stream().mapToLong(Long::longValue).sum() - unavailableBefore - unavailable - availableBefore;
                windows.add(Map.of("fromInvocation", offset, "invocations", Math.min(batch, iterations-offset),
                        "available", available, "unavailable", unavailable,
                        "cpuNanos", InputContextPhaseMetrics.THREADS.getCurrentThreadCpuTime()-batchCpu,
                        "allocatedBytes", InputContextPhaseMetrics.THREADS.getThreadAllocatedBytes(tid)-batchAllocation));
            }
        }
        long wallNanos = System.nanoTime() - wall;
        long cpuNanos = InputContextPhaseMetrics.THREADS.getCurrentThreadCpuTime() - cpu;
        long allocation = InputContextPhaseMetrics.THREADS.getThreadAllocatedBytes(tid) - allocated;
        Map<String, Object> phases = InputContextPhaseMetrics.finish();
        sink.close();
        Map<String, Long> accountingDelta = totals(ledger);
        accountingBefore.forEach((key, value) -> accountingDelta.merge(key, -value, Long::sum));
        if (game.getEventObservationFailure() != null) { throw game.getEventObservationFailure(); }
        if (!exhaustion && statuses.containsKey("UNAVAILABLE")) { throw new IllegalStateException("Unavailable input masked cost"); }
        if (!mode.equals("CAPTURE") && (counts[0] != iterations || counts[1] != (off ? 0 : 2L*iterations))) {
            throw new IllegalStateException("Callback/event mismatch");
        }
        if (off) {
            var field = PlayerControllerAi.class.getDeclaredField("inputContextState"); field.setAccessible(true);
            if (field.get(controller) != null || controller.observationAccounting() != null) { throw new IllegalStateException("Disabled/copy state"); }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workload", w.name); result.put("players", w.players); result.put("boardCardsPerPlayer", w.board);
        result.put("stackEntries", w.stack); result.put("delivery", w.delivery); result.put("mode", mode);
        result.put("round", round); result.put("memorySample", memory); result.put("exhaustionSample", exhaustion);
        result.put("profiled", Boolean.getBoolean("forge.context.profile"));
        result.put("iterations", iterations); result.put("warmupIterations", warmup);
        result.put("cpuNanos", cpuNanos); result.put("wallNanos", wallNanos); result.put("allocatedBytes", allocation);
        result.put("phases", phases); result.put("callbacks", counts[0]); result.put("events", counts[1]);
        result.put("statuses", statuses); result.put("omissions", omissions);
        result.put("linkedDisclosureFacts", counts[2]); result.put("diagnosticOptionEntries", counts[3]); result.put("linkedOptions", counts[4]);
        result.put("workConsumed", off ? 0 : workBefore - controller.inputContextState().remainingWork());
        result.put("remainingWork", off ? null : controller.inputContextState().remainingWork());
        result.put("accountingDelta", accountingDelta); result.put("windows", windows);
        result.put("policySha256", policyHash(retained[0]));
        result.put("lastContextReachableBytes", InputContextPhaseMetrics.reachable(retained[0]));
        result.put("lastContextRawBytes", TraceWriterProbeSink.encode(retained[0]).getBytes(StandardCharsets.UTF_8).length);
        // These ledgers retain only fixed scalar state; unlike disclosure proofs they do not lead back to Game.
        result.put("controllerLedgersReachableBytes", InputContextPhaseMetrics.reachable(ledgers));
        if (disclosure != null) {
            var snapshot = disclosure.snapshot();
            result.put("disclosureFactPayloadReachableBytes", InputContextPhaseMetrics.reachable(snapshot.facts()));
            result.put("publicProofSlots", snapshot.proofs().stream().filter(java.util.Objects::nonNull).count());
            result.put("proofShellShallowBytes", snapshot.proofs().stream().filter(java.util.Objects::nonNull)
                    .mapToLong(InputContextPhaseAgent.instrumentation::getObjectSize).sum());
        }
        if (prompt != null) { prompt.close(); }
        game.setRecordAiDecisions(false); game.closeEventObservations(); ledgers.forEach(AiObservationAccounting::close);
        return result;
    }
    public static void main(String[] args) {
        try { run(args); }
        catch (Throwable failure) { failure.printStackTrace(System.err); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        InputContextWorkloadProbe probe = new InputContextWorkloadProbe(); probe.initializeModel();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Workload workload : WORKLOADS) {
            for (int round = -1; round < Integer.getInteger("forge.context.rounds", 4); round++) {
                for (int offset = 0; offset < MODES.size(); offset++) {
                    String mode = MODES.get(Math.floorMod(round + offset, MODES.size()));
                    Map<String, Object> row = probe.trial(workload, mode, round, false, false);
                    if (round >= 0) { rows.add(row); }
                }
                Files.writeString(Path.of(args[0]), GSON.toJson(rows) + "\n");
            }
            if (Boolean.getBoolean("forge.context.profile")) {
                rows.add(probe.trial(workload, "ENTITY", 0, true, false));
            }
        }
        rows.add(probe.trial(WORKLOADS.get(3), "ENTITY", 0, false, true));
        if (Boolean.getBoolean("forge.context.profile") && InputContextPhaseAgent.transformed != 4) {
            throw new IllegalStateException("Expected four instrumented classes");
        }
        Files.writeString(Path.of(args[0]), GSON.toJson(rows) + "\n");
        System.exit(0);
    }
}
