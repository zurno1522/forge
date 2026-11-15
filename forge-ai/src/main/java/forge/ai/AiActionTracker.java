package forge.ai;

import com.google.common.eventbus.Subscribe;
import forge.game.Game;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Real-game action attempts. Object aliases are explicit; names/descriptions are never join keys. */
public final class AiActionTracker {
    private final Game game;
    private final List<Attempt> actions = new ArrayList<>();

    static final class Attempt {
        private final Map<String, Object> identity;
        private final Set<SpellAbility> aliases = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean started;
        private int activationsBefore;
        private Long executedAbilityId;
        private Long stackId;

        private Attempt(final Map<String, Object> identity) { this.identity = identity; }
    }

    AiActionTracker(final Game game) {
        this.game = game;
        game.subscribeToEvents(this);
    }

    synchronized void selected(final AiDecisionEvent event, final List<SpellAbility> chosen) {
        if (chosen == null) { return; }
        final List<?> selected = (List<?>) event.data().get("selectedActions");
        for (int index = 0; index < chosen.size(); index++) {
            final Map<?, ?> action = (Map<?, ?>) selected.get(index);
            final Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("executionSchemaVersion", 1);
            identity.put("decisionId", event.data().get("decisionId"));
            identity.put("playerId", event.data().get("playerId"));
            for (final String key : List.of("actionId", "abilityId", "sourceId", "kind")) {
                identity.put(key, action.get(key));
            }
            final Attempt attempt = new Attempt(identity);
            attempt.aliases.add(chosen.get(index));
            actions.add(attempt);
        }
    }

    synchronized Attempt begin(final SpellAbility sa) {
        if (!game.isRecordingAiDecisions()) { return null; }
        // Nested plays during payment/resolution are not the next selected action in the priority loop.
        if (actions.stream().anyMatch(attempt -> attempt.started)) { return null; }
        for (final Attempt attempt : actions) {
            if (!attempt.started && attempt.aliases.contains(sa)) {
                attempt.started = true;
                attempt.activationsBefore = game.getStack().getAbilityActivatedThisTurn().size();
                emit(attempt, "STARTED", "EXECUTION_REQUESTED");
                return attempt;
            }
        }
        return null;
    }

    synchronized void alias(final SpellAbility original, final SpellAbility transformed) {
        for (final Attempt attempt : actions) {
            if (attempt.started && attempt.aliases.contains(original)) { attempt.aliases.add(transformed); }
        }
    }

    @Subscribe
    public synchronized void stacked(final GameEventSpellAbilityCast event) {
        if (!game.isRecordingAiDecisions()) { return; }
        for (final var item : game.getStack()) {
            if (item.getId() != event.si().getId()) { continue; }
            final SpellAbility actual = item.getSpellAbility();
            if (actual.isCopied() || actual.isTrigger()) { return; }
            for (final Attempt attempt : actions) {
                if (attempt.started && (attempt.aliases.contains(actual) || attempt.aliases.contains(actual.getOriginalAbility()))) {
                    attempt.executedAbilityId = actual.getId();
                    attempt.stackId = item.getId();
                }
            }
            return;
        }
    }

    synchronized void finish(final Attempt attempt, final String status, final String reason) {
        if (attempt == null) { return; }
        if (!actions.remove(attempt)) { return; }
        emit(attempt, status, reason);
    }

    synchronized void played(final Attempt attempt, final SpellAbility sa, final boolean played) {
        if (attempt == null) { return; }
        if (!played) {
            finish(attempt, "FAILED", sa.isLandAbility() ? "LAND_NOT_PLAYED" : "CAST_OR_ACTIVATION_FAILED");
        } else if (sa.isLandAbility()) {
            finish(attempt, "SUCCEEDED", "LAND_PLAYED");
        } else if (attempt.stackId != null) {
            finish(attempt, "SUCCEEDED", "STACKED");
        } else if (observedImmediateActivation(attempt)) {
            finish(attempt, "SUCCEEDED", "RESOLVED_WITHOUT_STACK");
        } else {
            finish(attempt, "UNOBSERVED", "NO_STACK_ENTRY_OBSERVED");
        }
    }

    private boolean observedImmediateActivation(final Attempt attempt) {
        final List<SpellAbility> activated = game.getStack().getAbilityActivatedThisTurn();
        for (int index = attempt.activationsBefore; index < activated.size(); index++) {
            final SpellAbility actual = activated.get(index);
            // The engine records mana activations before resolving them; LKI copies retain their IDs.
            // Together with the successful helper return, this confirms the immediate execution path.
            if (actual.isManaAbility() && attempt.aliases.stream().anyMatch(sa -> sa.getId() == actual.getId())) {
                attempt.executedAbilityId = actual.getId();
                return true;
            }
        }
        return false;
    }

    synchronized void abandonPending(final String reason) {
        for (final Attempt attempt : new ArrayList<>(actions)) {
            if (!attempt.started) { finish(attempt, "ABANDONED", reason); }
        }
    }

    private void emit(final Attempt attempt, final String status, final String reason) {
        final Map<String, Object> record = new LinkedHashMap<>(attempt.identity);
        record.put("status", status);
        record.put("reason", reason);
        if (attempt.executedAbilityId != null) { record.put("executedAbilityId", attempt.executedAbilityId); }
        if (attempt.stackId != null) { record.put("stackId", attempt.stackId); }
        game.fireEvent(new AiActionExecutionEvent(record));
    }
}
