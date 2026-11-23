package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Detached, deliberately partial input at an explicitly audited boundary. Never calls policy. */
final class AiInputContext {
    static final int MAX_PLAYERS = 16;
    static final int MAX_CARDS = 128;
    static final int MAX_VISITS = 512;
    static final int MAX_MANA = 128;
    static final int MAX_COUNTERS = 32;
    static final int MAX_TEXT = 256;
    static final long MAX_GAME_WORK = 256_000;
    private static final String PROFILE = "PERMITTED_CHOICE_VIEW_V1";
    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Command,
            ZoneType.Graveyard, ZoneType.Exile, ZoneType.Hand, ZoneType.Library);

    private AiInputContext() { }

    /** One fixed share per original player; controlled replacement controllers are unsupported. */
    static final class State {
        private final AtomicLong remaining;
        private final AiContextDisclosures disclosures;

        State(final Player chooser) {
            remaining = new AtomicLong(MAX_GAME_WORK / Math.max(1, chooser.getGame().getRegisteredPlayers().size()));
            disclosures = new AiContextDisclosures(chooser, this::acquireWork);
        }

        AiContextDisclosures disclosures() { return disclosures; }
        long remainingWork() { return remaining.get(); }

        private boolean acquireWork() {
            long available = remaining.get();
            while (available > 0) {
                if (remaining.compareAndSet(available, available - 1)) { return true; }
                available = remaining.get();
            }
            return false;
        }
    }

    static Map<String, Object> capture(final Player chooser, final SpellAbility ability, final String timing,
            final String stateBasis, final BooleanSupplier open) {
        final Game game = chooser.getGame();
        if (!game.isRecordingAiDecisions() || !open.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return unavailable(chooser, timing, stateBasis, "INTERRUPTED");
        }
        if (chooser.isControlled() || game.getRules().hasSharedTeamTurns()
                || !(chooser.getController() instanceof PlayerControllerAi controller)) {
            return unavailable(chooser, timing, stateBasis, "UNSUPPORTED");
        }
        for (final Player player : game.getPlayers()) {
            if (player != chooser && player.getControllingPlayer() == chooser) {
                return unavailable(chooser, timing, stateBasis, "UNSUPPORTED");
            }
        }
        final State state = controller.inputContextState();
        if (!state.acquireWork()) { return unavailable(chooser, timing, stateBasis, "BUDGET_EXHAUSTED"); }
        return new Capture(chooser, timing, stateBasis, open, state).build();
    }

    static Map<String, Object> unavailable(final Player chooser, final String timing,
            final String stateBasis, final String reason) {
        final Map<String, Object> result = envelope(chooser, timing, stateBasis);
        result.put("status", "UNAVAILABLE");
        result.put("coverage", coverage("UNAVAILABLE", "UNAVAILABLE"));
        result.put("policy", null);
        result.put("diagnostics", Map.of("playerBindings", List.of(), "cardBindings", List.of()));
        result.put("omissions", List.of(Map.of("component", "UNSUPPORTED".equals(reason) ? "PERSPECTIVE" : "CONTEXT", "reason", reason)));
        return AiDecisionEvent.freezeMap(result);
    }

    private static Map<String, Object> envelope(final Player chooser, final String timing, final String stateBasis) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("profile", PROFILE);
        result.put("timing", timing);
        result.put("stateBasis", stateBasis);
        final Player controlling = chooser.getControllingPlayer();
        result.put("perspective", Map.of("chooserPlayerId", chooser.getId(),
                "controllingPlayerId", controlling == null ? chooser.getId() : controlling.getId()));
        return result;
    }

    private static Map<String, Object> coverage(final String view, final String disclosures) {
        return Map.of("playerView", view, "stack", "UNSUPPORTED", "disclosures", disclosures,
                "history", "INCOMPLETE", "options", "DIAGNOSTIC_ONLY");
    }

    private record CardSnapshot(Map<String, Object> permitted, long id, long timestamp) { }

    private static final class Capture {
        private final Player chooser;
        private final String timing;
        private final String stateBasis;
        private final BooleanSupplier open;
        private final State state;
        private final List<Map<String, Object>> omissions = new ArrayList<>();
        private final List<Map<String, Object>> players = new ArrayList<>();
        private final List<Map<String, Object>> playerBindings = new ArrayList<>();
        private final List<Map<String, Object>> cards = new ArrayList<>();
        private final List<Map<String, Object>> cardBindings = new ArrayList<>();
        private final Map<Long, String> playerRefs = new LinkedHashMap<>();
        private int visits;

        Capture(final Player chooser, final String timing, final String stateBasis,
                final BooleanSupplier open, final State state) {
            this.chooser = chooser;
            this.timing = timing;
            this.stateBasis = stateBasis;
            this.open = open;
            this.state = state;
        }

        private boolean active() {
            return chooser.getGame().isRecordingAiDecisions() && open.getAsBoolean()
                    && !Thread.currentThread().isInterrupted();
        }

        private boolean work(final String component) {
            if (!active()) { omit(component, "INTERRUPTED"); return false; }
            if (!state.acquireWork()) { omit(component, "BUDGET_EXHAUSTED"); return false; }
            return true;
        }

        private void omit(final String component, final String reason) {
            final Map<String, Object> omission = Map.of("component", component, "reason", reason);
            if (!omissions.contains(omission) && omissions.size() < 16) { omissions.add(omission); }
        }

        private String text(final String value) {
            if (value == null) { return ""; }
            if (value.length() <= MAX_TEXT) { return value; }
            omit("TEXT", "OUTSIDE_CAPTURED_PREFIX");
            return value.substring(0, MAX_TEXT);
        }

        private Map<String, Object> build() {
            final Game game = chooser.getGame();
            for (final Player player : game.getPlayers()) {
                if (players.size() >= MAX_PLAYERS) { omit("PLAYER_VIEW", "OUTSIDE_CAPTURED_PREFIX"); break; }
                if (!work("PLAYER_VIEW")) { break; }
                final String ref = "p" + players.size();
                playerRefs.put(player.getId(), ref);
                playerBindings.add(Map.of("entityRef", ref, "id", player.getId()));
                final Map<String, Object> visible = new LinkedHashMap<>();
                visible.put("entityRef", ref);
                visible.put("life", player.getLife());
                visible.put("handCount", player.getCardsIn(ZoneType.Hand).size());
                visible.put("libraryCount", player.getCardsIn(ZoneType.Library).size());
                final List<Integer> mana = new ArrayList<>();
                for (final Mana unit : player.getManaPool()) {
                    if (mana.size() >= MAX_MANA) { omit("MANA", "OUTSIDE_CAPTURED_PREFIX"); break; }
                    if (!work("MANA")) { break; }
                    mana.add((int) unit.getColor());
                }
                visible.put("floatingManaColors", mana);
                players.add(visible);
            }
            if (!playerRefs.containsKey(chooser.getId())) {
                return unavailable(chooser, timing, stateBasis,
                        !active() ? "INTERRUPTED" : state.remainingWork() <= 0
                                ? "BUDGET_EXHAUSTED" : "OUTSIDE_CAPTURED_PREFIX");
            }
            boolean complete = true;
            for (final Player player : game.getPlayers()) {
                if (!complete || !playerRefs.containsKey(player.getId())) { break; }
                for (final ZoneType zone : ZONES) {
                    final boolean hiddenOrder = zone == ZoneType.Library || zone == ZoneType.Hand || zone == ZoneType.Exile;
                    // Never choose a hidden-order prefix based on where identifiable objects happen to occur.
                    if (hiddenOrder && (player.getCardsIn(zone).size() > MAX_VISITS - visits
                            || player.getCardsIn(zone).size() > MAX_CARDS - cards.size())) {
                        omit("ENTITIES", "OUTSIDE_CAPTURED_PREFIX");
                        continue;
                    }
                    final List<CardSnapshot> permitted = new ArrayList<>();
                    for (final Card card : player.getCardsIn(zone)) {
                        if (visits >= MAX_VISITS || cards.size() + permitted.size() >= MAX_CARDS) {
                            omit("ENTITIES", "OUTSIDE_CAPTURED_PREFIX"); complete = false; break;
                        }
                        if (!work("ENTITIES")) { complete = false; break; }
                        visits++;
                        final CardView view = card.getView();
                        if (!view.canBeShownTo(chooser.getView())) { continue; }
                        // A face-up hidden library card does not pass this test merely by being face up.
                        final boolean identity = !view.isFaceDown() || view.canFaceDownBeShownTo(chooser.getView());
                        final Map<String, Object> visible = new LinkedHashMap<>();
                        visible.put("owner", card.getOwner() == null ? null : playerRefs.get(card.getOwner().getId()));
                        visible.put("controller", card.getController() == null ? null : playerRefs.get(card.getController().getId()));
                        visible.put("zone", zone.name());
                        visible.put("tapped", card.isTapped());
                        visible.put("faceDown", view.isFaceDown());
                        // Inspected name is separate from the characteristics of the current public face.
                        String name = identity ? card.getName() : null;
                        if (identity && view.isFaceDown()) {
                            if (view.getAlternateState() != null) { name = view.getAlternateState().getName(); }
                            else { name = null; omit("ENTITIES", "UNSUPPORTED"); }
                        }
                        visible.put("identityKnown", name != null);
                        visible.put("name", name == null ? null : text(name));
                        visible.put("types", text(card.getType().toString()));
                        visible.put("manaCost", text(card.getManaCost().toString()));
                        visible.put("power", card.isCreature() ? card.getNetPower() : null);
                        visible.put("toughness", card.isCreature() ? card.getNetToughness() : null);
                        final Map<String, Object> counters = new TreeMap<>();
                        for (final var entry : card.getCounters().entrySet()) {
                            if (counters.size() >= MAX_COUNTERS) { omit("COUNTERS", "OUTSIDE_CAPTURED_PREFIX"); break; }
                            if (!work("COUNTERS")) { break; }
                            counters.put(text(entry.getElement().toString()), entry.getCount());
                        }
                        visible.put("counters", counters);
                        permitted.add(new CardSnapshot(visible, card.getId(), card.getGameTimestamp()));
                    }
                    if (hiddenOrder && !complete) { permitted.clear(); }
                    // A viewing grant does not necessarily disclose positions within a hidden library.
                    if (hiddenOrder) {
                        permitted.sort(Comparator.comparing(snapshot -> snapshot.permitted().toString()));
                    }
                    for (final CardSnapshot snapshot : permitted) {
                        final String ref = "c" + cards.size();
                        final Map<String, Object> visible = new LinkedHashMap<>();
                        visible.put("entityRef", ref);
                        visible.putAll(snapshot.permitted());
                        cards.add(visible);
                        cardBindings.add(Map.of("entityRef", ref, "id", snapshot.id(), "gameTimestamp", snapshot.timestamp()));
                    }
                    if (!complete) { break; }
                }
            }
            if (state.remainingWork() <= 0) { return unavailable(chooser, timing, stateBasis, "BUDGET_EXHAUSTED"); }
            final AiContextDisclosures.Snapshot disclosed = state.disclosures().snapshot();
            final List<Map<String, Object>> facts = new ArrayList<>();
            for (final Map<String, Object> fact : disclosed.facts()) {
                final Map<String, Object> value = new LinkedHashMap<>();
                value.put("entityRef", "d" + facts.size());
                value.putAll(fact);
                facts.add(value);
            }
            for (final Map<String, Object> omission : disclosed.omissions()) {
                omit((String) omission.get("component"), (String) omission.get("reason"));
            }
            if (!active()) { return unavailable(chooser, timing, stateBasis, "INTERRUPTED"); }
            final Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("turn", game.getPhaseHandler().getTurn());
            policy.put("phase", game.getPhaseHandler().getPhase() == null ? "UNESTABLISHED" : game.getPhaseHandler().getPhase().name());
            final Player turnPlayer = game.getPhaseHandler().getPlayerTurn();
            policy.put("turnPlayer", turnPlayer == null ? null : playerRefs.get(turnPlayer.getId()));
            policy.put("chooser", playerRefs.get(chooser.getId()));
            policy.put("players", players);
            policy.put("cards", cards);
            policy.put("disclosedFacts", facts);
            final Map<String, Object> result = envelope(chooser, timing, stateBasis);
            result.put("status", omissions.isEmpty() ? "CAPTURED" : "PARTIAL");
            result.put("coverage", coverage("PERMITTED_CURRENT_FIELDS_V1", disclosed.coverage()));
            result.put("policy", policy);
            result.put("diagnostics", Map.of("playerBindings", playerBindings, "cardBindings", cardBindings));
            result.put("omissions", omissions);
            return AiDecisionEvent.freezeMap(result);
        }
    }

    /** Existing version-1 observation, retained byte-for-byte in meaning and field order. */
    static Map<String, Object> legacyObservation(final Game game, final Player viewer) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("turn", game.getPhaseHandler().getTurn());
        result.put("phase", game.getPhaseHandler().getPhase().name());
        result.put("turnPlayerId", game.getPhaseHandler().getPlayerTurn().getId());
        final List<Map<String, Object>> players = new ArrayList<>();
        for (final Player player : game.getPlayers()) {
            final Map<String, Object> state = new LinkedHashMap<>();
            state.put("id", player.getId());
            state.put("life", player.getLife());
            state.put("handCount", player.getCardsIn(ZoneType.Hand).size());
            state.put("libraryCount", player.getCardsIn(ZoneType.Library).size());
            final List<Map<String, Object>> cards = new ArrayList<>();
            for (final ZoneType zone : List.of(ZoneType.Battlefield, ZoneType.Command,
                    ZoneType.Graveyard, ZoneType.Exile, ZoneType.Hand)) {
                if (zone == ZoneType.Hand && player != viewer) { continue; }
                for (final Card card : player.getCardsIn(zone)) {
                    final Map<String, Object> ref = new LinkedHashMap<>();
                    ref.put("id", card.getId());
                    ref.put("zone", zone.name());
                    ref.put("tapped", card.isTapped());
                    final boolean visible = !card.isFaceDown() && card.getView().canBeShownTo(viewer.getView());
                    ref.put("identityKnown", visible);
                    if (visible) {
                        ref.put("name", card.getName());
                        ref.put("types", card.getType().toString());
                        ref.put("manaCost", card.getManaCost().toString());
                        if (card.isCreature()) {
                            ref.put("power", card.getNetPower());
                            ref.put("toughness", card.getNetToughness());
                        }
                    }
                    final Map<String, Object> counters = new LinkedHashMap<>();
                    for (final var entry : card.getCounters().entrySet()) {
                        counters.put(entry.getElement().toString(), entry.getCount());
                    }
                    ref.put("counters", counters);
                    cards.add(ref);
                }
            }
            state.put("cards", cards);
            final List<Integer> mana = new ArrayList<>();
            for (final Mana unit : player.getManaPool()) { mana.add((int) unit.getColor()); }
            state.put("floatingManaColors", mana);
            players.add(state);
        }
        result.put("players", players);
        return result;
    }
}
