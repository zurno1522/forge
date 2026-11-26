package forge.ai;

import com.google.gson.GsonBuilder;
import forge.game.ability.AbilityFactory;
import forge.game.ability.effects.AssignGroupEffect;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.mockito.Mockito;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Actual engine/controller excerpts; unsupported and foreign arguments are deliberate fixtures. */
public final class SpellRequestExamples extends AITest {
    public static void main(String[] args) throws Exception {
        var harness = new SpellRequestExamples(); harness.initializeModel(); var examples = new LinkedHashMap<String, Object>();
        for (var label : List.of("FRIEND_FOE_EFFECT", "NO_PARAMETERS", "UNSUPPORTED_MAP", "FOREIGN_EQUAL_ID")) {
            var game = harness.initAndCreateGame(); var actor = game.getPlayers().get(1); var other = game.getPlayers().get(0);
            var source = harness.addCardToZone("Time Beetle", actor, ZoneType.Battlefield);
            source.setSVar("Friend", "DB$ GainLife | Defined$ Remembered | LifeAmount$ 3");
            source.setSVar("Foe", "DB$ LoseLife | Defined$ Remembered | LifeAmount$ 2");
            boolean group = label.equals("FRIEND_FOE_EFFECT");
            var ability = AbilityFactory.getAbility("AB$ " + (group ? "AssignGroup" : "GenericChoice")
                    + " | Cost$ 0 | Defined$ Player | Choices$ Friend,Foe" + (group ? " | AILogic$ FriendOrFoe" : ""), source);
            ability.setActivatingPlayer(actor); var choices = new ArrayList<SpellAbility>(ability.getAdditionalAbilityList("Choices"));
            for (var choice : choices) { choice.setActivatingPlayer(actor); }
            var rows = new ArrayList<Map<String, Object>>(); game.setRecordAiDecisions(true);
            game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { rows.add(choice.data()); } });
            if (group) {
                new AssignGroupEffect().resolve(ability);
                if (actor.getLife() != 23 || other.getLife() != 18) { throw new AssertionError("Friend/foe effects changed"); }
            } else {
                Map<String, Object> params = Map.of();
                if (label.equals("UNSUPPORTED_MAP")) {
                    params = new AbstractMap<>() { @Override public Set<Entry<String, Object>> entrySet() { throw new AssertionError("unknown map inspected"); } };
                }
                if (label.equals("FOREIGN_EQUAL_ID")) {
                    var foreign = Mockito.spy(harness.initAndCreateGame().getPlayers().get(0)); Mockito.doReturn(other.getId()).when(foreign).getId();
                    params = new HashMap<>(); params.put("Affected", foreign);
                }
                if (actor.getController().chooseSingleSpellForEffect(choices, ability, "Choose", params) != choices.get(0)) { throw new AssertionError("Original selection changed"); }
            }
            if (game.getEventObservationFailure() != null) { throw new AssertionError("Observation fault"); }
            examples.put(label, rows);
        }
        Files.writeString(Path.of(args[0]), new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(
                Map.of("fixtureKind", "ACTUAL_ENGINE_AND_CONTROLLER_EXCERPTS", "completeGameTrace", false, "choices", examples)) + "\n");
        System.exit(0);
    }
}
