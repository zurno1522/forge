package forge.gamemodes.net;

import forge.card.CardStateName;
import forge.ai.AITest;
import forge.game.card.CardView;
import forge.game.spellability.StackItemView;
import forge.trackable.TrackableProperty;
import forge.trackable.TrackableTypes;
import forge.trackable.Tracker;
import forge.gamemodes.net.server.DeltaSyncManager;
import io.netty.handler.codec.serialization.ClassResolvers;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Map;

public class LongIdentifierWireTest extends AITest {
    private static Object roundTrip(final Object value) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new CObjectOutputStream(bytes, false, null, -1, false)) {
            out.writeObject(value);
        }
        try (CObjectInputStream in = new CObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()),
                ClassResolvers.cacheDisabled(null), null)) {
            return in.readObject();
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void deltaKeysRetainAllIdBitsAndCardState() throws Exception {
        final var low = DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_STACK_ITEM_VIEW, 7L);
        final var high = DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_STACK_ITEM_VIEW, 7L + (1L << 40));
        final var state = DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CSV, Long.MAX_VALUE, CardStateName.Original.ordinal());
        final var otherState = DeltaPacket.makeDeltaKey(DeltaPacket.TYPE_CSV, Long.MAX_VALUE, CardStateName.FaceDown.ordinal());
        Assert.assertNotEquals(low, high);
        Assert.assertNotEquals(state, otherState);
        final Map<DeltaPacket.ObjectKey, Map<TrackableProperty, Object>> values = new HashMap<>();
        values.put(low, new HashMap<>());
        values.put(high, new HashMap<>());
        values.put(state, new HashMap<>());
        values.put(otherState, new HashMap<>());
        final DeltaPacket packet = (DeltaPacket) roundTrip(new DeltaPacket(1L, values, null, 0, null));
        Assert.assertEquals(packet.getObjectDeltas().size(), 4);
        Assert.assertTrue(packet.getObjectDeltas().containsKey(high));
        Assert.assertEquals(DeltaPacket.getIdFromDeltaKey(state), Long.MAX_VALUE);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void fullWidthReferencesResolveToTheCanonicalViews() throws Exception {
        final long id = (1L << 40) + 7;
        final Tracker tracker = new Tracker();
        final CardView card = new CardView(id, tracker);
        tracker.putObj(TrackableTypes.CardViewType, id, card);
        final Object ref = TrackableSerializer.replace(card, tracker, -1, false);
        Assert.assertSame(TrackableSerializer.resolve(roundTrip(ref), tracker), card);

        final StackItemView stack = new StackItemView(id, tracker);
        tracker.putObj(TrackableTypes.StackItemViewType, id, stack);
        Assert.assertSame(NetworkGuiGame.resolveFromNetwork(TrackableProperty.SubInstance, id, tracker), stack);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void deltaCollectionHandlesTypedKeysAndDiagnosticFormatting() {
        final var game = initAndCreateGame();
        final DeltaSyncManager manager = new DeltaSyncManager();
        final DeltaPacket packet = manager.collectDeltas(game.getView());
        Assert.assertFalse(packet.getNewObjects().isEmpty());
        Assert.assertTrue(packet.getNewObjects().keySet().stream().allMatch(key -> key.id() >= 0));
    }
}
