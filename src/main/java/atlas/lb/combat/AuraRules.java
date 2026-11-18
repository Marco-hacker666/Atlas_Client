package atlas.lb.combat;

// The LiquidBounce aura's rules that need no world (LiquidBounce NextGen port, GPL-3.0)

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

    public static final double JUMP_MOTION = 0.42;
    private static final double GRAVITY = 0.08;

    private AuraRules() {
    }

    public static boolean requirementsMet(int required, boolean clickedRecently, boolean weapon, boolean emptyHand,
                                          boolean vanillaName, boolean breaking) {
        return ((required & REQUIRE_CLICK) == 0 || clickedRecently)
                && ((required & REQUIRE_WEAPON) == 0 || weapon)
                && ((required & REQUIRE_EMPTY_HAND) == 0 || emptyHand)
                && ((required & REQUIRE_VANILLA_NAME) == 0 || vanillaName)
                && ((required & REQUIRE_NOT_BREAKING) == 0 || !breaking);
    }

    public static float interactionRange(float rangeIncrease) {
        return VANILLA_REACH + rangeIncrease;
    }

    public static float scanRange(float interactionRange, float throughWallsRange, float scanAddition) {
        return Math.max(interactionRange, throughWallsRange) + scanAddition;
    }

    public static boolean allowsCritical(boolean onGround, boolean blocked, boolean ignoreOnGround) {
        return !blocked && (ignoreOnGround || !onGround);
    }

    public static boolean shouldWaitForCrit(boolean onGround, boolean jumping, double motionY, float fallDistance,
                                            boolean blocked, int landingTick) {
        if (!allowsCritical(onGround, blocked, true)) {
            return false;
        }
        if (onGround && !jumping) {
            return false;
        }
        if (!onGround && motionY <= 0.0) {
            return fallDistance <= 0.0F;
        }
        double initial = onGround ? JUMP_MOTION : motionY;
        double ticksTillFall = initial / GRAVITY;
        return !(landingTick >= 0 && landingTick < (int) ticksTillFall);
    }

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
