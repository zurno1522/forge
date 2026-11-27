package forge.game;

/** Isolated instrumentation fixture; no Forge game is run by this test. */
public final class Game {
    public void fireEvent(Runnable body, RuntimeException explicit) {
        if (explicit != null) { throw explicit; }
        body.run();
    }
}
