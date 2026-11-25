package forge.ai;

import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Actual controller selections, after delivery of the prompt's delayed reveal. */
final class AiEffectChoiceRecorder {
    private AiEffectChoiceRecorder() { }

    static <T> T prompt(final Player chooser, final Supplier<T> callback) {
        AiContextDisclosures disclosures = null;
        AiContextDisclosures.Prompt prompt = null;
        if (chooser.getGame().isRecordingAiDecisions() && chooser.getController() instanceof PlayerControllerAi ai) {
            try {
                disclosures = ai.inputContextState().disclosures();
                prompt = disclosures.prompt();
            } catch (RuntimeException failure) { chooser.getGame().recordEventObservationFailure(failure); }
        }
        try { return callback.get(); }
        catch (RuntimeException | Error failure) {
            if (disclosures != null) {
                try { disclosures.abort(); }
                catch (RuntimeException captureFailure) { chooser.getGame().recordEventObservationFailure(captureFailure); }
            }
            throw failure;
        } finally {
            if (prompt != null) {
                try { prompt.close(); }
                catch (RuntimeException failure) { chooser.getGame().recordEventObservationFailure(failure); }
            }
        }
    }

    static <T extends GameEntity> T single(final Player chooser, final SpellAbility ability,
            final Iterable<T> options, final boolean optional, final Player relatedPlayer, final Supplier<T> callback) {
        final AtomicReference<List<Map<String, Object>>> offered = new AtomicReference<>();
        final AtomicReference<AiOptionInput> proof = new AtomicReference<>();
        final AtomicReference<AiEntitySelectionHistory.Input> sequence = AiEntitySelectionHistory.present(chooser, ability)
                ? new AtomicReference<>() : null;
        return AiChoiceRecorder.observeInput(chooser, ability, "EFFECT_ENTITY_SELECTION", null,
                "CONTROLLER_EFFECT_ENTITY", "BEFORE_SELECTOR", () -> {
                    final var history = sequence == null ? null : AiEntitySelectionHistory.claim(chooser, ability, options, optional, relatedPlayer);
                    if (sequence != null) { sequence.set(history); }
                    final Map<String, Object> result = pool(options, offered, proof);
                    result.put("coverage", offered.get() == null ? "UNAVAILABLE" : "OBSERVED_EFFECT_OPTIONS_DIAGNOSTIC_ONLY");
                    result.put("optional", optional);
                    result.put("relatedPlayerId", relatedPlayer == null ? null : relatedPlayer.getId());
                    if (history != null) { result.put("selectionSequence", history.diagnostics()); }
                    return result;
                }, callback, value -> selected(value, offered.get()),
                sequence == null ? () -> AiPromptInput.single(optional, relatedPlayer)
                        : new AiEntitySelectionHistory.Prompt(sequence, optional, relatedPlayer), proof::get);
    }

    static <T extends GameEntity> List<T> multiple(final Player chooser, final SpellAbility ability,
            final Iterable<T> options, final int min, final int max, final Player relatedPlayer,
            final Supplier<List<T>> callback) {
        return multiple(chooser, ability, options, min, max, relatedPlayer, null, callback);
    }

    static <T extends GameEntity> List<T> multiple(final Player chooser, final SpellAbility ability,
            final Iterable<T> options, final int min, final int max, final Player relatedPlayer,
            final PlayerControllerAi receiver, final Supplier<List<T>> callback) {
        if (!chooser.getGame().isRecordingAiDecisions()) { return callback.get(); }
        return AiChoiceRecorder.observeInput(chooser, ability, "EFFECT_MULTI_ENTITY_SELECTION", null,
                "CONTROLLER_EFFECT_ENTITIES", "BEFORE_OPERATION", () -> {
                    final var captured = AiOptionInput.capture(options);
                    final Map<String, Object> result = new LinkedHashMap<>();
                    result.put("coverage", captured == null ? "UNAVAILABLE" : "OBSERVED_EFFECT_OPTIONS_DIAGNOSTIC_ONLY");
                    result.put("items", captured == null ? List.of() : captured.references());
                    result.put("truncated", captured == null || captured.truncated());
                    result.put("min", min); result.put("max", max);
                    result.put("relatedPlayerId", relatedPlayer == null ? null : relatedPlayer.getId());
                    return result;
                }, callback, values -> {
                    final int count = values.size();
                    final var captured = AiOptionInput.capture(values);
                    final List<Map<String, Object>> references = captured == null ? List.of() : captured.references();
                    if (references.size() > count) { throw new IllegalStateException("Returned entity list changed during capture"); }
                    return Map.of("coverage", captured == null ? "UNAVAILABLE" : "OBSERVED_RETURNED_ENTITY_PREFIX",
                            "items", references, "selectedCount", count,
                            "truncated", captured == null || captured.truncated() || count > references.size());
                }, new AiPromptInput.EntityBounds(min, max, relatedPlayer), null,
                (owner, policy) -> AiEntitySelectionHistory.operation(chooser, ability, receiver, min, max, relatedPlayer, owner, policy));
    }

    static Card zoneChange(final Player chooser, final SpellAbility ability, final ZoneType destination,
            final List<ZoneType> origins, final CardCollection options, final boolean optional,
            final Player decider, final Supplier<Card> callback) {
        final AtomicReference<List<Map<String, Object>>> offered = new AtomicReference<>();
        final AtomicReference<AiOptionInput> proof = new AtomicReference<>();
        return AiChoiceRecorder.observeInput(chooser, ability, "ZONE_CHANGE_CARD_SELECTION", null,
                "CONTROLLER_ZONE_CHANGE_CARD", "BEFORE_SELECTOR", () -> {
                    final Map<String, Object> result = pool(options, offered, proof);
                    result.put("coverage", offered.get() == null ? "UNAVAILABLE" : "OBSERVED_ZONE_CHANGE_OPTIONS_DIAGNOSTIC_ONLY");
                    result.put("optional", optional);
                    result.put("destination", destination.name());
                    result.put("origins", origins.stream().map(Enum::name).toList());
                    result.put("deciderPlayerId", decider == null ? null : decider.getId());
                    return result;
                }, callback, value -> selected(value, offered.get()), new AiPromptInput.ZoneRequest(destination, origins, optional, decider), proof::get);
    }

    private static Map<String, Object> pool(final Iterable<? extends GameObject> options,
            final AtomicReference<List<Map<String, Object>>> offered, final AtomicReference<AiOptionInput> proof) {
        final var references = AiOptionInput.capture(options);
        proof.set(references);
        final Map<String, Object> result = new LinkedHashMap<>();
        offered.set(references == null ? null : references.references());
        result.put("items", references == null ? List.of() : references.references());
        result.put("truncated", references == null || references.truncated());
        return result;
    }

    private static Object selected(final GameObject value, final List<Map<String, Object>> offered) {
        if (value == null) { return null; }
        final Map<String, Object> reference = AiTargetChoiceRecorder.reference(value);
        Integer index = null;
        int matches = 0;
        for (int i = 0; offered != null && i < offered.size(); i++) {
            if (offered.get(i).equals(reference)) { matches++; index = i; }
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("selected", reference);
        result.put("selectedOptionIndex", matches == 1 ? index : null);
        result.put("match", offered == null ? "UNAVAILABLE" : matches == 1 ? "UNIQUE"
                : matches == 0 ? "OUTSIDE_CAPTURED_POOL" : "AMBIGUOUS");
        return result;
    }
}
