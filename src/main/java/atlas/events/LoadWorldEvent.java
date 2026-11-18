package atlas.events;

import atlas.event.events.Event;
import net.minecraft.client.multiplayer.WorldClient;

public class LoadWorldEvent implements Event {
    private final WorldClient world;

    public LoadWorldEvent() {
        this(null);
    }

    public LoadWorldEvent(WorldClient world) {
        this.world = world;
    }

    public WorldClient getWorld() {
        return this.world;
    }

    public boolean isUnload() {
        return this.world == null;
    }
}
