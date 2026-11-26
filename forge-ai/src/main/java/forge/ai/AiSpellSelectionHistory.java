package forge.ai;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Detached, bounded receipts belonging to one admitted base-controller operation. */
final class AiSpellSelectionHistory {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private AiSpellSelectionHistory() { }

    record Input(int requestedSelections, Long attemptIndex, Long priorNonNullReturnCount, int retainedCount,
            String progressReason, String receiptCoverage, String receiptReason, List<Map<String, Object>> receipts) {
        boolean complete() { return progressReason == null && "COMPLETE".equals(receiptCoverage); }
        String omission() { return progressReason == null ? receiptReason : progressReason; }
        Map<String, Object> diagnostics() {
            final Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", 1); value.put("progressCoverage", progressReason == null ? "COMPLETE" : "UNAVAILABLE");
            value.put("progressReason", progressReason); value.put("attemptIndex", attemptIndex);
            value.put("priorNonNullReturnCount", priorNonNullReturnCount); value.put("retainedCount", retainedCount);
            value.put("requestedSelections", requestedSelections); value.put("receiptCoverage", receiptCoverage);
            value.put("receiptReason", receiptReason); value.put("priorReturns", receipts); return value;
        }
    }

    record Prompt(AtomicReference<Input> input, AiPromptInput.SpellRequest fallback) implements Supplier<AiPromptInput> {
        @Override public AiPromptInput get() {
            final var request = fallback.get(); final var progress = input.get();
            return progress == null || request.kind() == AiPromptInput.Kind.UNSUPPORTED_REQUEST ? request
                    : new AiPromptInput(AiPromptInput.Kind.SPELL_ABILITY_PICK_PROGRESS, 0, 0, null, false,
                            request.relatedPlayer(), false, null, null, null, null, progress);
        }
    }

    static <T> T operation(final Player chooser, final SpellAbility ability, final PlayerControllerAi receiver,
            final int requestedSelections, final Map<String, Object> params, final AiChoiceRecorder.Capture owner,
            final Supplier<T> callback) {
        final Scope previous = ACTIVE.get(); Scope current = null;
        if (owner != null && owner.open() && receiver != null && receiver.getClass() == PlayerControllerAi.class) {
            try {
                if (chooser.getController() == receiver && owner.open()) {
                    current = new Scope(chooser, ability, receiver, requestedSelections, params, owner);
                }
            } catch (RuntimeException failure) { owner.failure(failure); }
        }
        if (current == null) { ACTIVE.remove(); } else { ACTIVE.set(current); }
        try { return callback.get(); }
        finally {
            if (current != null) { current.retire(); }
            if (previous == null) { ACTIVE.remove(); } else { ACTIVE.set(previous); }
        }
    }

    private static Scope scope(final Player chooser, final SpellAbility ability, final PlayerControllerAi receiver) {
        final Scope value = ACTIVE.get();
        return value != null && !value.retired && value.chooser == chooser && value.ability == ability && value.receiver == receiver ? value : null;
    }
    static void beforePick(final Player chooser, final SpellAbility ability, final PlayerControllerAi receiver,
            final List<SpellAbility> offered, final Map<String, Object> params, final int retainedCount) {
        final Scope value = scope(chooser, ability, receiver);
        if (value != null) { value.before(offered, params, retainedCount); }
    }
    static Input claim(final Player chooser, final SpellAbility ability, final PlayerControllerAi receiver,
            final List<SpellAbility> offered, final Map<String, Object> params) {
        final Scope value = scope(chooser, ability, receiver);
        return value == null ? null : value.claim(offered, params);
    }
    static void returned(final Player chooser, final SpellAbility ability, final PlayerControllerAi receiver,
            final SpellAbility returned, final int retainedCount) {
        final Scope value = scope(chooser, ability, receiver);
        if (value != null) { value.returned(returned, retainedCount); }
    }

    private static final class Scope {
        private final Player chooser;
        private final SpellAbility ability;
        private final PlayerControllerAi receiver;
        private final int requestedSelections;
        private final AiChoiceRecorder.Capture owner;
        private Map<String, Object> params;
        private List<SpellAbility> offered;
        private final List<Map<String, Object>> receipts = new ArrayList<>();
        private long attemptIndex = -1;
        private long nonNullReturns;
        private boolean attemptKnown = true;
        private boolean returnsKnown = true;
        private int retainedCount;
        private String progressReason;
        private String receiptReason;
        private boolean pending;
        private boolean claimable;
        private boolean witnessed;
        private boolean retired;

        Scope(final Player chooser, final SpellAbility ability, final PlayerControllerAi receiver,
                final int requestedSelections, final Map<String, Object> params, final AiChoiceRecorder.Capture owner) {
            this.chooser = chooser; this.ability = ability; this.receiver = receiver;
            this.requestedSelections = requestedSelections; this.params = params; this.owner = owner;
        }
        private void retire() { retired = true; params = null; offered = null; receipts.clear(); }
        private boolean active() {
            if (retired || ACTIVE.get() != this) { return false; }
            if (!owner.open() || Thread.currentThread().isInterrupted()) { retire(); return false; }
            try {
                if (chooser.getController() != receiver) { retire(); return false; }
            } catch (RuntimeException failure) { owner.failure(failure); retire(); return false; }
            if (!owner.open()) { retire(); return false; }
            return true;
        }
        private void unsupported() {
            if (progressReason == null) { progressReason = "UNSUPPORTED"; }
            if (receiptReason == null) { receiptReason = "UNSUPPORTED"; }
        }
        private void before(final List<SpellAbility> offered, final Map<String, Object> params, final int retainedCount) {
            if (!active()) { return; }
            if (retainedCount < 0) { retire(); return; }
            if (pending) { unsupported(); returnsKnown = false; }
            if (params != this.params || retainedCount != this.retainedCount) { unsupported(); }
            if (attemptIndex == Long.MAX_VALUE) {
                attemptKnown = false; progressReason = "COUNTER_OVERFLOW"; receiptReason = "COUNTER_OVERFLOW";
            } else if (attemptKnown) { attemptIndex++; }
            if (attemptKnown && returnsKnown && (attemptIndex != nonNullReturns || nonNullReturns != retainedCount)) { unsupported(); }
            this.offered = offered; this.retainedCount = retainedCount; pending = true; claimable = true; witnessed = false;
        }
        private Input claim(final List<SpellAbility> offered, final Map<String, Object> params) {
            if (!active() || !claimable || offered != this.offered || params != this.params) { return null; }
            claimable = false; witnessed = true;
            final String coverage = receiptReason != null ? "UNAVAILABLE" : nonNullReturns > receipts.size() ? "PREFIX" : "COMPLETE";
            return new Input(requestedSelections, attemptKnown ? attemptIndex : null, returnsKnown ? nonNullReturns : null,
                    retainedCount, progressReason, coverage,
                    receiptReason != null ? receiptReason : "PREFIX".equals(coverage) ? "OUTSIDE_CAPTURED_PREFIX" : null,
                    List.copyOf(receipts));
        }
        private void returned(final SpellAbility value, final int retainedCount) {
            if (!active()) { return; }
            if (retainedCount < 0) { retire(); return; }
            if (!pending) { unsupported(); returnsKnown = false; }
            if (!witnessed && receiptReason == null) { receiptReason = "UNSUPPORTED"; }
            pending = false; claimable = false; offered = null;
            if ((long) retainedCount != (long) this.retainedCount + (value == null ? 0 : 1)) { unsupported(); }
            this.retainedCount = retainedCount;
            if (value == null) { return; }
            if (nonNullReturns == Long.MAX_VALUE) {
                returnsKnown = false; progressReason = "COUNTER_OVERFLOW"; receiptReason = "COUNTER_OVERFLOW";
            } else if (returnsKnown) { nonNullReturns++; }
            if (receiptReason != null || !returnsKnown || nonNullReturns > AiInputContext.MAX_PROMPT_VALUES) { return; }
            try {
                final Map<String, Object> reference = AiTargetChoiceRecorder.reference(value);
                if (!active()) { return; }
                receipts.add(reference);
            } catch (RuntimeException failure) {
                if (!active()) { return; }
                receiptReason = "CAPTURE_FAILED"; owner.failure(failure);
            }
        }
    }
}
