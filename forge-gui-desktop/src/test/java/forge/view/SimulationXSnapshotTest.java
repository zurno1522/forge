package forge.view;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Map;

public class SimulationXSnapshotTest extends AITest {
    @Test(groups = {"UnitTest", "fast"})
    public void zeroUnannouncedAndUnavailableRemainDifferentAndSnapshotsDoNotChange() {
        final Game game = initAndCreateGame();
        final SpellAbility sa = addCardToZone("Walking Ballista", game.getPlayers().get(1), ZoneType.Hand).getFirstSpellAbility();
        final Map<String, Object> unannounced = SimulationChoiceSnapshot.x(new SpellAbilityStackInstance(sa));
        Assert.assertTrue(unannounced.containsKey("announcedValue"));
        Assert.assertNull(unannounced.get("announcedValue"));
        sa.setXManaCostPaid(0);
        final Map<String, Object> zero = SimulationChoiceSnapshot.x(new SpellAbilityStackInstance(sa));
        Assert.assertEquals(zero.get("announcedValue"), 0);
        sa.setXManaCostPaid(3);
        Assert.assertEquals(zero.get("announcedValue"), 0, "Subsequent changes cannot rewrite the earlier announcement");
        Assert.assertNull(unannounced.get("announcedValue"));
        Assert.assertNull(SimulationChoiceSnapshot.x(null));
    }
}
