package forge.ai;

import forge.game.Game;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.EventEmissionObserver;
import forge.game.spellability.SpellAbility;
import forge.util.AITimeoutTracker;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Real-game action attempts. Object aliases are explicit; names/descriptions are never join keys. */
public final class AiActionTracker implements EventEmissionObserver {
    private final Game game;
    private final EventEmissionObserver.Subscription observation;
    private final List<Attempt> actions = new ArrayList<>();
    private boolean retired;
    private Object selectionGeneration = new Object();

    synchronized void retire() {
        retired = true;
        actions.clear();
        if (observation != null) { observation.close(); }
    }

    static final class Attempt {
        private final Map<String, Object> identity;
        private final Set<SpellAbility> aliases = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean started;
        private int activationsBefore;
        private Long executedAbilityId;
        private Long stackId;
        private AiTargetProvenance targetProvenance;

        private Attempt(final Map<String, Object> identity) { this.identity = identity; }
    }

    AiActionTracker(final Game game) {
        this.game = game;
        observation = game.observeEventEmission(this);
    }

    @Override public void capture(final forge.game.event.Event event) {
        if (event instanceof GameEventSpellAbilityCast cast) { stacked(cast); }
    }

    @Override public void close() { retire(); }

    static final class PreparedSelection {
        private final Object scope;
        private final List<Attempt> attempts;
        private PreparedSelection(final Object scope, final List<Attempt> attempts) {
            this.scope = scope; this.attempts = List.copyOf(attempts);
        }
    }

    static final class Registration {
        private final List<Attempt> attempts;
        private Registration(final List<Attempt> attempts) { this.attempts = attempts; }
    }

    synchronized Object selectionScope() {
        return retired || !game.isRecordingAiDecisions() ? null : selectionGeneration;
    }

    private boolean currentScope(final Object scope) {
        return scope != null && !retired && game.isRecordingAiDecisions() && selectionGeneration == scope;
    }

    PreparedSelection prepareSelected(final Object scope, final AiDecisionEvent event,
            final List<SpellAbility> chosen, final AiDecisionRecorder recorder) {
        synchronized (this) { if (!currentScope(scope)) { return null; } }
        final List<Attempt> prepared = new ArrayList<>();
        try {
            final List<SpellAbility> actual = chosen == null ? List.of() : List.copyOf(chosen);
            final List<?> selected = (List<?>) event.data().get("selectedActions");
            for (int index = 0; index < actual.size(); index++) {
                final Map<?, ?> action = (Map<?, ?>) selected.get(index);
                final Map<String, Object> identity = new LinkedHashMap<>();
                identity.put("executionSchemaVersion", 1);
                identity.put("decisionId", event.data().get("decisionId"));
                identity.put("playerId", event.data().get("playerId"));
                for (final String key : List.of("actionId", "abilityId", "sourceId", "kind")) {
                    identity.put(key, action.get(key));
                }
                final Attempt attempt = new Attempt(identity);
                attempt.aliases.add(actual.get(index));
                attempt.targetProvenance = recorder.targetProvenance(actual.get(index));
                prepared.add(attempt);
            }
        } catch (RuntimeException failure) {
            if (failure instanceof AITimeoutTracker.Expired) { throw failure; }
            synchronized (this) { if (!currentScope(scope)) { return null; } }
            throw failure;
        }
        return new PreparedSelection(scope, prepared);
    }

    synchronized Registration commitSelected(final PreparedSelection prepared) {
        if (prepared == null || !currentScope(prepared.scope)) { return null; }
        actions.addAll(prepared.attempts);
        selectionGeneration = new Object();
        return new Registration(prepared.attempts);
    }

    synchronized void rollbackSelected(final Registration registration) {
        if (registration == null) { return; }
        // Publication failure cannot undo an execution that a synchronous consumer already started.
        for (final Attempt attempt : registration.attempts) { if (!attempt.started) { actions.remove(attempt); } }
    }

    synchronized Attempt begin(final SpellAbility sa) {
        if (retired || !game.isRecordingAiDecisions()) { return null; }
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

    private Attempt startedAttempt(final SpellAbility actual) {
        if (!game.isRecordingAiDecisions() || actual == null || actual.isCopied() || actual.isTrigger()) { return null; }
        // MagicStack's execution copy points directly to the selected/transformed object.
        // Walking farther would cross rules-grant ancestry and admit unselected grants.
        for (final Attempt attempt : actions) {
            if (attempt.started && (attempt.aliases.contains(actual)
                    || attempt.aliases.contains(actual.getImmediateOriginalAbility()))) { return attempt; }
        }
        return null;
    }

    synchronized AiActionReference referenceFor(final SpellAbility actual) {
        return reference(startedAttempt(actual));
    }

    synchronized Map<String, Object> targetChoiceProvenanceFor(final SpellAbility actual) {
        final Attempt attempt = startedAttempt(actual);
        return attempt == null ? null : attempt.targetProvenance.capture(actual);
    }

    synchronized AiActionReference landReferenceFor(final long sourceId) {
        if (!game.isRecordingAiDecisions()) { return null; }
        for (final Attempt attempt : actions) {
            if (attempt.started && "LAND".equals(attempt.identity.get("kind"))
                    && Long.valueOf(sourceId).equals(attempt.identity.get("sourceId"))) {
                return reference(attempt);
            }
        }
        return null;
    }

    private static AiActionReference reference(final Attempt attempt) {
        return attempt == null ? null : new AiActionReference(
                (String) attempt.identity.get("decisionId"), (String) attempt.identity.get("actionId"));
    }

    private synchronized void stacked(final GameEventSpellAbilityCast event) {
        if (!game.isRecordingAiDecisions()) { return; }
        for (final var item : game.getStack()) {
            if (item.getId() != event.si().getId()) { continue; }
            final SpellAbility actual = item.getSpellAbility();
            final Attempt attempt = startedAttempt(actual);
            if (attempt != null) {
                attempt.executedAbilityId = actual.getId();
                attempt.stackId = item.getId();
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
        selectionGeneration = new Object();
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
