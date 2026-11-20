package forge.ai;

import forge.ai.simulation.FlatMonteCarloPicker;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

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
    private final List<TargetChoice> targetChoices = new ArrayList<>();
    private int targetCapturesInFlight;
    private long targetChoicesOmittedByBudget;
    private long interruptedTargetCaptures;
    private final AiTargetProvenance targetProvenance = new AiTargetProvenance();

    synchronized AiTargetProvenance targetProvenance(final SpellAbility selected) {
        final java.util.Set<String> allowed = new java.util.HashSet<>();
        for (final TargetChoice choice : targetChoices) {
            if (choice.origin.candidate == selected) { allowed.add((String) choice.data.get("choiceId")); }
        }
        return targetProvenance.selected(selected, allowed);
    }

    synchronized void captureFailed(final RuntimeException failure) {
        if (!closed && viewer.getGame().isRecordingAiDecisions()) {
            viewer.getGame().recordEventObservationFailure(failure);
        }
    }

    private static final class Assessment {
        private final SpellAbility candidate;
        private Integer index;
        private Assessment(final SpellAbility candidate) { this.candidate = candidate; }
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
            synchronized (AiDecisionRecorder.this) {
                if (closed) { return; }
                final Map<String, Object> selected = AiTargetChoiceRecorder.reference(chosen);
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

        void atTargetAddition() {
            synchronized (AiDecisionRecorder.this) {
                if (!closed) { data.put("poolSnapshotTiming", "AT_TARGET_ADDITION"); }
            }
        }

        TargetAddition prepareAddition(final SpellAbility ability, final GameObject actual, final GameObject returned) {
            synchronized (AiDecisionRecorder.this) {
                if (closed || ability != evaluatedAbility
                        || ability.getTargets().size() >= AiTargetChoiceRecorder.MAX_REFERENCES) { return null; }
                final var targets = ability.getTargets();
                final var previous = targetProvenance.before(targets.getSelectionStateIdentity(), targets.size());
                final boolean delegated = ability.getTargetingPlayer() != null && ability.getTargetingPlayer() != viewer;
                final var origin = actual == returned && !delegated && ability.getActivatingPlayer() == viewer && data.get("selected") != null
                        && data.get("selected").equals(AiTargetChoiceRecorder.reference(returned))
                        && !"OPPONENT_PREFERENCE".equals(data.get("purpose"))
                        ? new AiTargetProvenance.Origin((String) data.get("choiceId"), viewer.getId(),
                                AiTargetChoiceRecorder.reference(returned)) : null;
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
            synchronized (AiDecisionRecorder.this) {
                if (!closed && targets.size() == previous.size() + 1) {
                    targetProvenance.added(targets.getSelectionStateIdentity(), previous, origin);
                }
            }
        }
    }

    TargetChoice beginTargetChoice(final SpellAbility ability,
            final Iterable<? extends GameObject> options, final String boundary) {
        final Assessment origin;
        synchronized (this) {
            if (closed || assessment == null || !ability.usesTargeting()
                    || ability.getRootAbility() != assessment.candidate.getRootAbility()) { return null; }
            if (targetChoices.size() + targetCapturesInFlight >= AiTargetChoiceRecorder.MAX_CHOICES) {
                targetChoicesOmittedByBudget++;
                return null;
            }
            origin = assessment;
            targetCapturesInFlight++;
        }
        boolean reserved = true;
        boolean interrupted = false;
        try {
            // Pool traversal must not hold the monitor needed to publish a timed-out parent.
            final java.util.function.BooleanSupplier active = () -> !closed && !Thread.currentThread().isInterrupted();
            final var prior = AiTargetChoiceRecorder.references(ability.getTargets(), active);
            final var offered = prior == null ? null : AiTargetChoiceRecorder.references(options, active);
            if (offered == null) { interrupted = true; return null; }
            final Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("choiceSchemaVersion", 1);
            choice.put("choiceType", "TARGET_POOL_SELECTION");
            choice.put("stage", "CANDIDATE_EVALUATION");
            choice.put("chooserPlayerId", viewer.getId());
            choice.put("candidateAbilityId", origin.candidate.getId());
            choice.put("abilityId", ability.getId());
            choice.put("sourceId", ability.getHostCard().getId());
            final Card source = ability.getHostCard();
            final boolean visible = !source.isFaceDown() && source.getView().canBeShownTo(viewer.getView());
            choice.put("boundary", visible ? boundary : "MASKED_TARGET_POOL");
            choice.put("purpose", !visible ? "MASKED_TARGET_PLANNING" : "DAMAGE_OPPONENT_POOL".equals(boundary)
                    ? "OPPONENT_PREFERENCE" : "TARGET_RECOMMENDATION");
            choice.put("optionCoverage", "OBSERVED_HEURISTIC_POOL_NOT_ALL_LEGAL_TARGET_SETS");
            choice.put("priorTargets", prior.values());
            choice.put("priorTargetsTruncated", prior.truncated());
            choice.put("options", offered.values());
            choice.put("optionsTruncated", offered.truncated());
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
        data.put("observation", observation(game, viewer));
    }

    // Evaluation can run on a worker which outlives cancellation. Never write into another decision.
    synchronized void beginAssessment(final SpellAbility sa) {
        if (!closed) {
            details.remove(sa.getRootAbility().getId());
            assessment = new Assessment(sa);
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
        data.put("targetChoiceProvenanceSchemaVersion", 1);
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
            if (result.containsKey("sourceName")) { result.put("description", sa.getDescription()); }
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

    private static Map<String, Object> observation(final Game game, final Player viewer) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("turn", game.getPhaseHandler().getTurn());
        result.put("phase", game.getPhaseHandler().getPhase().name());
        result.put("turnPlayerId", game.getPhaseHandler().getPlayerTurn().getId());
        final List<Map<String, Object>> players = new ArrayList<>();
        for (final Player player : game.getPlayers()) {
            final Map<String, Object> state = new LinkedHashMap<>();
            state.put("id", player.getId());
            state.put("life", player.getLife());
            state.put("handCount", player.getCardsIn(ZoneType.Hand).size());
            state.put("libraryCount", player.getCardsIn(ZoneType.Library).size());
            final List<Map<String, Object>> cards = new ArrayList<>();
            for (final ZoneType zone : List.of(ZoneType.Battlefield, ZoneType.Command,
                    ZoneType.Graveyard, ZoneType.Exile, ZoneType.Hand)) {
                if (zone == ZoneType.Hand && player != viewer) {
                    continue; // Even revealed opponent hands are omitted in this initial, conservative observation.
                }
                for (final Card card : player.getCardsIn(zone)) {
                    final Map<String, Object> ref = new LinkedHashMap<>();
                    ref.put("id", card.getId());
                    ref.put("zone", zone.name());
                    ref.put("tapped", card.isTapped());
                    // Face-down identity is hidden even when this player could inspect it.
                    final boolean visible = !card.isFaceDown() && card.getView().canBeShownTo(viewer.getView());
                    ref.put("identityKnown", visible);
                    if (visible) {
                        ref.put("name", card.getName());
                        ref.put("types", card.getType().toString());
                        ref.put("manaCost", card.getManaCost().toString());
                        if (card.isCreature()) {
                            ref.put("power", card.getNetPower());
                            ref.put("toughness", card.getNetToughness());
                        }
                    }
                    final Map<String, Object> counters = new LinkedHashMap<>();
                    for (final var entry : card.getCounters().entrySet()) {
                        counters.put(entry.getElement().toString(), entry.getCount());
                    }
                    ref.put("counters", counters);
                    cards.add(ref);
                }
            }
            state.put("cards", cards);
            final List<Integer> mana = new ArrayList<>();
            for (final Mana unit : player.getManaPool()) {
                mana.add((int) unit.getColor());
            }
            state.put("floatingManaColors", mana);
            players.add(state);
        }
        result.put("players", players);
        return result;
    }
}
