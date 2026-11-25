package forge.ai;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.player.ZoneChangeSelectionObserver;
import forge.game.player.ZoneChangeSelectionRequest;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/** Facts owned by one audited engine callback, never a cross-operation history registry. */
final class AiZoneSelectionHistory {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private AiZoneSelectionHistory() { }

    record Input(ZoneChangeSelectionRequest operation, boolean optional, Long attemptIndex, int loopPosition,
            Long priorNonNullReturnCount, int retainedCount, String progressReason, String receiptCoverage,
            String receiptReason, List<AiOptionInput.Entry> receipts) {
        boolean complete() { return progressReason == null && "COMPLETE".equals(receiptCoverage); }
        String omission() { return progressReason == null ? receiptReason : progressReason; }
        Map<String, Object> diagnostics() {
            final Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", 1); value.put("progressCoverage", progressReason == null ? "COMPLETE" : "UNAVAILABLE");
            value.put("progressReason", progressReason); value.put("attemptIndex", attemptIndex);
            value.put("loopPosition", loopPosition); value.put("priorNonNullReturnCount", priorNonNullReturnCount);
            value.put("retainedCount", retainedCount); value.put("requestedIterations", operation.requestedIterations());
            value.put("receiptCoverage", receiptCoverage); value.put("receiptReason", receiptReason);
            value.put("priorReturns", receipts.stream().map(AiOptionInput.Entry::reference).toList());
            return value;
        }
    }

    /** Both branches have audited planning permission; an incomplete claim never uses the fallback. */
    record Prompt(AtomicReference<Input> input, AiPromptInput.ZoneRequest fallback) implements Supplier<AiPromptInput> {
        @Override public AiPromptInput get() {
            final Input value = input.get();
            return value == null ? fallback.get() : AiPromptInput.zoneProgress(value, fallback);
        }
    }

    static CardCollection operation(final Player chooser, final PlayerControllerAi receiver,
            final ZoneChangeSelectionRequest request, final AiChoiceRecorder.Capture owner,
            final Function<ZoneChangeSelectionObserver, CardCollection> callback) {
        final Scope previous = ACTIVE.get(); Scope current = null;
        if (owner != null && owner.open() && receiver != null && receiver.getClass() == PlayerControllerAi.class) {
            try {
                if (chooser.getController() == receiver && owner.open()) { current = new Scope(chooser, receiver, request, owner); }
            } catch (RuntimeException failure) { owner.failure(failure); }
        }
        if (current == null) { ACTIVE.remove(); } else { ACTIVE.set(current); }
        try { return callback.apply(current == null ? ZoneChangeSelectionObserver.NONE : current); }
        finally {
            if (current != null) { current.retire(); }
            if (previous == null) { ACTIVE.remove(); } else { ACTIVE.set(previous); }
        }
    }

    private static Scope scope(final Player chooser, final SpellAbility ability) {
        final Scope value = ACTIVE.get();
        return value != null && !value.retired && value.chooser == chooser && value.request.ability() == ability ? value : null;
    }
    static boolean present(final Player chooser, final SpellAbility ability) { return scope(chooser, ability) != null; }
    static Input claim(final Player chooser, final SpellAbility ability, final ZoneType destination,
            final List<ZoneType> origins, final CardCollection offered, final boolean optional, final Player decider) {
        final Scope value = scope(chooser, ability);
        return value == null ? null : value.claim(destination, origins, offered, optional, decider);
    }

    private static final class Scope implements ZoneChangeSelectionObserver {
        private final Player chooser;
        private final PlayerControllerAi receiver;
        private final ZoneChangeSelectionRequest request;
        private final AiChoiceRecorder.Capture owner;
        private final List<AiOptionInput.Entry> receipts = new ArrayList<>();
        private long attemptIndex = -1;
        private long nonNullReturns;
        private boolean attemptKnown = true;
        private boolean returnsKnown = true;
        private String progressReason;
        private String receiptReason;
        private CardCollection offered;
        private boolean optional;
        private int loopPosition;
        private int retainedCount;
        private boolean claimable;
        private boolean awaitingReturn;
        private boolean witnessed;
        private boolean retired;

        Scope(final Player chooser, final PlayerControllerAi receiver, final ZoneChangeSelectionRequest request,
                final AiChoiceRecorder.Capture owner) {
            this.chooser = chooser; this.receiver = receiver; this.request = request; this.owner = owner;
        }
        private void retire() { retired = true; offered = null; receipts.clear(); }
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
        @Override public void beforeSelector(final CardCollection offered, final boolean optional,
                final int loopPosition, final int retainedCount) {
            if (!active()) { return; }
            if (loopPosition < 0 || retainedCount < 0) { retire(); return; }
            if (awaitingReturn) { unsupported(); returnsKnown = false; }
            if (attemptIndex == Long.MAX_VALUE) {
                attemptKnown = false; progressReason = "COUNTER_OVERFLOW"; receiptReason = "COUNTER_OVERFLOW";
            } else if (attemptKnown) { attemptIndex++; }
            this.offered = offered; this.optional = optional; this.loopPosition = loopPosition; this.retainedCount = retainedCount;
            claimable = true; awaitingReturn = true; witnessed = false;
        }
        private Input claim(final ZoneType destination, final List<ZoneType> origins, final CardCollection offered,
                final boolean optional, final Player decider) {
            if (!active() || !claimable || offered != this.offered || optional != this.optional
                    || destination != request.destination() || origins != request.origins() || decider != request.decider()) { return null; }
            claimable = false; witnessed = true;
            final String coverage = receiptReason != null ? "UNAVAILABLE" : nonNullReturns > receipts.size() ? "PREFIX" : "COMPLETE";
            return new Input(request, optional, attemptKnown ? attemptIndex : null, loopPosition,
                    returnsKnown ? nonNullReturns : null, retainedCount, progressReason, coverage,
                    receiptReason != null ? receiptReason : "PREFIX".equals(coverage) ? "OUTSIDE_CAPTURED_PREFIX" : null,
                    List.copyOf(receipts));
        }
        @Override public void returned(final Card card, final int retainedCount) {
            if (!active()) { return; }
            if (!awaitingReturn) { unsupported(); returnsKnown = false; }
            if (!witnessed && receiptReason == null) { receiptReason = "UNSUPPORTED"; }
            awaitingReturn = false; claimable = false; offered = null;
            if (card == null) { return; }
            if (nonNullReturns == Long.MAX_VALUE) {
                returnsKnown = false; progressReason = "COUNTER_OVERFLOW"; receiptReason = "COUNTER_OVERFLOW";
            } else if (returnsKnown) { nonNullReturns++; }
            if (receiptReason != null || !returnsKnown || nonNullReturns > AiInputContext.MAX_PROMPT_VALUES) { return; }
            try {
                final Map<String, Object> reference = AiTargetChoiceRecorder.reference(card);
                if (!active()) { return; }
                receipts.add(new AiOptionInput.Entry(card, reference));
            } catch (RuntimeException failure) {
                if (!active()) { return; }
                receiptReason = "CAPTURE_FAILED"; owner.failure(failure);
            }
        }
    }
}
