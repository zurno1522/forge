package forge.ai;

import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.player.ZoneChangeSelectionObserver;
import forge.game.player.ZoneChangeSelectionRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The audited engine selection callback, before reveal and before later application. */
final class AiZoneOperationRecorder {
    private AiZoneOperationRecorder() { }

    static CardCollection choose(final Player chooser, final PlayerControllerAi receiver, final ZoneChangeSelectionRequest request,
            final Function<ZoneChangeSelectionObserver, CardCollection> callback) {
        if (!chooser.getGame().isRecordingAiDecisions()) { return AiZoneSelectionHistory.operation(chooser, receiver, request, null, callback); }
        return AiChoiceRecorder.observeInput(chooser, request.ability(), "ZONE_CHANGE_SELECTION_OPERATION", null,
                "CONTROLLER_ZONE_CHANGE_SELECTIONS", "BEFORE_OPERATION", () -> {
                    final Map<String, Object> options = new LinkedHashMap<>();
                    options.put("coverage", "RECEIVED_ZONE_OPERATION_REQUEST"); options.put("branch", request.branch().name());
                    options.put("requestedIterations", request.requestedIterations());
                    options.put("destination", request.destination().name());
                    options.put("origins", request.origins().stream().map(Enum::name).toList());
                    options.put("sourcePlayerId", request.sourcePlayer() == null ? null : request.sourcePlayer().getId());
                    options.put("deciderPlayerId", request.decider() == null ? null : request.decider().getId());
                    return options;
                }, () -> callback.apply(ZoneChangeSelectionObserver.NONE), values -> {
                    final int count = values.size(); final var captured = AiOptionInput.capture(values);
                    final List<Map<String, Object>> references = captured == null ? List.of() : captured.references();
                    if (references.size() > count) { throw new IllegalStateException("Retained card collection changed during capture"); }
                    return Map.of("coverage", captured == null ? "UNAVAILABLE" : "OBSERVED_RETAINED_CARD_PREFIX",
                            "items", references, "selectedCount", count,
                            "truncated", captured == null || captured.truncated() || count > references.size());
                }, new AiPromptInput.ZoneOperation(request), null,
                (owner, ignored) -> AiZoneSelectionHistory.operation(chooser, receiver, request, owner, callback));
    }
}
