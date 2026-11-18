package atlas.event.events;

/**
 * Simple interface which should be implemented in events that can be cancelled.
 *
 * @author DarkMagician6
 * @since August 27, 2013
 */
public interface Cancellable {
    boolean isCancelled();

    void setCancelled(boolean state);
}
