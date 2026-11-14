package forge.ai;

import forge.ai.simulation.FlatMonteCarloPicker;
import forge.game.Game;
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
    private final Map<Integer, Map<String, Object>> details = new LinkedHashMap<>();
    private final long started = System.nanoTime();
    private boolean closed;
    private String evaluationFailure;
    private final Player viewer;

    AiDecisionRecorder(final Game game, final Player viewer, final long sequence) {
        this.viewer = viewer;
        data.put("decisionSchemaVersion", 1);
        data.put("decisionId", viewer.getId() + ":" + sequence);
        data.put("playerId", viewer.getId());
        data.put("decisionType", "MAIN_PHASE_PLAY_SELECTION");
        data.put("candidateCoverage", "OBSERVED_EVALUATIONS_NOT_ALL_LEGAL_ACTIONS");
        data.put("observationCoverage", "PARTIAL_PLAYER_VIEW_V1");
        data.put("observation", observation(game, viewer));
    }

    // Evaluation can run on a worker which outlives cancellation. Never write into another decision.
    synchronized void beginAssessment(final SpellAbility sa) {
        if (!closed) { details.remove(sa.getRootAbility().getId()); }
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
                selected.add(action(sa));
            }
        }
        data.put("selection", "ERROR".equals(status) ? "NO_ACTION" : selected.isEmpty() ? "PASS_PRIORITY" : "PLAY");
        data.put("selectedActions", selected);
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
