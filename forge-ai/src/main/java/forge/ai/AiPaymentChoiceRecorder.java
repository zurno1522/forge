package forge.ai;

import forge.game.GameObject;
import forge.game.card.CardCollection;
import forge.game.cost.CostPart;
import forge.game.cost.PaymentDecision;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        return AiChoiceRecorder.observe(chooser, ability, "PAYMENT_DECISION", "PAYMENT", "COST_VISITOR",
                () -> Map.of("coverage", "PAYMENT_VISITOR_INPUT_NOT_ALTERNATIVES", "costKind",
                        COST_KINDS.contains(part.getClass().getSimpleName()) ? part.getClass().getSimpleName() : "OTHER", "effect", effect),
                callback, AiPaymentChoiceRecorder::decision);
    }

    public static CardCollection cards(final Player chooser, final SpellAbility ability, final String boundary,
            final CardCollection options, final int min, final int max, final Supplier<CardCollection> callback) {
        return AiChoiceRecorder.observe(chooser, ability, "PAYMENT_CARD_SELECTION", null, boundary,
                () -> Map.of("coverage", "OBSERVED_CARD_OPTIONS_NOT_ALL_LEGAL_PAYMENT_PLANS", "cards", refs(options),
                        "minimum", min, "maximum", max), callback, AiPaymentChoiceRecorder::refs);
    }

    public static List<SpellAbility> manaPayment(final Player chooser, final SpellAbility ability,
            final ManaCostBeingPaid cost, final boolean effect, final Supplier<List<SpellAbility>> callback) {
        return AiChoiceRecorder.observe(chooser, ability, "PAYMENT_MANA", "PAYMENT", "AUTO_MANA_PAYMENT",
                () -> Map.of("coverage", "OBSERVED_REQUIREMENT_NOT_ALTERNATIVE_PLANS", "remainingManaValue", cost.getConvertedManaCost(),
                        "effect", effect), callback,
                result -> Map.of("paymentRoutineReturned", true, "costSatisfied", cost.isPaid(), "manaAbilities", refs(result)));
    }

    public static Mana manaFromPool(final Player chooser, final List<Mana> options, final Supplier<Mana> callback) {
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
