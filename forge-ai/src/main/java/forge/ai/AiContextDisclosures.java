package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardView;
import forge.game.event.Event;
import forge.game.event.EventEmissionObserver;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventPlayerControl;
import forge.game.event.GameEventSpellRemovedFromStack;
import forge.game.event.GameEventSpellResolved;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Bounded, delivered facts for one recipient. These are disclosures, not a cache of current cards.
 * A fact never asserts that an engine object can still be identified after its delivery.
 */
final class AiContextDisclosures {
    static final int MAX_FACTS = 32;
    static final int MAX_TEXT = 256;
    private static final long NO_STACK_SCOPE = -1L;

    record Proof(Card card, long timestamp, ZoneType zone, long epoch) { }

    record Snapshot(List<Map<String, Object>> facts, String coverage, List<Map<String, Object>> omissions,
                    List<Proof> proofs) {
        Snapshot {
            facts = List.copyOf(facts);
            omissions = List.copyOf(omissions);
            proofs = Collections.unmodifiableList(new ArrayList<>(proofs));
        }
    }

    final class Prompt implements AutoCloseable {
        private final long epoch;
        private final boolean direct;
        private boolean finished;

        private Prompt(final long epoch, final boolean direct) {
            this.epoch = epoch;
            this.direct = direct;
        }

        @Override public void close() {
            synchronized (AiContextDisclosures.this) {
                if (finished) { return; }
                finished = true;
                if (direct && epoch == directInterval && directPrompts > 0 && --directPrompts == 0) {
                    resetFacts();
                    directPoisoned = false;
                }
            }
        }
    }

    private final Player recipient;
    private final Game game;
    private final BooleanSupplier acquireWork;
    private final List<Map<String, Object>> facts = new ArrayList<>();
    private final List<Proof> proofs = new ArrayList<>();
    private long evidenceEpoch;
    private final Set<String> missing = new LinkedHashSet<>();
    private EventEmissionObserver.Subscription subscription;
    private boolean observing;
    private volatile boolean closed;
    private long stackScope = NO_STACK_SCOPE;
    private long resolvingAbility = -1L;
    private long poisonedStack = NO_STACK_SCOPE;
    private long generation;
    private long directInterval;
    private int directPrompts;
    private boolean directPoisoned;

    AiContextDisclosures(final Player recipient, final BooleanSupplier acquireWork) {
        this.recipient = recipient;
        game = recipient.getGame();
        this.acquireWork = acquireWork;
    }

    /** Open before actual delayed delivery; nested direct prompts share the outer prompt's facts. */
    Prompt prompt() {
        if (!enabled()) { return new Prompt(-1L, false); }
        if (!supportedRecipient()) { unsupportedRecipient(); return new Prompt(-1L, false); }
        ensureObserver();
        synchronized (this) {
            refresh();
            final boolean direct = stackScope == NO_STACK_SCOPE && !game.getStack().isResolving()
                    && !recipient.isControlled() && !closed;
            if (direct) {
                if (directPrompts == 0) {
                    resetFacts();
                    directInterval++;
                    directPoisoned = false;
                }
                directPrompts++;
            }
            return new Prompt(directInterval, direct);
        }
    }

    void cards(final CardCollectionView cards, final ZoneType zone) {
        delivered(cards, Card::getView, Function.identity(), zone, "CONTROLLER_CARD_REVEAL");
    }

    void views(final List<CardView> cards, final ZoneType zone) {
        delivered(cards, Function.identity(), ignored -> null, zone, "CONTROLLER_VIEW_REVEAL");
    }

    /** Abort the observed interval after a selector error; never carry its partial facts onward. */
    synchronized void abort() {
        poisonedStack = currentStackScope();
        directPoisoned = directPrompts > 0;
        resetFacts();
    }

    /** A delivery adapter failed; partial delivery is unavailable for the rest of its interval. */
    synchronized void failed() {
        abort();
        missing.add("CAPTURE_FAILED");
    }

    Snapshot snapshot() {
        if (!enabled() || !supportedRecipient()) {
            if (enabled()) { unsupportedRecipient(); }
            return new Snapshot(List.of(), "UNSUPPORTED_PRIOR_SCOPE", List.of(omission("UNSUPPORTED")), List.of());
        }
        ensureObserver();
        synchronized (this) {
            refresh();
            final String coverage = coverage();
            final List<Map<String, Object>> omissions = new ArrayList<>();
            if ("UNSUPPORTED_PRIOR_SCOPE".equals(coverage)) { omissions.add(omission("UNSUPPORTED")); }
            for (final String reason : missing) { omissions.add(omission(reason)); }
            return new Snapshot(facts, coverage, omissions, proofs);
        }
    }

    private <T> void delivered(final Iterable<T> supplied, final Function<T, CardView> view,
            final Function<T, Card> original, final ZoneType zone, final String source) {
        // In particular, do not request an iterator or inspect a delivered view in disabled/copy games.
        if (!enabled()) { return; }
        if (!supportedRecipient()) { unsupportedRecipient(); return; }
        ensureObserver();
        final long epoch;
        final String deliveryScope;
        synchronized (this) {
            refresh();
            deliveryScope = coverage();
            if ("UNSUPPORTED_PRIOR_SCOPE".equals(deliveryScope)) { return; }
            epoch = generation;
        }
        final Iterator<T> iterator = supplied.iterator();
        while (iterator.hasNext()) {
            synchronized (this) {
                if (closed || epoch != generation || !enabled()) { return; }
                if (Thread.currentThread().isInterrupted()) { missing.add("INTERRUPTED"); return; }
                if (facts.size() == MAX_FACTS) { missing.add("OUTSIDE_CAPTURED_PREFIX"); return; }
            }
            if (!acquireWork.getAsBoolean()) {
                synchronized (this) {
                    if (!closed && epoch == generation) { missing.add("BUDGET_EXHAUSTED"); }
                }
                return;
            }
            // Reading the presented face happens outside the publication monitor, and never findByView().
            final T item = iterator.next();
            final Card card = original.apply(item);
            final long proofEpoch;
            synchronized (this) { proofEpoch = evidenceEpoch; }
            final long timestamp = card == null ? -1 : card.getGameTimestamp();
            final CardView presented = view.apply(item);
            final CardView.CardStateView face = presented.getCurrentState();
            final boolean faceDown = presented.isFaceDown();
            final String name = face.getName();
            final String types = face.getType().toString();
            final String cost = face.getManaCost().toString();
            final Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("source", source);
            fact.put("scope", deliveryScope);
            fact.put("zone", zone == null ? null : zone.name());
            fact.put("presentedFace", face.getState().name());
            fact.put("name", bounded(name));
            fact.put("types", bounded(types));
            fact.put("manaCost", bounded(cost));
            fact.put("currentObjectAssociation", "UNRESOLVED");
            final Map<String, Object> frozen = Collections.unmodifiableMap(fact);
            final Proof proof = card != null && card.getGame() == game && publicZone(zone) && !faceDown && !card.isLKI()
                    && card.getZone() != null && card.getZone().getZoneType() == zone
                    ? new Proof(card, timestamp, zone, proofEpoch) : null;
            synchronized (this) {
                if (closed || epoch != generation || !enabled()) { return; }
                if (facts.size() == MAX_FACTS) { missing.add("OUTSIDE_CAPTURED_PREFIX"); return; }
                facts.add(frozen);
                proofs.add(proof);
                if (name.length() > MAX_TEXT || types.length() > MAX_TEXT || cost.length() > MAX_TEXT) {
                    missing.add("OUTSIDE_CAPTURED_PREFIX");
                }
                // The delivery API does not say which inspectable alternate face a UI presented.
                // Preserve the supplied current face, and do not certify richer face-down disclosure.
                if (faceDown) { missing.add("UNSUPPORTED"); }
            }
        }
    }

    private boolean enabled() {
        return !closed && game.isRecordingAiDecisions();
    }

    private boolean supportedRecipient() {
        return !recipient.isControlled() && !game.getRules().hasSharedTeamTurns();
    }

    private synchronized void unsupportedRecipient() {
        poisonedStack = stackScope;
        directPrompts = 0;
        directInterval++;
        directPoisoned = false;
        resetFacts();
    }

    private void ensureObserver() {
        synchronized (this) {
            if (observing || closed || !game.isRecordingAiDecisions()) { return; }
            observing = true;
        }
        final EventEmissionObserver.Subscription registered = game.observeEventEmission(new EventEmissionObserver() {
            @Override public void capture(final Event event) { onEvent(event); }
            @Override public void close() { closeScope(); }
        });
        synchronized (this) {
            if (closed || !game.isRecordingAiDecisions()) { registered.close(); closeScope(); }
            else { subscription = registered; }
        }
    }

    private synchronized void onEvent(final Event event) {
        if (event instanceof forge.game.event.GameEvent) { evidenceEpoch++; }
        if (event instanceof GameEventSpellResolved resolved
                && resolved.spell() != null && resolved.spell().getId() == resolvingAbility
                || event instanceof GameEventSpellRemovedFromStack removed
                && removed.disposition() == GameEventSpellRemovedFromStack.Disposition.FINAL
                && (removed.stackId() == null || removed.stackId() == stackScope)
                || event instanceof GameEventPlayerControl control && control.player().getId() == recipient.getId()) {
            poisonedStack = stackScope;
            directPrompts = 0;
            directInterval++;
            directPoisoned = false;
            resetFacts();
        } else if (event instanceof GameEventGameFinished) {
            closeScope();
        }
    }

    private synchronized void closeScope() {
        closed = true;
        directPrompts = 0;
        directInterval++;
        directPoisoned = false;
        resetFacts();
        if (subscription != null) { subscription.close(); subscription = null; }
    }

    private void refresh() {
        final long current = currentStackScope();
        if (current != stackScope) {
            stackScope = current;
            final SpellAbility ability = game.getStack().getResolvingAbility();
            resolvingAbility = current == NO_STACK_SCOPE || ability == null ? -1L : ability.getId();
            directPrompts = 0;
            directInterval++;
            directPoisoned = false;
            resetFacts();
        }
        if (recipient.isControlled()) {
            poisonedStack = stackScope;
            directPrompts = 0;
            directInterval++;
            directPoisoned = false;
            resetFacts();
            missing.add("UNSUPPORTED");
        }
    }

    /** The resolving top instance supplies the interval identity; an ability ID alone never does. */
    private long currentStackScope() {
        if (!game.getStack().isResolving()) { return NO_STACK_SCOPE; }
        final SpellAbility resolving = game.getStack().getResolvingAbility();
        final SpellAbilityStackInstance top = game.getStack().peek();
        return resolving != null && top != null && top.getSpellAbility().getId() == resolving.getId()
                ? top.getId() : NO_STACK_SCOPE;
    }

    private String coverage() {
        if (closed || recipient.isControlled()) { return "UNSUPPORTED_PRIOR_SCOPE"; }
        if (stackScope != NO_STACK_SCOPE && stackScope != poisonedStack) { return "STACK_RESOLUTION"; }
        return directPrompts > 0 && !directPoisoned && !game.getStack().isResolving()
                ? "CURRENT_PROMPT" : "UNSUPPORTED_PRIOR_SCOPE";
    }

    private void resetFacts() {
        facts.clear();
        proofs.clear();
        evidenceEpoch++;
        missing.clear();
        generation++;
    }

    /** No object lookup or historical repair: only direct delivery without an intervening event. */
    Card currentPublicSource(final Snapshot snapshot, final int index) {
        final Proof proof;
        synchronized (this) {
            if (!enabled() || !supportedRecipient() || index >= snapshot.proofs().size()) { return null; }
            proof = snapshot.proofs().get(index);
            if (proof == null || proof.epoch() != evidenceEpoch) { return null; }
        }
        if (proof.card().isLKI() || proof.card().isFaceDown()
                || proof.card().getGameTimestamp() != proof.timestamp() || proof.card().getZone() == null
                || proof.card().getZone().getZoneType() != proof.zone()) { return null; }
        if (!publicZone(proof.zone())) { return null; }
        final Map<String, Object> fact = snapshot.facts().get(index);
        final CardView.CardStateView face = proof.card().getView().getCurrentState();
        final boolean sameFace = face.getState().name().equals(fact.get("presentedFace"))
                && bounded(face.getName()).equals(fact.get("name")) && bounded(face.getType().toString()).equals(fact.get("types"))
                && bounded(face.getManaCost().toString()).equals(fact.get("manaCost"));
        synchronized (this) { return sameFace && enabled() && proof.epoch() == evidenceEpoch ? proof.card() : null; }
    }

    private static boolean publicZone(final ZoneType zone) {
        return zone == ZoneType.Battlefield || zone == ZoneType.Command || zone == ZoneType.Graveyard
                || zone == ZoneType.Exile || zone == ZoneType.Stack;
    }

    private static String bounded(final String value) {
        if (value.length() <= MAX_TEXT) { return value; }
        final int end = Character.isHighSurrogate(value.charAt(MAX_TEXT - 1)) ? MAX_TEXT - 1 : MAX_TEXT;
        return value.substring(0, end);
    }

    private static Map<String, Object> omission(final String reason) {
        return Map.of("component", "DISCLOSURES", "reason", reason);
    }
}
