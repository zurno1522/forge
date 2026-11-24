package forge.ai;

import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Observes existing heuristic target-pool selections; does not enumerate or filter legal targets. */
public final class AiTargetChoiceRecorder {
    static final int MAX_CHOICES = 128;
    static final int MAX_REFERENCES = 256;
    private AiTargetChoiceRecorder() { }

    public static <T extends GameObject, C extends Iterable<T>> T choose(final Player chooser, final SpellAbility ability,
            final C options, final String boundary, final Function<C, T> selection) {
        return select(chooser, ability, options, boundary, selection).value();
    }

    /** A result token carries the specific invocation to the existing target-addition site. */
    public static final class Selection<T extends GameObject> {
        private final T value;
        private final AiDecisionRecorder.TargetChoice observation;
        private boolean provenanceConsumed;
        private Selection(T value, AiDecisionRecorder.TargetChoice observation) { this.value = value; this.observation = observation; }
        public T value() { return value; }
        public boolean addToTargets(final SpellAbility ability) { return addToTargets(ability, value); }
        public boolean addToTargets(final SpellAbility ability, final T actual) {
            AiDecisionRecorder.TargetAddition addition = null;
            if (observation != null && !provenanceConsumed) {
                provenanceConsumed = true;
                try { addition = observation.prepareAddition(ability, actual, value); }
                catch (RuntimeException failure) { observation.captureFailed(failure); }
            }
            final boolean added = ability.getTargets().add(actual);
            if (added && addition != null) {
                try { addition.added(); }
                catch (RuntimeException failure) { observation.captureFailed(failure); }
            }
            return added;
        }
    }

    public static <T extends GameObject> Selection<T> unobserved(final T value) { return new Selection<>(value, null); }

    /** Observe an existing branch result against its existing pool immediately before its original addition. */
    public static <T extends GameObject, C extends Iterable<T>> boolean addSelected(final Player chooser,
            final SpellAbility ability, final C options, final String boundary, final T selected) {
        return selectedFrom(chooser, ability, options, boundary, selected).addToTargets(ability);
    }

    public static <T extends GameObject, C extends Iterable<T>> Selection<T> selectedFrom(final Player chooser,
            final SpellAbility ability, final C options, final String boundary, final T selected) {
        final Selection<T> result = select(chooser, ability, options, boundary, ignored -> selected, "AT_TARGET_ADDITION");
        if (result.observation != null) { result.observation.atTargetAddition(); }
        return result;
    }

    public static <T extends GameObject, C extends Iterable<T>> Selection<T> select(final Player chooser, final SpellAbility ability,
            final C options, final String boundary, final Function<C, T> selection) {
        return select(chooser, ability, options, boundary, selection, "BEFORE_CHOOSER");
    }

    private static <T extends GameObject, C extends Iterable<T>> Selection<T> select(final Player chooser, final SpellAbility ability,
            final C options, final String boundary, final Function<C, T> selection, final String timing) {
        AiDecisionRecorder.TargetChoice observation = null;
        AiDecisionRecorder recorder = null;
        if (chooser.getGame().isRecordingAiDecisions() && chooser.getController() instanceof PlayerControllerAi ai) {
            recorder = ai.getAi().evaluationRecorder();
            if (recorder != null) {
                try { observation = recorder.beginTargetChoice(ability, options, boundary); }
                catch (RuntimeException failure) { recorder.captureFailed(failure); }
            }
        }
        if (recorder == null && chooser.getGame().isRecordingAiDecisions() && ability.usesTargeting()) {
            return new Selection<>(AiChoiceRecorder.targetPool(chooser, ability, options, boundary, timing, selection), null);
        }
        final T result;
        try { result = selection.apply(options); }
        catch (RuntimeException | Error failure) {
            if (observation != null) { observation.failed(); }
            throw failure;
        }
        if (observation != null) {
            try { observation.complete(result); }
            catch (RuntimeException failure) { observation.captureFailed(failure); }
        }
        return new Selection<>(result, observation);
    }

    record References(List<Map<String, Object>> values, boolean truncated) { }

    static References references(final Iterable<? extends GameObject> objects, final BooleanSupplier active) {
        return references(objects, active, (object, reference) -> { });
    }

    static References references(final Iterable<? extends GameObject> objects, final BooleanSupplier active,
            final java.util.function.BiConsumer<GameObject, Map<String, Object>> captured) {
        if (!active.getAsBoolean()) { return null; }
        final List<Map<String, Object>> result = new ArrayList<>();
        final var iterator = objects.iterator();
        while (active.getAsBoolean()) {
            if (!iterator.hasNext()) { return new References(result, false); }
            if (result.size() == MAX_REFERENCES) { return new References(result, true); }
            final GameObject object = iterator.next();
            if (object == null) { throw new IllegalArgumentException("Null target-pool entry"); }
            final Map<String, Object> reference = reference(object);
            result.add(reference);
            captured.accept(object, reference);
        }
        return null;
    }

    static Map<String, Object> reference(final GameObject object) {
        if (object == null) { return null; }
        final Map<String, Object> ref = new LinkedHashMap<>();
        if (object instanceof Card card) {
            ref.put("type", "CARD");
            ref.put("id", card.getId());
            ref.put("gameTimestamp", card.getGameTimestamp());
        } else if (object instanceof Player player) {
            ref.put("type", "PLAYER");
            ref.put("id", player.getId());
        } else if (object instanceof SpellAbility ability) {
            ref.put("type", "SPELL_ABILITY");
            ref.put("id", ability.getId());
            final var stack = ability.getHostCard().getGame().getStack().getInstanceMatchingSpellAbilityID(ability);
            ref.put("stackId", stack == null ? null : stack.getId());
        } else {
            throw new IllegalArgumentException("Unsupported observed target reference");
        }
        return ref;
    }
}
