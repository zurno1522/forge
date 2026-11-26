package forge.ai;

import forge.game.GameObject;
import forge.card.mana.ManaCostShard;
import forge.game.card.CardCollection;
import forge.game.cost.CostPart;
import forge.game.cost.PaymentDecision;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.mana.ManaPoolChoiceContext;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/** Observed payment inputs and proposals. A completed visitor is not proof that a cost was paid. */
public final class AiPaymentChoiceRecorder {
    private AiPaymentChoiceRecorder() { }
    private static final Set<String> COST_KINDS = Set.of(
        "CostAddMana",
        "CostBehold",
        "CostBeholdExile",
        "CostBlight",
        "CostChooseColor",
        "CostChooseCreatureType",
        "CostCollectEvidence",
        "CostDamage",
        "CostDiscard",
        "CostDraw",
        "CostEnlist",
        "CostExert",
        "CostExile",
        "CostExileFromStack",
        "CostExiledMoveToGrave",
        "CostFlipCoin",
        "CostForage",
        "CostGainControl",
        "CostGainLife",
        "CostMill",
        "CostPartMana",
        "CostPayEnergy",
        "CostPayLife",
        "CostPayShards",
        "CostPromiseGift",
        "CostPutCardToLib",
        "CostPutCounter",
        "CostPutCounterYou",
        "CostRemoveAnyCounter",
        "CostRemoveCounter",
        "CostReturn",
        "CostReveal",
        "CostRevealChosen",
        "CostRollDice",
        "CostSacrifice",
        "CostTap",
        "CostTapType",
        "CostTeamwork",
        "CostUnattach",
        "CostUntap",
        "CostUntapType",
        "CostWaterbend");

    public static PaymentDecision decide(final Player chooser, final SpellAbility ability, final CostPart part,
            final boolean effect, final Supplier<PaymentDecision> callback) {
        return AiChoiceRecorder.observeInput(chooser, ability, "PAYMENT_DECISION", "PAYMENT", "COST_VISITOR", "BEFORE_OPERATION",
                () -> Map.of("coverage", "PAYMENT_VISITOR_INPUT_NOT_ALTERNATIVES", "costKind",
                        COST_KINDS.contains(part.getClass().getSimpleName()) ? part.getClass().getSimpleName() : "OTHER", "effect", effect),
                callback, AiPaymentChoiceRecorder::decision);
    }

    public static CardCollection cards(final Player chooser, final SpellAbility ability, final String boundary,
            final CardCollection options, final int min, final int max, final Supplier<CardCollection> callback) {
        if ("PAYMENT_DISCARD_POOL".equals(boundary) || "PAYMENT_SACRIFICE_POOL".equals(boundary)) {
            return AiChoiceRecorder.observeInput(chooser, ability, "PAYMENT_CARD_SELECTION", null, boundary, "BEFORE_SELECTOR",
                    () -> Map.of("coverage", "OBSERVED_CARD_OPTIONS_NOT_ALL_LEGAL_PAYMENT_PLANS", "cards", refs(options),
                            "minimum", min, "maximum", max), callback, AiPaymentChoiceRecorder::refs);
        }
        return AiChoiceRecorder.observe(chooser, ability, "PAYMENT_CARD_SELECTION", null, boundary,
                () -> Map.of("coverage", "OBSERVED_CARD_OPTIONS_NOT_ALL_LEGAL_PAYMENT_PLANS", "cards", refs(options),
                        "minimum", min, "maximum", max), callback, AiPaymentChoiceRecorder::refs);
    }

    public static List<SpellAbility> manaPayment(final Player chooser, final SpellAbility ability,
            final ManaCostBeingPaid cost, final boolean effect, final Supplier<List<SpellAbility>> callback) {
        return AiChoiceRecorder.observeInput(chooser, ability, "PAYMENT_MANA", "PAYMENT", "AUTO_MANA_PAYMENT", "BEFORE_OPERATION",
                () -> Map.of("coverage", "OBSERVED_REQUIREMENT_NOT_ALTERNATIVE_PLANS", "remainingManaValue", cost.getConvertedManaCost(),
                        "effect", effect), callback,
                result -> Map.of("paymentRoutineReturned", true, "costSatisfied", cost.isPaid(), "manaAbilities", refs(result)));
    }

    static SpellAbility manaSource(final Player chooser, final SpellAbility ability, final ManaCostBeingPaid cost,
            final ManaCostShard shard, final Collection<SpellAbility> options, final boolean checkCosts, final boolean effect,
            final Supplier<SpellAbility> callback) {
        final var parameters = new AtomicReference<AiReceivedParameters.Input>();
        return AiChoiceRecorder.observeParametersAt(chooser, ability, "PAYMENT_MANA_SOURCE_SELECTION", null,
                "MANA_SOURCE_OPTIONS", "BEFORE_SELECTOR", () -> {
                    final var received = AiReceivedParameters.manaSource(cost, shard, checkCosts, effect); parameters.set(received);
                    return Map.of("coverage", "OBSERVED_MANA_SOURCE_OPTIONS_NOT_ALL_LEGAL_PAYMENT_PLANS", "abilities", refs(options),
                            "callbackParameters", received.diagnostics());
                }, callback, AiTargetChoiceRecorder::reference, null, null, new AiReceivedParameters.Carrier(parameters));
    }

    private static final ThreadLocal<PoolRequest> POOL_REQUEST = new ThreadLocal<>();

    private static final class PoolRequest {
        final Player chooser;
        final PlayerControllerAi receiver;
        final List<Mana> options;
        final ManaPoolChoiceContext request;
        final boolean admitted;
        boolean claimed;

        PoolRequest(Player chooser, PlayerControllerAi receiver, List<Mana> options, ManaPoolChoiceContext request) {
            this.chooser = chooser; this.receiver = receiver; this.options = options; this.request = request;
            admitted = chooser.getGame().isRecordingAiDecisions();
        }
    }

    static Mana withPoolRequest(final Player chooser, final PlayerControllerAi receiver, final List<Mana> options,
            final ManaPoolChoiceContext request, final Supplier<Mana> callback) {
        final PoolRequest previous = POOL_REQUEST.get();
        final PoolRequest current = new PoolRequest(chooser, receiver, options, request);
        POOL_REQUEST.set(current);
        try {
            return request != null && request.testPayment() ? AiChoiceRecorder.planning(chooser, callback) : callback.get();
        } finally {
            if (previous == null) { POOL_REQUEST.remove(); } else { POOL_REQUEST.set(previous); }
        }
    }

    public static Mana manaFromPool(final Player chooser, final List<Mana> options, final Supplier<Mana> callback) {
        final PoolRequest pending = POOL_REQUEST.get();
        if (pending != null && !pending.claimed && pending.chooser == chooser && pending.options == options
                && pending.receiver == chooser.getController()) {
            pending.claimed = true; // Claim before any observational getter; failed capture cannot lend this request.
            if (!pending.admitted) { return callback.get(); }
            if (pending.request != null && pending.receiver.getClass() == PlayerControllerAi.class) {
                final var parameters = new AtomicReference<AiReceivedParameters.Input>();
                return AiChoiceRecorder.observeParametersAt(chooser, pending.request.ability(), "PAYMENT_MANA_SELECTION", null,
                        "MANA_POOL_OPTIONS", "BEFORE_SELECTOR", () -> {
                            final var received = AiReceivedParameters.manaPool(pending.request); parameters.set(received);
                            return Map.of("coverage", "OBSERVED_MANA_OPTIONS", "mana", prefix(options, AiPaymentChoiceRecorder::mana),
                                    "callbackParameters", received.diagnostics());
                        }, callback, AiPaymentChoiceRecorder::mana, null, null, new AiReceivedParameters.Carrier(parameters));
            }
        }
        final SpellAbility ability = AiChoiceRecorder.activeAbility(chooser);
        return AiChoiceRecorder.observe(chooser, ability, "PAYMENT_MANA_SELECTION", null, "MANA_POOL_OPTIONS",
                () -> Map.of("coverage", "OBSERVED_MANA_OPTIONS", "mana", prefix(options, AiPaymentChoiceRecorder::mana)),
                callback, AiPaymentChoiceRecorder::mana);
    }

    private static Map<String, Object> decision(final PaymentDecision decision) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("decisionCount", decision.c);
        result.put("cards", refs(decision.cards));
        result.put("mana", decision.mana == null ? null : prefix(decision.mana, AiPaymentChoiceRecorder::mana));
        result.put("players", decision.players == null ? null : refs(decision.players));
        result.put("abilities", decision.sp == null ? null : refs(decision.sp));
        result.put("colorMask", decision.colors == null ? null : (int) decision.colors.getColor());
        final List<String> unavailable = new ArrayList<>();
        if (decision.type != null) { unavailable.add("TYPE_TEXT"); }
        if (decision.counterTable != null) { unavailable.add("COUNTER_TABLE"); }
        if (decision.matrix != null) { unavailable.add("MANA_CONVERSION_MATRIX"); }
        result.put("unrecordedDetails", unavailable);
        return result;
    }

    private static Map<String, Object> refs(final Iterable<? extends GameObject> values) {
        return prefix(values, AiTargetChoiceRecorder::reference);
    }

    private static <T> Map<String, Object> prefix(final Iterable<T> values, final Function<T, Object> mapper) {
        final List<Object> items = new ArrayList<>();
        final var it = values.iterator();
        while (it.hasNext() && items.size() < AiTargetChoiceRecorder.MAX_REFERENCES) { items.add(mapper.apply(it.next())); }
        return Map.of("items", items, "truncated", it.hasNext());
    }

    private static Map<String, Object> mana(final Mana value) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", AiTargetChoiceRecorder.reference(value.getSourceCard()));
        result.put("player", AiTargetChoiceRecorder.reference(value.getPlayer()));
        result.put("color", (int) value.getColor());
        result.put("snow", value.isSnow());
        return result;
    }
}
