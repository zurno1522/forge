package forge.gamemodes.net;

import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.gamemodes.net.event.NetEvent;
import forge.trackable.TrackableObject;
import forge.trackable.TrackableProperty;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Packet containing delta updates for TrackableObjects.
 * Contains changed properties for existing objects and full property maps for new objects.
 * Properties are sent as Map<TrackableProperty, Object> with ID substitution for object references.
 * Standard Java serialization handles the maps natively via Netty's ObjectEncoder.
 */
public final class DeltaPacket implements NetEvent {
    private static final long serialVersionUID = 9L;

    private final long sequenceNumber;
    private final Map<ObjectKey, Map<TrackableProperty, Object>> objectDeltas;
    private final Map<ObjectKey, Map<TrackableProperty, Object>> newObjects;
    private final int checksum;
    private final int[] checksumProperties;
    private List<Object> events;

    public static final int TYPE_CARD_VIEW = 0;
    public static final int TYPE_PLAYER_VIEW = 1;
    public static final int TYPE_STACK_ITEM_VIEW = 2;
    public static final int TYPE_GAME_VIEW = 3;
    public static final int TYPE_CSV = 4;

    public static int typeTagFor(TrackableObject obj) {
        if (obj == null) return -1;
        if (obj instanceof CardStateView) return TYPE_CSV;
        if (obj instanceof CardView) return TYPE_CARD_VIEW;
        if (obj instanceof PlayerView) return TYPE_PLAYER_VIEW;
        if (obj instanceof StackItemView) return TYPE_STACK_ITEM_VIEW;
        if (obj instanceof GameView) return TYPE_GAME_VIEW;
        return -1;
    }

    /** Full-width object identity; card state is separate rather than packed into ID bits. */
    public record ObjectKey(int type, long id, int stateOrdinal) implements Serializable, Comparable<ObjectKey> {
        private static final long serialVersionUID = 1L;

        @Override
        public int compareTo(final ObjectKey other) {
            int result = Integer.compare(type, other.type);
            if (result == 0) { result = Long.compare(id, other.id); }
            return result == 0 ? Integer.compare(stateOrdinal, other.stateOrdinal) : result;
        }
    }

    public static ObjectKey makeDeltaKey(TrackableObject obj) {
        return new ObjectKey(typeTagFor(obj), obj.getId(),
                obj instanceof CardStateView csv ? csv.getState().ordinal() : -1);
    }

    public static ObjectKey makeDeltaKey(int type, long id) {
        return new ObjectKey(type, id, -1);
    }

    public static ObjectKey makeDeltaKey(int type, long id, int stateOrdinal) {
        return new ObjectKey(type, id, stateOrdinal);
    }

    public static int getTypeFromDeltaKey(ObjectKey key) { return key.type(); }
    public static long getIdFromDeltaKey(ObjectKey key) { return key.id(); }

    /** Each entry represents one attacking band with its defender, blockers, and planned blockers. */
    public static class CombatData implements Serializable {
        private static final long serialVersionUID = 1L;

        public final List<List<Long>> bandAttackerIds;
        /** {typeMarker, id} per band — 0=CardView, 1=PlayerView. */
        public final List<long[]> bandDefenderRefs;
        public final List<List<Long>> bandBlockerIds;
        public final List<List<Long>> bandPlannedBlockerIds;

        public CombatData(List<List<Long>> bandAttackerIds, List<long[]> bandDefenderRefs,
                          List<List<Long>> bandBlockerIds, List<List<Long>> bandPlannedBlockerIds) {
            this.bandAttackerIds = bandAttackerIds;
            this.bandDefenderRefs = bandDefenderRefs;
            this.bandBlockerIds = bandBlockerIds;
            this.bandPlannedBlockerIds = bandPlannedBlockerIds;
        }
    }

    /** Create an events-only DeltaPacket with no state deltas (seq=-1 means no ack needed). */
    public static DeltaPacket eventsOnly(List<Object> events) {
        DeltaPacket packet = new DeltaPacket(-1L, null, null, 0, null);
        packet.setEvents(events);
        return packet;
    }

    public DeltaPacket(long sequenceNumber, Map<ObjectKey, Map<TrackableProperty, Object>> objectDeltas,
                       Map<ObjectKey, Map<TrackableProperty, Object>> newObjects,
                       int checksum, int[] checksumProperties) {
        this.sequenceNumber = sequenceNumber;
        this.objectDeltas = objectDeltas != null ? objectDeltas : Collections.emptyMap();
        this.newObjects = newObjects != null ? newObjects : Collections.emptyMap();
        this.checksum = checksum;
        this.checksumProperties = checksumProperties;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public Map<ObjectKey, Map<TrackableProperty, Object>> getObjectDeltas() {
        return Collections.unmodifiableMap(objectDeltas);
    }

    public Map<ObjectKey, Map<TrackableProperty, Object>> getNewObjects() {
        return Collections.unmodifiableMap(newObjects);
    }

    public int getChecksum() {
        return checksum;
    }

    public boolean hasChecksum() {
        return checksumProperties != null;
    }

    /** Property ordinals included in the sampled checksum, or null for legacy checksum. */
    public int[] getChecksumProperties() {
        return checksumProperties;
    }

    public boolean isEmpty() {
        return objectDeltas.isEmpty() && newObjects.isEmpty() && !hasEvents() && !hasChecksum();
    }

    public void setEvents(List<Object> events) {
        this.events = events;
    }
    public List<Object> getEvents() {
        return events;
    }

    public boolean hasEvents() {
        return events != null && !events.isEmpty();
    }

    /** Return a shallow copy without events, for state-only size measurement. */
    public DeltaPacket withoutEvents() {
        return new DeltaPacket(sequenceNumber, objectDeltas, newObjects, checksum, checksumProperties);
    }

    public int getApproximateSize() {
        int size = 8 + 4; // sequenceNumber + checksum
        if (checksumProperties != null) {
            size += 4 + checksumProperties.length * 4; // array overhead + int per ordinal
        }
        for (Map<TrackableProperty, Object> delta : objectDeltas.values()) {
            size += 16 + delta.size() * 50; // key + ~50 bytes per property estimate
        }
        for (Map<TrackableProperty, Object> props : newObjects.values()) {
            size += 16 + props.size() * 50; // key + properties
        }
        return size;
    }

    @Override
    public String toString() {
        return String.format("DeltaPacket[seq=%d, newObjects=%d, deltas=%d, size~%d bytes]",
                sequenceNumber, newObjects.size(), objectDeltas.size(), getApproximateSize());
    }
}
