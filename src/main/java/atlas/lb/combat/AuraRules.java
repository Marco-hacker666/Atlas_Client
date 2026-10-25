package atlas.lb.combat;

/**
 * The LiquidBounce aura's rules that need no world (2026-10-25, LiquidBounce
 * NextGen port, GPL-3.0): KillAuraRequirements, the Criticals selection
 * (CriticalsSelectionMode, CriticalsJump.shouldWaitForCrit) and the ranges
 * (KillAuraRange / RangeValueGroup), adapted to 1.8:
 *
 *  - 1.8 has no attack cooldown, so the "ticks until the next possible crit"
 *    term is always 0;
 *  - a 1.8 critical needs falling, not on ground, no ladder, water, blindness
 *    or riding -- sprinting does not stop it -- so Smart / Always never make
 *    the aura stop sprinting (LiquidBounce does, for 1.9's rule);
 *  - the vanilla reach is 3.0 (LiquidBounce reads the 1.21 attribute).
 */
public final class AuraRules {
    public static final int REQUIRE_CLICK = 1;
    public static final int REQUIRE_WEAPON = 2;
    public static final int REQUIRE_EMPTY_HAND = 4;
    public static final int REQUIRE_VANILLA_NAME = 8;
    public static final int REQUIRE_NOT_BREAKING = 16;

    public static final int CRITS_SMART = 0;
    public static final int CRITS_IGNORE = 1;
    public static final int CRITS_ALWAYS = 2;

    public static final float VANILLA_REACH = 3.0F;
    /** Vanilla's jump velocity, LiquidBounce's default CriticalsJump height. */
    public static final double JUMP_MOTION = 0.42;
    private static final double GRAVITY = 0.08;

    private AuraRules() {
    }

    /** Whether every requirement in {@code required} (REQUIRE_* flags) holds. */
    public static boolean requirementsMet(int required, boolean clickedRecently, boolean weapon, boolean emptyHand,
                                          boolean vanillaName, boolean breaking) {
        return ((required & REQUIRE_CLICK) == 0 || clickedRecently)
                && ((required & REQUIRE_WEAPON) == 0 || weapon)
                && ((required & REQUIRE_EMPTY_HAND) == 0 || emptyHand)
                && ((required & REQUIRE_VANILLA_NAME) == 0 || vanillaName)
                && ((required & REQUIRE_NOT_BREAKING) == 0 || !breaking);
    }

    /** Interaction range: vanilla reach plus RangeIncrease. */
    public static float interactionRange(float rangeIncrease) {
        return VANILLA_REACH + rangeIncrease;
    }

    /** Scan range: the larger of interaction and through-walls range, plus this hold's ScanRangeIncrease draw. */
    public static float scanRange(float interactionRange, float throughWallsRange, float scanAddition) {
        return Math.max(interactionRange, throughWallsRange) + scanAddition;
    }

    /**
     * What vanilla 1.8 needs for a critical, apart from falling:
     * {@code blocked} = on a ladder, in liquid, blind, riding, flying, in a web.
     */
    public static boolean allowsCritical(boolean onGround, boolean blocked, boolean ignoreOnGround) {
        return !blocked && (ignoreOnGround || !onGround);
    }

    /**
     * Smart: whether to hold the click for a critical. {@code landingTick} is
     * the tick a fall from the jump (or the current rise) would land, or -1
     * when it does not within the look-ahead.
     */
    public static boolean shouldWaitForCrit(boolean onGround, boolean jumping, double motionY, float fallDistance,
                                            boolean blocked, int landingTick) {
        if (!allowsCritical(onGround, blocked, true)) {
            return false;
        }
        if (onGround && !jumping) {
            return false;
        }
        if (!onGround && motionY <= 0.0) {
            /* Already falling: a crit now, or next tick (no cooldown to wait for in 1.8). */
            return fallDistance <= 0.0F;
        }
        double initial = onGround ? JUMP_MOTION : motionY;
        double ticksTillFall = initial / GRAVITY;
        return !(landingTick >= 0 && landingTick < (int) ticksTillFall);
    }

    /** Whether a click now would be allowed by the Criticals selection. */
    public static boolean criticalAllows(int mode, boolean onGround, boolean jumping, double motionY, float fallDistance,
                                         boolean blocked, int landingTick) {
        switch (mode) {
            case CRITS_IGNORE:
                return true;
            case CRITS_ALWAYS:
                return allowsCritical(onGround, blocked, false) && fallDistance > 0.0F;
            default:
                return !shouldWaitForCrit(onGround, jumping, motionY, fallDistance, blocked, landingTick);
        }
    }
}
