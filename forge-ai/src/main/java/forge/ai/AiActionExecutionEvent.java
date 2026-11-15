package forge.ai;

import forge.game.event.Event;

import java.util.Map;

/** Observational execution of a selected action; success does not imply spell resolution. */
public record AiActionExecutionEvent(Map<String, Object> data) implements Event {
    public AiActionExecutionEvent {
        data = new AiDecisionEvent(data).data();
    }
}
