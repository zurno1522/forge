package forge.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import forge.game.Game;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.view.TraceWriterProbeSink;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Known, callback-once fixtures. No gameplay policy, RNG, production hook or persistent registry. */
public final class InputContextPhaseProbe extends AITest {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static volatile Object consumed;
    private static final List<String> MODES = List.of("DISABLED", "COPY", "BASE_CHOICE", "INPUT_CHOICE",
            "CAPTURE_ONLY", "FREEZE_ONLY", "PUBLISH_ONLY", "SERIALIZE_ONLY", "DISCLOSURE_ONLY");

    private static Map<String,Long> gc() {
        long collections = 0, millis = 0;
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getCollectionCount() >= 0) { collections += bean.getCollectionCount(); }
            if (bean.getCollectionTime() >= 0) { millis += bean.getCollectionTime(); }
        }
        return Map.of("collections", collections, "millis", millis);
    }
    private static Map<String,Object> selection() {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("value", 3); value.put("optionIndex", null); value.put("domainMatch", "WITHIN_OBSERVED_DOMAIN");
        return value;
    }
    private Map<String,Object> trial(int board, int facts, String mode, int round, boolean memory) throws Exception {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1);
        for (Player player : game.getPlayers()) {
            fillLibrary(player, 32);
            for (int i = 0; i < 7; i++) { addCardToZone("Island", player, ZoneType.Hand); }
            for (int i = 0; i < board; i++) { addCardToZone(i % 2 == 0 ? "Memnite" : "Grizzly Bears", player, ZoneType.Battlefield); }
        }
        SpellAbility ability = addCardToZone("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(ai);
        game.setRecordAiDecisions(!mode.equals("DISABLED"));
        if (mode.equals("COPY")) { game.markAsSimulationCopy(); }
        PlayerControllerAi controller = (PlayerControllerAi) ai.getController();
        boolean off = mode.equals("DISABLED") || mode.equals("COPY");
        AiContextDisclosures disclosure = off ? null : controller.inputContextState().disclosures();
        AiContextDisclosures.Prompt prompt = facts == 0 || off ? null : disclosure.prompt();
        CardCollection shown = facts == 0 ? new CardCollection()
                : new CardCollection(game.getPlayers().get(0).getCardsIn(ZoneType.Library));
        if (prompt != null) { disclosure.cards(shown, ZoneType.Library); }
        Map<String,Object> representative = off ? Map.of() : AiInputContext.capture(ai, ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true);
        Map<String,Object> options = Map.of("coverage", "OBSERVED_RANGE", "minimum", 0, "maximum", 7);
        Map<String,Object> selection = selection();
        Map<String,Object> start = new LinkedHashMap<>();
        start.put("choiceSchemaVersion", 1); start.put("choiceId", "development-fixture"); start.put("status", "STARTED");
        start.put("parentChoiceId", null); start.put("choiceType", "NUMBER_SELECTION");
        start.put("boundary", "CONTROLLER_NUMBER_RANGE"); start.put("stage", "CONTROLLER_CALLBACK");
        start.put("chooserPlayerId", ai.getId()); start.put("chooserKind", "AI");
        start.put("abilityId", ability.getId()); start.put("sourceId", ability.getHostCard().getId());
        for (String key : List.of("decisionId", "actionId", "candidateAbilityId", "assessmentId", "resolvingAbilityId")) { start.put(key, null); }
        start.put("options", options); start.put("inputContext", representative);
        AiChoiceEvent frozenEvent = new AiChoiceEvent(start);
        Object[] retained = new Object[2]; // Last frozen context and last event data only.
        Map<String,Long> statuses = new LinkedHashMap<>();
        int[] events = {0}, callbacks = {0};
        TraceWriterProbeSink sink = new TraceWriterProbeSink();
        boolean serializeSink = !mode.equals("PUBLISH_ONLY");
        game.observeEventEmission(event -> {
            if (event instanceof AiChoiceEvent choice) {
                events[0]++; retained[1] = choice.data();
                if (choice.data().get("inputContext") instanceof Map<?,?> context) {
                    retained[0] = context; statuses.merge((String) context.get("status"), 1L, Long::sum);
                }
                if (serializeSink) { serialize(sink, choice.data()); }
            }
        });
        Runnable operation = () -> {
            switch (mode) {
                case "CAPTURE_ONLY" -> {
                    Map<String,Object> context = AiInputContext.capture(ai, ability, "BEFORE_SELECTOR", "RULES_STATE", () -> true);
                    retained[0] = context; consumed = context;
                    statuses.merge((String) context.get("status"), 1L, Long::sum);
                }
                case "FREEZE_ONLY" -> { consumed = new AiChoiceEvent(start); }
                case "PUBLISH_ONLY" -> game.fireEvent(frozenEvent);
                case "SERIALIZE_ONLY" -> serialize(sink, frozenEvent.data());
                case "DISCLOSURE_ONLY" -> {
                    InputContextPhaseMetrics.enter(6);
                    try (var local = disclosure.prompt()) {
                        disclosure.cards(shown, ZoneType.Library);
                        consumed = disclosure.snapshot().facts();
                    } finally { InputContextPhaseMetrics.exit(6); }
                }
                case "BASE_CHOICE" -> AiChoiceRecorder.observe(ai, ability, "NUMBER_SELECTION", null,
                        "CONTROLLER_NUMBER_RANGE", () -> options, () -> { callbacks[0]++; return 3; }, ignored -> selection);
                default -> AiChoiceRecorder.observeInput(ai, ability, "NUMBER_SELECTION", null,
                        "CONTROLLER_NUMBER_RANGE", "BEFORE_SELECTOR", () -> options,
                        () -> { callbacks[0]++; return 3; }, ignored -> selection);
            }
        };
        // Delivery-only needs independent prompt lifetimes instead of a retained populated parent prompt.
        if (mode.equals("DISCLOSURE_ONLY") && prompt != null) { prompt.close(); prompt = null; }
        int warmup = memory ? 0 : Integer.getInteger("forge.context.warmup", 200);
        int iterations = memory ? 1 : Integer.getInteger("forge.context.iterations", 400);
        for (int i = 0; i < warmup; i++) { operation.run(); }
        statuses.clear(); events[0] = 0; callbacks[0] = 0;
        long remainingBefore = off ? -1 : controller.inputContextState().remainingWork();
        Map<String,Long> beforeGc = gc();
        InputContextPhaseMetrics.begin(memory);
        long tid = Thread.currentThread().threadId();
        long allocated = InputContextPhaseMetrics.THREADS.getThreadAllocatedBytes(tid);
        long cpu = InputContextPhaseMetrics.THREADS.getCurrentThreadCpuTime(), wall = System.nanoTime();
        for (int i = 0; i < iterations; i++) { operation.run(); }
        long wallNanos = System.nanoTime() - wall;
        long cpuNanos = InputContextPhaseMetrics.THREADS.getCurrentThreadCpuTime() - cpu;
        long allocation = InputContextPhaseMetrics.THREADS.getThreadAllocatedBytes(tid) - allocated;
        Map<String,Object> phases = InputContextPhaseMetrics.finish();
        sink.close();
        Map<String,Long> afterGc = gc();
        if (game.getEventObservationFailure() != null) { throw game.getEventObservationFailure(); }
        if (statuses.containsKey("UNAVAILABLE")) { throw new IllegalStateException("Budget or capture failure masked cost"); }
        if (mode.endsWith("CHOICE") || off) {
            if (callbacks[0] != iterations || events[0] != (off ? 0 : 2 * iterations)) { throw new IllegalStateException("Callback/event count"); }
        }
        if (mode.equals("INPUT_CHOICE") || mode.equals("CAPTURE_ONLY")) {
            if (statuses.values().stream().mapToLong(Long::longValue).sum() != iterations) { throw new IllegalStateException("Missing contexts"); }
        }
        if (off) {
            var field = PlayerControllerAi.class.getDeclaredField("inputContextState"); field.setAccessible(true);
            if (field.get(controller) != null) { throw new IllegalStateException("Disabled/copy allocated state"); }
        }
        String encoded = TraceWriterProbeSink.encode(representative);
        if (!GSON.fromJson(encoded, com.google.gson.JsonElement.class).equals(GSON.toJsonTree(representative))) {
            throw new IllegalStateException("Production encoder round-trip mismatch");
        }
        byte[] raw = encoded.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(raw); }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("boardCardsPerPlayer", board); result.put("disclosureFacts", facts); result.put("mode", mode);
        result.put("round", round); result.put("memorySample", memory); result.put("iterations", iterations);
        result.put("warmupIterations", warmup);
        result.put("profiled", Boolean.getBoolean("forge.context.profile")); result.put("phases", phases);
        result.put("cpuNanos", cpuNanos); result.put("wallNanos", wallNanos); result.put("allocatedBytes", allocation);
        result.put("gcCollections", afterGc.get("collections") - beforeGc.get("collections"));
        result.put("gcMillis", afterGc.get("millis") - beforeGc.get("millis"));
        result.put("statuses", statuses); result.put("callbacks", callbacks[0]); result.put("events", events[0]);
        result.put("serializedCharactersIncludingWarmup", sink.characters());
        result.put("workConsumed", off ? 0 : remainingBefore - controller.inputContextState().remainingWork());
        result.put("representativeRawBytes", raw.length); result.put("representativeGzipBytes", compressed.size());
        result.put("policySha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(GSON.toJson(representative.get("policy")).getBytes(StandardCharsets.UTF_8))));
        result.put("lastContextReachableBytes", InputContextPhaseMetrics.reachable(retained[0]));
        result.put("disclosureFactsReachableBytes", off ? 0 : InputContextPhaseMetrics.reachable(disclosure.snapshot().facts()));
        if (prompt != null) { prompt.close(); }
        game.closeEventObservations();
        return result;
    }
    private static void serialize(TraceWriterProbeSink sink, Object value) {
        InputContextPhaseMetrics.enter(5);
        try {
            sink.write(value);
            InputContextPhaseMetrics.sample(value);
        } finally { InputContextPhaseMetrics.exit(5); }
    }
    public static void main(String[] args) throws Exception {
        InputContextPhaseProbe probe = new InputContextPhaseProbe(); probe.initializeModel();
        List<Map<String,Object>> rows = new ArrayList<>();
        for (int board : List.of(4, 30)) {
            for (int facts : List.of(0, 32)) {
                for (int round = -1; round < Integer.getInteger("forge.context.rounds", 6); round++) {
                    for (int offset = 0; offset < MODES.size(); offset++) {
                        String mode = MODES.get(Math.floorMod(round + offset, MODES.size()));
                        Map<String,Object> row = probe.trial(board, facts, mode, round, false);
                        if (round >= 0) { rows.add(row); }
                    }
                    Files.writeString(Path.of(args[0]), GSON.toJson(rows) + "\n");
                }
                if (Boolean.getBoolean("forge.context.profile")) {
                    for (String mode : List.of("INPUT_CHOICE", "CAPTURE_ONLY", "FREEZE_ONLY")) {
                        rows.add(probe.trial(board, facts, mode, 0, true));
                    }
                }
            }
        }
        if (Boolean.getBoolean("forge.context.profile") && InputContextPhaseAgent.transformed != 4) {
            throw new IllegalStateException("Expected four instrumented classes, got " + InputContextPhaseAgent.transformed);
        }
        Files.writeString(Path.of(args[0]), GSON.toJson(rows) + "\n");
        System.exit(0);
    }
}
