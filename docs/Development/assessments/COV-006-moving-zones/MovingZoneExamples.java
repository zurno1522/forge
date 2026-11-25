package forge.ai;

import com.google.gson.GsonBuilder;
import forge.game.ability.AbilityFactory;
import forge.game.ability.effects.DraftEffect;
import forge.game.ability.effects.HeistEffect;
import forge.game.zone.ZoneType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual engine-resolution excerpts, including rejected returns; not complete-game traces. */
public final class MovingZoneExamples extends AITest {
    public static void main(String[] args) throws Exception {
        var harness = new MovingZoneExamples(); harness.initializeModel();
        var examples = new LinkedHashMap<String, Object>();
        for (var label : List.of("HEIST", "HEIST_REJECTED", "DRAFT")) {
            boolean draft = label.equals("DRAFT"), reject = label.equals("HEIST_REJECTED");
            var game = harness.initAndCreateGame(); var player = game.getPlayers().get(1); var target = game.getPlayers().get(0);
            var source = harness.addCardToZone("Time Beetle", player, ZoneType.Battlefield);
            for (var name : List.of("Grizzly Bears", "Shivan Dragon", "Elvish Visionary", "Forest")) { harness.addCardToZone(name, target, ZoneType.Library); }
            var text = draft ? "Draft | Spellbook$ Grizzly Bears,Shivan Dragon,Forest | DraftNum$ 2" : "Heist | ValidTgts$ Opponent | Num$ 2";
            var ability = AbilityFactory.getAbility("AB$ " + text + " | Cost$ 0 | Defined$ You", source);
            ability.setActivatingPlayer(player); if (!draft) { ability.getTargets().add(target); }
            if (reject) { source.addStaticAbility("Mode$ CantExile | ValidCard$ Card | ForCost$ False"); }
            var rows = new ArrayList<Map<String, Object>>(); game.setRecordAiDecisions(true);
            game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { rows.add(choice.data()); } });
            if (draft) { new DraftEffect().resolve(ability); } else { new HeistEffect().resolve(ability); }
            if (game.getEventObservationFailure() != null || (draft ? player.getCardsIn(ZoneType.Hand).size() != 2
                    : target.getCardsIn(ZoneType.Exile).size() != (reject ? 0 : 2))) { throw new AssertionError("Original movement/health changed"); }
            examples.put(label, rows);
        }
        Files.writeString(Path.of(args[0]), new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(
                Map.of("fixtureKind", "ACTUAL_ENGINE_RESOLUTION_EXCERPTS", "completeGameTrace", false, "choices", examples)) + "\n");
        System.exit(0);
    }
}
