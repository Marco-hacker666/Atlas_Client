package atlas.module.modules;

import atlas.property.Property;
import atlas.property.properties.ModeProperty;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Polar's ledge rule (2026-10-11). Polar is LiquidBounce's GodBridge technique,
 * run with polar.json's config: Modes = [Jump], ForceSneakBelowCount = 5,
 * SneakTime = 1..1.
 *
 * The owner's report against the first build was that it jumped on every block
 * ("it should jump like 1 time per 7 blocks"), that the look was not fixed, and
 * that the polar settings appeared while another rotation mode was selected.
 * The look itself is godBridgeRotation, which needs a live player, but the rule
 * that decides the ledge action is polarLedgeAction and has no client in it --
 * so the jump cadence and the mode scoping are checked here.
 */
public class ScaffoldPolarTest {

    /* Scaffold's own constants: POLAR_JUMP..POLAR_BACKWARDS, LEDGE_NONE. */
    private static final int JUMP = 0;
    private static final int SNEAK = 1;
    private static final int STOP_INPUT = 2;
    private static final int BACKWARDS = 3;
    private static final int NONE = -1;
    private static final int GODBRIDGE = 4;
    private static final int POLAR = 10;
    /** polar.json's ForceSneakBelowCount. */
    private static final int FORCE_SNEAK_BELOW = 5;

    private static int ledge(boolean onGround, boolean forwardPressed, boolean hasTarget,
            boolean onEdge, boolean canPlaceFromLook, int blockCount, int mode, int jumpBoost) {
        return Scaffold.polarLedgeAction(onGround, forwardPressed, hasTarget, onEdge,
                canPlaceFromLook, blockCount, FORCE_SNEAK_BELOW, mode, jumpBoost);
    }

    /** Bridging at the edge, plenty of blocks, no Jump Boost, Modes = [Jump]. */
    private static int atTheEdge(boolean canPlaceFromLook) {
        return ledge(true, true, true, true, canPlaceFromLook, 64, JUMP, -1);
    }

    /**
     * The whole of the "jumps way too often" complaint: LiquidBounce only takes
     * a ledge action when the look it is about to send cannot place the block,
     * so the edges that can still place are silent.
     */
    @Test
    public void aLookThatStillPlacesGetsNoLedgeAction() {
        assertEquals("the block goes down from here, so there is nothing to jump for",
                NONE, atTheEdge(true));
    }

    @Test
    public void theEdgeThatCannotPlaceIsWhatJumps() {
        assertEquals(JUMP, atTheEdge(false));
    }

    @Test
    public void awayFromTheEdgeNothingHappens() {
        assertEquals(NONE, ledge(true, true, true, false, false, 64, JUMP, -1));
    }

    @Test
    public void offTheGroundOrStandingStillNothingHappens() {
        assertEquals("a jump needs the ground",
                NONE, ledge(false, true, true, true, false, 64, JUMP, -1));
        assertEquals("not bridging forward",
                NONE, ledge(true, false, true, true, false, 64, JUMP, -1));
        assertEquals("no target block to place",
                NONE, ledge(true, true, false, true, false, 64, JUMP, -1));
    }

    @Test
    public void aLowBlockCountSneaksInsteadOfJumping() {
        assertEquals("polar.json: ForceSneakBelowCount = 5", SNEAK,
                ledge(true, true, true, true, false, FORCE_SNEAK_BELOW - 1, JUMP, -1));
        assertEquals("five blocks left is not below five", JUMP,
                ledge(true, true, true, true, false, FORCE_SNEAK_BELOW, JUMP, -1));
    }

    @Test
    public void theConfiguredModeIsTheActionTaken() {
        assertEquals(SNEAK, ledge(true, true, true, true, false, 64, SNEAK, -1));
        assertEquals(STOP_INPUT, ledge(true, true, true, true, false, 64, STOP_INPUT, -1));
        assertEquals(BACKWARDS, ledge(true, true, true, true, false, 64, BACKWARDS, -1));
    }

    @Test
    public void aJumpBoostIsNotWorthJumpingFor() {
        assertEquals("a jump that carries two blocks is a sneak instead", SNEAK,
                ledge(true, true, true, true, false, 64, JUMP, 1));
        assertEquals("one level still reaches under two blocks", JUMP,
                ledge(true, true, true, true, false, 64, JUMP, 0));
    }

    @Test
    public void aPlainJumpReachesAboutOneAndAQuarterBlocks() {
        double plain = Scaffold.jumpApexHeight(-1);
        assertTrue("0.42 stepped down by gravity and drag, not a guess: " + plain,
                plain > 1.2 && plain < 1.3);
        assertTrue("so it is under the two-block guard", plain < 2.0);
        assertTrue("Jump Boost II clears two blocks", Scaffold.jumpApexHeight(1) >= 2.0);
        assertTrue("Jump Boost I does not", Scaffold.jumpApexHeight(0) < 2.0);
    }

    /* ------------------------------------------------- polar.json's settings */

    private static Scaffold scaffold() throws Exception {
        Constructor<Scaffold> constructor = Scaffold.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Property<?> setting(Scaffold scaffold, String field) throws Exception {
        Field declared = Scaffold.class.getDeclaredField(field);
        declared.setAccessible(true);
        return (Property<?>) declared.get(scaffold);
    }

    private static int intValue(Property<?> property) {
        return (Integer) property.getValue();
    }

    @Test
    public void thePolarSettingsAreThePolarConfig() throws Exception {
        Scaffold scaffold = scaffold();

        assertEquals("ForceSneakBelowCount", FORCE_SNEAK_BELOW,
                intValue(setting(scaffold, "polarForceSneakBelowCount")));
        assertEquals("SneakTime", 1, intValue(setting(scaffold, "polarSneakTime")));

        ModeProperty modes = (ModeProperty) setting(scaffold, "polarModes");
        assertEquals("polar-modes", modes.getName());
        assertEquals("Modes = [Jump]", "Jump", modes.getModeString());
    }

    /**
     * The report: the polar settings showed while another rotation mode was selected. Since 2026-10-31 GodBridge is
     * LiquidBounce's GodBridge whole, ledge included (owner), so they are GodBridge's too -- and still no other mode's.
     */
    @Test
    public void thePolarSettingsAreShownOnlyForTheLiquidBounceGodBridge() throws Exception {
        Scaffold scaffold = scaffold();
        ModeProperty rotations = (ModeProperty) setting(scaffold, "rotationMode");
        String[] polarSettings = {"polarForceSneakBelowCount", "polarSneakTime", "polarModes"};

        for (String name : polarSettings) {
            rotations.setValue(1);
            assertFalse(name + " is not a DEFAULT setting", setting(scaffold, name).isVisible());
            rotations.setValue(GODBRIDGE);
            assertTrue(name + " is a GodBridge setting", setting(scaffold, name).isVisible());
            rotations.setValue(POLAR);
            assertTrue(name + " is a polar setting", setting(scaffold, name).isVisible());
        }
    }

    @Test
    public void thePolarModeNamesParseAsTheConfigSpellsThem() throws Exception {
        ModeProperty modes = (ModeProperty) setting(scaffold(), "polarModes");

        assertTrue(modes.parseString("Jump"));
        assertEquals(JUMP, intValue(modes));
        assertTrue(modes.parseString("Sneak"));
        assertEquals(SNEAK, intValue(modes));
        assertTrue("polar.json writes StopInput, a config may write stop_input",
                modes.parseString("stop_input"));
        assertEquals(STOP_INPUT, intValue(modes));
        assertTrue(modes.parseString("Backwards"));
        assertEquals(BACKWARDS, intValue(modes));
        assertFalse(modes.parseString("JumpReset"));
        assertEquals("an unknown name leaves the mode alone", BACKWARDS, intValue(modes));
    }
}
