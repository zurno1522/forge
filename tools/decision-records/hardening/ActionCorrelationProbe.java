package forge.view;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.ai.RecordedSelectionTestSupport;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.Event;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ActionCorrelationProbe extends AITest {
    private record PlayInsideDispatch(Runnable action) implements Event { }

    public static final class ReentrantSubscriber {
        @Subscribe
        public void play(final PlayInsideDispatch event) {
            event.action().run();
        }
    }

    public static void main(final String[] args) throws Exception {
        final ActionCorrelationProbe probe = new ActionCorrelationProbe();
        final Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        probe.initializeModel();
        probe.run(output, "ordinary", false, false);
        probe.run(output, "granted", true, false);
        probe.run(output, "nested", false, true);
    }

    private void run(final Path output, final String name, final boolean granted, final boolean nested)
            throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 3);
        final Card source = addCardToZone(granted ? "Necrotic Ooze" : "Archivist", ai, ZoneType.Battlefield);
        source.setSickness(false);
        if (granted) {
            addCardToZone("Archivist", ai, ZoneType.Graveyard);
            game.getAction().checkStaticAbilities();
        }
        final SpellAbility selected = source.getSpellAbilities().stream()
                .filter(SpellAbility::isActivatedAbility)
                .findFirst().orElseThrow();
        final SpellAbility original = selected.getOriginalAbility();
        require((original != null) == granted, "unexpected selected original");
        if (granted) {
            require("Archivist".equals(original.getHostCard().getName()), "missing real Archivist grant");
        }

        final Path full = output.resolve(name + "-full.jsonl");
        final Path decisions = output.resolve(name + "-decisions.jsonl");
        final long stackId;
        final long actualId;
        try (SimulationTraceWriter fullWriter = SimulationTraceWriter.open(full.toString());
                SimulationTraceWriter decisionWriter = SimulationTraceWriter.open(decisions.toString())) {
            final SimulationTraceCollector collector = new SimulationTraceCollector(
                    game, fullWriter, decisionWriter, 1, Map.of(), null, null, null);
            collector.attach();
            final Runnable play = () -> {
                RecordedSelectionTestSupport.select(ai, selected);
                ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(selected);
            };
            if (nested) {
                game.subscribeToEvents(new ReentrantSubscriber());
                game.fireEvent(new PlayInsideDispatch(play));
            } else {
                play.run();
            }
            require(!game.getStack().isEmpty(), "the ability must actually reach the stack");
            final var item = game.getStack().peek();
            final SpellAbility actual = item.getSpellAbility();
            require(actual != selected, "activation did not take the engine copy path");
            require(actual.getOriginalAbility() == (granted ? original : selected), "unexpected actual original");
            stackId = item.getId();
            actualId = actual.getId();
            collector.executionFinished("NORMAL", null);
        }
        final List<JsonObject> fullRows = read(full);
        final List<JsonObject> decisionRows = read(decisions);
        final JsonObject decision = row(fullRows, "AI_DECISION");
        final JsonObject added = row(fullRows, "STACK_ADDED");
        final List<JsonObject> execution = fullRows.stream()
                .filter(r -> "AI_ACTION_EXECUTION".equals(r.get("type").getAsString())).toList();
        require(execution.size() == 2, "unexpected number of execution events");
        final JsonObject started = execution.get(0);
        final JsonObject terminal = execution.get(1);
        final JsonObject terminalValues = terminal.getAsJsonObject("execution");
        require("STARTED".equals(started.getAsJsonObject("execution").get("status").getAsString()),
                "missing start event");
        require(index(decision) < index(started) && index(started) < index(added) && index(added) < index(terminal),
                "unexpected emitter order");
        require(added.get("stackId").getAsLong() == stackId && added.get("abilityId").getAsLong() == actualId,
                "wrong live stack identity");
        // Emission-time capture covers ordinary, granted and reentrant activations.
        final boolean missing = false;
        require(added.get("actionId").isJsonNull() == missing, "unexpected action linkage");
        require((!terminalValues.has("stackId")) == missing, "unexpected terminal stackId");
        require((!terminalValues.has("executedAbilityId")) == missing, "unexpected terminal executedAbilityId");
        require((missing ? "UNOBSERVED" : "SUCCEEDED").equals(terminalValues.get("status").getAsString()),
                "unexpected terminal status");
        require((missing ? "NO_STACK_ENTRY_OBSERVED" : "STACKED").equals(terminalValues.get("reason").getAsString()),
                "unexpected terminal reason");
        require(decisionRows.stream().anyMatch(r -> "STACK_ADDED".equals(r.get("type").getAsString())) == !missing,
                "unexpected filtered stack coverage");
        System.out.printf("%s selected=%d original=%s actual=%d stack=%d indices=%d/%d/%d/%d"
                        + " addedAction=%s terminal=%s reason=%s filteredAddition=%s%n",
                name, selected.getId(), original == null ? "null" : original.getId(), actualId, stackId,
                index(decision), index(started), index(added), index(terminal),
                added.get("actionId"), terminalValues.get("status"), terminalValues.get("reason"), !missing);
    }

    private static List<JsonObject> read(final Path path) throws Exception {
        return Files.readAllLines(path).stream().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    }

    private static JsonObject row(final List<JsonObject> rows, final String type) {
        return rows.stream().filter(r -> type.equals(r.get("type").getAsString())).findFirst().orElseThrow();
    }

    private static long index(final JsonObject row) {
        return row.get("eventIndex").getAsLong();
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
