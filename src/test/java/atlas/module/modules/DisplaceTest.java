package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.*;

/** 2026-11-04: Dynamic falls back on Yaw-offset and Direction, so both stay in the menu. */
public class DisplaceTest {

    @Test
    public void theFallbackSettingsAreShownInDynamicToo() {
        Displace displace = new Displace();
        assertTrue(displace.dynamicAngle.parseString("Dynamic"));
        assertTrue(displace.yawOffset.isVisible());
        assertTrue(displace.direction.isVisible());
        assertFalse("still only Static's", displace.findVoid.isVisible());
    }

    /** 2026-11-04 (owner): Keep-hitbox is off by default, in both modes. */
    @Test
    public void keepHitboxStartsOff() {
        assertFalse(new Displace().keepHitbox.getValue());
        assertFalse(new Hitflick().keepHitbox.getValue());
    }
}
