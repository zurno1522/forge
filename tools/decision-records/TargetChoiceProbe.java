package forge.ai;

import com.google.gson.Gson;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Development-only producer-cost control: existing ranking/parent capture with or without pool capture. */
public final class TargetChoiceProbe extends AITest {
    private static volatile AiDecisionEvent consumed;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> measure(final int size) throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility sa = addCardToZone("Doom Blade", ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        final List<Card> pool = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            pool.add(addCardToZone(i == size - 1 ? "Shivan Dragon" : "Grizzly Bears",
                    game.getPlayers().get(0), ZoneType.Battlefield));
        }
        final Card expected = ComputerUtilCard.getBestRemovalTargetAI(ai, pool);
        final var brain = ((PlayerControllerAi) ai.getController()).getAi();
        final var field = AiController.class.getDeclaredField("evaluationDecision");
        field.setAccessible(true);
        final ThreadLocal<AiDecisionRecorder> context = (ThreadLocal<AiDecisionRecorder>) field.get(brain);
        final var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);
        threads.setThreadCpuTimeEnabled(true);
        final List<Map<String, Object>> results = new ArrayList<>();
        long sequence = 0;
        for (int round = -1; round < 3; round++) {
            for (int offset = 0; offset < 2; offset++) {
                final boolean enabled = ((round + offset) & 1) != 0;
                game.setRecordAiDecisions(enabled);
                final int iterations = 200;
                final long allocated = threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
                final long cpu = threads.getCurrentThreadCpuTime();
                final long start = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    final AiDecisionRecorder recorder = new AiDecisionRecorder(game, ai, ++sequence);
                    recorder.beginAssessment(sa);
                    context.set(recorder);
                    try {
                        final Card chosen = AiTargetChoiceRecorder.choose(ai, sa, pool, "DESTROY_REMOVAL_POOL",
                                options -> ComputerUtilCard.getBestRemovalTargetAI(ai, options));
                        if (chosen != expected) { throw new IllegalStateException("Ranking result changed"); }
                        recorder.evaluated(sa, "WillPlay");
                        consumed = recorder.finish(List.of(sa), "COMPLETED", null);
                        if (((List<?>) consumed.data().get("subordinateChoices")).size() != (enabled ? 1 : 0)) {
                            throw new IllegalStateException("Capture mode did not match payload");
                        }
                    } finally { context.remove(); }
                }
                final long wallNanos = System.nanoTime() - start;
                final long cpuNanos = threads.getCurrentThreadCpuTime() - cpu;
                final long bytes = threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
                if (round >= 0) {
                    final Map<String, Object> row = new LinkedHashMap<>();
                    row.put("poolSize", size);
                    row.put("enabled", enabled);
                    row.put("round", round);
                    row.put("iterations", iterations);
                    row.put("wallNanos", wallNanos);
                    row.put("cpuNanos", cpuNanos);
                    row.put("allocatedBytes", bytes);
                    results.add(row);
                }
            }
        }
        return results;
    }

    public static void main(final String[] args) throws Exception {
        final TargetChoiceProbe probe = new TargetChoiceProbe();
        probe.initializeModel();
        final List<Map<String, Object>> results = new ArrayList<>();
        results.addAll(probe.measure(4));
        results.addAll(probe.measure(30));
        Files.writeString(Path.of(args[0]), new Gson().toJson(results) + "\n");
        System.exit(0);
    }
}
