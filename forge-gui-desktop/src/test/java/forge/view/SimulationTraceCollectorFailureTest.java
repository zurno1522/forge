package forge.view;

import com.google.common.eventbus.EventBus;
import forge.game.Game;
import forge.game.card.CardView;
import forge.game.event.GameEventCardAttachment;
import forge.game.event.GameEventShuffle;
import forge.game.phase.PhaseHandler;
import forge.game.player.PlayerView;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SimulationTraceCollectorFailureTest {
    @Test(groups = { "UnitTest", "fast" })
    public void translationFailureRemainsVisibleAfterEventBusDispatch() throws Exception {
        final Path file = Files.createTempFile("forge-sim-trace-failure-", ".jsonl");
        try (SimulationTraceWriter writer = SimulationTraceWriter.open(file.toString())) {
            final SimulationTraceCollector collector = collector(writer);
            final AtomicReference<Throwable> escaped = new AtomicReference<>();
            final EventBus events = new EventBus((error, context) -> escaped.set(error));
            events.register(collector);

            final IllegalStateException cause = new IllegalStateException("unavailable card view");
            final CardView attachment = mock(CardView.class);
            when(attachment.getName()).thenThrow(cause);
            events.post(new GameEventCardAttachment(attachment, null, null));

            Assert.assertNull(escaped.get(), "The collector must retain subscriber failures itself");
            final IOException failure = Assert.expectThrows(IOException.class, collector::checkHealthy);
            Assert.assertTrue(failure.getMessage().contains("GameEventCardAttachment"));
            Assert.assertSame(failure.getCause(), cause);

            // A later valid event must not make an incomplete trace look healthy.
            events.post(new GameEventShuffle((PlayerView) null));
            Assert.assertSame(Assert.expectThrows(IOException.class, collector::checkHealthy), failure);
            Assert.assertSame(Assert.expectThrows(IOException.class,
                    () -> collector.executionFinished("NORMAL", null)), failure);
            writer.flush();
            Assert.assertEquals(Files.readString(file), "");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test(groups = { "UnitTest", "fast" })
    public void writeFailureRetainsItsCauseAndSuppressesLaterEvents() throws Exception {
        final Path file = Files.createTempFile("forge-sim-trace-write-failure-", ".jsonl");
        final SimulationTraceWriter writer = SimulationTraceWriter.open(file.toString());
        try {
            final SimulationTraceCollector collector = collector(writer);
            final EventBus events = new EventBus();
            events.register(collector);
            events.post(new GameEventShuffle((PlayerView) null));
            collector.checkHealthy();
            writer.close();

            events.post(new GameEventShuffle((PlayerView) null));
            final IOException failure = Assert.expectThrows(IOException.class, collector::checkHealthy);
            Assert.assertTrue(failure.getMessage().contains("GameEventShuffle"));
            Assert.assertTrue(failure.getCause() instanceof IOException);

            final CardView attachment = mock(CardView.class);
            when(attachment.getName()).thenThrow(new IllegalStateException("later failure"));
            events.post(new GameEventCardAttachment(attachment, null, null));
            Assert.assertSame(Assert.expectThrows(IOException.class, collector::checkHealthy), failure);
            Assert.assertSame(Assert.expectThrows(IOException.class,
                    () -> collector.executionFinished("NORMAL", null)), failure);
            Assert.assertEquals(Files.readAllLines(file).size(), 1);
        } finally {
            try {
                writer.close();
            } catch (IOException ignored) {
                // The writer was deliberately closed to provoke the failure.
            }
            Files.deleteIfExists(file);
        }
    }

    private static SimulationTraceCollector collector(final SimulationTraceWriter writer) {
        final Game game = mock(Game.class);
        when(game.getPhaseHandler()).thenReturn(mock(PhaseHandler.class));
        return new SimulationTraceCollector(game, writer, 1, Map.of(), null, null, null);
    }
}
