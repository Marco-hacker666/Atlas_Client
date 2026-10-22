package atlas.module.modules;

import java.lang.reflect.Field;
import java.util.List;
import atlas.event.types.EventType;
import atlas.events.LoadWorldEvent;
import atlas.events.TickEvent;
import atlas.module.Module;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The hold has to end (CrewX port, 2026-10-11). A hold that outlives its world
 * or its module never gives the client the knockback it is owed, and the queue
 * is what the ending releases -- so these check that a world change and a
 * disable both empty it, and that an ordinary tick before the timeout does not.
 *
 * onPacket() needs a live client (it asks mc.thePlayer for the entity id), and
 * the timeout's chat line does too, so the hold is set up through the state the
 * packet handler would leave behind and the release is triggered through the
 * paths that do not talk to chat.
 */
public class FreezeTest {

    private static Freeze holdingFreeze() throws Exception {
        Freeze freeze = new Freeze();
        set(freeze, Module.class, "enabled", true);
        set(freeze, Freeze.class, "holding", true);
        queue(freeze).add(new S32PacketConfirmTransaction());
        return freeze;
    }

    private static void set(Object owner, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static boolean holding(Freeze freeze) throws Exception {
        Field field = Freeze.class.getDeclaredField("holding");
        field.setAccessible(true);
        return field.getBoolean(freeze);
    }

    private static int heldTicks(Freeze freeze) throws Exception {
        Field field = Freeze.class.getDeclaredField("heldTicks");
        field.setAccessible(true);
        return field.getInt(freeze);
    }

    @SuppressWarnings("unchecked")
    private static List<Packet<INetHandlerPlayClient>> queue(Freeze freeze) throws Exception {
        Field field = Freeze.class.getDeclaredField("held");
        field.setAccessible(true);
        return (List<Packet<INetHandlerPlayClient>>) field.get(freeze);
    }

    private static void postTick(Freeze freeze) {
        freeze.onTick(new TickEvent(EventType.POST));
    }

    @Test
    public void aWorldChangeReleasesTheHold() throws Exception {
        Freeze freeze = holdingFreeze();

        freeze.onLoadWorld(new LoadWorldEvent());

        assertEquals("a hold belongs to the world it started in", 0, queue(freeze).size());
        assertFalse("and it is over", holding(freeze));
    }

    @Test
    public void disablingReleasesTheHold() throws Exception {
        Freeze freeze = holdingFreeze();

        freeze.onDisabled();

        assertEquals("the client is owed the held packets back", 0, queue(freeze).size());
        assertFalse(holding(freeze));
    }

    @Test
    public void theHoldDoesNotEndBeforeTheTimeout() throws Exception {
        Freeze freeze = holdingFreeze();
        freeze.maxTimeout.setValue(100);

        for (int i = 0; i < 99; i++) {
            postTick(freeze);
        }

        assertEquals("still holding just before the timeout", 1, queue(freeze).size());
        assertTrue(holding(freeze));
        assertEquals(99, heldTicks(freeze));
    }

    @Test
    public void theSuffixShowsTheCountdownAndThenReady() throws Exception {
        Freeze freeze = holdingFreeze();
        assertEquals("Ready", freeze.getSuffix()[0]);

        freeze.maxTimeout.setValue(100);
        for (int i = 0; i < 5; i++) {
            postTick(freeze);
        }

        assertEquals("5", freeze.getSuffix()[0]);
    }
}
