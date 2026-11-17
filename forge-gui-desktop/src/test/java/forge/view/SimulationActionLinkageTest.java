package forge.view;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.ai.RecordedSelectionTestSupport;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.event.GameEventSpellResolved;
import forge.game.player.Player;
import forge.game.spellability.LandAbility;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class SimulationActionLinkageTest extends AITest {
    @DataProvider
    public Object[][] actions() {
        return new Object[][] { {true, "Memnite"}, {false, "Memnite"},
                {true, "Archivist"}, {false, "Archivist"}, {true, "Island"}, {false, "Island"} };
    }

    private SpellAbility ability(final Game game, final String name) {
        final Player ai = game.getPlayers().get(1);
        final Card card = addCardToZone(name, ai, name.equals("Archivist") ? ZoneType.Battlefield : ZoneType.Hand);
        card.setSickness(false);
        if (name.equals("Island")) { return new LandAbility(card, null); }
        return name.equals("Archivist") ? card.getSpellAbilities().stream()
                .filter(SpellAbility::isActivatedAbility).findFirst().orElseThrow() : card.getFirstSpellAbility();
    }

    @Test(dataProvider = "actions", groups = {"UnitTest", "fast"})
    public void existingEngineEventsKeepTheSelectionIdentity(final boolean full, final String name) throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (final Player player : game.getPlayers()) { fillLibrary(player, 10); }
        final SpellAbility selected = ability(game, name);
        // Register the collector before the lazily created AI tracker, as in real simulations.
        try (Trace trace = new Trace(game, full)) {
            RecordedSelectionTestSupport.select(ai, selected);
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(selected);
            if (!selected.isLandAbility()) { game.getStack().resolveStack(); }
            final List<JsonObject> rows = trace.decisions();
            final JsonObject decision = row(rows, "AI_DECISION").getAsJsonObject("decision");
            final String actionId = decision.getAsJsonArray("selectedActions").get(0).getAsJsonObject().get("actionId").getAsString();
            final List<String> types = selected.isLandAbility() ? List.of("LAND_PLAYED")
                    : List.of("STACK_ADDED", "STACK_RESOLVED", "STACK_REMOVED");
            for (final String type : types) {
                final JsonObject event = row(rows, type);
                Assert.assertEquals(event.get("decisionId"), decision.get("decisionId"));
                Assert.assertEquals(event.get("actionId").getAsString(), actionId);
                Assert.assertFalse(event.has("source"));
                Assert.assertFalse(event.has("choices"));
                if (full) {
                    final JsonObject complete = row(trace.full(), type);
                    Assert.assertEquals(complete.get("eventIndex"), event.get("eventIndex"));
                    Assert.assertEquals(complete.get("actionId"), event.get("actionId"));
                }
            }
            if (!selected.isLandAbility()) {
                final JsonObject added = row(rows, "STACK_ADDED");
                if (name.equals("Archivist")) { Assert.assertNotEquals(added.get("abilityId").getAsLong(), selected.getId()); }
                Assert.assertFalse(row(rows, "STACK_RESOLVED").get("fizzled").getAsBoolean());
                Assert.assertEquals(row(rows, "STACK_REMOVED").get("stackId"), added.get("stackId"));
                final JsonObject terminal = rows.stream().filter(r -> r.get("type").getAsString().equals("AI_ACTION_EXECUTION")
                        && r.getAsJsonObject("execution").get("status").getAsString().equals("SUCCEEDED")).findFirst().orElseThrow();
                Assert.assertTrue(added.get("eventIndex").getAsLong() < terminal.get("eventIndex").getAsLong());
                Assert.assertTrue(terminal.get("eventIndex").getAsLong() < row(rows, "STACK_RESOLVED").get("eventIndex").getAsLong());
            }
            if (!full) { assertContiguous(rows); }
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void counterEventAfterRemovalStillLinksToTheCounteredAction() throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility selected = ability(game, "Memnite");
        try (Trace trace = new Trace(game, true)) {
            RecordedSelectionTestSupport.select(ai, selected);
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(selected);
            final Player opponent = game.getPlayers().get(0);
            final SpellAbility counter = addCardToZone("Counterspell", opponent, ZoneType.Hand).getFirstSpellAbility();
            counter.setActivatingPlayer(opponent);
            counter.getTargets().add(game.getStack().peekAbility());
            AbilityUtils.resolve(counter);
            Assert.assertTrue(game.getStack().isEmpty());
            final List<JsonObject> rows = trace.decisions();
            final JsonObject added = row(rows, "STACK_ADDED");
            final JsonObject removed = row(rows, "STACK_REMOVED");
            final JsonObject countered = row(rows, "STACK_COUNTERED");
            Assert.assertTrue(removed.get("eventIndex").getAsLong() < countered.get("eventIndex").getAsLong());
            Assert.assertEquals(countered.get("actionId"), added.get("actionId"));
            Assert.assertEquals(countered.get("stackId"), added.get("stackId"));
            Assert.assertFalse(countered.has("cause"));
            Assert.assertEquals(row(trace.full(), "STACK_COUNTERED").getAsJsonObject("cause").get("abilityId").getAsLong(), counter.getId());
            Assert.assertTrue(rows.stream().noneMatch(r -> r.get("type").getAsString().equals("STACK_RESOLVED")));
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void bulkClearListsLinkedActionsAndDoesNotLinkLateResolutionByAbility() throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility selected = ability(game, "Memnite");
        final SpellAbility other = ability(game, "Memnite");
        try (Trace trace = new Trace(game, true)) {
            RecordedSelectionTestSupport.select(ai, selected, other);
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(selected);
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(other);
            final SpellAbility actual = game.getStack().peekAbility();
            game.getStack().clear();
            game.fireEvent(new GameEventSpellResolved(actual, false));
            final JsonObject added = row(trace.decisions(), "STACK_ADDED");
            final JsonObject cleared = row(trace.decisions(), "STACK_CLEARED");
            Assert.assertTrue(cleared.get("actionId").isJsonNull());
            final JsonObject reference = cleared.getAsJsonArray("clearedActions").get(0).getAsJsonObject();
            Assert.assertEquals(cleared.getAsJsonArray("clearedActions").size(), 2);
            Assert.assertEquals(reference.get("actionId"), added.get("actionId"));
            Assert.assertEquals(reference.get("stackId"), added.get("stackId"));
            Assert.assertNotEquals(reference.get("actionId"), cleared.getAsJsonArray("clearedActions").get(1).getAsJsonObject().get("actionId"));
            Assert.assertTrue(row(trace.full(), "STACK_RESOLVED").get("actionId").isJsonNull());
            Assert.assertTrue(trace.decisions().stream().noneMatch(r -> r.get("type").getAsString().equals("STACK_RESOLVED")));
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void unrecordedPlaysHaveNullReferencesAndAreOmittedFromDecisionStream() throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility selected = ability(game, "Memnite");
        selected.setActivatingPlayer(ai);
        try (Trace trace = new Trace(game, true)) {
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(selected);
            game.getStack().resolveStack();
            for (final String type : List.of("STACK_ADDED", "STACK_RESOLVED", "STACK_REMOVED")) {
                final JsonObject event = row(trace.full(), type);
                Assert.assertTrue(event.get("decisionId").isJsonNull());
                Assert.assertTrue(event.get("actionId").isJsonNull());
            }
            Assert.assertTrue(trace.decisions().isEmpty());
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void linkedEventWriteFailureRemainsVisibleAfterEventBusDispatch() throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final SpellAbility selected = ability(game, "Memnite");
        try (Trace trace = new Trace(game, false)) {
            RecordedSelectionTestSupport.select(ai, selected);
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(selected);
            trace.decisionWriter.close();
            trace.decisionClosed = true;
            game.getStack().resolveStack();
            final java.io.IOException failure = Assert.expectThrows(java.io.IOException.class, trace.collector::checkHealthy);
            Assert.assertTrue(failure.getMessage().contains("GameEventSpellResolved"));
            Assert.assertNotNull(failure.getCause());
        }
    }

    private static JsonObject row(final List<JsonObject> rows, final String type) {
        return rows.stream().filter(r -> r.get("type").getAsString().equals(type)).findFirst().orElseThrow();
    }

    @DataProvider
    public Object[][] outputModes() { return new Object[][] { {true}, {false} }; }

    @Test(dataProvider = "outputModes", groups = {"UnitTest", "fast"})
    public void finalizedTargetsFollowCopiedActivationsIntoBothOutputs(final boolean full) throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCardToZone("Island", ai, ZoneType.Battlefield);
        final Card target = addCardToZone("Memnite", game.getPlayers().get(0), ZoneType.Battlefield);
        final long targetTimestamp = target.getGameTimestamp();
        final SpellAbility selected = AbilityFactory.getAbility(
                "AB$ DealDamage | Cost$ 0 | ValidTgts$ Creature | NumDmg$ 1 | SpellDescription$ Target snapshot fixture.", source);
        selected.getTargets().add(target);
        try (Trace trace = new Trace(game, full)) {
            RecordedSelectionTestSupport.select(ai, selected);
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(selected);
            Assert.assertFalse(game.getStack().isEmpty());
            final long actualId = game.getStack().peekAbility().getId();
            Assert.assertNotEquals(actualId, selected.getId());
            game.getStack().peekAbility().resetTargets();
            target.setGameTimestamp(targetTimestamp + 1);
            final JsonObject added = row(trace.decisions(), "STACK_ADDED");
            final JsonObject snapshot = added.getAsJsonObject("finalizedTargets");
            final JsonObject group = snapshot.getAsJsonArray("abilities").get(0).getAsJsonObject();
            Assert.assertEquals(group.get("abilityId").getAsLong(), actualId);
            Assert.assertTrue(group.get("usesTargeting").getAsBoolean());
            final JsonObject ref = group.getAsJsonArray("targets").get(0).getAsJsonObject();
            Assert.assertEquals(ref.get("type").getAsString(), "CARD");
            Assert.assertEquals(ref.get("id").getAsLong(), target.getId());
            Assert.assertEquals(ref.get("gameTimestamp").getAsLong(), targetTimestamp);
            Assert.assertFalse(snapshot.toString().contains("Memnite"));
            Assert.assertFalse(added.has("targets"), "Privileged full-trace targets must stay out of the filtered stream");
            if (full) { Assert.assertEquals(row(trace.full(), "STACK_ADDED").get("finalizedTargets"), snapshot); }
            else { assertContiguous(trace.decisions()); }
        }
    }

    private static void assertContiguous(final List<JsonObject> rows) {
        for (int i = 0; i < rows.size(); i++) { Assert.assertEquals(rows.get(i).get("eventIndex").getAsLong(), i + 1L); }
    }

    @Test(dataProvider = "outputModes", groups = {"UnitTest", "fast"})
    public void committedModesSurviveCastingAndProduceTheRecordedEffects(final boolean full) throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 10);
        for (int i = 0; i < 4; i++) { addCardToZone("Island", ai, ZoneType.Battlefield); }
        final Card creature = addCardToZone("Memnite", game.getPlayers().get(0), ZoneType.Battlefield);
        final SpellAbility sa = addCardToZone("Cryptic Command", ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        forge.game.ability.effects.CharmEffect.makePossibleOptions(sa);
        final var options = sa.getAdditionalAbilityList("Choices");
        sa.setChosenList(new java.util.ArrayList<>(List.of(options.get(2), options.get(3))));
        try (Trace trace = new Trace(game, full)) {
            RecordedSelectionTestSupport.select(ai, sa);
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(sa);
            Assert.assertFalse(game.getStack().isEmpty());
            final JsonObject snapshot = row(trace.decisions(), "STACK_ADDED").getAsJsonObject("finalizedModes");
            Assert.assertEquals(snapshot.getAsJsonArray("modes").size(), 2);
            Assert.assertEquals(snapshot.getAsJsonArray("modes").get(0).getAsJsonObject().get("optionIndex").getAsInt(), 2);
            Assert.assertEquals(snapshot.getAsJsonArray("modes").get(1).getAsJsonObject().get("optionIndex").getAsInt(), 3);
            if (full) { Assert.assertEquals(row(trace.full(), "STACK_ADDED").get("finalizedModes"), snapshot); }
            game.getStack().resolveStack();
            Assert.assertTrue(creature.isTapped(), "The recorded tap mode actually resolved");
            Assert.assertEquals(ai.getCardsIn(ZoneType.Hand).size(), 1, "The recorded draw mode actually resolved");
        }
    }

    @Test(dataProvider = "outputModes", groups = {"UnitTest", "fast"})
    public void announcedXIsNotTheTotalManaPaidAndControlsResolution(final boolean full) throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (int i = 0; i < 4; i++) { addCardToZone("Forest", ai, ZoneType.Battlefield); }
        final SpellAbility sa = addCardToZone("Walking Ballista", ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setXManaCostPaid(2);
        try (Trace trace = new Trace(game, full)) {
            RecordedSelectionTestSupport.select(ai, sa);
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(sa);
            Assert.assertFalse(game.getStack().isEmpty());
            Assert.assertEquals(game.getStack().peekAbility().getPayingMana().size(), 4, "XX means four mana for X=2");
            final JsonObject snapshot = row(trace.decisions(), "STACK_ADDED").getAsJsonObject("finalizedX");
            Assert.assertEquals(snapshot.get("announcedValue").getAsInt(), 2);
            if (full) { Assert.assertEquals(row(trace.full(), "STACK_ADDED").get("finalizedX"), snapshot); }
            game.getStack().resolveStack();
            final Card ballista = ai.getCardsIn(ZoneType.Battlefield).stream()
                    .filter(c -> c.getName().equals("Walking Ballista")).findFirst().orElseThrow();
            Assert.assertEquals(ballista.getCounters(forge.game.card.CounterEnumType.P1P1), 2);
        }
    }

    private static final class Trace implements AutoCloseable {
        private final Path directory = Files.createTempDirectory("forge-action-linkage-");
        private final Path full = directory.resolve("full.jsonl");
        private final Path decisions = directory.resolve("decisions.jsonl");
        private final SimulationTraceWriter fullWriter;
        private final SimulationTraceWriter decisionWriter = SimulationTraceWriter.open(decisions.toString());
        private final SimulationTraceCollector collector;
        private boolean decisionClosed;

        private Trace(final Game game, final boolean fullTrace) throws Exception {
            fullWriter = fullTrace ? SimulationTraceWriter.open(full.toString()) : null;
            collector = new SimulationTraceCollector(game, fullWriter, decisionWriter, 1, Map.of(), null, null, null);
            game.subscribeToEvents(collector);
        }

        private List<JsonObject> read(final Path path, final SimulationTraceWriter writer) throws Exception {
            collector.checkHealthy();
            writer.flush();
            return Files.readAllLines(path).stream().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
        }

        private List<JsonObject> decisions() throws Exception { return read(decisions, decisionWriter); }
        private List<JsonObject> full() throws Exception { return read(full, fullWriter); }

        @Override public void close() throws Exception {
            try {
                if (fullWriter != null) { fullWriter.close(); }
            } finally {
                if (!decisionClosed) { decisionWriter.close(); }
                Files.deleteIfExists(full);
                Files.deleteIfExists(decisions);
                Files.deleteIfExists(directory);
            }
        }
    }
}
