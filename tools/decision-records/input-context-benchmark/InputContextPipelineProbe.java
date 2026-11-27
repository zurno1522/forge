package forge.ai;

import com.google.gson.GsonBuilder;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.view.TraceCollectorProbeSink;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed callbacks through actual production capture/collector/encoder/UTF-8 flush. No AI policy or game play. */
public final class InputContextPipelineProbe extends AITest {
    private static final List<InputContextWorkloadProbe.Workload> FIXTURES = List.of(
            new InputContextWorkloadProbe.Workload("SMALL", 2, 4, 0, "NONE"),
            new InputContextWorkloadProbe.Workload("MULTIPLAYER", 4, 18, 8, "PUBLIC32"),
            new InputContextWorkloadProbe.Workload("CAPPED", 4, 40, 12, "PUBLIC32"));
    private static final List<String> MODES = List.of("NUMBER", "MAX_X", "DAMAGE", "EXHAUSTED", "CAPTURE_FAILURE", "WRITER_FAILURE");
    private static volatile Object consumed;
    private static Map<String, Long> totals(AiObservationAccounting ledger) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String field : AiObservationAccounting.FIELDS) { result.put(field, 0L); }
        for (Object row : ((Map<?, ?>) ledger.snapshot().get("boundaries")).values()) {
            for (String field : AiObservationAccounting.FIELDS) { result.merge(field, ((Number) ((Map<?, ?>) row).get(field)).longValue(), Long::sum); }
        }
        return result;
    }
    private static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static long gcCount() {
        long sum = 0;
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) { long n = bean.getCollectionCount(); if (n < 0) { return -1; } sum += n; }
        return sum;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> trial(InputContextWorkloadProbe.Workload fixture, String mode, int round, Path root) throws Exception {
        final Game game = new InputContextWorkloadProbe().fixture(fixture); final Player ai = game.getPlayers().get(1);
        final var controller = (PlayerControllerAi) ai.getController();
        // Include real non-ASCII, quotes, slashes, newline and a surrogate pair in permitted public input.
        game.getPlayers().get(0).getCardsIn(ZoneType.Battlefield).getFirst().setName("Public α\n\\\" 🧪");
        final var ability = addCardToZone("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility(); ability.setActivatingPlayer(ai);
        final Path file = root.resolve(fixture.name() + "-" + mode + "-" + round + ".jsonl");
        final var sink = new TraceCollectorProbeSink(game, file);
        final var state = controller.inputContextState();
        final var prompt = fixture.delivery().equals("NONE") ? null : state.disclosures().prompt();
        if (prompt != null) {
            final var shown = new CardCollection();
            for (Player p : game.getPlayers()) { for (Card c : p.getCardsIn(ZoneType.Battlefield)) { if (shown.size() < 32) { shown.add(c); } } }
            state.disclosures().cards(shown, ZoneType.Battlefield);
        }
        if (mode.equals("EXHAUSTED")) {
            while (state.remainingWork() > 0) {
                final long previous = state.remainingWork(); consumed = AiInputContext.capture(ai, null, "BEFORE_SELECTOR", "RULES_STATE", () -> true);
                if (state.remainingWork() >= previous) { throw new AssertionError("Exhaustion setup made no progress"); }
            }
        }
        final Map<String, Long> statuses = new LinkedHashMap<>(), reasons = new LinkedHashMap<>();
        final Map<String, Object>[] last = new Map[]{Map.of()};
        final long[] counts = new long[3]; // callbacks, choice events, entry receipts
        game.observeEventEmission(event -> {
            if (event instanceof AiChoiceEvent e) {
                counts[1]++;
                if (e.data().containsKey("entryLocation")) { counts[2]++; }
                if (e.data().get("inputContext") instanceof Map<?, ?> context) {
                    last[0] = (Map<String, Object>) context;
                    statuses.merge((String) context.get("status"), 1L, Long::sum);
                    for (Object o : (List<?>) context.get("omissions")) { reasons.merge((String) ((Map<?, ?>) o).get("reason"), 1L, Long::sum); }
                }
            }
        });
        final var original = new IllegalStateException("injected capture failure");
        final var decision = new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        final Runnable operation = () -> {
            switch (mode) {
                case "DAMAGE" -> {
                    Object value = AiDamageChoiceRecorder.canPlay(ai, ability, () -> { counts[0]++; return decision; });
                    if (value != decision) { throw new AssertionError("Changed Damage result"); }
                }
                case "MAX_X" -> {
                    int value = AiNumberChoiceRecorder.calculateMaxX(ai, ability, false, () -> { counts[0]++; return 3; });
                    if (value != 3) { throw new AssertionError("Changed X result"); }
                }
                case "CAPTURE_FAILURE" -> {
                    int value = AiChoiceRecorder.observe(ai, ability, "NUMBER_SELECTION", null, "CONTROLLER_NUMBER_RANGE",
                            () -> Map.of("coverage", "OBSERVED_RANGE", "minimum", 0, "maximum", 3, "callbackParameters",
                                    AiReceivedParameters.capture(AiReceivedParameters.Family.NUMBER, null).diagnostics()),
                            () -> { counts[0]++; return 2; }, result -> {
                                final var numeric = new LinkedHashMap<String, Object>();
                                numeric.put("value", result); numeric.put("optionIndex", null); numeric.put("domainMatch", "WITHIN_OBSERVED_DOMAIN"); return numeric;
                            }, "BEFORE_SELECTOR",
                            open -> AiInputContext.capture(ai, ability, "BEFORE_SELECTOR", "RULES_STATE", open, () -> { throw original; }));
                    if (value != 2 || game.getEventObservationFailure() != original) { throw new AssertionError("Changed original callback/capture failure"); }
                }
                default -> {
                    int value = AiNumberChoiceRecorder.range(ai, ability, "CONTROLLER_NUMBER_RANGE", 0, 3, () -> { counts[0]++; return 2; });
                    if (value != 2) { throw new AssertionError("Changed numeric callback"); }
                }
            }
        };
        final int warmup = Integer.getInteger("forge.pipeline.warmup", 4), iterations = Integer.getInteger("forge.pipeline.iterations", 8);
        for (int i = 0; i < warmup; i++) { operation.run(); }
        sink.flush(); final long offset = Files.size(file);
        if (mode.equals("WRITER_FAILURE")) { sink.breakWriter(); }
        statuses.clear(); reasons.clear(); java.util.Arrays.fill(counts, 0L);
        final var before = totals(controller.observationAccounting()); final long workBefore = state.remainingWork();
        final long gcBefore = gcCount();
        for (var pool : ManagementFactory.getMemoryPoolMXBeans()) { if (pool.getType() == MemoryType.HEAP) { pool.resetPeakUsage(); } }
        InputContextPhaseMetrics.begin(false);
        final long tid = Thread.currentThread().threadId();
        final long allocation = InputContextPhaseMetrics.THREADS.getThreadAllocatedBytes(tid), cpu = InputContextPhaseMetrics.THREADS.getCurrentThreadCpuTime(), wall = System.nanoTime();
        for (int i = 0; i < iterations; i++) { operation.run(); }
        boolean flushFailed = false;
        try { sink.flush(); } catch (IOException expected) { if (!mode.equals("WRITER_FAILURE")) { throw expected; } flushFailed = true; }
        final long wallNanos = System.nanoTime() - wall, cpuNanos = InputContextPhaseMetrics.THREADS.getCurrentThreadCpuTime() - cpu;
        final long allocated = InputContextPhaseMetrics.THREADS.getThreadAllocatedBytes(tid) - allocation;
        final var phases = InputContextPhaseMetrics.finish();
        final long end = Files.size(file); final var delta = totals(controller.observationAccounting()); before.forEach((k, v) -> delta.merge(k, -v, Long::sum));
        final boolean failed = sink.failed(), expectedFailure = mode.endsWith("FAILURE");
        if (failed != expectedFailure || flushFailed != mode.equals("WRITER_FAILURE")) { throw new AssertionError("Unexpected sink health"); }
        if (prompt != null) { prompt.close(); }
        try { sink.close(); } catch (IOException expected) { if (!expectedFailure) { throw expected; } }
        byte[] all = Files.readAllBytes(file), interval = java.util.Arrays.copyOfRange(all, Math.toIntExact(offset), Math.toIntExact(end));
        final var heap = new LinkedHashMap<String, Long>();
        for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) { var usage = pool.getPeakUsage(); heap.put(pool.getName(), usage == null ? null : usage.getUsed()); }
        }
        final var row = new LinkedHashMap<String, Object>();
        row.put("measurementSchemaVersion", 1); row.put("scope", "SYNTHETIC_OPERATIONS_WITH_REAL_COLLECTOR_UTF8");
        row.put("profile", AiInputContext.PROFILE); row.put("fixture", fixture.name()); row.put("mode", mode); row.put("round", round);
        row.put("profiled", Boolean.getBoolean("forge.context.profile")); row.put("iterations", iterations); row.put("warmup", warmup);
        row.put("callbacks", counts[0]); row.put("choiceEvents", counts[1]); row.put("entryLocationRecords", counts[2]);
        row.put("statuses", statuses); row.put("omissionReasons", reasons); row.put("sourceDelta", delta); row.put("workConsumed", workBefore - state.remainingWork());
        row.put("remainingWork", state.remainingWork()); row.put("sinkFailed", failed); row.put("flushFailed", flushFailed);
        row.put("cpuNanos", cpuNanos); row.put("wallNanos", wallNanos); row.put("allocatedBytes", allocated); row.put("phases", phases);
        final long gcAfter = gcCount();
        row.put("gcCollectionsAfterWarmupThroughCleanup", gcBefore < 0 || gcAfter < 0 ? null : gcAfter - gcBefore); row.put("heapPoolPeaksThroughCleanup", heap);
        row.put("controllerShallowBytes", InputContextPhaseAgent.instrumentation.getObjectSize(controller));
        row.put("stateShallowBytes", InputContextPhaseAgent.instrumentation.getObjectSize(state));
        long counterBytes = 0;
        try { var field = state.getClass().getDeclaredField("admission"); field.setAccessible(true); counterBytes = InputContextPhaseMetrics.reachable(field.get(state)); }
        catch (NoSuchFieldException legacy) { /* The source-pinned V28 State has no admission counter. */ }
        row.put("admissionCounterReachableBytes", counterBytes);
        row.put("measuredFileOffset", offset); row.put("measuredEndOffset", end);
        row.put("measuredFileBytes", end - offset); row.put("measuredLineCount", new String(interval, StandardCharsets.UTF_8).lines().count());
        row.put("measuredFileSha256", digest(interval)); row.put("file", file.toString()); row.put("fileSha256", digest(all)); row.put("fileBytes", all.length);
        row.put("policySha256", digest(new GsonBuilder().serializeNulls().create().toJson(last[0].get("policy")).getBytes(StandardCharsets.UTF_8)));
        row.put("trainingReady", false); return row;
    }
    public static void main(String[] args) {
        try {
            final var probe = new InputContextPipelineProbe(); probe.initializeModel(); final Path out = Path.of(args[0]); Files.createDirectories(out);
            final var rows = new ArrayList<Map<String, Object>>();
            for (var fixture : FIXTURES) { for (int round = -1; round < Integer.getInteger("forge.pipeline.rounds", 4); round++) {
                for (int offset = 0; offset < MODES.size(); offset++) {
                    var row = probe.trial(fixture, MODES.get(Math.floorMod(round + offset, MODES.size())), round, out);
                    if (round >= 0) { rows.add(row); }
                    Files.writeString(out.resolve("measurements.json"), new GsonBuilder().serializeNulls().create().toJson(rows) + "\n");
                }
            } }
            if (Boolean.getBoolean("forge.context.profile") && InputContextPhaseAgent.transformed != 5) { throw new AssertionError("Missing pipeline instrumentation"); }
            System.out.println("PASS: synthetic pipeline operations completed; not played games"); System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(System.err); System.exit(1); }
    }
}
