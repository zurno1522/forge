package forge.view;

import com.google.gson.Gson;
import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.ai.RecordedSelectionTestSupport;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.event.GameEventStackCountered;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Controlled engine/trace workload, not a pilot-strength or whole-JVM allocation benchmark. */
public final class HardeningProbe extends AITest {
    private final com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private final Path directory;

    private HardeningProbe(Path directory) { this.directory = directory; }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static int size(Object value) {
        if (value instanceof Map<?, ?> map) { return map.size(); }
        if (value instanceof java.util.Collection<?> values) { return values.size(); }
        throw new IllegalArgumentException("Not a collection");
    }

    private static long reachableBytes(Object root) throws Exception {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object> queue = new ArrayDeque<>();
        queue.add(root);
        long bytes = 0;
        while (!queue.isEmpty()) {
            Object value = queue.removeFirst();
            if (!seen.add(value)) { continue; }
            bytes += HardeningSizeAgent.instrumentation.getObjectSize(value);
            Class<?> type = value.getClass();
            if (type.isArray()) {
                if (!type.getComponentType().isPrimitive()) {
                    for (int i = 0; i < Array.getLength(value); i++) {
                        Object item = Array.get(value, i);
                        if (item != null) { queue.add(item); }
                    }
                }
            } else {
                for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                    for (Field field : current.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) { continue; }
                        if (!field.trySetAccessible()) { throw new IllegalStateException("Inaccessible graph field: " + field); }
                        Object item = field.get(value);
                        if (item != null) { queue.add(item); }
                    }
                }
            }
        }
        return bytes;
    }

    private static long gcHeap() throws Exception {
        System.gc();
        Thread.sleep(100);
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private final class Fixture implements AutoCloseable {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility ability;
        final SimulationTraceWriter writer;
        final SimulationTraceCollector collector;
        final Path output;

        Fixture(String name, String mode, int boardCards) throws Exception {
            for (Player player : game.getPlayers()) {
                fillLibrary(player, 30);
                for (int i = 0; i < boardCards; i++) { addCardToZone("Memnite", player, ZoneType.Battlefield); }
                for (int i = 0; i < 7; i++) { addCardToZone("Island", player, ZoneType.Hand); }
            }
            Card source = addCardToZone("Island", ai, ZoneType.Battlefield);
            ability = AbilityFactory.getAbility("AB$ Draw | Cost$ 0 | NumCards$ 0 | SpellDescription$ Hardening workload.", source);
            ability.setActivatingPlayer(ai);
            output = directory.resolve(name + ".jsonl");
            writer = mode.equals("NONE") ? null : SimulationTraceWriter.open(output.toString());
            collector = writer == null ? null : new SimulationTraceCollector(game,
                    mode.equals("FULL") ? writer : null, mode.equals("DECISIONS") ? writer : null,
                    1, Map.of(), null, null, null);
            if (collector != null) { collector.attach(); }
        }

        void play() {
            if (collector != null) { RecordedSelectionTestSupport.select(ai, ability); }
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(ability);
            if (game.getStack().isEmpty()) { throw new IllegalStateException("Workload did not enter stack"); }
        }

        Object tracker() throws Exception {
            return field(((PlayerControllerAi) ai.getController()).getAi(), "actionTracker");
        }

        Map<String, Object> counts() throws Exception {
            Map<String, Object> counts = new LinkedHashMap<>();
            for (String key : List.of("abilityToStack", "stackActions", "completedStacks", "unstartedActions")) {
                counts.put(key, size(field(collector, key)));
            }
            Object tracker = tracker();
            counts.put("pendingAttempts", tracker == null ? 0 : size(field(tracker, "actions")));
            counts.put("stackActionReachableBytes", reachableBytes(field(collector, "stackActions")));
            return counts;
        }

        @Override public void close() throws Exception { if (writer != null) { writer.close(); } }
    }

    private Map<String, Object> trial(String name, String mode, int board, int iterations) throws Exception {
        try (Fixture fixture = new Fixture(name, mode, board)) {
            long beforeHeap = gcHeap();
            long thread = Thread.currentThread().threadId();
            long allocated = threads.getThreadAllocatedBytes(thread);
            long cpu = threads.getCurrentThreadCpuTime();
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                fixture.play();
                fixture.game.getStack().resolveStack();
            }
            if (fixture.collector != null) { fixture.collector.executionFinished("NORMAL", null); }
            long elapsed = System.nanoTime() - start;
            long cpuNanos = threads.getCurrentThreadCpuTime() - cpu;
            long allocation = threads.getThreadAllocatedBytes(thread) - allocated;
            long afterHeap = gcHeap();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name); result.put("mode", mode); result.put("boardCardsPerPlayer", board);
            result.put("iterations", iterations); result.put("wallNanos", elapsed); result.put("currentThreadCpuNanos", cpuNanos);
            result.put("currentThreadAllocatedBytes", allocation); result.put("wholeJvmPostGcHeapBefore", beforeHeap);
            result.put("wholeJvmPostGcHeapWithGameRetained", afterHeap);
            result.put("outputBytes", fixture.writer == null ? 0 : Files.size(fixture.output));
            if (fixture.collector != null) { result.put("retention", fixture.counts()); }
            return result;
        }
    }

    private final List<WeakReference<Game>> weakGames = new ArrayList<>();

    private Map<String, Object> retention(String path, int iterations) throws Exception {
        try (Fixture fixture = new Fixture("retention-" + path + "-" + iterations, "DECISIONS", 4)) {
            for (int i = 0; i < iterations; i++) {
                fixture.play();
                var item = fixture.game.getStack().peek();
                if (path.equals("RESOLVED")) { fixture.game.getStack().resolveStack(); }
                else if (path.equals("CLEARED")) { fixture.game.getStack().clear(); }
                else {
                    fixture.game.getStack().remove(item);
                    if (path.equals("COUNTERED")) {
                        fixture.game.fireEvent(new GameEventStackCountered(item.getId(), item.getSpellAbility(), fixture.ability));
                    }
                }
            }
            Map<String, Object> before = fixture.counts();
            // A never-started choice models cancellation after selection has returned.
            RecordedSelectionTestSupport.select(fixture.ai, fixture.ability);
            fixture.collector.executionFinished("TIMEOUT", null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", path); result.put("iterations", iterations); result.put("beforeExecutionFinish", before);
            result.put("afterExecutionFinish", fixture.counts());
            weakGames.add(new WeakReference<>(fixture.game));
            return result;
        }
    }

    public static void main(String[] args) throws Exception {
        HardeningProbe probe = new HardeningProbe(Path.of(args[0]));
        Files.createDirectories(probe.directory);
        probe.initializeModel();
        probe.threads.setThreadAllocatedMemoryEnabled(true);
        probe.threads.setThreadCpuTimeEnabled(true);
        List<Map<String, Object>> measurements = new ArrayList<>();
        List<String> modes = List.of("NONE", "DECISIONS", "FULL");
        for (String mode : modes) { probe.trial("warmup-" + mode, mode, 30, 2000); }
        for (int board : List.of(4, 30)) {
            for (int round = 0; round < 3; round++) {
                for (int offset = 0; offset < 3; offset++) {
                    String mode = modes.get((round + offset) % 3);
                    measurements.add(probe.trial("board-" + board + "-round-" + round + "-" + mode, mode, board, 400));
                }
            }
        }
        List<Map<String, Object>> retention = new ArrayList<>();
        for (int count : List.of(100, 1000)) {
            for (String path : List.of("RESOLVED", "COUNTERED", "CLEARED", "UNCLASSIFIED_REMOVAL")) {
                retention.add(probe.retention(path, count));
            }
        }
        for (int i = 0; i < 3; i++) { gcHeap(); }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("java", System.getProperty("java.runtime.version"));
        result.put("measurements", measurements); result.put("retention", retention);
        result.put("uncollectedProbeGamesAfterGc", probe.weakGames.stream().filter(w -> w.get() != null).count());
        Files.writeString(probe.directory.resolve("probe.json"), new Gson().toJson(result) + "\n");
        System.exit(0);
    }
}
