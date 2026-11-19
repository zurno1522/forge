package forge.view;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.common.eventbus.Subscribe;
import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.ai.RecordedSelectionTestSupport;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.event.GameEventSpellResolved;
import forge.game.event.Event;
import forge.game.event.GameEventShuffle;
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
    private record NestedPlay(Runnable play) implements Event { }

    public static final class NestedSubscriber {
        @Subscribe public void play(final NestedPlay event) { event.play().run(); }
    }

    public static final class ShuffleSubscriber {
        private int calls;
        @Subscribe public void shuffle(final GameEventShuffle event) { calls++; }
    }

    public static final class DuringCounterRemoval {
        private final Runnable action;
        public DuringCounterRemoval(final Runnable action) { this.action = action; }
        @Subscribe public void removed(final forge.game.event.GameEventSpellRemovedFromStack event) {
            if (event.disposition() == forge.game.event.GameEventSpellRemovedFromStack.Disposition.COUNTER_PENDING) { action.run(); }
        }
    }

    private static int retainedLinks(final SimulationTraceCollector collector) throws Exception {
        final var field = SimulationTraceCollector.class.getDeclaredField("stackActions");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(collector)).size();
    }

    @Test(groups = {"UnitTest", "fast"})
    public void terminalRetirementIncludesTrackersOfControllersThatWereReplaced() throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final var temporary = new PlayerControllerAi(game, ai, ai.getLobbyPlayer());
        final SpellAbility selected = ability(game, "Memnite");
        try (Trace trace = new Trace(game, false)) {
            ai.addController(1L, game.getPlayers().get(0), temporary, false);
            RecordedSelectionTestSupport.select(ai, selected);
            final var trackerField = temporary.getAi().getClass().getDeclaredField("actionTracker");
            trackerField.setAccessible(true);
            final Object tracker = trackerField.get(temporary.getAi());
            final var actions = tracker.getClass().getDeclaredField("actions");
            actions.setAccessible(true);
            Assert.assertEquals(((List<?>) actions.get(tracker)).size(), 1);
            ai.removeController(1L, false);
            Assert.assertNotSame(ai.getController(), temporary);
            trace.collector.executionFinished("TIMEOUT", null);
            Assert.assertTrue(((List<?>) actions.get(tracker)).isEmpty());
            game.setRecordAiDecisions(true);
            Assert.assertFalse(game.isRecordingAiDecisions(), "A terminated observation session cannot reopen");
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void finalRemovalsRetireHistoryDuringTheGame() throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCardToZone("Island", ai, ZoneType.Battlefield);
        final SpellAbility selected = AbilityFactory.getAbility("AB$ Draw | Cost$ 0 | NumCards$ 0 | SpellDescription$ Removal test.", source);
        try (Trace trace = new Trace(game, false)) {
            for (int i = 0; i < 64; i++) {
                RecordedSelectionTestSupport.select(ai, selected);
                Assert.assertTrue(((PlayerControllerAi) ai.getController()).playChosenSpellAbility(selected));
                Assert.assertEquals(retainedLinks(trace.collector), 1);
                game.getStack().remove(game.getStack().peek());
                Assert.assertEquals(retainedLinks(trace.collector), 0);
            }
            Assert.assertFalse(game.isGameOver());
            Assert.assertEquals(trace.decisions().stream().filter(r -> "STACK_REMOVED".equals(r.get("type").getAsString())).count(), 64L);
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void pendingCounterSurvivesInterveningEventsAndBulkClear() throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        try (Trace trace = new Trace(game, true)) {
            final SpellAbility selected = ability(game, "Memnite");
            final SpellAbility other = ability(game, "Memnite");
            RecordedSelectionTestSupport.select(ai, selected, other);
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(selected);
            final SpellAbility target = game.getStack().peekAbility();
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(other);
            game.subscribeToEvents(new DuringCounterRemoval(() -> {
                game.getStack().clear();
                for (int i = 0; i < 20; i++) { game.fireEvent(new GameEventShuffle((forge.game.player.PlayerView) null)); }
            }));
            final Player opponent = game.getPlayers().get(0);
            final SpellAbility counter = addCardToZone("Counterspell", opponent, ZoneType.Hand).getFirstSpellAbility();
            counter.setActivatingPlayer(opponent);
            counter.getTargets().add(target);
            AbilityUtils.resolve(counter);
            final List<JsonObject> rows = trace.decisions();
            final JsonObject addition = row(rows, "STACK_ADDED");
            final JsonObject countered = row(rows, "STACK_COUNTERED");
            Assert.assertEquals(countered.get("actionId"), addition.get("actionId"));
            Assert.assertTrue(row(rows, "STACK_CLEARED").get("eventIndex").getAsLong() < countered.get("eventIndex").getAsLong());
            Assert.assertEquals(retainedLinks(trace.collector), 0);
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void counterOperationFailureRetiresPendingRemovalWithoutInventingCounterEvent() throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        try (Trace trace = new Trace(game, false)) {
            final SpellAbility selected = ability(game, "Memnite");
            RecordedSelectionTestSupport.select(ai, selected);
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(selected);
            final Player opponent = game.getPlayers().get(0);
            final Card source = addCardToZone("Counterspell", opponent, ZoneType.Hand);
            final SpellAbility counter = AbilityFactory.getAbility("DB$ Counter | ValidTgts$ Spell | Destination$ Invalid", source);
            counter.setActivatingPlayer(opponent);
            counter.getTargets().add(game.getStack().peekAbility());
            Assert.expectThrows(IllegalArgumentException.class, () -> AbilityUtils.resolve(counter));
            Assert.assertTrue(game.getStack().isEmpty());
            Assert.assertEquals(retainedLinks(trace.collector), 0);
            Assert.assertTrue(trace.decisions().stream().noneMatch(r -> "STACK_COUNTERED".equals(r.get("type").getAsString())));
        }
    }

    @DataProvider
    public Object[][] nestedModes() {
        return new Object[][] {{true, true}, {true, false}, {false, true}, {false, false}};
    }

    @Test(dataProvider = "nestedModes", groups = {"UnitTest", "fast"})
    public void nestedExecutionCapturesIdentityAndTargetsBeforeResolutionAndDeferredDelivery(
            final boolean full, final boolean subscriberFirst) throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCardToZone("Island", ai, ZoneType.Battlefield);
        final Card target = addCardToZone("Memnite", game.getPlayers().get(0), ZoneType.Battlefield);
        final long timestamp = target.getGameTimestamp();
        final SpellAbility selected = AbilityFactory.getAbility(
                "AB$ DealDamage | Cost$ 0 | ValidTgts$ Creature | NumDmg$ 1 | SpellDescription$ Nested capture.", source);
        selected.getTargets().add(target);
        if (subscriberFirst) { game.subscribeToEvents(new NestedSubscriber()); }
        try (Trace trace = new Trace(game, full)) {
            if (!subscriberFirst) { game.subscribeToEvents(new NestedSubscriber()); }
            game.fireEvent(new NestedPlay(() -> {
                RecordedSelectionTestSupport.select(ai, selected);
                Assert.assertTrue(((PlayerControllerAi) ai.getController()).playChosenSpellAbility(selected));
                final SpellAbility actual = game.getStack().peekAbility();
                game.getStack().resolveStack();
                actual.resetTargets();
                Assert.assertTrue(game.getStack().isEmpty());
            }));
            final List<JsonObject> rows = trace.decisions();
            final JsonObject addition = row(rows, "STACK_ADDED");
            Assert.assertFalse(addition.get("actionId").isJsonNull());
            final JsonObject targetRef = addition.getAsJsonObject("finalizedTargets").getAsJsonArray("abilities")
                    .get(0).getAsJsonObject().getAsJsonArray("targets").get(0).getAsJsonObject();
            Assert.assertEquals(targetRef.get("id").getAsLong(), target.getId());
            Assert.assertEquals(targetRef.get("gameTimestamp").getAsLong(), timestamp);
            final List<JsonObject> executions = rows.stream().filter(r -> "AI_ACTION_EXECUTION".equals(r.get("type").getAsString())).toList();
            Assert.assertEquals(executions.size(), 2);
            final JsonObject terminal = executions.get(1).getAsJsonObject("execution");
            Assert.assertEquals(terminal.get("status").getAsString(), "SUCCEEDED");
            Assert.assertEquals(terminal.get("reason").getAsString(), "STACKED");
            Assert.assertEquals(terminal.get("stackId"), addition.get("stackId"));
            Assert.assertEquals(terminal.get("executedAbilityId"), addition.get("abilityId"));
            Assert.assertEquals(rows.stream().filter(r -> "STACK_ADDED".equals(r.get("type").getAsString())).count(), 1L);
            Assert.assertEquals(row(rows, "STACK_RESOLVED").get("actionId"), addition.get("actionId"));
            if (full) {
                Assert.assertEquals(row(trace.full(), "STACK_ADDED").get("finalizedTargets"), addition.get("finalizedTargets"));
            }
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void failedEmissionCaptureIsReportedEvenAfterDetachAndEngineDeliveryContinues() throws Exception {
        final Game game = initAndCreateGame();
        final ShuffleSubscriber subscriber = new ShuffleSubscriber();
        game.subscribeToEvents(subscriber);
        final IllegalStateException cause = new IllegalStateException("capture failed");
        try (Trace trace = new Trace(game, false)) {
            final var observation = game.observeEventEmission(event -> { throw cause; });
            game.fireEvent(new GameEventShuffle((forge.game.player.PlayerView) null));
            observation.close();
            Assert.assertEquals(subscriber.calls, 1);
            final var failure = Assert.expectThrows(java.io.IOException.class, trace.collector::checkHealthy);
            Assert.assertSame(failure.getCause(), cause);
            Assert.assertSame(Assert.expectThrows(java.io.IOException.class,
                    () -> trace.collector.executionFinished("NORMAL", null)), failure);
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void authoritativeCloseWaitsForAdmittedCaptureAndRejectsLateIntake() throws Exception {
        final Game game = initAndCreateGame();
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        final var finishing = new java.util.concurrent.CountDownLatch(1);
        final var output = new java.io.StringWriter();
        final java.io.Writer blocked = new java.io.Writer() {
            @Override public void write(char[] chars, int offset, int length) throws java.io.IOException {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(e);
                }
                output.write(chars, offset, length);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        final var constructor = SimulationTraceWriter.class.getDeclaredConstructor(java.io.BufferedWriter.class);
        constructor.setAccessible(true);
        final var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try (SimulationTraceWriter writer = constructor.newInstance(new java.io.BufferedWriter(blocked, 1))) {
            final var collector = new SimulationTraceCollector(game, writer, 1, Map.of(), null, null, null);
            collector.attach();
            final var capture = threads.submit(() -> game.fireEvent(new forge.ai.AiDecisionEvent(Map.of("decisionId", "admitted"))));
            Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            final var close = threads.submit(() -> {
                finishing.countDown();
                collector.executionFinished("TIMEOUT", null);
                return null;
            });
            Assert.assertTrue(finishing.await(5, java.util.concurrent.TimeUnit.SECONDS));
            Assert.assertFalse(close.isDone());
            release.countDown();
            capture.get(5, java.util.concurrent.TimeUnit.SECONDS);
            close.get(5, java.util.concurrent.TimeUnit.SECONDS);
            final List<JsonObject> rows = output.toString().lines().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
            Assert.assertEquals(rows.size(), 2);
            Assert.assertEquals(rows.get(0).getAsJsonObject("decision").get("decisionId").getAsString(), "admitted");
            Assert.assertEquals(rows.get(0).get("eventIndex").getAsLong(), 1L);
            Assert.assertEquals(rows.get(1).get("type").getAsString(), "GAME_EXECUTION_FINISHED");
            Assert.assertEquals(rows.get(1).get("eventIndex").getAsLong(), 2L);
            final String finalized = output.toString();
            game.fireEvent(new forge.ai.AiDecisionEvent(Map.of("decisionId", "late")));
            Assert.assertEquals(output.toString(), finalized);
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }

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

    @Test(dataProvider = "outputModes", groups = {"UnitTest", "fast"})
    public void authoritativeFinishRetiresRemovedLinksAndPendingAliasesEvenAfterSinkFailure(final boolean healthy) throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        try (Trace trace = new Trace(game, false)) {
            final SpellAbility sa = ability(game, "Memnite");
            RecordedSelectionTestSupport.select(ai, sa);
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(sa);
            game.getStack().remove(game.getStack().peek(), forge.game.event.GameEventSpellRemovedFromStack.Disposition.COUNTER_PENDING);
            final var mapField = SimulationTraceCollector.class.getDeclaredField("stackActions");
            mapField.setAccessible(true);
            Assert.assertEquals(((Map<?, ?>) mapField.get(trace.collector)).size(), 1);
            final SpellAbility pending = ability(game, "Memnite");
            RecordedSelectionTestSupport.select(ai, pending);
            final var brain = ((PlayerControllerAi) ai.getController()).getAi();
            final var trackerField = brain.getClass().getDeclaredField("actionTracker");
            trackerField.setAccessible(true);
            final Object tracker = trackerField.get(brain);
            final var actionsField = tracker.getClass().getDeclaredField("actions");
            actionsField.setAccessible(true);
            Assert.assertEquals(((List<?>) actionsField.get(tracker)).size(), 1);
            game.getPlayers().remove(ai); // Eliminated players still own trackers needing retirement.
            Assert.assertTrue(game.getRegisteredPlayers().contains(ai));
            if (!healthy) {
                trace.decisionWriter.close();
                trace.decisionClosed = true;
                Assert.expectThrows(java.io.IOException.class, () -> trace.collector.executionFinished("TIMEOUT", null));
            } else { trace.collector.executionFinished("TIMEOUT", null); }
            Assert.assertTrue(((Map<?, ?>) mapField.get(trace.collector)).isEmpty());
            Assert.assertTrue(((List<?>) actionsField.get(tracker)).isEmpty());
            Assert.assertFalse(game.isRecordingAiDecisions());
            final long next = trace.collector.nextEventIndex();
            RecordedSelectionTestSupport.select(ai, pending); // Model a late extension producer.
            Assert.assertTrue(((List<?>) actionsField.get(tracker)).isEmpty());
            Assert.assertEquals(trace.collector.nextEventIndex(), next);
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void writerCloseStillClosesDelegateWhenExplicitFlushFails() throws Exception {
        final boolean[] closed = { false };
        final java.io.Writer delegate = new java.io.Writer() {
            @Override public void write(char[] data, int offset, int length) { }
            @Override public void flush() throws java.io.IOException { throw new java.io.IOException("flush failure"); }
            @Override public void close() { closed[0] = true; }
        };
        final var constructor = SimulationTraceWriter.class.getDeclaredConstructor(java.io.BufferedWriter.class);
        constructor.setAccessible(true);
        final SimulationTraceWriter writer = constructor.newInstance(new java.io.BufferedWriter(delegate));
        Assert.expectThrows(java.io.IOException.class, writer::close);
        Assert.assertTrue(closed[0]);
        writer.close();
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

    @Test(dataProvider = "outputModes", groups = {"UnitTest", "fast"})
    public void recordedSacrificeAndManaAgreeWithAnActuallyPaidCost(final boolean full) throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card swamp = addCardToZone("Swamp", ai, ZoneType.Battlefield);
        final Card creature = addCardToZone("Memnite", ai, ZoneType.Battlefield);
        final long beforeTimestamp = creature.getGameTimestamp();
        fillLibrary(ai, 10);
        final SpellAbility sa = addCardToZone("Village Rites", ai, ZoneType.Hand).getFirstSpellAbility();
        try (Trace trace = new Trace(game, full)) {
            RecordedSelectionTestSupport.select(ai, sa);
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(sa);
            final var entry = game.getStack().peek();
            Assert.assertNotNull(entry);
            final JsonObject snapshot = row(trace.decisions(), "STACK_ADDED").getAsJsonObject("finalizedPayments");
            Assert.assertEquals(snapshot.getAsJsonArray("mana").size(), 1);
            final JsonObject mana = snapshot.getAsJsonArray("mana").get(0).getAsJsonObject();
            Assert.assertEquals(mana.get("color").getAsString(), "B");
            Assert.assertEquals(mana.getAsJsonObject("source").get("id").getAsLong(), swamp.getId());
            JsonObject sacrificed = null;
            for (final var element : snapshot.getAsJsonArray("cardPayments")) {
                final JsonObject group = element.getAsJsonObject();
                if (group.get("type").getAsString().equals("Sacrificed")) { sacrificed = group; }
            }
            Assert.assertNotNull(sacrificed);
            final JsonObject paid = sacrificed.getAsJsonArray("cards").get(0).getAsJsonObject();
            Assert.assertEquals(paid.get("id").getAsLong(), creature.getId());
            Assert.assertEquals(paid.get("gameTimestamp").getAsLong(), beforeTimestamp);
            Assert.assertFalse(snapshot.toString().contains("Memnite"));
            if (full) { Assert.assertEquals(row(trace.full(), "STACK_ADDED").get("finalizedPayments"), snapshot); }
            final var copy = entry.getSpellAbility().copy();
            copy.setCopied(true);
            Assert.assertNull(SimulationChoiceSnapshot.payments(new forge.game.spellability.SpellAbilityStackInstance(copy)),
                    "A copied effect must not report inherited history as a new payment");
            entry.getSpellAbility().addCostToHashList(creature, "Secret future payment label", true);
            final Map<String, Object> partial = SimulationChoiceSnapshot.payments(entry);
            Assert.assertEquals(partial.get("unrecognizedCardPaymentGroups"), 1);
            final String frozen = new com.google.gson.Gson().toJson(partial);
            Assert.assertFalse(frozen.contains("Secret future payment label"));
            entry.getSpellAbility().resetPaidHash();
            Assert.assertEquals(new com.google.gson.Gson().toJson(partial), frozen);
            game.getStack().resolveStack();
            Assert.assertEquals(ai.getCardsIn(ZoneType.Hand).size(), 2);
            Assert.assertTrue(ai.getCardsIn(ZoneType.Graveyard).stream().anyMatch(c -> c.getId() == creature.getId()));
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void multiplePhyrexianPaymentsDoNotPretendTheLastPaymentIsATotal() throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        addCardToZone("Forest", ai, ZoneType.Battlefield);
        final Card target = addCardToZone("Bear Cub", game.getPlayers().get(0), ZoneType.Battlefield);
        final SpellAbility sa = addCardToZone("Dismember", ai, ZoneType.Hand).getFirstSpellAbility();
        sa.getTargets().add(target);
        final int life = ai.getLife();
        try (Trace trace = new Trace(game, false)) {
            RecordedSelectionTestSupport.select(ai, sa);
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(sa);
            final JsonObject payment = row(trace.decisions(), "STACK_ADDED").getAsJsonObject("finalizedPayments");
            Assert.assertEquals(ai.getLife(), life - 4);
            Assert.assertEquals(payment.get("phyrexianLifePaid").getAsInt(), 4);
            Assert.assertEquals(payment.get("lastLifePayment").getAsInt(), 2);
            Assert.assertEquals(payment.getAsJsonArray("mana").size(), 1);
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void kickedCostIsRecordedAndChangesTheResolvedDamage() throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        for (int i = 0; i < 5; i++) { addCardToZone("Mountain", ai, ZoneType.Battlefield); }
        final SpellAbility base = addCardToZone("Burst Lightning", ai, ZoneType.Hand).getFirstSpellAbility();
        base.setActivatingPlayer(ai);
        final SpellAbility kicked = forge.game.GameActionUtil.addOptionalCosts(base, forge.game.GameActionUtil.getOptionalCostValues(base));
        kicked.getTargets().add(opponent);
        final int life = opponent.getLife();
        try (Trace trace = new Trace(game, false)) {
            RecordedSelectionTestSupport.select(ai, kicked);
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(kicked);
            final JsonObject payment = row(trace.decisions(), "STACK_ADDED").getAsJsonObject("finalizedPayments");
            Assert.assertEquals(payment.getAsJsonArray("optionalCosts").get(0).getAsString(), "Kicker1");
            Assert.assertEquals(payment.getAsJsonArray("mana").size(), 5);
            game.getStack().resolveStack();
            Assert.assertEquals(opponent.getLife(), life - 4);
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void flashbackCostIsRecordedAndTheResolvedSpellIsExiled() throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (int i = 0; i < 3; i++) { addCardToZone("Island", ai, ZoneType.Battlefield); }
        fillLibrary(ai, 10);
        final Card card = addCardToZone("Think Twice", ai, ZoneType.Graveyard);
        final SpellAbility flashback = card.getAllPossibleAbilities(ai, true).stream().filter(SpellAbility::isFlashback).findFirst().orElseThrow();
        try (Trace trace = new Trace(game, false)) {
            RecordedSelectionTestSupport.select(ai, flashback);
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(flashback);
            final JsonObject payment = row(trace.decisions(), "STACK_ADDED").getAsJsonObject("finalizedPayments");
            Assert.assertEquals(payment.get("alternativeCost").getAsString(), "Flashback");
            Assert.assertEquals(payment.getAsJsonArray("mana").size(), 3);
            game.getStack().resolveStack();
            Assert.assertEquals(ai.getCardsIn(ZoneType.Hand).size(), 1);
            Assert.assertTrue(ai.getCardsIn(ZoneType.Exile).stream().anyMatch(c -> c.getId() == card.getId()));
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void multikickerCountReflectsTheCastTransformationAndItsTokenResult() throws Exception {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        for (int i = 0; i < 6; i++) { addCardToZone("Forest", ai, ZoneType.Battlefield); }
        final SpellAbility sa = addCardToZone("Wolfbriar Elemental", ai, ZoneType.Hand).getFirstSpellAbility();
        try (Trace trace = new Trace(game, false)) {
            RecordedSelectionTestSupport.select(ai, sa);
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(sa);
            final JsonObject payment = row(trace.decisions(), "STACK_ADDED").getAsJsonObject("finalizedPayments");
            final JsonObject keyword = payment.getAsJsonArray("keywordAmounts").get(0).getAsJsonObject();
            Assert.assertEquals(keyword.get("keyword").getAsString(), "MULTIKICKER");
            Assert.assertEquals(keyword.get("amount").getAsInt(), 2);
            Assert.assertEquals(payment.getAsJsonArray("mana").size(), 6);
            game.getStack().resolveStack();
            game.getTriggerHandler().runWaitingTriggers();
            game.getStack().addAllTriggeredAbilitiesToStack();
            Assert.assertFalse(game.getStack().isEmpty());
            game.getStack().resolveStack();
            Assert.assertEquals(ai.getCreaturesInPlay().size(), 3, "Elemental plus two Wolves from the recorded multikicker count");
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
            collector.attach();
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
