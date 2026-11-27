package forge.ai;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableList;
import forge.game.GameEntity;
import forge.card.mana.ManaCostShard;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterCustomType;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterKeywordType;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.mana.ManaPoolChoiceContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded received shapes at audited numeric/entity callbacks; never an arbitrary map serializer. */
final class AiReceivedParameters {
    static final int MAX_ROLE_LIST = 64;
    enum Family { NUMBER, ENTITY }
    enum Kind { NONE, VOTER, COUNTER_AMOUNT, COUNTER_TRANSFER_AMOUNT, ENTITY_CARD_ROLE, ENTITY_TARGET_ROLE,
        ENTITY_MANA_VALUE_LIMIT, ENTITY_COUNTER_REQUEST, TRIGGER_REQUIREMENT, ENTITY_ATTACHMENT_ROLES, ENTITY_ATTACKER_ROLES, MANA_POOL_REQUEST, MANA_SOURCE_REQUEST, CARD_PAYMENT_REQUEST }
    record Carrier(AtomicReference<Input> receipt) { }
    record Input(Kind kind, String coverage, String reason, Map<String, Object> values, Map<String, AiOptionInput.Entry> roles, Map<String, List<AiOptionInput.Entry>> roleLists) {
        Map<String, Object> diagnostics() {
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", 1); result.put("coverage", coverage); result.put("kind", kind == null ? null : kind.name());
            result.put("values", values); result.put("reason", reason); return result;
        }
    }
    private AiReceivedParameters() { }

    static Input trigger(final Boolean mandatory) {
        return mandatory == null ? unsupported() : complete(Kind.TRIGGER_REQUIREMENT, Map.of("mandatory", mandatory), Map.of());
    }

    static Input manaPool(final ManaPoolChoiceContext request) {
        return request == null || request.shard() == null ? unsupported() : complete(Kind.MANA_POOL_REQUEST,
                Map.of("testPayment", request.testPayment(), "shard", request.shard().name(), "colorsPaid", (int) request.colorsPaid()), Map.of());
    }

    static Input manaSource(final ManaCostBeingPaid cost, final ManaCostShard shard, final boolean checkCosts, final boolean effect) {
        return cost == null || cost.getClass() != ManaCostBeingPaid.class || shard == null ? unsupported() : complete(Kind.MANA_SOURCE_REQUEST,
                Map.of("shard", shard.name(), "remainingManaValue", cost.getConvertedManaCost(), "checkCosts", checkCosts, "effect", effect), Map.of());
    }

    static Input cardPayment(final String boundary, final int minimum, final int maximum) {
        final String kind = "PAYMENT_DISCARD_POOL".equals(boundary) ? "DISCARD" : "PAYMENT_SACRIFICE_POOL".equals(boundary) ? "SACRIFICE" : null;
        return kind == null ? unsupported() : complete(Kind.CARD_PAYMENT_REQUEST,
                Map.of("paymentKind", kind, "minimum", minimum, "maximum", maximum), Map.of());
    }

    private static Input unsupported() { return new Input(null, "UNSUPPORTED", "UNSUPPORTED", null, Map.of(), Map.of()); }
    private static boolean nativeCounter(final Object value) {
        return value instanceof CounterEnumType || value != null && (value.getClass() == CounterCustomType.class || value.getClass() == CounterKeywordType.class);
    }
    private static boolean entity(final Object value) { return value instanceof Card || value instanceof Player; }
    private static boolean empty(final Map<String, Object> params) {
        return params == null || params == Map.<String, Object>of() || params == Collections.<String, Object>emptyMap()
                || params == ImmutableMap.<String, Object>of();
    }
    static Input capture(final Family family, final Map<String, Object> params) {
        if (empty(params)) { return complete(Kind.NONE, Map.of(), Map.of()); }
        if (params.getClass() != HashMap.class) { return unsupported(); }
        final int size = params.size();
        if (size == 0) { return complete(Kind.NONE, Map.of(), Map.of()); }
        if (size < 0 || size > 3) { return unsupported(); }
        if (family == Family.NUMBER) {
            if (size == 1 && params.containsKey("Voter") && params.get("Voter") instanceof Player voter) {
                return roles(Kind.VOTER, Map.of(), Map.of("voter", voter));
            }
            if ((size == 2 || size == 3 && params.containsKey("Source")) && params.containsKey("Target") && params.containsKey("CounterType")) {
                final Object target = params.get("Target"), counter = params.get("CounterType");
                final Object source = size == 3 ? params.get("Source") : null;
                if (!entity(target) || size == 3 && !entity(source) || !nativeCounter(counter)) { return unsupported(); }
                final Kind kind = size == 3 ? Kind.COUNTER_TRANSFER_AMOUNT : Kind.COUNTER_AMOUNT;
                final String name = ((CounterType) counter).getName();
                if (name == null) { return unsupported(); }
                if (name.length() > AiInputContext.MAX_TEXT) { return overlong(kind); }
                return roles(kind, Map.of("counterType", name), size == 3
                        ? Map.of("source", (GameEntity) source, "target", (GameEntity) target) : Map.of("target", (GameEntity) target));
            }
        } else {
            if (size == 1) {
                if (params.containsKey("Attachments")) { return roleList(Kind.ENTITY_ATTACHMENT_ROLES, params.get("Attachments")); }
                if (params.containsKey("Attackers")) { return roleList(Kind.ENTITY_ATTACKER_ROLES, params.get("Attackers")); }
                for (final String key : new String[] {"Attach", "Attacker", "Partner"}) {
                    if (params.containsKey(key)) {
                        if (!(params.get(key) instanceof Card card)) { return unsupported(); }
                        return roles(Kind.ENTITY_CARD_ROLE, Map.of("role", key), Map.of("entity", card));
                    }
                }
                if (params.containsKey("Target")) {
                    final Object target = params.get("Target");
                    return entity(target) ? roles(Kind.ENTITY_TARGET_ROLE, Map.of(), Map.of("target", (GameEntity) target)) : unsupported();
                }
                if (params.containsKey("CMCLimit")) {
                    return params.get("CMCLimit") instanceof Integer value
                            ? complete(Kind.ENTITY_MANA_VALUE_LIMIT, Map.of("manaValueLimit", value), Map.of()) : unsupported();
                }
            }
            if (params.containsKey("CounterType") && (size == 1 || size == 2 && (params.containsKey("Amount") || params.containsKey("Target"))
                    || size == 3 && params.containsKey("Amount") && params.containsKey("Target"))) {
                final Object counter = params.get("CounterType");
                final boolean hasAmount = params.containsKey("Amount"), hasTarget = params.containsKey("Target");
                final Object amount = hasAmount ? params.get("Amount") : null, target = hasTarget ? params.get("Target") : null;
                if (!nativeCounter(counter) || hasAmount && !(amount instanceof Integer) || hasTarget && !entity(target)) { return unsupported(); }
                final String name = ((CounterType) counter).getName();
                if (name == null) { return unsupported(); }
                if (name.length() > AiInputContext.MAX_TEXT) { return overlong(Kind.ENTITY_COUNTER_REQUEST); }
                final Map<String, Object> values = new LinkedHashMap<>(); values.put("counterType", name); values.put("amount", amount);
                if (!hasTarget) { values.put("target", null); }
                return roles(Kind.ENTITY_COUNTER_REQUEST, values, hasTarget ? Map.of("target", (GameEntity) target) : Map.of());
            }
        }
        return unsupported();
    }
    private static Input roleList(final Kind kind, final Object value) {
        final boolean knownEmpty = value == CardCollection.EMPTY || value == List.of() || value == Collections.emptyList()
                || value == ImmutableList.of();
        if (!knownEmpty && (value == null || value.getClass() != CardCollection.class && value.getClass() != ArrayList.class)) { return unsupported(); }
        final List<Card> cards = new ArrayList<>();
        if (!knownEmpty) {
            final Collection<?> collection = (Collection<?>) value;
            if (collection.size() > MAX_ROLE_LIST) { return overlong(kind); }
            final var iterator = collection.iterator();
            while (iterator.hasNext()) {
                if (cards.size() == MAX_ROLE_LIST) { return overlong(kind); }
                final Object item = iterator.next();
                if (!(item instanceof Card card)) { return unsupported(); }
                cards.add(card);
            }
        }
        // Validate the whole bounded native collection before any entity identity getter.
        final List<Map<String, Object>> references = new ArrayList<>();
        final List<AiOptionInput.Entry> entries = new ArrayList<>();
        for (final Card card : cards) {
            final Map<String, Object> reference = Collections.unmodifiableMap(AiTargetChoiceRecorder.reference(card));
            references.add(reference); entries.add(new AiOptionInput.Entry(card, reference));
        }
        return new Input(kind, "COMPLETE", null, Map.of("entities", List.copyOf(references)), Map.of(), Map.of("entities", List.copyOf(entries)));
    }
    private static Input overlong(final Kind kind) { return new Input(kind, "UNAVAILABLE", "OUTSIDE_CAPTURED_PREFIX", null, Map.of(), Map.of()); }
    private static Input roles(final Kind kind, final Map<String, Object> scalars, final Map<String, ? extends GameEntity> objects) {
        final Map<String, Object> values = new LinkedHashMap<>(scalars);
        final Map<String, AiOptionInput.Entry> roles = new LinkedHashMap<>();
        // Order is declared by the shape, never by an arbitrary input map's iteration order.
        for (final String key : new String[] {"voter", "source", "target", "entity"}) {
            final GameEntity object = objects.get(key);
            if (object != null) {
                final Map<String, Object> reference = Collections.unmodifiableMap(AiTargetChoiceRecorder.reference(object));
                values.put(key, reference); roles.put(key, new AiOptionInput.Entry(object, reference));
            }
        }
        return complete(kind, values, roles);
    }
    private static Input complete(final Kind kind, final Map<String, Object> values, final Map<String, AiOptionInput.Entry> roles) {
        return new Input(kind, "COMPLETE", null, Collections.unmodifiableMap(new LinkedHashMap<>(values)), Map.copyOf(roles), Map.of());
    }
}
