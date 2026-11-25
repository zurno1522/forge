package forge.ai;

import com.google.gson.GsonBuilder;
import forge.game.ability.AbilityFactory;
import forge.game.ability.effects.ChangeZoneEffect;
import forge.game.zone.ZoneType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual engine resolution excerpts; these are not complete-game trace envelopes. */
public final class ZoneVariantExamples extends AITest {
    public static void main(String[] args) throws Exception {
        var harness = new ZoneVariantExamples(); harness.initializeModel();
        var examples = new LinkedHashMap<String, Object>();
        for (boolean named : List.of(true, false)) {
            var game = harness.initAndCreateGame(); var player = game.getPlayers().get(1);
            var source = harness.addCardToZone("Time Beetle", player, ZoneType.Battlefield);
            for (var name : List.of("Forest", "Island")) {
                harness.addCardToZone(name, player, ZoneType.Library);
                harness.addCardToZone(name, player, ZoneType.Battlefield);
            }
            var ability = AbilityFactory.getAbility("AB$ ChangeZone | Cost$ 0 | Origin$ Library | Destination$ Hand"
                    + " | ChangeType$ " + (named ? "Card | SameNameAsEach$ Valid Land.YouCtrl" : "EACH Forest & Island")
                    + " | ChangeNum$ 2 | Mandatory$ True | Shuffle$ False", source);
            ability.setActivatingPlayer(player); game.setRecordAiDecisions(true);
            var rows = new ArrayList<Map<String, Object>>();
            game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { rows.add(choice.data()); } });
            new ChangeZoneEffect().resolve(ability);
            if (player.getCardsIn(ZoneType.Hand).size() != 2 || game.getEventObservationFailure() != null) {
                throw new AssertionError("Original movement/observation health changed");
            }
            examples.put(named ? "SAME_NAME_HIDDEN_ORIGIN" : "EACH_TYPE_HIDDEN_ORIGIN", rows);
        }
        Files.writeString(Path.of(args[0]), new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(
                Map.of("fixtureKind", "ACTUAL_ENGINE_RESOLUTION_EXCERPTS", "completeGameTrace", false, "choices", examples)) + "\n");
        System.exit(0);
    }
}
