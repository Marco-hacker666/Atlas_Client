package atlas.events;

import atlas.event.events.Event;
import atlas.management.ServerSession;

public class SessionEvent implements Event {
    public enum Type {
        START,

        WORLD,

        END
    }

    private final Type type;
    private final ServerSession.Session session;
    private final boolean serverChanged;

    public SessionEvent(Type type, ServerSession.Session session, boolean serverChanged) {
        this.type = type;
        this.session = session;
        this.serverChanged = serverChanged;
    }

    public Type getType() {
        return this.type;
    }

    public ServerSession.Session getSession() {
        return this.session;
    }

    public boolean serverChanged() {
        return this.serverChanged;
    }
}
