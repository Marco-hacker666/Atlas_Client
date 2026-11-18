package atlas.event.events;

/**
 * The most basic form of an stoppable Event.
 * Stoppable events are called seperate from other events and the calling of methods is stopped
 * as soon as the EventStoppable is stopped.
 *
 * @author DarkMagician6
 * @since 26-9-13
 */
public abstract class EventStoppable implements Event {
    private boolean stopped;

    protected EventStoppable() {
    }

    public void stop() {
        stopped = true;
    }

    public boolean isStopped() {
        return stopped;
    }
}
