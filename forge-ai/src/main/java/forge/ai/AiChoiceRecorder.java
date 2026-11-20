package forge.ai;

import forge.game.Game;
import forge.game.GameObject;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/** Observes a callback once. No policy reruns, inferred legal alternatives, or diagnostic strings. */
public final class AiChoiceRecorder {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ThreadLocal<Observation> ACTIVE = new ThreadLocal<>();
    private AiChoiceRecorder() { }

    static SpellAbility activeAbility(final Player chooser) {
        final Observation active = ACTIVE.get();
        return active != null && active.game == chooser.getGame() && active.chooser == chooser && active.open()
                ? active.ability : null;
    }

    public static String stage(final Player chooser, final SpellAbility ability) {
        final Observation active = ACTIVE.get();
        if (active != null && active.game == chooser.getGame() && active.open()) { return active.stage; }
        if (evaluation(chooser, ability) != null) { return "CANDIDATE_EVALUATION"; }
        if (chooser.getGame().getStack().isResolving()) { return "RESOLUTION"; }
        return action(ability) != null ? "EXECUTION" : "CONTROLLER_CALLBACK";
    }

    private static AiController owner(final SpellAbility ability) {
        final Player activator = ability.getActivatingPlayer();
        return activator != null && activator.getController() instanceof PlayerControllerAi ai ? ai.getAi() : null;
    }

    private static AiActionReference action(final SpellAbility ability) {
        final AiController owner = owner(ability);
        return owner == null ? null : owner.getActionReference(ability.getRootAbility());
    }

    private static AiDecisionRecorder evaluation(final Player chooser, final SpellAbility ability) {
        final AiController owner = owner(ability);
        final AiDecisionRecorder recorder = owner == null ? null : owner.evaluationRecorder();
        return recorder != null && recorder.choiceContext(ability) != null ? recorder : null;
    }

    private static boolean closedEvaluation(final AiController brain) {
        final AiDecisionRecorder recorder = brain == null ? null : brain.evaluationRecorder();
        return recorder != null && !recorder.choiceCaptureOpen();
    }

    public static <T> T observe(final Player chooser, final SpellAbility ability, final String type,
            final String requestedStage, final String boundary, final Supplier<Map<String, Object>> options,
            final Supplier<T> callback, final Function<T, Object> selection) {
        final Game game = chooser.getGame();
        Observation observation = null;
        if (game.isRecordingAiDecisions() && ability != null) {
            try {
                final AiController chooserBrain = chooser.getController() instanceof PlayerControllerAi ai ? ai.getAi() : null;
                if (ability.getHostCard() != null && ability.getHostCard().getGame() == game
                        && !closedEvaluation(owner(ability)) && !closedEvaluation(chooserBrain)) {
                    observation = new Observation(chooser, ability, type, requestedStage, boundary, options);
                }
            }
            catch (RuntimeException failure) { game.recordEventObservationFailure(failure); }
        }
        final T result;
        final Observation previous = ACTIVE.get();
        if (observation != null) { ACTIVE.set(observation); }
        try {
            try { result = callback.get(); }
            finally {
                if (previous == null) { ACTIVE.remove(); } else { ACTIVE.set(previous); }
            }
        }
        catch (RuntimeException | Error failure) {
            if (observation != null) { observation.finish("ERROR", null); }
            throw failure;
        }
        if (observation != null && observation.open()) {
            try { observation.finish(result == null ? "DECLINED" : "COMPLETED", observation.visible && result != null ? selection.apply(result) : null); }
            catch (RuntimeException failure) { game.recordEventObservationFailure(failure); }
        }
        return result;
    }

    private static final class Observation {
        private final Game game;
        private final Player chooser;
        private final SpellAbility ability;
        private final AiDecisionRecorder evaluation;
        private final String id;
        private final boolean visible;
        private final String stage;
        private boolean finished;
        private final long started = System.nanoTime();

        private Observation(final Player chooser, final SpellAbility ability, final String type,
                final String requestedStage, final String boundary, final Supplier<Map<String, Object>> options) {
            game = chooser.getGame();
            this.chooser = chooser;
            this.ability = ability;
            evaluation = evaluation(chooser, ability);
            id = "choice:" + SEQUENCE.incrementAndGet();
            visible = !ability.getHostCard().isFaceDown() && ability.getHostCard().getView().canBeShownTo(chooser.getView());
            final AiActionReference action = action(ability);
            final Map<String, Object> context = evaluation == null ? null : evaluation.choiceContext(ability);
            final Map<String, Object> record = new LinkedHashMap<>();
            record.put("choiceSchemaVersion", 1);
            record.put("choiceId", id);
            final Observation parent = ACTIVE.get();
            record.put("parentChoiceId", parent != null && parent.game == game && parent.open() ? parent.id : null);
            record.put("status", "STARTED");
            record.put("choiceType", visible ? type : "MASKED_CHOICE");
            record.put("boundary", visible ? boundary : "MASKED_CHOICE");
            stage = requestedStage == null ? AiChoiceRecorder.stage(chooser, ability) : requestedStage;
            record.put("stage", stage);
            record.put("chooserPlayerId", chooser.getId());
            record.put("chooserKind", chooser.getController() instanceof PlayerControllerAi ? "AI" : "OTHER");
            record.put("abilityId", ability.getId());
            record.put("sourceId", ability.getHostCard().getId());
            record.put("decisionId", action != null ? action.decisionId() : context == null ? null : context.get("decisionId"));
            record.put("actionId", action == null ? null : action.actionId());
            record.put("candidateAbilityId", context == null ? null : context.get("candidateAbilityId"));
            record.put("assessmentId", context == null ? null : context.get("assessmentId"));
            final SpellAbility resolving = game.getStack().getResolvingAbility();
            record.put("resolvingAbilityId", resolving == null ? null : resolving.getId());
            record.put("options", visible ? options.get() : Map.of("coverage", "MASKED"));
            if (open()) { game.fireEvent(new AiChoiceEvent(record)); }
        }

        private boolean open() { return !finished && game.isRecordingAiDecisions() && (evaluation == null || evaluation.choiceCaptureOpen()); }

        private void finish(final String status, final Object selected) {
            if (!open()) { return; }
            finished = true;
            try {
                final Map<String, Object> record = new LinkedHashMap<>();
                record.put("choiceSchemaVersion", 1);
                record.put("choiceId", id);
                record.put("status", status);
                record.put("selection", visible ? selected : null);
                record.put("elapsedNanos", System.nanoTime() - started);
                game.fireEvent(new AiChoiceEvent(record));
            } catch (RuntimeException failure) { game.recordEventObservationFailure(failure); }
        }
    }

    public static Map<String, Object> targetPlan(final SpellAbility root) {
        final List<Map<String, Object>> groups = new ArrayList<>();
        SpellAbility current = root;
        for (; current != null && groups.size() < AiTargetChoiceRecorder.MAX_CHOICES; current = current.getSubAbility()) {
            final var references = AiTargetChoiceRecorder.references(current.getTargets(), () -> !Thread.currentThread().isInterrupted());
            if (references == null) { break; }
            groups.add(Map.of("abilityId", current.getId(), "targets", references.values(), "targetsTruncated", references.truncated()));
        }
        return Map.of("abilities", groups, "abilitiesTruncated", current != null);
    }

    public static boolean targeting(final Player chooser, final SpellAbility ability, final String stage,
            final Supplier<Boolean> callback) {
        return observe(chooser, ability, "TARGET_SELECTION_RESULT", stage, "CONTROLLER_TARGETS",
                () -> Map.of("coverage", "PRIOR_TARGET_PLAN_ONLY", "priorTargets", targetPlan(ability)), callback,
                accepted -> Map.of("accepted", accepted, "targets", targetPlan(ability)));
    }

    public static <T extends GameObject, C extends Iterable<T>> T targetPool(final Player chooser, final SpellAbility ability,
            final C pool, final String boundary, final String timing, final Function<C, T> callback) {
        final AtomicReference<List<Map<String, Object>>> offered = new AtomicReference<>();
        return observe(chooser, ability, "TARGET_POOL_SELECTION", null, boundary, () -> {
            final var options = AiTargetChoiceRecorder.references(pool, () -> !Thread.currentThread().isInterrupted());
            final var prior = AiTargetChoiceRecorder.references(ability.getTargets(), () -> !Thread.currentThread().isInterrupted());
            if (options == null || prior == null) { return Map.of("coverage", "UNAVAILABLE"); }
            offered.set(options.values());
            return Map.of("coverage", "OBSERVED_HEURISTIC_POOL_NOT_ALL_LEGAL_TARGET_SETS", "items", options.values(),
                    "truncated", options.truncated(), "priorTargets", prior.values(), "priorTargetsTruncated", prior.truncated(),
                    "poolSnapshotTiming", timing, "purpose", "DAMAGE_OPPONENT_POOL".equals(boundary) ? "OPPONENT_PREFERENCE" : "TARGET_RECOMMENDATION");
        }, () -> callback.apply(pool), value -> {
            if (value == null) { return null; }
            final var selected = AiTargetChoiceRecorder.reference(value);
            final var options = offered.get();
            Integer index = null;
            int matches = 0;
            for (int i = 0; options != null && i < options.size(); i++) {
                if (options.get(i).equals(selected)) { matches++; index = i; }
            }
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("selected", selected);
            result.put("selectedOptionIndex", matches == 1 ? index : null);
            result.put("match", options == null ? "UNAVAILABLE" : matches == 1 ? "UNIQUE" : matches == 0 ? "OUTSIDE_CAPTURED_POOL" : "AMBIGUOUS");
            return result;
        });
    }
}
