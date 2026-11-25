package forge.ai;

import com.google.gson.GsonBuilder;
import forge.game.ability.AbilityFactory;
import forge.game.card.CardCollection;
import forge.game.player.ZoneChangeSelectionRequest;
import forge.game.zone.ZoneType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Baseline/fixed actual-callback reproduction; emitted rows may be intentionally invalid on the baseline. */
public final class OriginDiagnosticReproduction extends AITest {
    public static void main(String[] args) throws Exception {
        var harness = new OriginDiagnosticReproduction(); harness.initializeModel();
        var results = new LinkedHashMap<String, Object>();
        for (boolean outer : List.of(false, true)) {
            var game = harness.initAndCreateGame(); var player = game.getPlayers().get(1);
            var source = harness.addCardToZone("Time Beetle", player, ZoneType.Battlefield);
            var card = harness.addCardToZone("Grizzly Bears", player, ZoneType.Graveyard);
            var ability = AbilityFactory.getAbility("AB$ ChangeZone | Cost$ 0 | Origin$ Library | Destination$ Hand | ChangeType$ Card", source);
            ability.setActivatingPlayer(player); game.setRecordAiDecisions(true);
            var ledger = ((PlayerControllerAi) player.getController()).configureObservationAccounting();
            var rows = new ArrayList<Map<String, Object>>();
            game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { rows.add(choice.data()); } });
            var origins = new ArrayList<>(Collections.nCopies(ZoneType.values().length + 1, ZoneType.Library));
            var calls = new AtomicInteger(); boolean same;
            if (outer) {
                var returned = new CardCollection(card);
                same = player.getController().withZoneChangeSelections(new ZoneChangeSelectionRequest(ability, ZoneType.Hand,
                        origins, player, player, 1), ignored -> { calls.incrementAndGet(); return returned; }) == returned;
            } else {
                same = AiEffectChoiceRecorder.zoneChange(player, ability, ZoneType.Hand, origins, new CardCollection(card),
                        true, player, () -> { calls.incrementAndGet(); return card; }) == card;
            }
            if (calls.get() != 1 || !same || game.getEventObservationFailure() != null) { throw new AssertionError("Callback/health changed"); }
            results.put(outer ? "OUTER" : "SINGLE", Map.of("callbackCalls", calls.get(), "sameResult", same,
                    "observationFailure", false, "originCount", origins.size(), "events", rows, "accounting", ledger.snapshot()));
        }
        Files.writeString(Path.of(args[0]), new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(
                Map.of("fixtureKind", "ACTUAL_CALLBACK_BOUND_REPRODUCTION", "completeGameTrace", false, "results", results)) + "\n");
        System.exit(0);
    }
}
