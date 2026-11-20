package forge.ai;

import forge.game.event.Event;
import java.util.Map;

/** Immutable standalone subordinate-choice observation, independent of rules events and storage. */
public record AiChoiceEvent(Map<String, Object> data) implements Event {
    public AiChoiceEvent { data = AiDecisionEvent.freezeMap(data); }
}
