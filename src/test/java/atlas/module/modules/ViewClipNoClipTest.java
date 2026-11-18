package atlas.module.modules;

import atlas.module.Module;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ViewClipNoClipTest {
    private static ViewClip enabledViewClip() throws Exception {
        ViewClip viewClip = new ViewClip();
        setEnabledFlag(viewClip, true);
        return viewClip;
    }

    private static void setEnabledFlag(ViewClip viewClip, boolean enabled) throws Exception {
        Field field = Module.class.getDeclaredField("enabled");
        field.setAccessible(true);
        field.setBoolean(viewClip, enabled);
    }

    @Test
    public void noClipOffTurnsTheBypassOff() throws Exception {
        ViewClip viewClip = enabledViewClip();
        viewClip.noClip.setValue(false);
        assertFalse("no-clip off must not bypass blocks", viewClip.isNoClipActive());
        viewClip.noClip.setValue(true);
        assertTrue("no-clip on, CAMERA mode must bypass blocks", viewClip.isNoClipActive());
    }

    @Test
    public void vanillaModeNeverBypasses() throws Exception {
        ViewClip viewClip = enabledViewClip();
        viewClip.mode.setValue(1);
        assertFalse("VANILLA mode must leave the camera as vanilla has it", viewClip.isNoClipActive());
        viewClip.mode.setValue(0);
        assertTrue("CAMERA must bypass blocks again", viewClip.isNoClipActive());
    }

    @Test
    public void disabledModuleNeverBypasses() throws Exception {
        ViewClip viewClip = enabledViewClip();
        setEnabledFlag(viewClip, false);
        assertFalse("disabled module must not bypass blocks", viewClip.isNoClipActive());
    }
}
