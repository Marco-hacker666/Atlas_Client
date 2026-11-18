package atlas.management;

import atlas.event.EventManager;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.SessionEvent;
import atlas.util.ActionLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.play.server.S01PacketJoinGame;

import java.util.Locale;

public final class ServerSession {
    public static final class Session {
        private final long id;
        private final String serverKey;
        private final String address;
        private final long startedAt;
        private volatile int joins;
        private volatile int worlds;
        private volatile long endedAt;

        Session(long id, String serverKey, String address, long startedAt) {
            this.id = id;
            this.serverKey = serverKey;
            this.address = address;
            this.startedAt = startedAt;
        }

        public long id() {
            return this.id;
        }

        public String serverKey() {
            return this.serverKey;
        }

        public String address() {
            return this.address;
        }

        public long startedAt() {
            return this.startedAt;
        }

        public int joins() {
            return this.joins;
        }

        public int worlds() {
            return this.worlds;
        }

        public long endedAt() {
            return this.endedAt;
        }

        public boolean isOpen() {
            return this.endedAt == 0L;
        }

        @Override
        public String toString() {
            return "#" + this.id + " " + this.serverKey;
        }
    }

    public static final String SINGLEPLAYER = "singleplayer";

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static volatile ServerSession instance;

    private long nextId = 1L;
    private volatile Session current;

    private volatile String lastServerKey;

    private volatile long generation;

    private volatile int staleJoins;

    public ServerSession() {
        instance = this;
    }

    public static ServerSession get() {
        return instance;
    }

    public static Session current() {
        ServerSession self = instance;
        return self == null ? null : self.current;
    }

    public static String currentServerKey() {
        ServerSession self = instance;
        Session open = self == null ? null : self.current;
        return open == null ? null : open.serverKey;
    }

    public static String lastServerKey() {
        ServerSession self = instance;
        return self == null ? null : self.lastServerKey;
    }

    public static int staleJoins() {
        ServerSession self = instance;
        return self == null ? 0 : self.staleJoins;
    }

    public static String keyFor(String address) {
        if (address == null || address.trim().isEmpty()) {
            return SINGLEPLAYER;
        }
        String key = address.trim().toLowerCase(Locale.ROOT);
        if (key.endsWith(":25565")) {
            key = key.substring(0, key.length() - ":25565".length());
        }
        while (key.endsWith(".")) {
            key = key.substring(0, key.length() - 1);
        }
        return key.isEmpty() ? SINGLEPLAYER : key;
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.RECEIVE || !(event.getPacket() instanceof S01PacketJoinGame)
                || mc == null) {
            return;
        }
        ServerData data = mc.getCurrentServerData();
        mc.addScheduledTask(joinArrived(data == null ? null : data.serverIP));
    }

    Runnable joinArrived(final String address) {
        final long bound = this.generation;
        return new Runnable() {
            @Override
            public void run() {
                if (generation != bound) {
                    staleJoins++;
                    return;
                }
                joined(address);
            }
        };
    }

    @EventTarget(Priority.HIGHEST)
    public void onLoadWorld(LoadWorldEvent event) {
        worldLoaded(!event.isUnload());
    }

    void joined(String address) {
        String key = keyFor(address);
        Session open = this.current;
        if (open != null) {
            if (open.serverKey.equals(key)) {
                open.joins++;
                return;
            }
            end(open);
        }
        boolean changed = !key.equals(this.lastServerKey);
        Session session = new Session(this.nextId++, key, address == null ? SINGLEPLAYER : address,
                System.currentTimeMillis());
        session.joins = 1;
        this.current = session;
        this.lastServerKey = key;

        ActionLedger.clear();
        EventManager.call(new SessionEvent(SessionEvent.Type.START, session, changed));
    }

    void worldLoaded(boolean world) {
        if (!world) {
            this.generation++;
        }
        Session open = this.current;
        if (open == null) {
            return;
        }
        if (world) {
            open.worlds++;
            EventManager.call(new SessionEvent(SessionEvent.Type.WORLD, open, false));
        } else {
            end(open);
        }
    }

    private void end(Session session) {
        if (this.current == session) {
            this.current = null;
        }
        session.endedAt = System.currentTimeMillis();
        EventManager.call(new SessionEvent(SessionEvent.Type.END, session, false));
    }
}
