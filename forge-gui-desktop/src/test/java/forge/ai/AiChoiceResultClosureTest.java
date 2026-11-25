package forge.ai;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AiChoiceResultClosureTest extends AITest {
    private record Fixture(Game game, Player chooser, SpellAbility ability, List<Map<String, Object>> events) { }

    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player chooser = game.getPlayers().get(1);
        final SpellAbility ability = addCardToZone("Lightning Bolt", chooser, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(chooser); game.setRecordAiDecisions(true);
        final List<Map<String, Object>> events = new ArrayList<>();
        game.observeEventEmission(event -> { if (event instanceof AiChoiceEvent choice) { events.add(choice.data()); } });
        return new Fixture(game, chooser, ability, events);
    }

    @DataProvider public Object[][] closures() { return new Object[][] {{false}, {true}}; }

    @Test(dataProvider = "closures", groups = {"UnitTest", "fast"})
    @SuppressWarnings("unchecked")
    public void lateResultProjectionFailureAfterClosureCannotPoisonHealthOrReplaceTheReturnedValue(final boolean parentClosure) throws Exception {
        final Fixture f = fixture();
        final var parent = new AiDecisionRecorder(f.game, f.chooser, 1); parent.beginAssessment(f.ability);
        final var brain = ((PlayerControllerAi) f.chooser.getController()).getAi();
        final var field = AiController.class.getDeclaredField("evaluationDecision"); field.setAccessible(true);
        final ThreadLocal<AiDecisionRecorder> evaluation = (ThreadLocal<AiDecisionRecorder>) field.get(brain);
        final var entered = new CountDownLatch(1); final var release = new CountDownLatch(1);
        final AtomicInteger callbacks = new AtomicInteger();
        final var worker = Executors.newSingleThreadExecutor();
        try {
            final var result = worker.submit(() -> {
                if (parentClosure) { evaluation.set(parent); }
                try {
                    return AiChoiceRecorder.observe(f.chooser, f.ability, "NUMBER_SELECTION", null,
                            "CONTROLLER_NUMBER_RANGE", () -> Map.of("coverage", "OBSERVED_RANGE", "minimum", 0, "maximum", 50),
                            () -> { callbacks.incrementAndGet(); return 42; }, value -> {
                                entered.countDown();
                                try { Assert.assertTrue(release.await(5, TimeUnit.SECONDS)); }
                                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                                throw new IllegalStateException("obsolete result projection");
                            });
                } finally { evaluation.remove(); }
            });
            Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
            if (parentClosure) { Assert.assertNotNull(parent.finish(null, "AI_TIMEOUT", null)); }
            else { f.game.setRecordAiDecisions(false); }
            release.countDown();
            Assert.assertEquals(result.get(5, TimeUnit.SECONDS), Integer.valueOf(42));
            Assert.assertEquals(callbacks.get(), 1);
            Assert.assertEquals(f.events.size(), 1, "Closed observation cannot publish a late terminal");
            Assert.assertNull(f.game.getEventObservationFailure());
            f.game.setRecordAiDecisions(true);
            AiChoiceRecorder.observe(f.chooser, f.ability, "NUMBER_SELECTION", null, "CONTROLLER_NUMBER_RANGE",
                    () -> Map.of("coverage", "OBSERVED_RANGE", "minimum", 0, "maximum", 50), () -> 3, value -> value);
            Assert.assertEquals(f.events.size(), 3);
            Assert.assertNull(f.events.get(1).get("parentChoiceId"));
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void liveResultProjectionFailureRemainsVisibleWhileTheOriginalCallbackReturnsItsValue() {
        final Fixture f = fixture(); final var failure = new IllegalStateException("live result projection");
        Assert.assertEquals(AiChoiceRecorder.observe(f.chooser, f.ability, "NUMBER_SELECTION", null, "CONTROLLER_NUMBER_RANGE",
                () -> Map.of("coverage", "OBSERVED_RANGE", "minimum", 0, "maximum", 50), () -> 42,
                value -> { throw failure; }), Integer.valueOf(42));
        Assert.assertSame(f.game.getEventObservationFailure(), failure);
        Assert.assertEquals(f.events.size(), 1);
    }
}
