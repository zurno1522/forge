package forge.ai;

import com.google.gson.GsonBuilder;
import forge.game.ability.AbilityFactory;
import forge.game.ability.effects.ChooseGenericEffect;
import forge.game.ability.effects.VillainousChoiceEffect;
import forge.game.zone.ZoneType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual engine/controller excerpts, not complete-game trace envelopes. */
public final class SpellChoiceExamples extends AITest {
    public static void main(String[] args) throws Exception {
        var harness = new SpellChoiceExamples(); harness.initializeModel(); var examples = new LinkedHashMap<String, Object>();
        for (var label : List.of("GENERIC", "VILLAINOUS", "ZERO_REQUEST", "DECLINED")) {
            var game = harness.initAndCreateGame(); var player = game.getPlayers().get(1);
            var source = harness.addCardToZone("Time Beetle", player, ZoneType.Battlefield);
            source.setSVar("ChoiceLife", "DB$ GainLife | LifeAmount$ 3"); source.setSVar("ChoiceDraw", "DB$ Draw | NumCards$ 1");
            var api = label.equals("VILLAINOUS") ? "VillainousChoice" : label.equals("DECLINED") ? "AssignGroup" : "GenericChoice";
            var ability = AbilityFactory.getAbility("AB$ " + api + " | Cost$ 0 | Defined$ You | Choices$ ChoiceLife,ChoiceDraw | ChoiceAmount$ 2", source);
            ability.setActivatingPlayer(player); for (var choice : ability.getAdditionalAbilityList("Choices")) { choice.setActivatingPlayer(player); }
            harness.addCardToZone("Forest", player, ZoneType.Library);
            var rows = new ArrayList<Map<String, Object>>(); game.setRecordAiDecisions(true);
            game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { rows.add(choice.data()); } });
            switch (label) {
                case "GENERIC" -> { new ChooseGenericEffect().resolve(ability); if (player.getLife() != 23 || player.getCardsIn(ZoneType.Hand).size() != 1) { throw new AssertionError("Generic effect changed"); } }
                case "VILLAINOUS" -> { new VillainousChoiceEffect().resolve(ability); if (player.getLife() != 23) { throw new AssertionError("Villainous effect changed"); } }
                case "ZERO_REQUEST" -> {
                    var result = player.getController().chooseSpellAbilitiesForEffect(new ArrayList<>(ability.getAdditionalAbilityList("Choices")), ability, "Choose", 0, Map.of());
                    if (result.size() != 1) { throw new AssertionError("Original do-while changed"); }
                }
                case "DECLINED" -> {
                    if (!player.getController().chooseSpellAbilitiesForEffect(List.of(), ability, "Choose", 2, Map.of()).isEmpty()) { throw new AssertionError("Declined result changed"); }
                }
                default -> throw new AssertionError(label);
            }
            if (game.getEventObservationFailure() != null) { throw new AssertionError("Observation failure"); } examples.put(label, rows);
        }
        Files.writeString(Path.of(args[0]), new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(
                Map.of("fixtureKind", "ACTUAL_ENGINE_AND_CONTROLLER_EXCERPTS", "completeGameTrace", false, "choices", examples)) + "\n");
        System.exit(0);
    }
}
