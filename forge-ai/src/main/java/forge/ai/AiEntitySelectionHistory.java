package forge.ai;

import forge.game.GameEntity;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Bounded receipts owned by one admitted controller operation, never a history registry. */
final class AiEntitySelectionHistory {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private AiEntitySelectionHistory() { }

    record Input(int minimum, int maximum, int priorCount, List<AiOptionInput.Entry> receipts,
            String coverage, String reason, boolean optional, Player relatedPlayer) {
        Map<String, Object> diagnostics() {
            final Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", 1); value.put("coverage", coverage);
            value.put("minimumTotal", minimum); value.put("maximumTotal", maximum);
            value.put("priorSelectionCount", priorCount);
            value.put("priorSelections", receipts.stream().map(AiOptionInput.Entry::reference).toList());
            value.put("reason", reason);
            return value;
        }
    }

    /** Snapshot is filled only by the visible selector's diagnostic capture, before prompt projection. */
    record Prompt(AtomicReference<Input> input, boolean optional, Player relatedPlayer) implements Supplier<AiPromptInput> {
        boolean sequenced() { return input.get() != null; }
        @Override public AiPromptInput get() {
            final Input value = input.get();
            return value == null ? AiPromptInput.single(optional, relatedPlayer) : AiPromptInput.sequence(value);
        }
    }

    static <T> T operation(final Player chooser, final SpellAbility ability, final PlayerControllerAi receiver,
            final int min, final int max, final Player relatedPlayer, final AiChoiceRecorder.Capture owner,
            final Supplier<T> callback) {
        final Scope previous = ACTIVE.get();
        Scope current = null;
        if (owner != null && owner.open() && receiver != null && receiver.getClass() == PlayerControllerAi.class) {
            try {
                if (chooser.getController() == receiver && owner.open()) {
                    current = new Scope(chooser, ability, receiver, min, max, relatedPlayer, owner);
                }
            } catch (RuntimeException failure) { owner.failure(failure); }
        }
        // Even an unrecorded/unsupported nested operation masks an older frame.
        if (current == null) { ACTIVE.remove(); } else { ACTIVE.set(current); }
        try { return callback.get(); }
        finally {
            if (current != null) { current.retire(); }
            if (previous == null) { ACTIVE.remove(); } else { ACTIVE.set(previous); }
        }
    }

    private static Scope scope(final Player chooser, final SpellAbility ability) {
        final Scope scope = ACTIVE.get();
        return scope != null && scope.chooser == chooser && scope.ability == ability && !scope.retired ? scope : null;
    }

    static boolean present(final Player chooser, final SpellAbility ability) { return scope(chooser, ability) != null; }

    static void beforePick(final Player chooser, final SpellAbility ability, final Iterable<?> remaining,
            final int count, final boolean optional, final Player relatedPlayer) {
        final Scope scope = scope(chooser, ability);
        if (scope != null) { scope.before(remaining, count, optional, relatedPlayer); }
    }

    static Input claim(final Player chooser, final SpellAbility ability, final Iterable<?> options,
            final boolean optional, final Player relatedPlayer) {
        final Scope scope = scope(chooser, ability);
        return scope == null ? null : scope.claim(options, optional, relatedPlayer);
    }

    static void returned(final Player chooser, final SpellAbility ability, final GameEntity value, final int count) {
        final Scope scope = scope(chooser, ability);
        if (scope != null) { scope.returned(value, count); }
    }

    private static final class Scope {
        private final Player chooser;
        private final SpellAbility ability;
        private final PlayerControllerAi receiver;
        private final int minimum;
        private final int maximum;
        private final Player relatedPlayer;
        private final AiChoiceRecorder.Capture owner;
        private final List<AiOptionInput.Entry> receipts = new ArrayList<>();
        private int count;
        private Iterable<?> remaining;
        private boolean optional;
        private boolean pending;
        private boolean witnessed;
        private boolean retired;
        private String failureReason;

        Scope(final Player chooser, final SpellAbility ability, final PlayerControllerAi receiver,
                final int minimum, final int maximum, final Player relatedPlayer, final AiChoiceRecorder.Capture owner) {
            this.chooser = chooser; this.ability = ability; this.receiver = receiver;
            this.minimum = minimum; this.maximum = maximum; this.relatedPlayer = relatedPlayer; this.owner = owner;
        }

        private void retire() { retired = true; receipts.clear(); remaining = null; }

        private boolean active() {
            if (retired) { return false; }
            if (!owner.open() || Thread.currentThread().isInterrupted()) { retire(); return false; }
            try {
                if (chooser.getController() != receiver) { retire(); return false; }
            } catch (RuntimeException failure) {
                owner.failure(failure); retire(); return false;
            }
            if (!owner.open()) { retire(); return false; }
            return true;
        }

        private void before(final Iterable<?> remaining, final int count, final boolean optional, final Player related) {
            if (!active()) { return; }
            if (count != this.count || optional != (count >= minimum) || related != relatedPlayer) { unproved(); }
            this.count = count;
            if (receipts.size() > count) { receipts.clear(); unproved(); }
            this.remaining = remaining; this.optional = optional; pending = true; witnessed = false;
        }

        private Input claim(final Iterable<?> options, final boolean optional, final Player related) {
            if (!active() || !pending || options != remaining || optional != this.optional || related != relatedPlayer) { return null; }
            pending = false; witnessed = true;
            final String coverage = failureReason != null ? "UNAVAILABLE"
                    : count > receipts.size() ? "PREFIX" : "COMPLETE";
            return new Input(minimum, maximum, count, List.copyOf(receipts), coverage,
                    failureReason != null ? failureReason : "PREFIX".equals(coverage) ? "OUTSIDE_CAPTURED_PREFIX" : null,
                    optional, related);
        }

        private void unproved() { if (failureReason == null) { failureReason = "UNSUPPORTED"; } }

        private void returned(final GameEntity value, final int count) {
            if (!active()) { return; }
            if (value == null || count != this.count + 1 || !witnessed) { unproved(); }
            this.count = count;
            if (receipts.size() > count) { receipts.clear(); unproved(); }
            if (failureReason != null || count > AiInputContext.MAX_PROMPT_VALUES) { return; }
            try {
                final Map<String, Object> reference = AiTargetChoiceRecorder.reference(value);
                if (!active()) { return; }
                receipts.add(new AiOptionInput.Entry(value, reference));
            } catch (RuntimeException failure) {
                if (!active()) { return; }
                failureReason = "CAPTURE_FAILED";
                owner.failure(failure);
            }
        }
    }
}
