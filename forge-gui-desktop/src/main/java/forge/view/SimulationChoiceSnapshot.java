package forge.view;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Copies committed action parameters without consulting the AI's evaluation caches. */
final class SimulationChoiceSnapshot {
    private SimulationChoiceSnapshot() { }

    static Map<String, Object> payments(final SpellAbilityStackInstance entry) {
        if (entry == null || entry.getSpellAbility().isCopied()) { return null; }
        final SpellAbility sa = entry.getSpellAbility();
        final List<Map<String, Object>> mana = new ArrayList<>();
        for (final var paid : sa.getPayingMana()) {
            final Map<String, Object> unit = new LinkedHashMap<>();
            unit.put("color", paid.toString());
            unit.put("playerId", paid.getPlayer() == null ? null : paid.getPlayer().getId());
            unit.put("source", cardIdentity(paid.getSourceCard()));
            unit.put("snow", paid.getSourceCard() == null ? null : paid.isSnow());
            mana.add(unit);
        }
        final List<String> optional = new ArrayList<>();
        for (final var cost : sa.getOptionalCosts()) { optional.add(cost.name()); }
        final List<Map<String, Object>> keywords = new ArrayList<>();
        for (final var keyword : sa.getOptionalKeywords().stream().sorted().toList()) {
            keywords.add(Map.of("keyword", keyword.name(), "amount", sa.getOptionalKeywordAmount(keyword)));
        }
        final List<Map<String, Object>> cards = new ArrayList<>();
        int unrecognized = 0;
        for (final var cell : sa.getPaidHash().cellSet()) {
            if (!knownPaymentType(cell.getRowKey())) { unrecognized++; continue; }
            final List<Map<String, Object>> identities = new ArrayList<>();
            for (final Card card : cell.getValue()) { identities.add(cardIdentity(card)); }
            cards.add(Map.of("type", cell.getRowKey(), "intrinsic", cell.getColumnKey(), "cards", identities));
        }
        final Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("coverage", "ENGINE_PAYMENT_FACTS_V1");
        snapshot.put("mana", mana);
        // Player.payLife overwrites this field per payment; it is not an aggregate.
        snapshot.put("lastLifePayment", sa.getAmountLifePaid());
        snapshot.put("phyrexianLifePaid", sa.getSpendPhyrexianMana());
        snapshot.put("optionalCosts", optional);
        snapshot.put("alternativeCost", sa.getAlternativeCost() == null ? null : sa.getAlternativeCost().name());
        snapshot.put("keywordAmounts", keywords);
        snapshot.put("withoutManaCost", sa.hasParam("WithoutManaCost"));
        snapshot.put("cardPayments", cards);
        snapshot.put("unrecognizedCardPaymentGroups", unrecognized);
        return snapshot;
    }

    private static Map<String, Object> cardIdentity(final Card card) {
        return card == null ? null : Map.of("id", card.getId(), "gameTimestamp", card.getGameTimestamp());
    }

    private static boolean knownPaymentType(final String type) {
        final String base = type.endsWith("Cards") ? type.substring(0, type.length() - 5) : type;
        return switch (base) {
            case "Discarded", "Enlisted", "Exerted", "Exiled", "MovedToGrave", "ControlGained", "CardPutToLib",
                    "CounterPut", "Returned", "Revealed", "Sacrificed", "Tapped", "Unattached", "Untapped" -> true;
            default -> false;
        };
    }

    static Map<String, Object> x(final SpellAbilityStackInstance entry) {
        if (entry == null) { return null; }
        final Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("announcedValue", entry.getSpellAbility().getXManaCostPaid());
        return snapshot;
    }

    static Map<String, Object> modes(final SpellAbilityStackInstance entry) {
        if (entry == null) { return null; }
        final SpellAbility root = entry.getSpellAbility();
        final boolean modal = root.getApi() == ApiType.Charm;
        final List<Map<String, Object>> modes = new ArrayList<>();
        if (modal) {
            // CharmEffect appends copies of each chosen option's entire ability chain. Match the
            // original script parameters of that chain, not rendered text or the cached chosenList.
            for (SpellAbilityStackInstance current = entry.getSubInstance(); current != null;) {
                SpellAbilityStackInstance next = null;
                boolean matched = false;
                Integer optionIndex = null;
                final var options = root.getAdditionalAbilityList("Choices");
                for (int index = 0; index < options.size(); index++) {
                    SpellAbility template = options.get(index);
                    SpellAbilityStackInstance actual = current;
                    while (template != null && actual != null && template.getOriginalMapParams()
                            .equals(actual.getSpellAbility().getOriginalMapParams())) {
                        template = template.getSubAbility();
                        actual = actual.getSubInstance();
                    }
                    if (template != null) { continue; }
                    if (matched && next != actual) { return null; } // Ambiguous chain boundary.
                    optionIndex = matched ? null : index;
                    next = actual;
                    matched = true;
                }
                if (!matched) { return null; }
                final Map<String, Object> mode = new LinkedHashMap<>();
                mode.put("abilityId", current.getSpellAbility().getId());
                mode.put("optionIndex", optionIndex);
                modes.add(mode);
                current = next;
            }
        }
        return Map.of("schemaVersion", 1, "modal", modal, "modes", modes);
    }
}
