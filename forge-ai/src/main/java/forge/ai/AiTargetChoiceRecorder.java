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
        AiDecisionRecorder.TargetChoice observation = null;
        if (chooser.getGame().isRecordingAiDecisions() && chooser.getController() instanceof PlayerControllerAi ai) {
            final AiDecisionRecorder recorder = ai.getAi().evaluationRecorder();
            if (recorder != null) {
                try { observation = recorder.beginTargetChoice(ability, options, boundary); }
                catch (RuntimeException failure) { recorder.captureFailed(failure); }
            }
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
        return result;
    }

    record References(List<Map<String, Object>> values, boolean truncated) { }

    static References references(final Iterable<? extends GameObject> objects, final BooleanSupplier active) {
        if (!active.getAsBoolean()) { return null; }
        final List<Map<String, Object>> result = new ArrayList<>();
        final var iterator = objects.iterator();
        while (active.getAsBoolean()) {
            if (!iterator.hasNext()) { return new References(result, false); }
            if (result.size() == MAX_REFERENCES) { return new References(result, true); }
            final GameObject object = iterator.next();
            if (object == null) { throw new IllegalArgumentException("Null target-pool entry"); }
            result.add(reference(object));
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
