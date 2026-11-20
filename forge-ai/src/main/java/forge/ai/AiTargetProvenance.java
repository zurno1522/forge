package forge.ai;

import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded AI-owned origins of explicit additions to an unchanged ordered target plan. */
final class AiTargetProvenance {
    record Origin(String choiceId, long chooserPlayerId, Map<String, Object> selected) { }
    private final Map<Object, List<Origin>> states = new IdentityHashMap<>();

    List<Origin> before(final Object state, final int size) {
        final List<Origin> known = states.get(state);
        return known != null ? new ArrayList<>(known) : new ArrayList<>(Collections.nCopies(size, null));
    }

    void added(final Object state, final List<Origin> origins, final Origin origin) {
        origins.add(origin);
        states.put(state, Collections.unmodifiableList(origins));
    }

    AiTargetProvenance selected(final SpellAbility root, final Set<String> allowedChoices) {
        final AiTargetProvenance result = new AiTargetProvenance();
        if (states.isEmpty()) { return result; }
        int groups = 0;
        for (SpellAbility sa = root; sa != null && groups++ < AiTargetChoiceRecorder.MAX_CHOICES; sa = sa.getSubAbility()) {
            final Object state = sa.getTargets().getSelectionStateIdentity();
            if (states.containsKey(state)) {
                final List<Origin> filtered = new ArrayList<>();
                for (final Origin origin : states.get(state)) {
                    filtered.add(origin != null && allowedChoices.contains(origin.choiceId()) ? origin : null);
                }
                result.states.put(state, Collections.unmodifiableList(filtered));
            }
        }
        return result;
    }

    Map<String, Object> capture(final SpellAbility root) {
        final List<Map<String, Object>> abilities = new ArrayList<>();
        SpellAbility current = root;
        for (; current != null && abilities.size() < AiTargetChoiceRecorder.MAX_CHOICES; current = current.getSubAbility()) {
            final var targets = current.getTargets();
            final List<Origin> known = states.isEmpty() ? null : states.get(targets.getSelectionStateIdentity());
            final List<Map<String, Object>> origins = new ArrayList<>();
            final int count = Math.min(targets.size(), AiTargetChoiceRecorder.MAX_REFERENCES);
            for (int i = 0; i < count; i++) {
                final Origin origin = known == null || i >= known.size() ? null : known.get(i);
                // The target object itself may have changed incarnation without a list mutation.
                origins.add(origin != null && origin.selected().equals(AiTargetChoiceRecorder.reference(targets.get(i)))
                        ? Map.of("choiceId", origin.choiceId(), "chooserPlayerId", origin.chooserPlayerId(),
                                "stage", "CANDIDATE_EVALUATION") : null);
            }
            final Map<String, Object> group = new LinkedHashMap<>();
            group.put("abilityId", current.getId());
            group.put("targetChoices", origins);
            group.put("targetsTruncated", count < targets.size());
            abilities.add(group);
        }
        return Map.of("schemaVersion", 1, "coverage", "EXPLICIT_TARGET_ADDITIONS_V1",
                "abilities", abilities, "abilitiesTruncated", current != null);
    }
}
