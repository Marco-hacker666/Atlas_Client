package atlas.humanize;

/**
 * Every number the humanization engine works from (2026-10-25, owner: "the
 * aura hits too often -- a person lands about 40 %, misses aims; one global
 * humanization with every detail adjustable, for any module to use").
 *
 * The Humanization module copies its settings into a fresh profile each tick;
 * engines only read it. Percentages are 0..100 here as in the menu; the
 * engines turn them into fractions. Defaults are the Legit preset.
 */
public final class HumanProfile {
    public static final int PRESET_LEGIT = 0;
    public static final int PRESET_GOOD = 1;
    public static final int PRESET_TRYHARD = 2;
    public static final int PRESET_CUSTOM = 3;

    public static final int SHAPE_TRIANGULAR = 0;
    public static final int SHAPE_LOG_NORMAL = 1;
    public static final int SHAPE_UNIFORM = 2;

    // ---- accuracy
    /** Hit rate aimed for: clicks that land / clicks. */
    public float hitRate = 40.0F;
    /** How far the rate wanders around hitRate (slow drift), in points. */
    public float hitRateSpread = 8.0F;
    /** Time scale of that drift, seconds. */
    public float driftSeconds = 20.0F;
    /** Clicks the measured rate is taken over. */
    public int window = 30;
    /** How hard the measured rate is pulled back to the aim (0: pure dice). */
    public float correction = 0.6F;
    public boolean streaks = true;
    /** Per click, while neither hot nor cold. */
    public float hotChance = 3.0F;
    public float hotBonus = 15.0F;
    public int hotMin = 3;
    public int hotMax = 8;
    public float coldChance = 3.0F;
    public float coldPenalty = 20.0F;
    public int coldMin = 3;
    public int coldMax = 10;

    // ---- situations (points off, or on, the hit rate)
    public float distanceStart = 2.5F;
    public float distancePenalty = 8.0F;
    /** Per 0.1 block a tick of the target's sideways speed (across our look). */
    public float strafePenalty = 4.0F;
    public float targetAirPenalty = 10.0F;
    public float selfAirPenalty = 10.0F;
    public float hurtPenalty = 20.0F;
    /** Ticks after being hit the hurt penalty lasts (fading out). */
    public int hurtTicks = 10;
    /** The first click of a combo (after 1 s without a hit): signed. */
    public float firstHitBonus = 0.0F;
    public float lowHealthBonus = 10.0F;
    public float lowHealthThreshold = 6.0F;
    /** Lands at least this many a second whatever the dice say (0: off). */
    public float minHitsPerSecond = 0.0F;
    /** Never more than this many a second (0: off). */
    public float maxHitsPerSecond = 3.0F;

    // ---- how a miss looks (weights)
    public int missAimOff = 70;
    public int missSwingOnly = 20;
    public int missSkip = 10;
    /** How far past the hitbox edge an aimed miss passes, blocks. */
    public float aimOffMin = 0.10F;
    public float aimOffMax = 0.45F;
    /** Share of aimed misses that go wide sideways (the rest high or low). */
    public float aimOffHorizontal = 70.0F;
    /* Off by default: vanilla 1.8's 10-tick lock after a swing at the air is what most PvP clients remove. */
    public boolean missCooldown = false;

    // ---- reaction (ms)
    public int reactionNewMin = 180;
    public int reactionNewMax = 320;
    public int reactionReacquireMin = 120;
    public int reactionReacquireMax = 220;
    public int reactionSwitchMin = 150;
    public int reactionSwitchMax = 280;
    public int reactionShape = SHAPE_TRIANGULAR;
    /** A target seen again within this long is a reacquire, not a new one. */
    public int reacquireWindow = 3000;

    // ---- aim error
    /** Hand tremor on the aim, degrees. */
    public float trackingNoise = 0.6F;
    /** How fast it changes, Hz. */
    public float noiseSpeed = 1.5F;
    /** Extra tremor per 10 degrees a tick the target moves across the view. */
    public float noiseMotionScale = 1.0F;
    public float overshootChance = 25.0F;
    /** Turns larger than this may overshoot or undershoot, degrees. */
    public float overshootMinTurn = 20.0F;
    public float overshootMin = 5.0F;
    public float overshootMax = 15.0F;
    public float undershootChance = 15.0F;
    public float undershootMin = 5.0F;
    public float undershootMax = 20.0F;
    /** Chance a second of a short lapse (the aim stops following). */
    public float lapseChance = 4.0F;
    public int lapseMin = 3;
    public int lapseMax = 8;
    /** Turn speed varies log-normally by this sigma, within min..max times. */
    public float speedSigma = 0.15F;
    public float speedMin = 0.6F;
    public float speedMax = 1.6F;

    // ---- clicks
    public boolean humanClicks = true;
    public float clickDrop = 2.0F;
    public float clickDouble = 3.0F;

    /** A preset's values (Custom: the defaults). */
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
