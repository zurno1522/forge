package forge.ai;

import forge.ai.simulation.FlatMonteCarloPicker;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One main-phase selection. Capture before evaluating: evaluation can mutate abilities. */
final class AiDecisionRecorder {
    private final Map<String, Object> data = new LinkedHashMap<>();
    private final List<Map<String, Object>> candidates = new ArrayList<>();
    private final Map<Long, Map<String, Object>> details = new LinkedHashMap<>();
    private final long started = System.nanoTime();
    private volatile boolean closed;
    private String evaluationFailure;
    private final Player viewer;
    private Assessment assessment;
    private long assessmentSequence;
    private final List<TargetChoice> targetChoices = new ArrayList<>();
    private int targetCapturesInFlight;
    private long targetChoicesOmittedByBudget;
    private long interruptedTargetCaptures;
    private final AiTargetProvenance targetProvenance = new AiTargetProvenance();

    synchronized Map<String, Object> choiceContext(final SpellAbility ability) {
        if (closed || assessment == null || ability.getRootAbility() != assessment.candidate.getRootAbility()) { return null; }
        if (assessment.id == null) {
            assessment.id = data.get("decisionId") + ":assessment:" + assessment.ordinal;
            if (assessment.index != null) { candidates.get(assessment.index).put("assessmentId", assessment.id); }
        }
        return Map.of("decisionId", data.get("decisionId"), "candidateAbilityId", assessment.candidate.getId(), "assessmentId", assessment.id);
    }

    boolean choiceCaptureOpen() { return !closed; }

    synchronized AiTargetProvenance targetProvenance(final SpellAbility selected) {
        final java.util.Set<String> allowed = new java.util.HashSet<>();
        for (final TargetChoice choice : targetChoices) {
            if (choice.origin.candidate == selected) { allowed.add((String) choice.data.get("choiceId")); }
        }
        for (final SearchTargetApplication application : searchTargetApplications) {
            if (application.candidate == selected) { allowed.add((String) application.record.get("choiceId")); }
        }
        return targetProvenance.selected(selected, allowed, searchTargetCaptureDeclared ? 2 : 1);
    }

    enum SearchTargetOperation {
        HEURISTIC_REVALIDATION, RESTORE_APPROVED_TARGET, ROLLOUT_TARGET_OVERRIDE, DISCOVERED_TARGET, GUARD_REAIM
    }

    private final List<SearchTargetApplication> searchTargetApplications = new ArrayList<>();
    private boolean searchTargetCaptureDeclared;
    private int searchTargetCapturesInFlight;
    private long searchTargetBudgetOmissions;
    private long searchTargetInterruptedCaptures;

    /** Wrap only the actual final search application/revalidation, never speculative rollout target changes. */
    boolean searchTargets(final SpellAbility candidate, final SearchTargetOperation operation, final GameObject requested,
            final java.util.function.BooleanSupplier callback) {
        SearchTargetApplication application = null;
        if (viewer.getGame().isRecordingAiDecisions() && candidate.getHostCard() != null
                && candidate.getHostCard().getGame() == viewer.getGame()) {
            try { application = beginSearchTargetApplication(candidate, operation, requested); }
            catch (RuntimeException failure) { captureFailed(failure); }
        }
        final boolean accepted;
        try { accepted = callback.getAsBoolean(); }
        catch (RuntimeException | Error failure) {
            if (application != null) { application.complete("ERROR"); }
            throw failure;
        }
        if (application != null) { application.complete(accepted ? "ACCEPTED" : "DECLINED"); }
        return accepted;
    }

    private record SearchPlanGroup(SpellAbility ability, Object state, List<Map<String, Object>> targets,
                                   boolean truncated) { }
    private record SearchPlan(List<SearchPlanGroup> groups, boolean truncated) {
        Map<String, Object> wire() {
            final List<Map<String, Object>> result = new ArrayList<>();
            for (final var group : groups) {
                result.add(Map.of("abilityId", group.ability.getId(), "targets", group.targets, "targetsTruncated", group.truncated));
            }
            return Map.of("abilities", result, "abilitiesTruncated", truncated);
        }
    }

    private SearchPlan searchPlan(final SpellAbility candidate) {
        final List<SearchPlanGroup> groups = new ArrayList<>();
        SpellAbility current = candidate;
        final java.util.function.BooleanSupplier active = () -> !closed && !Thread.currentThread().isInterrupted()
                && viewer.getGame().isRecordingAiDecisions();
        for (; current != null && groups.size() < AiTargetChoiceRecorder.MAX_CHOICES; current = current.getSubAbility()) {
            final var refs = AiTargetChoiceRecorder.references(current.getTargets(), active);
            if (refs == null) { return null; }
            groups.add(new SearchPlanGroup(current, current.getTargets().getSelectionStateIdentity(), refs.values(), refs.truncated()));
        }
        return active.getAsBoolean() ? new SearchPlan(groups, current != null) : null;
    }

    private SearchTargetApplication beginSearchTargetApplication(final SpellAbility candidate,
            final SearchTargetOperation operation, final GameObject requested) {
        synchronized (this) {
            if (closed) { return null; }
            searchTargetCaptureDeclared = true;
            if (searchTargetApplications.size() + searchTargetCapturesInFlight >= AiTargetChoiceRecorder.MAX_CHOICES) {
                searchTargetBudgetOmissions++; return null;
            }
            searchTargetCapturesInFlight++;
        }
        boolean reserved = true;
        try {
            final Card source = candidate.getHostCard();
            final boolean visible = !source.isFaceDown() && source.getView().canBeShownTo(viewer.getView());
            final SearchPlan before = visible ? searchPlan(candidate) : null;
            if (visible && before == null) {
                synchronized (this) { if (!closed) { searchTargetInterruptedCaptures++; } }
                return null;
            }
            final Map<String, Object> record = new LinkedHashMap<>();
            record.put("applicationSchemaVersion", 1);
            record.put("choiceType", "SEARCH_TARGET_APPLICATION");
            record.put("stage", "SEARCH_TARGET_APPLICATION");
            record.put("chooserPlayerId", viewer.getId());
            record.put("candidateAbilityId", candidate.getId());
            record.put("sourceId", source.getId());
            record.put("operation", visible ? operation.name() : "MASKED");
            record.put("optionCoverage", visible ? "OBSERVED_OPERATION_NOT_ALTERNATIVES" : "MASKED");
            record.put("requestedTarget", visible ? AiTargetChoiceRecorder.reference(requested) : null);
            record.put("beforeTargets", before == null ? null : before.wire());
            record.put("afterTargets", null);
            record.put("changedAbilities", List.of());
            record.put("status", "INCOMPLETE");
            synchronized (this) {
                if (closed) { return null; }
                record.put("choiceId", data.get("decisionId") + ":search-target:" + searchTargetApplications.size());
                final var application = new SearchTargetApplication(candidate, record, before, visible);
                searchTargetApplications.add(application);
                searchTargetCapturesInFlight--;
                reserved = false;
                return application;
            }
        } finally {
            synchronized (this) { if (reserved) { searchTargetCapturesInFlight--; } }
        }
    }

    private final class SearchTargetApplication {
        private final SpellAbility candidate;
        private final Map<String, Object> record;
        private SearchPlan before;
        private final boolean visible;
        private SearchTargetApplication(SpellAbility candidate, Map<String, Object> record, SearchPlan before, boolean visible) {
            this.candidate = candidate; this.record = record; this.before = before; this.visible = visible;
        }
        void complete(final String status) {
            try {
                final SearchPlan after = visible ? searchPlan(candidate) : null;
                synchronized (AiDecisionRecorder.this) {
                    if (closed) { return; }
                    if (visible && after == null) {
                        record.put("status", "CAPTURE_INTERRUPTED");
                        searchTargetInterruptedCaptures++;
                        return;
                    }
                    record.put("status", visible ? status : "MASKED");
                    if (!visible) { return; }
                    record.put("afterTargets", after.wire());
                    final List<Integer> changed = new ArrayList<>();
                    final Map<Object, List<AiTargetProvenance.Origin>> pending = new java.util.IdentityHashMap<>();
                    for (int i = 0; i < after.groups.size(); i++) {
                        final var group = after.groups.get(i);
                        if (i < before.groups.size() && group.ability == before.groups.get(i).ability
                                && group.state == before.groups.get(i).state) { continue; }
                        changed.add(i);
                        // No origin for a declined callback, a different actor, or a delegated target chooser.
                        if (!"ACCEPTED".equals(status) || group.ability.getActivatingPlayer() != viewer
                                || group.ability.getTargetingPlayer() != null && group.ability.getTargetingPlayer() != viewer) { continue; }
                        final List<AiTargetProvenance.Origin> origins = new ArrayList<>();
                        for (int j = 0; j < group.targets.size(); j++) {
                            origins.add(new AiTargetProvenance.Origin((String) record.get("choiceId"), viewer.getId(), group.targets.get(j), i, j));
                        }
                        pending.put(group.state, origins);
                    }
                    record.put("changedAbilities", changed);
                    pending.forEach(targetProvenance::applied);
                }
            } catch (RuntimeException failure) {
                synchronized (AiDecisionRecorder.this) {
                    if (!closed) {
                        record.put("status", "CAPTURE_FAILED");
                        record.put("afterTargets", null);
                        record.put("changedAbilities", List.of());
                    }
                }
                captureFailed(failure);
            }
            finally { before = null; }
        }
    }

    synchronized void captureFailed(final RuntimeException failure) {
        if (!closed && viewer.getGame().isRecordingAiDecisions()) {
            viewer.getGame().recordEventObservationFailure(failure);
        }
    }

    private static final class Assessment {
        private final SpellAbility candidate;
        private String id;
        private final long ordinal;
        private Integer index;
        private Assessment(final SpellAbility candidate, final long ordinal) { this.candidate = candidate; this.ordinal = ordinal; }
    }

    final class TargetChoice {
        private final Assessment origin;
        private final SpellAbility evaluatedAbility;
        private final Map<String, Object> data;
        private TargetChoice(final Assessment origin, final SpellAbility evaluatedAbility, final Map<String, Object> data) {
            this.origin = origin;
            this.evaluatedAbility = evaluatedAbility;
            this.data = data;
        }

        void complete(final GameObject chosen) {
            if (closed) { return; }
            final Map<String, Object> selected = AiTargetChoiceRecorder.reference(chosen);
            synchronized (AiDecisionRecorder.this) {
                if (closed) { return; }
                data.put("selected", selected);
                final List<?> options = (List<?>) data.get("options");
                int matches = 0;
                Integer index = null;
                for (int i = 0; i < options.size(); i++) {
                    if (options.get(i).equals(selected)) { matches++; index = i; }
                }
                data.put("selectedOptionIndex", matches == 1 ? index : null);
                data.put("status", selected == null ? "DECLINED" : matches == 1 ? "SELECTED"
                        : matches == 0 ? "OUTSIDE_OBSERVED_POOL" : "AMBIGUOUS_MATCH");
            }
        }

        void failed() {
            synchronized (AiDecisionRecorder.this) {
                if (!closed) { data.put("status", "ERROR"); }
            }
        }

        void captureFailed(final RuntimeException failure) { AiDecisionRecorder.this.captureFailed(failure); }

        TargetAddition prepareAddition(final SpellAbility ability, final GameObject actual, final GameObject returned) {
            if (closed || ability != evaluatedAbility) { return null; }
            final var targets = ability.getTargets();
            final int size = targets.size();
            if (size >= AiTargetChoiceRecorder.MAX_REFERENCES) { return null; }
            final Object state = targets.getSelectionStateIdentity();
            final boolean delegated = ability.getTargetingPlayer() != null && ability.getTargetingPlayer() != viewer;
            final boolean actorMatches = ability.getActivatingPlayer() == viewer;
            final var reference = actual == returned ? AiTargetChoiceRecorder.reference(returned) : null;
            synchronized (AiDecisionRecorder.this) {
                if (closed) { return null; }
                final var previous = targetProvenance.before(state, size);
                final var origin = actual == returned && !delegated && actorMatches && data.get("selected") != null
                        && data.get("selected").equals(reference)
                        && !"OPPONENT_PREFERENCE".equals(data.get("purpose"))
                        ? new AiTargetProvenance.Origin((String) data.get("choiceId"), viewer.getId(),
                                reference) : null;
                return new TargetAddition(targets, previous, origin);
            }
        }
    }

    final class TargetAddition {
        private final forge.game.spellability.TargetChoices targets;
        private final List<AiTargetProvenance.Origin> previous;
        private final AiTargetProvenance.Origin origin;
        private TargetAddition(final forge.game.spellability.TargetChoices targets,
                final List<AiTargetProvenance.Origin> previous, final AiTargetProvenance.Origin origin) {
            this.targets = targets; this.previous = previous; this.origin = origin;
        }
        void added() {
            if (closed) { return; }
            final int size = targets.size();
            final Object state = targets.getSelectionStateIdentity();
            synchronized (AiDecisionRecorder.this) {
                if (!closed && size == previous.size() + 1) {
                    targetProvenance.added(state, previous, origin);
                }
            }
        }
    }

    TargetChoice beginTargetChoice(final SpellAbility ability,
            final Iterable<? extends GameObject> options, final String boundary) {
        return beginTargetChoice(ability, options, boundary, "BEFORE_CHOOSER");
    }

    TargetChoice beginTargetChoice(final SpellAbility ability,
            final Iterable<? extends GameObject> options, final String boundary, final String poolTiming) {
        if (!viewer.getGame().isRecordingAiDecisions()) { return null; }
        final Assessment origin;
        synchronized (this) {
            if (closed || assessment == null) { return null; }
            origin = assessment;
        }
        // Eligibility getters must not delay parent timeout publication either.
        final boolean matches = ability.usesTargeting() && ability.getRootAbility() == origin.candidate.getRootAbility();
        synchronized (this) {
            if (closed || assessment != origin || !matches) { return null; }
            if (targetChoices.size() + targetCapturesInFlight >= AiTargetChoiceRecorder.MAX_CHOICES) {
                targetChoicesOmittedByBudget++;
                return null;
            }
            targetCapturesInFlight++;
        }
        boolean reserved = true;
        boolean interrupted = false;
        try {
            // Pool traversal must not hold the monitor needed to publish a timed-out parent.
            final java.util.function.BooleanSupplier active = () -> !closed && !Thread.currentThread().isInterrupted()
                    && viewer.getGame().isRecordingAiDecisions();
            final Card source = ability.getHostCard();
            final boolean visible = !source.isFaceDown() && source.getView().canBeShownTo(viewer.getView());
            final String timing = "AT_TARGET_ADDITION".equals(poolTiming) ? "AFTER_SELECTION_BEFORE_APPLICATION" : "BEFORE_SELECTOR";
            final var entry = AiEntryLocation.capture(viewer, timing, "PLANNING_WORKING_STATE", visible, active);
            final var prior = AiTargetChoiceRecorder.references(ability.getTargets(), active);
            final var offered = prior == null ? null : AiOptionInput.capture(options, active);
            if (offered == null) { interrupted = true; return null; }
            final Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("choiceSchemaVersion", 1);
            choice.put("choiceType", "TARGET_POOL_SELECTION");
            choice.put("stage", "CANDIDATE_EVALUATION");
            choice.put("chooserPlayerId", viewer.getId());
            choice.put("candidateAbilityId", origin.candidate.getId());
            choice.put("abilityId", ability.getId());
            choice.put("sourceId", ability.getHostCard().getId());
            choice.put("boundary", visible ? boundary : "MASKED_TARGET_POOL");
            choice.put("purpose", !visible ? "MASKED_TARGET_PLANNING" : "DAMAGE_OPPONENT_POOL".equals(boundary)
                    ? "OPPONENT_PREFERENCE" : "TARGET_RECOMMENDATION");
            choice.put("optionCoverage", "OBSERVED_HEURISTIC_POOL_NOT_ALL_LEGAL_TARGET_SETS");
            choice.put("priorTargets", prior.values());
            choice.put("priorTargetsTruncated", prior.truncated());
            choice.put("options", offered.references());
            choice.put("optionsTruncated", offered.truncated());
            if ("AT_TARGET_ADDITION".equals(poolTiming)) { choice.put("poolSnapshotTiming", poolTiming); }
            choice.put("entryLocation", entry);
            Map<String, Object> input;
            try {
                input = visible ? AiInputContext.capture(viewer, ability, timing, "PLANNING_WORKING_STATE", active, null, () -> offered)
                        : AiInputContext.unavailable(viewer, timing, "PLANNING_WORKING_STATE", "HIDDEN");
            } catch (RuntimeException failure) {
                captureFailed(failure);
                input = AiInputContext.unavailable(viewer, timing, "PLANNING_WORKING_STATE", "CAPTURE_FAILED");
            }
            choice.put("inputContext", input);
            choice.put("status", "INCOMPLETE");
            choice.put("selected", null);
            choice.put("selectedOptionIndex", null);
            synchronized (this) {
                targetCapturesInFlight--;
                reserved = false;
                if (closed) { return null; }
                choice.put("choiceId", data.get("decisionId") + ":target:" + targetChoices.size());
                final TargetChoice result = new TargetChoice(origin, ability, choice);
                targetChoices.add(result);
                return result;
            }
        } finally {
            if (reserved) {
                synchronized (this) {
                    targetCapturesInFlight--;
                    if (!closed && interrupted) { interruptedTargetCaptures++; }
                }
            }
        }
    }

    AiDecisionRecorder(final Game game, final Player viewer, final long sequence) {
        this.viewer = viewer;
        data.put("decisionSchemaVersion", 1);
        data.put("actionExecutionSchemaVersion", 1);
        data.put("decisionId", viewer.getId() + ":" + sequence);
        data.put("playerId", viewer.getId());
        data.put("decisionType", "MAIN_PHASE_PLAY_SELECTION");
        data.put("candidateCoverage", "OBSERVED_EVALUATIONS_NOT_ALL_LEGAL_ACTIONS");
        data.put("observationCoverage", "PARTIAL_PLAYER_VIEW_V1");
        data.put("observation", AiInputContext.legacyObservation(game, viewer));
        try {
            data.put("inputContext", AiInputContext.capture(viewer, null, "BEFORE_OPERATION", "RULES_STATE",
                    () -> !closed && game.isRecordingAiDecisions()));
        } catch (RuntimeException failure) {
            game.recordEventObservationFailure(failure);
            data.put("inputContext", AiInputContext.unavailable(viewer, "BEFORE_OPERATION", "RULES_STATE", "CAPTURE_FAILED"));
        }
    }

    // Evaluation can run on a worker which outlives cancellation. Never write into another decision.
    synchronized void beginAssessment(final SpellAbility sa) {
        if (!closed) {
            details.remove(sa.getRootAbility().getId());
            assessment = new Assessment(sa, ++assessmentSequence);
        }
    }

    synchronized void detail(final SpellAbility sa, final Map<String, Object> reason) {
        if (!closed) { details.put(sa.getRootAbility().getId(), reason); }
    }

    synchronized void lands(final Iterable<Card> playable, final SpellAbility heuristic) {
        if (closed) { return; }
        for (final Card land : playable) {
            final boolean preferred = land.getId() == heuristic.getHostCard().getId();
            final Map<String, Object> option = preferred ? action(heuristic) : cardAction(land);
            option.put("kind", "LAND");
            option.put("assessment", "LAND_OPTION");
            option.put("assessmentStage", "LAND_SHORTLIST");
            option.put("heuristicDefault", preferred);
            option.put("searchOffered", false);
            candidates.add(option);
        }
    }

    synchronized void landSearchCandidates(final List<SpellAbility> offered) {
        if (closed) { return; }
        for (final Map<String, Object> candidate : candidates) {
            if (!"LAND_SHORTLIST".equals(candidate.get("assessmentStage"))) { continue; }
            for (final SpellAbility sa : offered) {
                if (candidate.get("sourceId").equals(sa.getHostCard().getId())) {
                    candidate.put("searchOffered", true);
                    candidate.put("abilityId", sa.getId());
                }
            }
        }
    }

    synchronized void evaluated(final SpellAbility sa, final String reason) {
        if (!closed) {
            final Map<String, Object> candidate = action(sa);
            if (assessment != null && assessment.candidate == sa && assessment.index == null) {
                assessment.index = candidates.size();
                if (assessment.id != null) { candidate.put("assessmentId", assessment.id); }
            }
            candidate.put("assessment", reason);
            final Map<String, Object> detail = details.remove(sa.getRootAbility().getId());
            if (detail != null && !"WillPlay".equals(reason) && !"MONTE_CARLO_ONLY_WillPlay".equals(reason)) {
                // Handler names can reveal a face-down card's implementation. Retain only the generic code then.
                candidate.put("assessmentDetail", candidate.containsKey("sourceName") ? detail
                        : Map.of("reason", detail.get("reason")));
            }
            candidates.add(candidate);
        }
    }

    synchronized void interrupted(final String reason) {
        if (!closed) {
            evaluationFailure = reason;
        }
    }

    synchronized AiDecisionEvent finish(final List<SpellAbility> chosen, final String status,
            final FlatMonteCarloPicker.Result search) {
        closed = true;
        data.put("status", evaluationFailure == null ? status : evaluationFailure);
        data.put("candidates", candidates);
        final List<Map<String, Object>> selected = new ArrayList<>();
        if (chosen != null) {
            for (final SpellAbility sa : chosen) {
                final Map<String, Object> action = action(sa);
                action.put("actionId", data.get("decisionId") + ":" + selected.size());
                selected.add(action);
            }
        }
        data.put("selection", "ERROR".equals(status) ? "NO_ACTION" : selected.isEmpty() ? "PASS_PRIORITY" : "PLAY");
        data.put("selectedActions", selected);
        data.put("subordinateChoiceSchemaVersion", 1);
        data.put("targetChoiceProvenanceSchemaVersion", searchTargetCaptureDeclared ? 2 : 1);
        data.put("subordinateChoiceCoverage", "OBSERVED_HEURISTIC_TARGET_POOLS_V1");
        final List<Map<String, Object>> choices = new ArrayList<>();
        for (final TargetChoice choice : targetChoices) {
            choice.data.put("candidateIndex", choice.origin.index);
            String candidateActionId = null;
            if (chosen != null) {
                for (int i = 0; i < chosen.size(); i++) {
                    if (chosen.get(i) == choice.origin.candidate) {
                        candidateActionId = (String) selected.get(i).get("actionId");
                        break;
                    }
                }
            }
            choice.data.put("candidateActionId", candidateActionId);
            choices.add(choice.data);
        }
        data.put("subordinateChoices", choices);
        if (searchTargetCaptureDeclared) {
            final List<Map<String, Object>> applications = new ArrayList<>();
            for (final SearchTargetApplication application : searchTargetApplications) {
                String actionId = null;
                if (chosen != null) {
                    for (int i = 0; i < chosen.size(); i++) {
                        if (chosen.get(i) == application.candidate) { actionId = (String) selected.get(i).get("actionId"); break; }
                    }
                }
                application.record.put("candidateActionId", actionId);
                applications.add(application.record);
            }
            data.put("searchTargetApplicationSchemaVersion", 1);
            data.put("searchTargetApplications", applications);
            data.put("searchTargetApplicationOmissions", Map.of("budget", searchTargetBudgetOmissions,
                    "interrupted", searchTargetInterruptedCaptures + searchTargetCapturesInFlight));
        }
        data.put("subordinateChoiceOmissions", Map.of("budget", targetChoicesOmittedByBudget,
                "interrupted", interruptedTargetCaptures + targetCapturesInFlight));
        data.put("elapsedNanos", System.nanoTime() - started);
        if (search != null) {
            data.put("search", Map.of("method", "FLAT_MONTE_CARLO", "reason", search.reason,
                    "decidedByRollouts", search.decidedByRollouts,
                    "candidatesEvaluated", search.candidatesEvaluated, "rollouts", search.rollouts,
                    "elapsedNanos", search.elapsedNanos));
        }
        return new AiDecisionEvent(data);
    }

    private Map<String, Object> action(final SpellAbility sa) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("abilityId", sa.getId());
        result.put("kind", sa.isLandAbility() ? "LAND" : sa.isSpell() ? "SPELL" : "ABILITY");
        if (sa.getHostCard() != null) {
            result.putAll(cardAction(sa.getHostCard()));
            if (result.containsKey("sourceName")) {
                result.put("description", sa.getDescription());
                if (sa.getRootAbility().getPayCosts() != null && sa.getRootAbility().costHasManaX()) {
                    result.put("evaluatedX", sa.getRootAbility().getXManaCostPaid());
                }
            }
        }
        return result;
    }

    private Map<String, Object> cardAction(final Card card) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceId", card.getId());
        if (!card.isFaceDown() && card.getView().canBeShownTo(viewer.getView())) {
            result.put("sourceName", card.getName());
        }
        if (card.getZone() != null) { result.put("zone", card.getZone().getZoneType().name()); }
        return result;
    }

}
