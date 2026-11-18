package atlas.humanize;

public final class HumanProfile {
    public static final int PRESET_LEGIT = 0;
    public static final int PRESET_GOOD = 1;
    public static final int PRESET_TRYHARD = 2;
    public static final int PRESET_CUSTOM = 3;

    public static final int SHAPE_TRIANGULAR = 0;
    public static final int SHAPE_LOG_NORMAL = 1;
    public static final int SHAPE_UNIFORM = 2;

    public float hitRate = 40.0F;

    public float hitRateSpread = 8.0F;

    public float driftSeconds = 20.0F;

    public int window = 30;

    public float correction = 0.6F;
    public boolean streaks = true;

    public float hotChance = 3.0F;
    public float hotBonus = 15.0F;
    public int hotMin = 3;
    public int hotMax = 8;
    public float coldChance = 3.0F;
    public float coldPenalty = 20.0F;
    public int coldMin = 3;
    public int coldMax = 10;

    public float distanceStart = 2.5F;
    public float distancePenalty = 8.0F;

    public float strafePenalty = 4.0F;
    public float targetAirPenalty = 10.0F;
    public float selfAirPenalty = 10.0F;
    public float hurtPenalty = 20.0F;

    public int hurtTicks = 10;

    public float firstHitBonus = 0.0F;
    public float lowHealthBonus = 10.0F;
    public float lowHealthThreshold = 6.0F;

    public float minHitsPerSecond = 0.0F;

    public float maxHitsPerSecond = 3.0F;

    public int missAimOff = 70;
    public int missSwingOnly = 20;
    public int missSkip = 10;

    public float aimOffMin = 0.10F;
    public float aimOffMax = 0.45F;

    public float aimOffHorizontal = 70.0F;

    public boolean missCooldown = false;

    public int reactionNewMin = 180;
    public int reactionNewMax = 320;
    public int reactionReacquireMin = 120;
    public int reactionReacquireMax = 220;
    public int reactionSwitchMin = 150;
    public int reactionSwitchMax = 280;
    public int reactionShape = SHAPE_TRIANGULAR;

    public int reacquireWindow = 3000;

    public float trackingNoise = 0.6F;

    public float noiseSpeed = 1.5F;

    public float noiseMotionScale = 1.0F;
    public float overshootChance = 25.0F;

    public float overshootMinTurn = 20.0F;
    public float overshootMin = 5.0F;
    public float overshootMax = 15.0F;
    public float undershootChance = 15.0F;
    public float undershootMin = 5.0F;
    public float undershootMax = 20.0F;

    public float lapseChance = 4.0F;
    public int lapseMin = 3;
    public int lapseMax = 8;

    public float speedSigma = 0.15F;
    public float speedMin = 0.6F;
    public float speedMax = 1.6F;

    public boolean humanClicks = true;
    public float clickDrop = 2.0F;
    public float clickDouble = 3.0F;

    public static HumanProfile preset(int preset) {
        HumanProfile p = new HumanProfile();
        switch (preset) {
            case PRESET_GOOD:
                p.hitRate = 55.0F;
                p.hitRateSpread = 7.0F;
                p.reactionNewMin = 170;
                p.reactionNewMax = 280;
                p.reactionReacquireMin = 110;
                p.reactionReacquireMax = 190;
                p.reactionSwitchMin = 140;
                p.reactionSwitchMax = 240;
                p.trackingNoise = 0.45F;
                p.overshootChance = 20.0F;
                p.undershootChance = 12.0F;
                p.lapseChance = 3.0F;
                p.distancePenalty = 6.0F;
                p.maxHitsPerSecond = 3.5F;
                break;
            case PRESET_TRYHARD:
                p.hitRate = 70.0F;
                p.hitRateSpread = 6.0F;
                p.reactionNewMin = 140;
                p.reactionNewMax = 230;
                p.reactionReacquireMin = 90;
                p.reactionReacquireMax = 160;
                p.reactionSwitchMin = 120;
                p.reactionSwitchMax = 200;
                p.trackingNoise = 0.3F;
                p.overshootChance = 15.0F;
                p.undershootChance = 8.0F;
                p.lapseChance = 1.5F;
                p.distancePenalty = 4.0F;
                p.hurtPenalty = 12.0F;
                p.maxHitsPerSecond = 4.0F;
                break;
            default:
                break;
        }
        return p;
    }
}
