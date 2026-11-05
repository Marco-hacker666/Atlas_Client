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

    /** 2026-11-05: a saved config, or none, keeps every hit; the new settings change nothing until asked. */
    @Test
    public void newSettingsStartAsItWas() {
        Displace displace = new Displace();
        assertEquals("Every", displace.hitMode.getModeString());
        assertFalse(displace.maxHurtTime.isVisible());
        assertFalse(displace.hitCooldown.isVisible());
        assertEquals(0.0F, displace.angleRandom.getValue(), 0.0F);
        assertFalse(displace.directionLock.getValue());
        assertFalse(displace.onlyFacingAway.getValue());
        assertEquals("Left", displace.direction.getModeString());
        assertTrue(displace.direction.parseString("Right"));
        assertTrue(displace.hitMode.parseString("Effective"));
        assertTrue(displace.maxHurtTime.isVisible());
    }

    /** 2026-11-05: a catch has the look; a turn at priority 100 would take it from under the placement. */
    @Test
    public void noTurnDuringACatch() {
        atlas.management.Arbiter.setCatching("Clutch", true);
        try {
            assertTrue(Hitflick.standsAside());
        } finally {
            atlas.management.Arbiter.setCatching("Clutch", false);
        }
        assertFalse(atlas.management.Arbiter.catching());
    }

    @Test
    public void onlyAHitThatCanDoSomethingIsWorthTheTick() {
        assertTrue(DisplacePlan.worthTurning(3, 3, true, false, 500L, 500L));
        assertTrue("Knockback needs no sprint", DisplacePlan.worthTurning(0, 3, false, true, 500L, 500L));
        assertFalse("still in its hurt time", DisplacePlan.worthTurning(4, 3, true, false, 500L, 500L));
        assertFalse("nothing to steer", DisplacePlan.worthTurning(0, 3, false, false, 500L, 500L));
        assertFalse("cooling down", DisplacePlan.worthTurning(0, 3, true, false, 499L, 500L));
    }

    @Test
    public void theSideFollowsTheDirectionSetting() {
        assertTrue(DisplacePlan.left(DisplacePlan.DIRECTION_LEFT, -1.0F, false));
        assertFalse(DisplacePlan.left(DisplacePlan.DIRECTION_RIGHT, 1.0F, true));
        assertTrue(DisplacePlan.left(DisplacePlan.DIRECTION_RANDOM, 0.0F, true));
        assertFalse(DisplacePlan.left(DisplacePlan.DIRECTION_RANDOM, 0.0F, false));
        assertTrue("left key", DisplacePlan.left(DisplacePlan.DIRECTION_WITH_STRAFE, 1.0F, false));
        assertFalse("right key", DisplacePlan.left(DisplacePlan.DIRECTION_WITH_STRAFE, -1.0F, true));
        assertFalse(DisplacePlan.left(DisplacePlan.DIRECTION_AGAINST_STRAFE, 1.0F, true));
        assertTrue(DisplacePlan.left(DisplacePlan.DIRECTION_AGAINST_STRAFE, -1.0F, false));
    }

    @Test
    public void withNoStrafeYetTheStrafeWaysStillTurn() {
        assertTrue(DisplacePlan.left(DisplacePlan.DIRECTION_WITH_STRAFE, 0.0F, false));
        assertTrue(DisplacePlan.left(DisplacePlan.DIRECTION_AGAINST_STRAFE, 0.0F, false));
    }

    @Test
    public void theLockLastsForOneFightOnOneTarget() {
        assertTrue(DisplacePlan.lockHolds(7, 7, 100, 100 + DisplacePlan.LOCK_TICKS - 1));
        assertFalse(DisplacePlan.lockHolds(7, 7, 100, 100 + DisplacePlan.LOCK_TICKS));
        assertFalse("someone else", DisplacePlan.lockHolds(7, 8, 100, 101));
    }

    @Test
    public void facingAwayIsWithinAHundredDegreesOfOurYaw() {
        assertTrue(DisplacePlan.facingAway(0.0F));
        assertTrue(DisplacePlan.facingAway(-99.0F));
        assertFalse(DisplacePlan.facingAway(100.0F));
        assertFalse("face to face", DisplacePlan.facingAway(180.0F));
    }

    @Test
    public void theJitterStaysInItsRange() {
        assertEquals(-10.0F, DisplacePlan.jitter(10.0F, 0.0), 1.0E-6F);
        assertEquals(0.0F, DisplacePlan.jitter(10.0F, 0.5), 1.0E-6F);
        assertEquals(0.0F, DisplacePlan.jitter(0.0F, 0.9), 0.0F);
    }
}
