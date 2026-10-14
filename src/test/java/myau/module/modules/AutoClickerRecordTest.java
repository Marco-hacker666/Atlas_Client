package myau.module.modules;

import myau.events.LeftClickMouseEvent;
import myau.module.Module;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

/**
 * Record mode must not learn the module's own clicks (PR #5 review). The
 * module presses the attack key, vanilla turns that press into clickMouse() in
 * the same tick, and the click comes back through LeftClickMouseEvent -- so
 * onCLick() ignores anything in a tick where the module clicked.
 *
 * The tick handler and the key simulation need a live client, so this drives
 * onCLick() directly with the state the tick would leave behind. The enabled
 * flag is set by reflection: setEnabled() runs onEnabled(), which needs a game.
 */
public class AutoClickerRecordTest {

    private static final int RECORD_MODE = 2;

    /** An enabled AutoClicker in Record mode that has already seen one click. */
    private static AutoClicker recordingClicker() throws Exception {
        AutoClicker autoClicker = new AutoClicker();
        set(autoClicker, Module.class, "enabled", true);
        autoClicker.clickMode.setValue(RECORD_MODE);
        set(autoClicker, AutoClicker.class, "lastRealClick", System.currentTimeMillis() - 100L);
        return autoClicker;
    }

    private static void set(Object owner, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static int intField(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(owner);
    }

    @Test
    public void aClickTheModuleSynthesizedIsNotRecorded() throws Exception {
        AutoClicker autoClicker = recordingClicker();
        set(autoClicker, AutoClicker.class, "moduleClickThisTick", true);

        autoClicker.onCLick(new LeftClickMouseEvent());

        assertEquals("a module click must not enter the recorded rhythm",
                0, intField(autoClicker, "recordCount"));
    }

    @Test
    public void aRealClickIsRecorded() throws Exception {
        AutoClicker autoClicker = recordingClicker();

        autoClicker.onCLick(new LeftClickMouseEvent());

        assertEquals("a real click must still be recorded", 1, intField(autoClicker, "recordCount"));
    }

    @Test
    public void clicksOutsideRecordModeAreIgnored() throws Exception {
        AutoClicker autoClicker = recordingClicker();
        autoClicker.clickMode.setValue(0); // Normal

        autoClicker.onCLick(new LeftClickMouseEvent());

        assertEquals("only record mode collects a rhythm", 0, intField(autoClicker, "recordCount"));
    }

    @Test
    public void aDisabledOrCancelledClickIsIgnored() throws Exception {
        AutoClicker autoClicker = recordingClicker();
        autoClicker.onCLick(new LeftClickMouseEvent());
        assertEquals("baseline real click", 1, intField(autoClicker, "recordCount"));

        LeftClickMouseEvent cancelled = new LeftClickMouseEvent();
        cancelled.setCancelled(true);
        autoClicker.onCLick(cancelled);
        assertEquals("a cancelled click is not a real click", 1, intField(autoClicker, "recordCount"));
    }
}
