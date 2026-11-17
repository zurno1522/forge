package forge.view;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.ability.effects.CharmEffect;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SimulationModeSnapshotTest extends AITest {
    private SpellAbility spell(final Game game, final String name) {
        final Player ai = game.getPlayers().get(1);
        final SpellAbility sa = addCardToZone(name, ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> modes(final Map<String, Object> snapshot) {
        return (List<Map<String, Object>>) snapshot.get("modes");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void committedCrypticModesOverrideAStaleEvaluationCache() {
        final Game game = initAndCreateGame();
        final SpellAbility sa = spell(game, "Cryptic Command");
        final List<AbilitySub> options = sa.getAdditionalAbilityList("Choices");
        CharmEffect.makePossibleOptions(sa);
        CharmEffect.chainAbilities(sa, new ArrayList<>(List.of(options.get(2), options.get(3))));
        sa.setChosenList(List.of(options.get(0))); // Deliberately stale counterspell evaluation.
        final Map<String, Object> snapshot = SimulationChoiceSnapshot.modes(new SpellAbilityStackInstance(sa));
        Assert.assertEquals(snapshot.get("modal"), true);
        Assert.assertEquals(modes(snapshot).stream().map(m -> m.get("optionIndex")).toList(), List.of(2, 3));
        Assert.assertEquals(modes(snapshot).get(0).get("abilityId"), sa.getSubAbility().getId());
        Assert.assertNotEquals(modes(snapshot).get(0).get("abilityId"), options.get(2).getId());
        sa.setSubAbility(null);
        Assert.assertEquals(modes(snapshot).size(), 2, "Later changes must not mutate the snapshot");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void repeatedModesAndNestedEffectsRetainTheirBoundaries() {
        final Game game = initAndCreateGame();
        final SpellAbility confluence = spell(game, "Fiery Confluence");
        final List<AbilitySub> options = CharmEffect.makePossibleOptions(confluence);
        final AbilitySub first = options.get(0);
        CharmEffect.chainAbilities(confluence, new ArrayList<>(List.of(first, first, first)));
        final List<Map<String, Object>> repeated = modes(SimulationChoiceSnapshot.modes(new SpellAbilityStackInstance(confluence)));
        Assert.assertEquals(repeated.stream().map(m -> m.get("optionIndex")).toList(), List.of(0, 0, 0));
        Assert.assertEquals(repeated.stream().map(m -> m.get("abilityId")).distinct().count(), 3L);
        final SpellAbility inscription = spell(game, "Inscription of Abundance");
        CharmEffect.makePossibleOptions(inscription);
        final AbilitySub fight = inscription.getAdditionalAbilityList("Choices").get(2);
        CharmEffect.chainAbilities(inscription, new ArrayList<>(List.of(fight)));
        Assert.assertNotNull(inscription.getSubAbility().getSubAbility(), "The chosen mode has a nested fight effect");
        final List<Map<String, Object>> nested = modes(SimulationChoiceSnapshot.modes(new SpellAbilityStackInstance(inscription)));
        Assert.assertEquals(nested.size(), 1, "A nested effect is not another selected mode");
        Assert.assertEquals(nested.get(0).get("optionIndex"), 2);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void entwineRecordsTheEngineChainWithoutAnAiChosenList() {
        final Game game = initAndCreateGame();
        final SpellAbility sa = spell(game, "Tooth and Nail");
        sa.addOptionalCost(OptionalCost.Entwine);
        Assert.assertNull(sa.getChosenList());
        Assert.assertTrue(CharmEffect.makeChoices(sa));
        final Map<String, Object> snapshot = SimulationChoiceSnapshot.modes(new SpellAbilityStackInstance(sa));
        Assert.assertEquals(modes(snapshot).stream().map(m -> m.get("optionIndex")).toList(), List.of(0, 1));
        Assert.assertNull(sa.getChosenList());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void unavailableOrAmbiguousDefinitionsAreNotGuessed() {
        final Game game = initAndCreateGame();
        final SpellAbility sa = spell(game, "Cryptic Command");
        CharmEffect.makePossibleOptions(sa);
        final AbilitySub draw = sa.getAdditionalAbilityList("Choices").get(3);
        CharmEffect.chainAbilities(sa, new ArrayList<>(List.of(draw)));
        sa.setAdditionalAbilityList("Choices", List.of(draw, draw));
        final Map<String, Object> ambiguous = SimulationChoiceSnapshot.modes(new SpellAbilityStackInstance(sa));
        Assert.assertNull(modes(ambiguous).get(0).get("optionIndex"));
        sa.setAdditionalAbilityList("Choices", List.of());
        Assert.assertNull(SimulationChoiceSnapshot.modes(new SpellAbilityStackInstance(sa)));
        Assert.assertNull(SimulationChoiceSnapshot.modes(null));
        final Map<String, Object> nonmodal = SimulationChoiceSnapshot.modes(new SpellAbilityStackInstance(spell(game, "Memnite")));
        Assert.assertEquals(nonmodal.get("modal"), false);
        Assert.assertTrue(modes(nonmodal).isEmpty());
    }
}
