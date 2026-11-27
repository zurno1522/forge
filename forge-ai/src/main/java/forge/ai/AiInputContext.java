package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.GameObject;
import forge.game.spellability.SpellAbilityStackInstance;
import java.util.IdentityHashMap;
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
import java.util.function.Supplier;

/** Detached, deliberately partial input at an explicitly audited boundary. Never calls policy. */
final class AiInputContext {
    static final int MAX_STACK = 8;
    static final int MAX_STACK_TARGETS = 16;
    static final int MAX_PROMPT_VALUES = 64;
    static final int MAX_PLAYERS = 16;
    static final int MAX_CARDS = 128;
    static final int MAX_VISITS = 512;
    static final int MAX_MANA = 128;
    static final int MAX_COUNTERS = 32;
    static final int MAX_TEXT = 256;
    static final int MAX_REQUEST_ZONES = ZoneType.values().length;
    static final long MAX_GAME_WORK = 256_000;
    static final String PROFILE = "PERMITTED_CHOICE_VIEW_V29";
    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Command,
            ZoneType.Graveyard, ZoneType.Exile, ZoneType.Hand, ZoneType.Library);

    private AiInputContext() { }

    /** One fixed share per original player; controlled replacement controllers are unsupported. */
    static final class State {
        private final AtomicLong remaining;
        private final AiContextDisclosures disclosures;
        private final AiInputAdmission admission = new AiInputAdmission();

        State(final Player chooser) {
            remaining = new AtomicLong(MAX_GAME_WORK / Math.max(1, chooser.getGame().getRegisteredPlayers().size()));
            disclosures = new AiContextDisclosures(chooser, this::acquireWork);
        }

        AiContextDisclosures disclosures() { return disclosures; }
        long remainingWork() { return remaining.get(); }
        Map<String, Object> nextAdmission() { return admission.next(); }

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
        return capture(chooser, ability, timing, stateBasis, open, null);
    }

    static Map<String, Object> capture(final Player chooser, final SpellAbility ability, final String timing,
            final String stateBasis, final BooleanSupplier open, final Supplier<AiPromptInput> prompt) {
        return capture(chooser, ability, timing, stateBasis, open, prompt, null);
    }

    static Map<String, Object> capture(final Player chooser, final SpellAbility ability, final String timing,
            final String stateBasis, final BooleanSupplier open, final Supplier<AiPromptInput> prompt,
            final Supplier<AiOptionInput> options) {
        return capture(chooser, ability, timing, stateBasis, open, prompt, options, null);
    }

    static Map<String, Object> capture(final Player chooser, final SpellAbility ability, final String timing,
            final String stateBasis, final BooleanSupplier open, final Supplier<AiPromptInput> prompt,
            final Supplier<AiOptionInput> options, final AiReceivedParameters.Carrier parameters) {
        final Game game = chooser.getGame();
        if (!game.isRecordingAiDecisions() || !open.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return unavailable(chooser, timing, stateBasis, "INTERRUPTED");
        }
        if (chooser.isControlled() || game.getRules().hasSharedTeamTurns()
                || !(chooser.getController() instanceof PlayerControllerAi controller)
                || !controller.ownsInputContext(chooser)) {
            return unavailable(chooser, timing, stateBasis, "UNSUPPORTED");
        }
        for (final Player player : game.getPlayers()) {
            if (player != chooser && player.getControllingPlayer() == chooser) {
                return unavailable(chooser, timing, stateBasis, "UNSUPPORTED");
            }
        }
        final State state = controller.inputContextState();
        final Map<String, Object> admission = state.nextAdmission();
        if ("COUNTER_OVERFLOW".equals(admission.get("status"))) {
            return unavailable(chooser, timing, stateBasis, "COUNTER_OVERFLOW", admission);
        }
        if (!state.acquireWork()) { return unavailable(chooser, timing, stateBasis, "BUDGET_EXHAUSTED", admission); }
        return new Capture(chooser, timing, stateBasis, open, state, prompt, options, parameters, admission).build();
    }

    static Map<String, Object> unavailable(final Player chooser, final String timing,
            final String stateBasis, final String reason) {
        return unavailable(chooser, timing, stateBasis, reason,
                List.of("INTERRUPTED", "UNSUPPORTED", "HIDDEN").contains(reason)
                        ? AiInputAdmission.BYPASSED : AiInputAdmission.UNKNOWN);
    }

    private static Map<String, Object> unavailable(final Player chooser, final String timing,
            final String stateBasis, final String reason, final Map<String, Object> admission) {
        final Map<String, Object> result = envelope(chooser, timing, stateBasis, admission);
        result.put("status", "UNAVAILABLE");
        result.put("coverage", coverage("UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE"));
        result.put("policy", null);
        result.put("diagnostics", Map.of("playerBindings", List.of(), "cardBindings", List.of(), "stackBindings", List.of(),
                "optionBindings", Map.of("coverage", "UNAVAILABLE", "items", List.of(), "truncated", true)));
        result.put("omissions", List.of(Map.of("component", "UNSUPPORTED".equals(reason) ? "PERSPECTIVE" : "CONTEXT", "reason", reason)));
        return AiDecisionEvent.freezeMap(result);
    }

    private static Map<String, Object> envelope(final Player chooser, final String timing, final String stateBasis,
            final Map<String, Object> admission) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 29);
        result.put("profile", PROFILE);
        result.put("timing", timing);
        result.put("stateBasis", stateBasis);
        result.put("admission", admission);
        final Player controlling = chooser.getControllingPlayer();
        result.put("perspective", Map.of("chooserPlayerId", chooser.getId(),
                "controllingPlayerId", controlling == null ? chooser.getId() : controlling.getId()));
        return result;
    }

    private static Map<String, Object> coverage(final String view, final String disclosures, final String prompt, final String parameters) {
        return Map.of("playerView", view, "stack", "UNAVAILABLE".equals(view) ? "UNAVAILABLE" : "PUBLIC_ROOT_STACK_V1", "disclosures", disclosures,
                "history", "INCOMPLETE", "options", "DIAGNOSTIC_ONLY", "prompt", prompt, "receivedParameters", parameters);
    }

    private record CardSnapshot(Map<String, Object> permitted, Card card, long id, long timestamp) { }

    private static final class Capture {
        private final Player chooser;
        private final String timing;
        private final String stateBasis;
        private final BooleanSupplier open;
        private final State state;
        private final Map<String, Object> admission;
        private final Supplier<AiPromptInput> promptInput;
        private final Supplier<AiOptionInput> optionInput;
        private String promptCoverage = "UNSUPPORTED";
        private final AiReceivedParameters.Carrier parameterInput;
        private String parameterCoverage = "UNSUPPORTED";
        private final List<Map<String, Object>> omissions = new ArrayList<>();
        private final List<Map<String, Object>> players = new ArrayList<>();
        private final List<Map<String, Object>> playerBindings = new ArrayList<>();
        private final List<Map<String, Object>> cards = new ArrayList<>();
        private final List<Map<String, Object>> cardBindings = new ArrayList<>();
        private final List<Map<String, Object>> stackBindings = new ArrayList<>();
        private final Map<Card, String> currentCardRefs = new IdentityHashMap<>();
        private final Map<Card, Long> cardIncarnations = new IdentityHashMap<>();
        private final Map<Long, String> playerRefs = new LinkedHashMap<>();
        private int visits;

        Capture(final Player chooser, final String timing, final String stateBasis,
                final BooleanSupplier open, final State state, final Supplier<AiPromptInput> promptInput,
                final Supplier<AiOptionInput> optionInput, final AiReceivedParameters.Carrier parameterInput,
                final Map<String, Object> admission) {
            this.chooser = chooser;
            this.timing = timing;
            this.stateBasis = stateBasis;
            this.open = open;
            this.state = state;
            this.admission = admission;
            this.promptInput = promptInput;
            this.optionInput = optionInput;
            this.parameterInput = parameterInput;
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

        private Map<String, Object> permittedCard(final Card card, final ZoneType zone) {
            final CardView view = card.getView();
            if (!view.canBeShownTo(chooser.getView())) { return null; }
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
            return visible;
        }

        private String publicCardRef(final Card card) {
            if (card == null || card.getZone() == null) { return null; }
            final ZoneType zone = card.getZone().getZoneType();
            if (zone != ZoneType.Battlefield && zone != ZoneType.Command && zone != ZoneType.Graveyard
                    && zone != ZoneType.Stack && !(zone == ZoneType.Exile && !card.isFaceDown())) { return null; }
            final Long timestamp = cardIncarnations.get(card);
            return timestamp != null && timestamp == card.getGameTimestamp() ? currentCardRefs.get(card) : null;
        }

        /** Top first. Only scalar metadata and currently represented public objects are bound. */
        private Map<String, Object> stack() {
            final Game game = chooser.getGame();
            final List<SpellAbilityStackInstance> instances = new ArrayList<>();
            final Map<SpellAbility, String> abilityRefs = new IdentityHashMap<>();
            boolean truncated = false;
            for (final SpellAbilityStackInstance instance : game.getStack()) {
                if (instances.size() == MAX_STACK || !work("STACK")) {
                    truncated = true; omit("STACK", "OUTSIDE_CAPTURED_PREFIX"); break;
                }
                final String ref = "s" + instances.size();
                final SpellAbility ability = instance.getSpellAbility();
                if (abilityRefs.containsKey(ability)) { abilityRefs.put(ability, null); }
                else { abilityRefs.put(ability, ref); }
                instances.add(instance);
                stackBindings.add(Map.of("entityRef", ref, "id", instance.getId(), "abilityId", ability.getId()));
            }
            // Inspect only represented active sources, never enumerate the staging queue.
            // Retired objects can retain their zone pointer: require exact stored identity too.
            for (final SpellAbilityStackInstance instance : instances) {
                final Card card = instance.getSourceCard();
                if (card == null || card.isLKI() || card.getZone() != game.getStackZone()
                        || currentCardRefs.containsKey(card)) { continue; }
                final var currentStackCards = game.getStackZone().getCards();
                if (!currentStackCards.contains(card) || currentStackCards.get(card) != card) { continue; }
                if (visits >= MAX_VISITS || cards.size() >= MAX_CARDS) {
                    omit("STACK", "OUTSIDE_CAPTURED_PREFIX"); break;
                }
                if (!work("STACK")) { break; }
                visits++;
                final Map<String, Object> projection = permittedCard(card, ZoneType.Stack);
                if (projection == null) { continue; }
                final String ref = "c" + cards.size();
                projection.put("entityRef", ref);
                cards.add(projection);
                cardBindings.add(Map.of("entityRef", ref, "id", card.getId(), "gameTimestamp", card.getGameTimestamp()));
                currentCardRefs.put(card, ref); cardIncarnations.put(card, card.getGameTimestamp());
            }
            final List<Map<String, Object>> entries = new ArrayList<>();
            for (final SpellAbilityStackInstance instance : instances) {
                if (!work("STACK")) { truncated = true; break; }
                final SpellAbility ability = instance.getSpellAbility();
                final String source = publicCardRef(instance.getSourceCard());
                final boolean known = source != null && Boolean.TRUE.equals(cards.get(Integer.parseInt(source.substring(1))).get("identityKnown"));
                // A working SA's live target/X fields need not be a publicly committed plan.
                final boolean details = known && "RULES_STATE".equals(stateBasis);
                final Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("entityRef", "s" + entries.size());
                entry.put("kind", instance.isSpell() ? "SPELL" : "ABILITY");
                final Player controller = instance.getActivatingPlayer();
                entry.put("controller", controller == null ? null : playerRefs.get(controller.getId()));
                if (entry.get("controller") == null) { omit("STACK", "UNRESOLVED_BINDING"); }
                entry.put("source", source);
                entry.put("rootX", details ? ability.getXManaCostPaid() : null);
                List<Map<String, Object>> targets = details ? new ArrayList<>() : null;
                if (!known) { omit("STACK", source == null ? "UNRESOLVED_BINDING" : "HIDDEN"); }
                else if (!details) { omit("STACK", "UNSUPPORTED"); }
                if (details) {
                    for (final GameObject target : ability.getTargets()) {
                        if (targets.size() == MAX_STACK_TARGETS) {
                            omit("STACK", "OUTSIDE_CAPTURED_PREFIX"); targets = null; break;
                        }
                        if (!work("STACK")) { targets = null; break; }
                        final String ref;
                        if (target instanceof Player player) { ref = playerRefs.get(player.getId()); }
                        else if (target instanceof Card card) { ref = publicCardRef(card); }
                        else if (target instanceof SpellAbility spell) { ref = abilityRefs.get(spell); }
                        else { ref = null; }
                        if (ref == null) { omit("STACK", "UNRESOLVED_BINDING"); targets = null; break; }
                        final Map<String, Object> value = new LinkedHashMap<>();
                        value.put("entityRef", ref);
                        value.put("dividedAmount", ability.getTargets().getDividedValue(target));
                        targets.add(value);
                    }
                }
                entry.put("targets", targets);
                entry.put("targetsCoverage", targets == null ? "UNAVAILABLE" : "ROOT_TARGETS_ONLY");
                entries.add(entry);
            }
            // Cancellation/budget exhaustion causes the entire context to become unavailable at the outer gate.
            if (entries.size() != stackBindings.size()) { stackBindings.subList(entries.size(), stackBindings.size()).clear(); }
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("entries", entries); result.put("truncated", truncated);
            final boolean resolving = game.getStack().isResolving();
            final String resolvingRef = abilityRefs.get(game.getStack().getResolvingAbility());
            result.put("isResolving", resolving);
            result.put("resolving", resolving ? resolvingRef : null);
            result.put("resolvingCoverage", resolving && resolvingRef == null ? "UNAVAILABLE" : "CAPTURED");
            if (resolving && resolvingRef == null) { omit("STACK", "UNRESOLVED_BINDING"); }
            return result;
        }

        private Map<String, Object> prompt() {
            if (promptInput == null) { return null; }
            promptCoverage = "UNAVAILABLE";
            if (!"RULES_STATE".equals(stateBasis) && !(promptInput instanceof AiPromptInput.ModeBounds)
                    && !(promptInput instanceof AiPromptInput.EntityBounds)
                    && !(promptInput instanceof AiPromptInput.ZoneRequest)
                    && !(promptInput instanceof AiPromptInput.ZoneOperation)
                    && !(promptInput instanceof AiPromptInput.SpellOperation)
                    && !(promptInput instanceof AiPromptInput.SpellRequest)
                    && !(promptInput instanceof AiSpellSelectionHistory.Prompt)
                    && !(promptInput instanceof AiZoneSelectionHistory.Prompt)
                    && !(promptInput instanceof AiEntitySelectionHistory.Prompt sequence && sequence.sequenced())) {
                omit("PROMPT", "UNSUPPORTED"); return null;
            }
            if (!work("PROMPT")) { return null; }
            final AiPromptInput request = promptInput.get();
            if (request.kind() == AiPromptInput.Kind.UNSUPPORTED_REQUEST) { omit("PROMPT", "UNSUPPORTED"); return null; }
            if (request.kind() == AiPromptInput.Kind.ENTITY_PICK_SEQUENCE && !"COMPLETE".equals(request.sequence().coverage())) {
                omit("PROMPT", request.sequence().reason()); return null;
            }
            if (request.kind() == AiPromptInput.Kind.ZONE_PICK_PROGRESS && !request.zoneProgress().complete()) {
                omit("PROMPT", request.zoneProgress().omission()); return null;
            }
            if (request.kind() == AiPromptInput.Kind.SPELL_ABILITY_PICK_PROGRESS && !request.spellProgress().complete()) {
                omit("PROMPT", request.spellProgress().omission()); return null;
            }
            String related = null;
            if (request.relatedPlayer() != null) {
                int index = 0;
                for (final Player player : chooser.getGame().getPlayers()) {
                    if (index++ == MAX_PLAYERS) { break; }
                    if (player == request.relatedPlayer()) { related = playerRefs.get(player.getId()); break; }
                }
                if (related == null) { omit("PROMPT", "UNRESOLVED_BINDING"); return null; }
            }
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("kind", request.kind().name());
            switch (request.kind()) {
                case NUMBER_RANGE -> {
                    result.put("minimum", request.minimum()); result.put("maximum", request.maximum());
                }
                case NUMBER_OPTIONS -> {
                    if (request.values().size() > MAX_PROMPT_VALUES) { omit("PROMPT", "OUTSIDE_CAPTURED_PREFIX"); return null; }
                    final List<Integer> values = new ArrayList<>();
                    for (final Integer value : request.values()) {
                        if (values.size() == MAX_PROMPT_VALUES) { omit("PROMPT", "OUTSIDE_CAPTURED_PREFIX"); return null; }
                        if (!work("PROMPT")) { return null; }
                        if (value == null) { omit("PROMPT", "UNSUPPORTED"); return null; }
                        values.add(value);
                    }
                    result.put("values", values); result.put("relatedPlayer", related);
                }
                case SINGLE_ENTITY -> {
                    result.put("minimumPicks", request.minimum()); result.put("maximumPicks", request.maximum());
                    result.put("optional", request.optional()); result.put("relatedPlayer", related);
                }
                case MODE_BOUNDS -> {
                    result.put("minimumPicks", request.minimum()); result.put("maximumPicks", request.maximum());
                    result.put("allowRepeat", request.allowRepeat());
                }
                case SPELL_ABILITY_PICK_PROGRESS -> {
                    final var progress = request.spellProgress();
                    result.put("affectedPlayer", related); result.put("requestedSelections", progress.requestedSelections());
                    result.put("attemptIndex", progress.attemptIndex()); result.put("priorNonNullReturnCount", progress.priorNonNullReturnCount());
                    result.put("retainedCount", progress.retainedCount());
                    final List<String> previous = new ArrayList<>();
                    for (final var ignored : progress.receipts()) {
                        if (!work("PROMPT")) { return null; }
                        previous.add(null);
                    }
                    result.put("previousReturns", previous);
                }
                case SPELL_ABILITY_REQUEST -> result.put("affectedPlayer", related);
                case SPELL_ABILITY_OPERATION_REQUEST -> result.put("requestedSelections", request.minimum());
                case ENTITY_BOUNDS -> {
                    result.put("minimumPicks", request.minimum()); result.put("maximumPicks", request.maximum());
                    result.put("relatedPlayer", related);
                }
                case ZONE_CHANGE_OPERATION_REQUEST -> {
                    final var requestValue = request.zoneOperation().request();
                    final Player sourcePlayer = requestValue.sourcePlayer();
                    final String source = sourcePlayer == null ? null : currentPlayerRef(sourcePlayer);
                    if (sourcePlayer != null && source == null) { omit("PROMPT", "UNRESOLVED_BINDING"); return null; }
                    if (requestValue.destination() == null || requestValue.origins() == null) { omit("PROMPT", "UNSUPPORTED"); return null; }
                    if (requestValue.origins().size() > MAX_REQUEST_ZONES) { omit("PROMPT", "OUTSIDE_CAPTURED_PREFIX"); return null; }
                    final List<String> origins = new ArrayList<>();
                    for (final ZoneType origin : requestValue.origins()) {
                        if (origins.size() == MAX_REQUEST_ZONES) { omit("PROMPT", "OUTSIDE_CAPTURED_PREFIX"); return null; }
                        if (!work("PROMPT")) { return null; }
                        if (origin == null) { omit("PROMPT", "UNSUPPORTED"); return null; }
                        origins.add(origin.name());
                    }
                    result.put("branch", requestValue.branch().name());
                    result.put("requestedIterations", requestValue.requestedIterations());
                    result.put("destination", requestValue.destination().name()); result.put("origins", origins);
                    result.put("sourcePlayer", source); result.put("decider", related);
                }
                case ZONE_CHANGE_REQUEST, ZONE_PICK_PROGRESS -> {
                    final var zones = request.zoneRequest();
                    if (zones.destination() == null || zones.origins() == null) { omit("PROMPT", "UNSUPPORTED"); return null; }
                    if (zones.origins().size() > MAX_REQUEST_ZONES) { omit("PROMPT", "OUTSIDE_CAPTURED_PREFIX"); return null; }
                    final List<String> origins = new ArrayList<>();
                    for (final ZoneType origin : zones.origins()) {
                        if (origins.size() == MAX_REQUEST_ZONES) { omit("PROMPT", "OUTSIDE_CAPTURED_PREFIX"); return null; }
                        if (!work("PROMPT")) { return null; }
                        if (origin == null) { omit("PROMPT", "UNSUPPORTED"); return null; }
                        origins.add(origin.name());
                    }
                    result.put("minimumPicks", request.minimum()); result.put("maximumPicks", request.maximum());
                    result.put("optional", request.optional()); result.put("destination", zones.destination().name());
                    result.put("origins", origins); result.put("decider", related);
                    if (request.kind() == AiPromptInput.Kind.ZONE_PICK_PROGRESS) {
                        final var progress = request.zoneProgress();
                        final Player sourcePlayer = progress.operation().sourcePlayer();
                        final String source = sourcePlayer == null ? null : currentPlayerRef(sourcePlayer);
                        if (sourcePlayer != null && source == null) { omit("PROMPT", "UNRESOLVED_BINDING"); return null; }
                        result.put("branch", progress.operation().branch().name());
                        result.put("sourcePlayer", source); result.put("requestedIterations", progress.operation().requestedIterations());
                        result.put("attemptIndex", progress.attemptIndex()); result.put("loopPosition", progress.loopPosition());
                        result.put("priorNonNullReturnCount", progress.priorNonNullReturnCount()); result.put("retainedCount", progress.retainedCount());
                        final List<String> previous = new ArrayList<>();
                        for (final AiOptionInput.Entry receipt : progress.receipts()) {
                            if (!work("PROMPT")) { return null; }
                            previous.add(previousSelectionRef(receipt));
                        }
                        result.put("previousReturns", previous);
                    }
                }
                case ENTITY_PICK_SEQUENCE -> {
                    final var sequence = request.sequence();
                    result.put("minimumPicks", request.minimum()); result.put("maximumPicks", request.maximum());
                    result.put("optional", request.optional()); result.put("relatedPlayer", related);
                    result.put("minimumTotal", sequence.minimum()); result.put("maximumTotal", sequence.maximum());
                    result.put("priorSelectionCount", sequence.priorCount());
                    final List<String> previous = new ArrayList<>();
                    for (final AiOptionInput.Entry receipt : sequence.receipts()) {
                        if (!work("PROMPT")) { return null; }
                        previous.add(previousSelectionRef(receipt));
                    }
                    result.put("previousSelections", previous);
                }
                default -> throw new IllegalStateException("Unsupported prompt kind");
            }
            promptCoverage = switch (request.kind()) {
                case SPELL_ABILITY_PICK_PROGRESS -> "RECEIVED_SPELL_PROGRESS_V1";
                case SPELL_ABILITY_REQUEST -> "RECEIVED_SPELL_REQUEST_V1";
                case SPELL_ABILITY_OPERATION_REQUEST -> "RECEIVED_SPELL_OPERATION_V1";
                case MODE_BOUNDS -> "RECEIVED_MODE_BOUNDS_V1";
                case ENTITY_BOUNDS -> "RECEIVED_ENTITY_BOUNDS_V1";
                case ENTITY_PICK_SEQUENCE -> "RECEIVED_ENTITY_SEQUENCE_V1";
                case ZONE_CHANGE_REQUEST -> "RECEIVED_ZONE_REQUEST_V1";
                case ZONE_CHANGE_OPERATION_REQUEST -> "RECEIVED_ZONE_OPERATION_V1";
                case ZONE_PICK_PROGRESS -> "RECEIVED_ZONE_PROGRESS_V1";
                default -> "CONTROLLER_REQUEST_V1";
            };
            return result;
        }

        private Map<String, Object> receivedParameters() {
            if (parameterInput == null) { return null; }
            parameterCoverage = "UNAVAILABLE";
            if (!work("PARAMETERS")) { return null; }
            final var input = parameterInput.receipt().get();
            if (input == null || !"COMPLETE".equals(input.coverage())) {
                omit("PARAMETERS", input == null ? "UNSUPPORTED" : input.reason()); return null;
            }
            final Map<String, Object> result = new LinkedHashMap<>(); result.put("kind", input.kind().name());
            for (final var field : input.values().entrySet()) {
                if (!work("PARAMETERS")) { return null; }
                final var roleList = input.roleLists().get(field.getKey());
                if (roleList != null) {
                    final List<String> refs = new ArrayList<>();
                    for (final var receipt : roleList) {
                        if (!work("PARAMETERS")) { return null; }
                        final String ref = input.kind() == AiReceivedParameters.Kind.DISCARD_PICK_REQUEST
                                ? discardPreviousSelectionRef(receipt) : previousSelectionRef(receipt);
                        if (ref == null) { omit("PARAMETERS", "UNRESOLVED_BINDING"); return null; }
                        refs.add(ref);
                    }
                    result.put(field.getKey(), List.copyOf(refs)); continue;
                }
                final var role = input.roles().get(field.getKey());
                final String ref = role == null ? null : previousSelectionRef(role);
                if (role != null && ref == null) { omit("PARAMETERS", "UNRESOLVED_BINDING"); return null; }
                result.put(field.getKey(), role == null ? field.getValue() : ref);
            }
            parameterCoverage = "RECEIVED_CALLBACK_PARAMETERS_V1"; return result;
        }

        private String discardPreviousSelectionRef(final AiOptionInput.Entry receipt) {
            final String publicRef = previousSelectionRef(receipt);
            if (publicRef != null) { return publicRef; }
            if (!(receipt.object() instanceof Card card) || card.isLKI()
                    || !java.util.Objects.equals(receipt.reference().get("gameTimestamp"), cardIncarnations.get(card))) { return null; }
            final String ref = currentCardRefs.get(card);
            if (ref == null || card.getZone() == null || card.getZone().getZoneType() != ZoneType.Hand
                    || card.getZone().getPlayer() != chooser || card.getOwner() != chooser || card.getController() != chooser
                    || card.getGameTimestamp() != cardIncarnations.get(card) || !card.getView().canBeShownTo(chooser.getView())) { return null; }
            final Map<String, Object> visible = cards.get(Integer.parseInt(ref.substring(1)));
            if (!"Hand".equals(visible.get("zone")) || !Boolean.TRUE.equals(visible.get("identityKnown"))
                    || !playerRefs.get(chooser.getId()).equals(visible.get("owner"))
                    || !playerRefs.get(chooser.getId()).equals(visible.get("controller"))) { return null; }
            // Exact identity must not break a tie in the hidden-hand canonical presentation.
            int matches = 0;
            for (final Map<String, Object> candidate : cards) {
                if (!work("PARAMETERS")) { return null; }
                boolean same = true;
                for (final var field : visible.entrySet()) {
                    if (!"entityRef".equals(field.getKey()) && !java.util.Objects.equals(field.getValue(), candidate.get(field.getKey()))) {
                        same = false; break;
                    }
                }
                if (same && ++matches > 1) { return null; }
            }
            return matches == 1 ? ref : null;
        }

        private String previousSelectionRef(final AiOptionInput.Entry receipt) {
            if (receipt.object() instanceof Player player) { return currentPlayerRef(player); }
            if (receipt.object() instanceof Card card && !card.isLKI()
                    && java.util.Objects.equals(receipt.reference().get("gameTimestamp"), cardIncarnations.get(card))) {
                return publicCardRef(card);
            }
            return null;
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
                                ? "BUDGET_EXHAUSTED" : "OUTSIDE_CAPTURED_PREFIX", admission);
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
                        final Map<String, Object> visible = permittedCard(card, zone);
                        if (visible == null) { continue; }
                        permitted.add(new CardSnapshot(visible, card, card.getId(), card.getGameTimestamp()));
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
                        currentCardRefs.put(snapshot.card(), ref);
                        cardIncarnations.put(snapshot.card(), snapshot.timestamp());
                    }
                    if (!complete) { break; }
                }
            }
            final Map<String, Object> stack = stack();
            final Map<String, Object> prompt = prompt();
            final Map<String, Object> optionBindings = optionBindings();
            final Map<String, Object> parameters = receivedParameters();
            if (state.remainingWork() <= 0) { return unavailable(chooser, timing, stateBasis, "BUDGET_EXHAUSTED", admission); }
            final AiContextDisclosures.Snapshot disclosed = state.disclosures().snapshot();
            final List<Map<String, Object>> facts = new ArrayList<>();
            for (final Map<String, Object> fact : disclosed.facts()) {
                final Map<String, Object> value = new LinkedHashMap<>();
                value.put("entityRef", "d" + facts.size());
                value.putAll(fact);
                final Card associated = "RULES_STATE".equals(stateBasis)
                        ? state.disclosures().currentPublicSource(disclosed, facts.size()) : null;
                final String association = disclosureRef(associated, fact);
                if (association != null && !associated.isFaceDown()) { value.put("currentObjectAssociation", association); }
                facts.add(value);
            }
            for (final Map<String, Object> omission : disclosed.omissions()) {
                omit((String) omission.get("component"), (String) omission.get("reason"));
            }
            if (!active()) { return unavailable(chooser, timing, stateBasis, "INTERRUPTED", admission); }
            final Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("turn", game.getPhaseHandler().getTurn());
            policy.put("phase", game.getPhaseHandler().getPhase() == null ? "UNESTABLISHED" : game.getPhaseHandler().getPhase().name());
            final Player turnPlayer = game.getPhaseHandler().getPlayerTurn();
            policy.put("turnPlayer", turnPlayer == null ? null : playerRefs.get(turnPlayer.getId()));
            policy.put("chooser", playerRefs.get(chooser.getId()));
            policy.put("players", players);
            policy.put("cards", cards);
            policy.put("stack", stack);
            policy.put("prompt", prompt);
            policy.put("receivedParameters", parameters);
            policy.put("disclosedFacts", facts);
            final Map<String, Object> result = envelope(chooser, timing, stateBasis, admission);
            result.put("status", omissions.isEmpty() ? "CAPTURED" : "PARTIAL");
            result.put("coverage", coverage("PERMITTED_CURRENT_FIELDS_V1", disclosed.coverage(), promptCoverage, parameterCoverage));
            result.put("policy", policy);
            result.put("diagnostics", Map.of("playerBindings", playerBindings, "cardBindings", cardBindings, "stackBindings", stackBindings,
                    "optionBindings", optionBindings));
            result.put("omissions", omissions);
            return AiDecisionEvent.freezeMap(result);
        }

        private String currentPlayerRef(final Player candidate) {
            int count = 0;
            for (final Player player : chooser.getGame().getPlayers()) {
                if (count++ == MAX_PLAYERS) { break; }
                if (player == candidate) { return playerRefs.get(player.getId()); }
            }
            return null;
        }

        private String disclosureRef(final Card card, final Map<String, Object> fact) {
            final String ref = publicCardRef(card);
            if (ref == null || omissions.stream().anyMatch(item -> List.of("ENTITIES", "PLAYER_VIEW", "TEXT", "COUNTERS")
                    .contains(item.get("component")))) { return null; }
            final Map<String, Object> expected = cards.get(Integer.parseInt(ref.substring(1)));
            if (!Boolean.TRUE.equals(expected.get("identityKnown")) || Boolean.TRUE.equals(expected.get("faceDown"))
                    || List.of("name", "types", "manaCost", "zone").stream()
                            .anyMatch(field -> !java.util.Objects.equals(expected.get(field), fact.get(field)))) { return null; }
            int matches = 0;
            for (final Map<String, Object> candidate : cards) {
                boolean same = true;
                for (final var field : expected.entrySet()) {
                    if (!"entityRef".equals(field.getKey()) && !java.util.Objects.equals(field.getValue(), candidate.get(field.getKey()))) {
                        same = false; break;
                    }
                }
                if (same && ++matches > 1) { return null; }
            }
            return ref;
        }

        private Map<String, Object> optionBindings() {
            if (optionInput == null) { return Map.of("coverage", "UNSUPPORTED", "items", List.of(), "truncated", false); }
            final AiOptionInput observed = optionInput.get();
            if (observed == null) { return Map.of("coverage", "UNAVAILABLE", "items", List.of(), "truncated", true); }
            final List<Map<String, Object>> links = new ArrayList<>();
            for (final AiOptionInput.Entry entry : observed.entries()) {
                if (!active()) { break; }
                String ref = null;
                if (entry.object() instanceof Player player) { ref = currentPlayerRef(player); }
                else if (entry.object() instanceof Card card && !card.isLKI()
                        && java.util.Objects.equals(entry.reference().get("gameTimestamp"), card.getGameTimestamp())) {
                    ref = publicCardRef(card);
                }
                final Map<String, Object> link = new LinkedHashMap<>();
                link.put("optionIndex", links.size()); link.put("entityRef", ref); links.add(link);
            }
            return Map.of("coverage", "DIAGNOSTIC_PUBLIC_CURRENT_V1", "items", links, "truncated", observed.truncated());
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
