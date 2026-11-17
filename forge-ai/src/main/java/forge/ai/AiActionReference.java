package forge.ai;

/** Identity of a recorded selection, scoped to its run and game. */
public record AiActionReference(String decisionId, String actionId) { }
