package forge.view;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.LobbyPlayerAi;
import forge.ai.AiDecisionEvent;
import forge.card.GamePieceType;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.GameEventCardAttachment;
import forge.game.event.GameEventCardTapped;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventSpellResolved;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.trackable.TrackableProperty;
import forge.util.Lang;
import forge.util.Localizer;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SimulationTraceCollectorTest {
    @BeforeClass
    public void initializeLocalizer() {
        Localizer.getInstance().initialize("en-US", "../forge-gui/res/languages/");
        Lang.createInstance("en-US");
    }

    @DataProvider(name = "missingGamePieceType")
    public Object[][] missingGamePieceType() {
        return new Object[][] { { true }, { false } };
    }

    @Test(groups = { "UnitTest", "fast" }, dataProvider = "missingGamePieceType")
    public void recordsDetachWhenAttachmentOrFormerTargetHasNoGamePieceType(
            final boolean missingAttachmentType) throws Exception {
        try (Trace trace = new Trace()) {
            final Card attachment = new Card(10, trace.game);
            attachment.setName("Equipment");
            final Card target = new Card(11, trace.game);
            target.setName("Creature");
            // A minimally populated Card view need not have its GamePieceType updated.
            if (missingAttachmentType) {
                target.setGamePieceType(GamePieceType.CARD);
            } else {
                attachment.setGamePieceType(GamePieceType.CARD);
            }
            final Card missingType = missingAttachmentType ? attachment : target;
            Assert.assertNull(missingType.getView().getGamePieceType());
            attachment.setEntityAttachedTo(target);
            target.addAttachedCard(attachment);

            attachment.unattachFromEntity(target);
            trace.game.fireEvent(new GameEventCardTapped(attachment, true));

            final List<JsonObject> records = trace.records();
            Assert.assertEquals(records.size(), 2);
            final JsonObject detach = records.get(0);
            Assert.assertEquals(detach.get("type").getAsString(), "ATTACHMENT_CHANGED");
            Assert.assertEquals(detach.get("eventIndex").getAsInt(), 1);
            assertCard(detach.getAsJsonObject("attachment"), 10, attachment.getView().getName());
            final JsonObject from = detach.getAsJsonObject("from");
            Assert.assertEquals(from.get("type").getAsString(), "CARD");
            assertCard(from.getAsJsonObject("card"), 11, target.getView().getName());
            Assert.assertFalse(detach.has("to"));
            final JsonObject missingRef = missingAttachmentType
                    ? detach.getAsJsonObject("attachment") : from.getAsJsonObject("card");
            Assert.assertFalse(missingRef.has("gamePieceType"));
            Assert.assertEquals(records.get(1).get("type").getAsString(), "CARD_TAPPED");
            Assert.assertEquals(records.get(1).get("eventIndex").getAsInt(), 2);
        }
    }

    @Test(groups = { "UnitTest", "fast" })
    public void retainsKnownGamePieceTypesForAttachmentAndTarget() throws Exception {
        try (Trace trace = new Trace()) {
            final CardView attachment = new CardView(20, trace.game.getTracker(), "Equipment");
            attachment.set(TrackableProperty.GamePieceType, GamePieceType.CARD);
            final CardView target = new CardView(21, trace.game.getTracker(), "Creature token");
            target.set(TrackableProperty.GamePieceType, GamePieceType.TOKEN);

            trace.game.fireEvent(new GameEventCardAttachment(attachment, null, target));

            final JsonObject attach = trace.records().get(0);
            Assert.assertEquals(attach.get("type").getAsString(), "ATTACHMENT_CHANGED");
            assertCard(attach.getAsJsonObject("attachment"), 20, "Equipment");
            Assert.assertEquals(attach.getAsJsonObject("attachment").get("gamePieceType").getAsString(), "CARD");
            Assert.assertFalse(attach.has("from"));
            final JsonObject to = attach.getAsJsonObject("to");
            Assert.assertEquals(to.get("type").getAsString(), "CARD");
            assertCard(to.getAsJsonObject("card"), 21, "Creature token");
            Assert.assertEquals(to.getAsJsonObject("card").get("gamePieceType").getAsString(), "TOKEN");
        }
    }

    @Test(groups = { "UnitTest", "fast" })
    public void retainsNullCardReferences() throws Exception {
        try (Trace trace = new Trace()) {
            trace.game.fireEvent(new GameEventCardTapped((CardView) null, false));

            final JsonObject record = trace.records().get(0);
            Assert.assertEquals(record.get("type").getAsString(), "CARD_TAPPED");
            Assert.assertTrue(record.get("card").isJsonNull());
        }
    }

    @Test(groups = { "UnitTest", "fast" })
    public void preservesPregameShufflesBeforeGameStartedWithContiguousIndices() throws Exception {
        try (Trace trace = new Trace()) {
            final PlayerView first = new PlayerView(1, trace.game.getTracker());
            first.set(TrackableProperty.Name, "First");
            final PlayerView second = new PlayerView(2, trace.game.getTracker());
            second.set(TrackableProperty.Name, "Second");

            trace.game.fireEvent(new GameEventShuffle(first));
            trace.game.fireEvent(new GameEventShuffle(second));
            trace.game.fireEvent(new GameEventGameStarted(GameType.Constructed, first, List.of(first, second)));
            trace.collector.executionFinished("NORMAL", null);

            final List<JsonObject> records = trace.records();
            Assert.assertEquals(records.size(), 4);
            final List<String> types = List.of("SHUFFLE", "SHUFFLE", "GAME_STARTED", "GAME_EXECUTION_FINISHED");
            for (int i = 0; i < records.size(); i++) {
                Assert.assertEquals(records.get(i).get("type").getAsString(), types.get(i));
                Assert.assertEquals(records.get(i).get("eventIndex").getAsInt(), i + 1);
                Assert.assertEquals(records.get(i).get("game").getAsInt(), 1);
            }
            Assert.assertEquals(records.get(0).getAsJsonObject("player").get("id").getAsInt(), 1);
            Assert.assertEquals(records.get(1).getAsJsonObject("player").get("id").getAsInt(), 2);
        }
    }

    @Test(groups = { "UnitTest", "fast" })
    public void recordsBulkStackClearAndContinuesWithoutStaleStackIds() throws Exception {
        final RegisteredPlayer registered = new RegisteredPlayer(new Deck("Empty deck"))
                .setPlayer(new LobbyPlayerAi("Player", null));
        try (Trace trace = new Trace(List.of(registered))) {
            final Card source = new Card(30, trace.game);
            source.setName("Ability source");
            source.setOwner(trace.game.getPlayers().get(0));
            source.setGamePieceType(GamePieceType.CARD);
            final List<SpellAbility> abilities = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final SpellAbility ability = AbilityFactory.getAbility(
                        "AB$ Draw | Cost$ 0 | NumCards$ 1 | Defined$ You | SpellDescription$ Draw a card.", source);
                ability.setActivatingPlayer(source.getOwner());
                // A copied ability needs no payment history or card database to enter the real stack.
                ability.setCopied(true);
                abilities.add(ability);
                trace.game.getStack().add(ability, 101 + i);
            }
            trace.collector.checkHealthy();
            Assert.assertEquals(trace.game.getStack().size(), 2);

            trace.game.getStack().clear();
            Assert.assertEquals(trace.game.getStack().size(), 0);
            trace.game.getStack().clear(); // An already empty stack emits no further event.
            for (final SpellAbility ability : abilities) {
                // Resolution can finish after an effect has cleared the stack.
                trace.game.fireEvent(new GameEventSpellResolved(ability, false));
            }
            trace.game.getStack().add(abilities.get(0), 103);
            trace.game.getStack().remove(trace.game.getStack().peek());
            trace.game.fireEvent(new GameEventCardTapped(source, true));
            trace.collector.executionFinished("NORMAL", null);

            final List<JsonObject> records = trace.records();
            final List<String> types = List.of("STACK_ADDED", "STACK_ADDED", "STACK_CLEARED",
                    "STACK_RESOLVED", "STACK_RESOLVED", "STACK_ADDED", "STACK_REMOVED",
                    "CARD_TAPPED", "GAME_EXECUTION_FINISHED");
            Assert.assertEquals(records.size(), types.size());
            for (int i = 0; i < records.size(); i++) {
                Assert.assertEquals(records.get(i).get("type").getAsString(), types.get(i));
                Assert.assertEquals(records.get(i).get("eventIndex").getAsInt(), i + 1);
            }
            final JsonObject clear = records.get(2);
            Assert.assertFalse(clear.has("abilityId"));
            Assert.assertFalse(clear.has("stackId"));
            Assert.assertFalse(clear.has("source"));
            Assert.assertFalse(records.get(3).has("stackId"));
            Assert.assertFalse(records.get(4).has("stackId"));
            Assert.assertEquals(records.get(6).get("stackId").getAsInt(), 103);
            Assert.assertEquals(records.get(6).get("abilityId").getAsInt(), abilities.get(0).getId());
            assertCard(records.get(6).getAsJsonObject("source"), 30, source.getView().getName());
        }
    }

    @Test(groups = { "UnitTest", "fast" })
    public void writesDecisionBeforeSubsequentGameEventsWithoutOverridingEnvelope() throws Exception {
        try (Trace trace = new Trace()) {
            final Map<String, Object> payload = Map.of("decisionId", "1:1", "selection", "PASS_PRIORITY",
                    "eventIndex", 999, "type", "payload-only");
            trace.game.fireEvent(new AiDecisionEvent(payload));
            trace.game.fireEvent(new GameEventShuffle((PlayerView) null));
            trace.collector.executionFinished("NORMAL", null);
            final List<JsonObject> records = trace.records();
            Assert.assertEquals(records.size(), 3);
            Assert.assertEquals(records.get(0).get("type").getAsString(), "AI_DECISION");
            Assert.assertEquals(records.get(0).get("eventIndex").getAsInt(), 1);
            Assert.assertEquals(records.get(0).getAsJsonObject("decision").get("decisionId").getAsString(), "1:1");
            Assert.assertEquals(records.get(1).get("type").getAsString(), "SHUFFLE");
            Assert.assertEquals(records.get(1).get("eventIndex").getAsInt(), 2);
        }
    }

    private static void assertCard(final JsonObject ref, final int id, final String name) {
        Assert.assertEquals(ref.get("id").getAsInt(), id);
        Assert.assertEquals(ref.get("name").getAsString(), name);
    }

    private static final class Trace implements AutoCloseable {
        private final Path file = Files.createTempFile("forge-collector-trace-", ".jsonl");
        private final SimulationTraceWriter writer = SimulationTraceWriter.open(file.toString());
        private final GameRules rules = new GameRules(GameType.Constructed);
        private final Game game;
        private final SimulationTraceCollector collector;

        private Trace() throws Exception {
            this(List.of());
        }

        private Trace(final List<RegisteredPlayer> players) throws Exception {
            game = new Game(players, rules, new Match(rules, players, "Trace test"));
            collector = new SimulationTraceCollector(game, writer, 1, Map.of(), null, null, null);
            game.subscribeToEvents(collector);
        }

        private List<JsonObject> records() throws Exception {
            collector.checkHealthy();
            writer.flush();
            final List<JsonObject> records = new ArrayList<>();
            for (final String line : Files.readAllLines(file)) {
                records.add(JsonParser.parseString(line).getAsJsonObject());
            }
            return records;
        }

        @Override
        public void close() throws Exception {
            try {
                writer.close();
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }
}
