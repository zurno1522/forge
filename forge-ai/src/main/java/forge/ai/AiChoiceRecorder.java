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
import java.util.function.BooleanSupplier;
import java.util.function.BiFunction;

/** Observes a callback once. No policy reruns, inferred legal alternatives, or diagnostic strings. */
public final class AiChoiceRecorder {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ThreadLocal<Observation> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Game> PLANNING = new ThreadLocal<>();
    private AiChoiceRecorder() { }

    /** A known metadata limitation, distinct from a failing observation getter or callback. */
    static RuntimeException unsupportedOptions() { return new UnsupportedOptions(); }

    private static final class UnsupportedOptions extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private UnsupportedOptions() { super(null, null, false, false); }
    }

    /** Exact, visible invocation admission; never a lookup of a nearby parent. */
    interface Capture {
        boolean open();
        void failure(RuntimeException failure);
    }

    /** Marks audited hypothetical policy work without changing or restoring that work. */
    public static <T> T planning(final Player chooser, final Supplier<T> callback) {
        final Game game = chooser.getGame();
        if (!game.isRecordingAiDecisions()) { return callback.get(); }
        final Game previous = PLANNING.get();
        PLANNING.set(game);
        try { return callback.get(); }
        finally {
            if (previous == null) { PLANNING.remove(); } else { PLANNING.set(previous); }
        }
    }

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
        if (recorder == null) { return null; }
        final Map<String, Object> context = recorder.choiceContext(ability);
        // A closed evaluation remains the admission owner; it cannot become an unrelated standalone capture.
        return context != null || !recorder.choiceCaptureOpen() ? recorder : null;
    }

    private static boolean closedEvaluation(final AiController brain) {
        final AiDecisionRecorder recorder = brain == null ? null : brain.evaluationRecorder();
        return recorder != null && !recorder.choiceCaptureOpen();
    }

    public static <T> T observe(final Player chooser, final SpellAbility ability, final String type,
            final String requestedStage, final String boundary, final Supplier<Map<String, Object>> options,
            final Supplier<T> callback, final Function<T, Object> selection) {
        return observe(chooser, ability, type, requestedStage, boundary, options, callback, selection, null, null);
    }

    /** Only audited scopes opt in. Merely wrapping a callback does not establish preselection timing. */
    public static <T> T observeInput(final Player chooser, final SpellAbility ability, final String type,
            final String requestedStage, final String boundary, final String timing,
            final Supplier<Map<String, Object>> options, final Supplier<T> callback, final Function<T, Object> selection) {
        return observeInput(chooser, ability, type, requestedStage, boundary, timing, options, callback, selection, null);
    }

    static <T> T observeInput(final Player chooser, final SpellAbility ability, final String type,
            final String requestedStage, final String boundary, final String timing,
            final Supplier<Map<String, Object>> options, final Supplier<T> callback, final Function<T, Object> selection,
            final Supplier<AiPromptInput> prompt) {
        return observeInput(chooser, ability, type, requestedStage, boundary, timing, options, callback, selection, prompt, null);
    }

    static <T> T observeInput(final Player chooser, final SpellAbility ability, final String type,
            final String requestedStage, final String boundary, final String timing,
            final Supplier<Map<String, Object>> options, final Supplier<T> callback, final Function<T, Object> selection,
            final Supplier<AiPromptInput> prompt, final Supplier<AiOptionInput> optionInput) {
        return observeInput(chooser, ability, type, requestedStage, boundary, timing, options, callback, selection, prompt, optionInput, null);
    }

    static <T> T observeInput(final Player chooser, final SpellAbility ability, final String type,
            final String requestedStage, final String boundary, final String timing,
            final Supplier<Map<String, Object>> options, final Supplier<T> callback, final Function<T, Object> selection,
            final Supplier<AiPromptInput> prompt, final Supplier<AiOptionInput> optionInput,
            final BiFunction<Capture, Supplier<T>, T> ownedCallback) {
        return observe(chooser, ability, type, requestedStage, boundary, options, callback, selection, timing,
                open -> AiInputContext.capture(chooser, ability, timing, stateBasis(chooser, ability), open, prompt, optionInput), ownedCallback);
    }

    /** A separate audited request component, independent of prompt/sequence projection. */
    static <T> T observeParameters(final Player chooser, final SpellAbility ability, final String type,
            final String boundary, final Supplier<Map<String, Object>> options, final Supplier<T> callback,
            final Function<T, Object> selection, final Supplier<AiPromptInput> prompt,
            final Supplier<AiOptionInput> optionInput, final AiReceivedParameters.Carrier parameters) {
        return observe(chooser, ability, type, null, boundary, options, callback, selection, "BEFORE_SELECTOR",
                open -> AiInputContext.capture(chooser, ability, "BEFORE_SELECTOR", stateBasis(chooser, ability), open, prompt, optionInput, parameters));
    }

    private static String stateBasis(final Player chooser, final SpellAbility ability) {
        final Observation active = ACTIVE.get();
        return PLANNING.get() == chooser.getGame() || evaluation(chooser, ability) != null || active != null && active.game == chooser.getGame()
                && "PLANNING_WORKING_STATE".equals(active.basis)
                ? "PLANNING_WORKING_STATE" : "RULES_STATE";
    }

    static <T> T observe(final Player chooser, final SpellAbility ability, final String type,
            final String requestedStage, final String boundary, final Supplier<Map<String, Object>> options,
            final Supplier<T> callback, final Function<T, Object> selection, final String timing,
            final Function<BooleanSupplier, Map<String, Object>> inputCapture) {
        return observe(chooser, ability, type, requestedStage, boundary, options, callback, selection, timing, inputCapture, null);
    }

    private static <T> T observe(final Player chooser, final SpellAbility ability, final String type,
            final String requestedStage, final String boundary, final Supplier<Map<String, Object>> options,
            final Supplier<T> callback, final Function<T, Object> selection, final String timing,
            final Function<BooleanSupplier, Map<String, Object>> inputCapture,
            final BiFunction<Capture, Supplier<T>, T> ownedCallback) {
        final Game game = chooser.getGame();
        AiObservationAccounting.Scope accounting = null;
        if (game.isRecordingAiDecisions()) {
            try {
                if (chooser.getController() instanceof PlayerControllerAi ai && ai.observationAccounting() != null) {
                    accounting = ai.observationAccounting().enter();
                }
            } catch (RuntimeException failure) { game.recordEventObservationFailure(failure); }
        }
        try {
            return observeAccounted(chooser, ability, type, requestedStage, boundary, options, callback, selection, timing, inputCapture, accounting, ownedCallback);
        } finally { if (accounting != null) { accounting.exit(); } }
    }

    private static <T> T observeAccounted(final Player chooser, final SpellAbility ability, final String type,
            final String requestedStage, final String boundary, final Supplier<Map<String, Object>> options,
            final Supplier<T> callback, final Function<T, Object> selection, final String timing,
            final Function<BooleanSupplier, Map<String, Object>> inputCapture, final AiObservationAccounting.Scope accounting,
            final BiFunction<Capture, Supplier<T>, T> ownedCallback) {
        final Game game = chooser.getGame();
        Observation observation = null;
        if (game.isRecordingAiDecisions() && ability != null) {
            AiController chooserBrain = null;
            AiController abilityBrain = null;
            try {
                chooserBrain = chooser.getController() instanceof PlayerControllerAi ai ? ai.getAi() : null;
                final var source = ability.getHostCard();
                if (source != null && source.getGame() == game) {
                    abilityBrain = owner(ability);
                    if (!closedEvaluation(abilityBrain) && !closedEvaluation(chooserBrain)) {
                        observation = new Observation(chooser, ability, type, requestedStage, boundary, options, timing, inputCapture, accounting);
                    } else if (accounting != null) { accounting.admissionClosed(); }
                } else if (accounting != null) { accounting.unsupported(); }
            }
            catch (RuntimeException failure) {
                // Use the already resolved owners, without new engine getters in failure handling.
                if (!game.isRecordingAiDecisions() || closedEvaluation(abilityBrain) || closedEvaluation(chooserBrain)) {
                    if (accounting != null) { accounting.admissionClosed(); }
                } else {
                    if (accounting != null) { accounting.failure(); }
                    game.recordEventObservationFailure(failure);
                }
            }
        } else if (accounting != null) {
            if (ability == null) { accounting.unsupported(); } else { accounting.admissionClosed(); }
        }
        final T result;
        final Observation previous = ACTIVE.get();
        if (observation != null) { ACTIVE.set(observation); }
        if (accounting != null) { accounting.callbackStarted(); }
        try {
            try {
                result = ownedCallback == null ? callback.get() : ownedCallback.apply(
                        observation != null && observation.visible && observation.open() ? observation : null, callback);
            }
            finally {
                if (previous == null) { ACTIVE.remove(); } else { ACTIVE.set(previous); }
            }
        }
        catch (RuntimeException | Error failure) {
            if (accounting != null) { accounting.callbackThrew(); }
            if (observation != null) { observation.finish("ERROR", null); }
            throw failure;
        }
        if (accounting != null) { accounting.callbackReturned(); }
        if (observation != null && observation.open()) {
            try { observation.finish(result == null ? "DECLINED" : "COMPLETED", observation.visible && result != null ? selection.apply(result) : null); }
            catch (RuntimeException failure) {
                // Projection can finish after timeout/recording closure; its obsolete failure is not live health.
                if (observation.open()) {
                    if (accounting != null) { accounting.failure(); }
                    game.recordEventObservationFailure(failure);
                }
            }
        }
        return result;
    }

    private static final class Observation implements Capture {
        private final Game game;
        private final Player chooser;
        private final SpellAbility ability;
        private final AiDecisionRecorder evaluation;
        private final String id;
        private final boolean visible;
        private final String stage;
        private final String basis;
        private final AiObservationAccounting.Scope accounting;
        private boolean finished;
        private final long started = System.nanoTime();

        private Observation(final Player chooser, final SpellAbility ability, final String type,
                final String requestedStage, final String boundary, final Supplier<Map<String, Object>> options,
                final String timing, final Function<BooleanSupplier, Map<String, Object>> inputCapture,
                final AiObservationAccounting.Scope accounting) {
            game = chooser.getGame();
            this.chooser = chooser;
            this.ability = ability;
            this.accounting = accounting;
            evaluation = evaluation(chooser, ability);
            id = "choice:" + SEQUENCE.incrementAndGet();
            if (evaluation != null && !evaluation.choiceCaptureOpen()) {
                visible = false;
                stage = requestedStage == null ? "CANDIDATE_EVALUATION" : requestedStage;
                basis = "PLANNING_WORKING_STATE";
                abandon();
                return;
            }
            visible = !ability.getHostCard().isFaceDown() && ability.getHostCard().getView().canBeShownTo(chooser.getView());
            if (accounting != null) { accounting.classify(visible ? boundary : "MASKED_CHOICE"); }
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
            basis = stateBasis(chooser, ability);
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
            if (timing != null) {
                final var entry = AiEntryLocation.capture(chooser, timing, basis, visible, this::open);
                record.put("entryLocation", entry);
                if (accounting != null && "CAPTURE_FAILED".equals(entry.get("reason"))) { accounting.failure(); }
            }
            if (!open()) { abandon(); return; }
            try { record.put("options", visible ? options.get() : Map.of("coverage", "MASKED")); }
            catch (UnsupportedOptions unsupported) {
                if (!open()) { abandon(); return; }
                finished = true;
                if (accounting != null) { accounting.unsupported(); }
                return;
            }
            catch (RuntimeException failure) {
                if (!open()) { abandon(); return; }
                throw failure;
            }
            if (inputCapture != null) {
                Map<String, Object> input;
                try {
                    input = visible ? inputCapture.apply(this::open)
                            : AiInputContext.unavailable(chooser, "UNESTABLISHED", "UNESTABLISHED", "HIDDEN");
                } catch (RuntimeException failure) {
                    if (!open()) { abandon(); return; }
                    if (accounting != null) { accounting.failure(); }
                    game.recordEventObservationFailure(failure);
                    input = AiInputContext.unavailable(chooser, timing, basis, "CAPTURE_FAILED");
                }
                record.put("inputContext", input);
                if (accounting != null && "UNAVAILABLE".equals(input.get("status"))) { accounting.unavailable(); }
            }
            if (accounting != null) { accounting.prepared(); }
            if (open()) {
                if (accounting != null) { accounting.startAttempt(); }
                game.fireEvent(new AiChoiceEvent(record));
                if (accounting != null) { accounting.startReturned(); }
            } else { finished = true; }
        }

        private void abandon() {
            finished = true;
            if (accounting != null) { accounting.admissionClosed(); }
        }

        @Override public boolean open() { return !finished && game.isRecordingAiDecisions() && (evaluation == null || evaluation.choiceCaptureOpen()); }

        @Override public void failure(final RuntimeException failure) {
            if (open()) {
                if (accounting != null) { accounting.failure(); }
                game.recordEventObservationFailure(failure);
            }
        }

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
                if (accounting != null) { accounting.terminalAttempt(); }
                game.fireEvent(new AiChoiceEvent(record));
                if (accounting != null) { accounting.terminalReturned(); }
            } catch (RuntimeException failure) { if (accounting != null) { accounting.failure(); } game.recordEventObservationFailure(failure); }
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
        return observeInput(chooser, ability, "TARGET_SELECTION_RESULT", stage, "CONTROLLER_TARGETS", "BEFORE_OPERATION",
                () -> Map.of("coverage", "PRIOR_TARGET_PLAN_ONLY", "priorTargets", targetPlan(ability)), callback,
                accepted -> Map.of("accepted", accepted, "targets", targetPlan(ability)));
    }

    public static boolean triggerPreparation(final Player chooser, final SpellAbility ability,
            final Supplier<Boolean> callback) {
        return observe(chooser, ability, "TRIGGER_PREPARATION_RESULT", "TRIGGER_PREPARATION", "TRIGGER_PREPARATION",
                () -> Map.of("coverage", "PRIOR_TARGET_PLAN_ONLY", "priorTargets", targetPlan(ability)), callback,
                accepted -> Map.of("accepted", accepted, "targets", targetPlan(ability)));
    }

    public static <T extends GameObject, C extends Iterable<T>> T targetPool(final Player chooser, final SpellAbility ability,
            final C pool, final String boundary, final String timing, final Function<C, T> callback) {
        final AtomicReference<List<Map<String, Object>>> offered = new AtomicReference<>();
        final AtomicReference<AiOptionInput> proof = new AtomicReference<>();
        final String inputTiming = "AT_TARGET_ADDITION".equals(timing) ? "AFTER_SELECTION_BEFORE_APPLICATION" : "BEFORE_SELECTOR";
        return observeInput(chooser, ability, "TARGET_POOL_SELECTION", null, boundary, inputTiming, () -> {
            final var options = AiOptionInput.capture(pool);
            proof.set(options);
            final var prior = AiTargetChoiceRecorder.references(ability.getTargets(), () -> !Thread.currentThread().isInterrupted());
            if (options == null || prior == null) { return Map.of("coverage", "UNAVAILABLE"); }
            offered.set(options.references());
            return Map.of("coverage", "OBSERVED_HEURISTIC_POOL_NOT_ALL_LEGAL_TARGET_SETS", "items", offered.get(),
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
        }, null, proof::get);
    }
}
