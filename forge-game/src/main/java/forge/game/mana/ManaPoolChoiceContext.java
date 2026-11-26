package forge.game.mana;

import forge.card.mana.ManaCostShard;
import forge.game.spellability.SpellAbility;

/** Received engine arguments for a distinct best-weight floating-mana tie, before controller selection. */
public record ManaPoolChoiceContext(SpellAbility ability, ManaCostShard shard, boolean testPayment, byte colorsPaid) { }
