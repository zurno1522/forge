package forge.ai;

import com.google.common.eventbus.Subscribe;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.trackable.TrackableIndex;
import forge.trackable.Tracker;
import forge.util.AITimeoutTracker;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

public class LongIdentifierTest extends AITest {
    private static final long LARGE_ID = 1L << 33;

    private static void advanceCounter(final Class<?> type) throws Exception {
        final Field field = type.getDeclaredField("maxId");
        field.setAccessible(true);
        // Keep allocators monotonic; do not restore counters and reuse IDs after the test.
        ((AtomicLong) field.get(null)).accumulateAndGet(LARGE_ID, Math::max);
    }

    public static final class Executions {
        final List<Map<String, Object>> records = new ArrayList<>();
        @Subscribe public void receive(final AiActionExecutionEvent event) { records.add(event.data()); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void allocationsViewsAndExecutionKeepIdsAbove32Bits() throws Exception {
        advanceCounter(Game.class);
        advanceCounter(SpellAbility.class);
        advanceCounter(SpellAbilityStackInstance.class);
        final Game game = initAndCreateGame();
        final Field cards = Game.class.getDeclaredField("cardIdCounter");
        cards.setAccessible(true);
        cards.setLong(game, LARGE_ID);
        final Player ai = game.getPlayers().get(1);
        final Card card = addCardToZone("Memnite", ai, ZoneType.Hand);
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        Assert.assertTrue(game.getId() > Integer.MAX_VALUE);
        Assert.assertTrue(card.getId() > Integer.MAX_VALUE);
        Assert.assertTrue(sa.getId() > Integer.MAX_VALUE);
        Assert.assertEquals(sa.getView().getId(), sa.getId());
        Assert.assertEquals(card.getView().getId(), card.getId());
        Assert.assertEquals(sa.copy(card, true).getId(), sa.getId(), "LKI retains identity");
        Assert.assertNotEquals(sa.copy().getId(), sa.getId(), "New instances receive new identity");
        Assert.assertTrue(card.isValid("Card.CardUID_" + card.getId(), ai, card, sa));

        final Executions events = new Executions();
        game.subscribeToEvents(events);
        game.setRecordAiDecisions(true);
        final PlayerControllerAi controller = (PlayerControllerAi) ai.getController();
        controller.getAi().publishDecision(new AiDecisionRecorder(game, ai, game.nextAiDecisionSequence()),
                List.of(sa), "COMPLETED", null);
        controller.playChosenSpellAbility(sa);
        final Map<String, Object> result = events.records.get(1);
        Assert.assertEquals(result.get("status"), "SUCCEEDED");
        Assert.assertEquals(result.get("abilityId"), sa.getId());
        Assert.assertTrue((Long) result.get("stackId") > Integer.MAX_VALUE);
        Assert.assertEquals(result.get("stackId"), game.getStack().iterator().next().getView().getId());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void hashCollisionsDoNotMakeDifferentLongIdsEqual() {
        final Tracker tracker = new Tracker();
        final CardView low = new CardView(1L, tracker);
        final CardView high = new CardView(1L << 32, tracker);
        Assert.assertEquals(low.hashCode(), high.hashCode());
        Assert.assertNotEquals(low, high);
        final TrackableIndex<CardView> index = new TrackableIndex<>();
        index.put(low.getId(), low);
        index.put(high.getId(), high);
        Assert.assertEquals(index.size(), 2);
        Assert.assertSame(index.get(high.getId()), high);

        final Game game = initAndCreateGame();
        final Card a = new Card(1L, null, game);
        final Card b = new Card(1L << 32, null, game);
        final Map<Card, String> entities = new HashMap<>();
        entities.put(a, "low");
        entities.put(b, "high");
        Assert.assertEquals(entities.size(), 2);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void gameAndPlayerSessionKeysKeepTheirFullWidth() {
        final var low = AITimeoutTracker.key(1, 0);
        final var highGame = AITimeoutTracker.key(1 + (1L << 32), 0);
        final var highPlayer = AITimeoutTracker.key(1, 1L << 32);
        Assert.assertNotEquals(low, highGame);
        Assert.assertNotEquals(low, highPlayer);
        AITimeoutTracker.begin(low, 1000000L);
        try {
            Assert.assertFalse(AITimeoutTracker.isActive(highGame));
            Assert.assertFalse(AITimeoutTracker.isActive(highPlayer));
        } finally {
            AITimeoutTracker.end(low);
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void parallelStackAllocationsRemainUniqueBeyondIntRange() throws Exception {
        advanceCounter(SpellAbilityStackInstance.class);
        final Set<Long> allocated = ConcurrentHashMap.newKeySet();
        IntStream.range(0, 1000).parallel().forEach(i -> allocated.add(SpellAbilityStackInstance.nextId()));
        Assert.assertEquals(allocated.size(), 1000);
        Assert.assertTrue(allocated.stream().allMatch(id -> id > Integer.MAX_VALUE));
    }
}
