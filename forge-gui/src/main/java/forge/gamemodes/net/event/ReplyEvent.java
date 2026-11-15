package forge.gamemodes.net.event;

import java.io.Serializable;

public final class ReplyEvent implements NetEvent {
    private static final long serialVersionUID = -2814651319617795386L;

    private final long index;
    private final Serializable reply;
    public ReplyEvent(final long index, final Serializable reply) {
        this.index = index;
        this.reply = reply;
    }

    public long getIndex() {
        return index;
    }
    public Object getReply() {
        return reply;
    }

    @Override
    public String toString() {
        return String.format("Reply (%d): %s", index, reply);
    }
}
