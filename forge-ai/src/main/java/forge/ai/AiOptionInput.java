package forge.ai;

import forge.game.GameObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Exact objects captured in the existing diagnostic enumeration; never a feature-pool grant. */
record AiOptionInput(List<Entry> entries, boolean truncated) {
    record Entry(GameObject object, Map<String, Object> reference) { }

    static AiOptionInput capture(final Iterable<? extends GameObject> objects) {
        return capture(objects, () -> !Thread.currentThread().isInterrupted());
    }

    static AiOptionInput capture(final Iterable<? extends GameObject> objects, final java.util.function.BooleanSupplier active) {
        final List<Entry> entries = new ArrayList<>();
        final var captured = AiTargetChoiceRecorder.references(objects, active,
                (object, reference) -> entries.add(new Entry(object, reference)));
        return captured == null ? null : new AiOptionInput(List.copyOf(entries), captured.truncated());
    }

    List<Map<String, Object>> references() { return entries.stream().map(Entry::reference).toList(); }
}
