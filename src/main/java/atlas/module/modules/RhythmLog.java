package atlas.module.modules;

import atlas.util.AsyncLog;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Silent Aura's Rhythm block, written down while it runs (2026-10-27): the
 * numbers that say whether it blocks more than half the hits taken without
 * costing damage dealt. config/Atlas/rhythm-[stamp].txt (Rhythm-Log).
 *
 *  - TAKEN: each hit taken, its damage (health and absorption before the
 *    hurt showed against three ticks after) and the block the server most
 *    likely had when it landed: our use/release packets reach the server half
 *    a round trip after they leave and the hurt comes back the other half, so
 *    the server judged the hit against the sword as the client had it about a
 *    round trip earlier (two neighbouring ticks: both up B, both down O, else ?).
 *    On Pika a blocked hit does half the damage after armour, so the damage
 *    column checks the guess.
 *  - HIT: each attack on the target, whether it was due; LAND when its hurt
 *    came back (and how many ticks later), NOSHOW when it did not.
 *  - LAG: each Rhythm-Lag hold, its ticks and the movement it held.
 *  - SUMMARY also counts the movement ticks the server judged with the sword
 *    up, how many were unslowed (FreeRaise) and the longest unslowed run --
 *    Grim's NoSlow flags at two in a row, so it must stay at 1.
 *  - SUMMARY each minute of fighting and when Rhythm stops.
 */
final class RhythmLog {
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss.SSS");
    private static final int HISTORY = 64;
    private static final int READ_DAMAGE_AFTER = 3;
    private static final int SUMMARY_TICKS = 1200;

    private File file;
    private boolean started;
    /** Sword up at the start of each tick (what the packets sent before it left the server with). */
    private final boolean[] upAt = new boolean[HISTORY];
    private final float[] hpAt = new float[HISTORY];
    private final boolean[] seen = new boolean[HISTORY];
    private int lastTick = Integer.MIN_VALUE;
    private int lastSelfHurt;
    private int upSince = -1;

    private int takenAt = -1;
    private float takenBefore;
    private String takenServer = "?";
    private boolean takenThreat;
    private boolean takenUp;
    private int takenHeld;
    private int takenRtt;
    private double takenNear;

    private int hitAt = -1;
    private int hitTarget = -1;
    private int lastTargetHurt;
    private int lastTarget = -1;

    private int ticks;
    private int taken;
    private int takenNoThreat;
    private int blocked;
    private int open;
    private int unsure;
    private float dmgBlocked;
    private float dmgOpen;
    private float dmgUnsure;
    private int attacks;
    private int dueAttacks;
    private int landed;
    private int noShow;
    private int threatTicks;
    private int upThreatTicks;
    private int raises;
    private int releases;
    private long pingSum;
    private int pingCount;
    private int holds;
    private int holdTicks;
    private int maxHold;
    private int usingTicks;
    private int unslowedTicks;
    private int unslowedRun;
    private int longestUnslowedRun;

    void start(String settings) {
        this.started = true;
        write("START rhythm  " + settings);
    }

    void tick(int tick, boolean up, boolean threat, int selfHurt, float hp, int rtt, int ping, boolean raised,
              boolean released, int targetId, int targetHurt, double nearest) {
        if (!this.started) {
            start("(log on)");
        }
        if (tick != this.lastTick + 1) {
            java.util.Arrays.fill(this.seen, false);
            this.takenAt = -1;
            this.hitAt = -1;
        }
        this.lastTick = tick;
        int i = Math.floorMod(tick, HISTORY);
        this.upAt[i] = up;
        this.hpAt[i] = hp;
        this.seen[i] = true;
        if (up && this.upSince < 0) {
            this.upSince = tick;
        } else if (!up) {
            this.upSince = -1;
        }

        this.ticks++;
        if (threat) {
            this.threatTicks++;
            if (up) {
                this.upThreatTicks++;
            }
        }
        if (raised) {
            this.raises++;
        }
        if (released) {
            this.releases++;
        }
        if (ping > 0) {
            this.pingSum += ping;
            this.pingCount++;
        }

        /* A hit taken: hurt rose to 10 this tick. */
        if (this.takenAt >= 0 && tick - this.takenAt >= READ_DAMAGE_AFTER) {
            finishTaken(hp);
        }
        if (selfHurt > this.lastSelfHurt && selfHurt >= 9) {
            if (this.takenAt >= 0) {
                finishTaken(hp);
            }
            this.takenAt = tick;
            this.takenBefore = valueAt(this.hpAt, tick - 1, hp);
            this.takenServer = serverBlock(tick, rtt);
            this.takenThreat = threat;
            this.takenUp = up;
            this.takenHeld = this.upSince >= 0 ? tick - this.upSince : 0;
            this.takenRtt = rtt;
            this.takenNear = nearest;
        }
        this.lastSelfHurt = selfHurt;

        /* Our attack's hurt coming back. A tick without a target (the aura's HurtTime filter drops one for the
           tick its hurt is 10: the 10-27 log's NOSHOW on every hit) keeps what is known about it. */
        if (targetId < 0) {
            if (this.hitAt >= 0 && tick - this.hitAt > rtt + 3) {
                write(String.format(Locale.ROOT, "NOSHOW after %dt (rtt %d)", tick - this.hitAt, rtt));
                this.noShow++;
                this.hitAt = -1;
            }
            if (this.ticks >= SUMMARY_TICKS) {
                summary();
            }
            return;
        }
        if (targetId != this.lastTarget) {
            this.lastTarget = targetId;
            this.lastTargetHurt = targetHurt;
        }
        if (this.hitAt >= 0) {
            if (targetId == this.hitTarget && targetHurt > this.lastTargetHurt && targetHurt >= 8) {
                write(String.format(Locale.ROOT, "LAND   +%dt", tick - this.hitAt - (10 - targetHurt)));
                this.landed++;
                this.hitAt = -1;
            } else if (tick - this.hitAt > rtt + 3) {
                write(String.format(Locale.ROOT, "NOSHOW after %dt (rtt %d)", tick - this.hitAt, rtt));
                this.noShow++;
                this.hitAt = -1;
            }
        }
        this.lastTargetHurt = targetHurt;

        if (this.ticks >= SUMMARY_TICKS) {
            summary();
        }
    }

    /** An attack on the target left; {@code due}: the window's damaging one. */
    void attack(int tick, String name, int targetHurt, int wait, int rtt, boolean due) {
        this.attacks++;
        if (due) {
            this.dueAttacks++;
            this.hitAt = tick;
            this.hitTarget = this.lastTarget;
        }
        write(String.format(Locale.ROOT, "HIT    %s hurt=%d wait=%d rtt=%d %s", name, targetHurt, wait, rtt,
                due ? "due" : "early"));
    }

    /** A movement tick the server judged with the sword up; {@code unslowed}: FreeRaise moved it at full speed. */
    void usingTick(boolean unslowed) {
        this.usingTicks++;
        if (unslowed) {
            this.unslowedTicks++;
            this.unslowedRun++;
            this.longestUnslowedRun = Math.max(this.longestUnslowedRun, this.unslowedRun);
        } else {
            this.unslowedRun = 0;
        }
    }

    /** A Rhythm-Lag hold ended. */
    void hold(int ticks, int moves, int packets, String why) {
        this.holds++;
        this.holdTicks += ticks;
        this.maxHold = Math.max(this.maxHold, ticks);
        write(String.format(Locale.ROOT, "LAG    held %dt (%d moves, %d packets) %s", ticks, moves, packets, why));
    }

    void stop() {
        if (this.takenAt >= 0) {
            finishTaken(valueAt(this.hpAt, this.lastTick, 0.0F));
        }
        if (this.ticks > 0) {
            summary();
        }
        if (this.started) {
            write("STOP");
        }
        this.started = false;
        this.lastTick = Integer.MIN_VALUE;
        this.upSince = -1;
    }

    private void finishTaken(float hpNow) {
        float damage = Math.max(0.0F, this.takenBefore - hpNow);
        this.taken++;
        if (!this.takenThreat) {
            this.takenNoThreat++;
        } else if ("B".equals(this.takenServer)) {
            this.blocked++;
            this.dmgBlocked += damage;
        } else if ("O".equals(this.takenServer)) {
            this.open++;
            this.dmgOpen += damage;
        } else {
            this.unsure++;
            this.dmgUnsure += damage;
        }
        write(String.format(Locale.ROOT, "TAKEN  %.2f srv=%s client=%s held=%dt threat=%s near=%.2f rtt=%d",
                damage, this.takenServer, this.takenUp ? "up" : "down", this.takenHeld, this.takenThreat ? "Y" : "N",
                this.takenNear > 99 ? -1.0 : this.takenNear, this.takenRtt));
        this.takenAt = -1;
    }

    /** B / O / ? from the sword as the client had it one round trip before {@code tick} (and the tick after). */
    private String serverBlock(int tick, int rtt) {
        int a = tick - rtt;
        int b = tick - rtt + 1;
        if (!known(a) || !known(b)) {
            return "?";
        }
        boolean upA = this.upAt[Math.floorMod(a, HISTORY)];
        boolean upB = this.upAt[Math.floorMod(b, HISTORY)];
        return upA && upB ? "B" : !upA && !upB ? "O" : "?";
    }

    private boolean known(int tick) {
        return tick <= this.lastTick && this.lastTick - tick < HISTORY && this.seen[Math.floorMod(tick, HISTORY)];
    }

    private float valueAt(float[] values, int tick, float fallback) {
        return known(tick) ? values[Math.floorMod(tick, HISTORY)] : fallback;
    }

    private void summary() {
        int judged = this.blocked + this.open;
        write(String.format(Locale.ROOT,
                "SUMMARY %ds  taken %d (no threat %d): blocked~ %d avg %s | open %d avg %s | unsure %d avg %s"
                        + " | blocked share %s",
                this.ticks / 20, this.taken, this.takenNoThreat, this.blocked, avg(this.dmgBlocked, this.blocked),
                this.open, avg(this.dmgOpen, this.open), this.unsure, avg(this.dmgUnsure, this.unsure),
                judged == 0 ? "-" : Math.round(100.0 * this.blocked / judged) + "%"));
        write(String.format(Locale.ROOT,
                "        attacks %d (due %d) landed %d noshow %d | sword up %s of threat ticks | raises %d releases %d"
                        + " | ping avg %s",
                this.attacks, this.dueAttacks, this.landed, this.noShow,
                this.threatTicks == 0 ? "-" : Math.round(100.0 * this.upThreatTicks / this.threatTicks) + "%",
                this.raises, this.releases, this.pingCount == 0 ? "-" : String.valueOf(this.pingSum / this.pingCount)));
        if (this.holds > 0) {
            write(String.format(Locale.ROOT, "        lag holds %d, %d ticks held (%d%% of fighting), longest %dt",
                    this.holds, this.holdTicks, Math.round(100.0 * this.holdTicks / Math.max(1, this.ticks)),
                    this.maxHold));
        }
        if (this.usingTicks > 0) {
            write(String.format(Locale.ROOT, "        sword-up moves the server judged %d: slowed %d, unslowed %d, longest unslowed run %d",
                    this.usingTicks, this.usingTicks - this.unslowedTicks, this.unslowedTicks, this.longestUnslowedRun));
        }
        this.ticks = 0;
        this.taken = 0;
        this.takenNoThreat = 0;
        this.blocked = 0;
        this.open = 0;
        this.unsure = 0;
        this.dmgBlocked = 0.0F;
        this.dmgOpen = 0.0F;
        this.dmgUnsure = 0.0F;
        this.attacks = 0;
        this.dueAttacks = 0;
        this.landed = 0;
        this.noShow = 0;
        this.threatTicks = 0;
        this.upThreatTicks = 0;
        this.raises = 0;
        this.releases = 0;
        this.pingSum = 0L;
        this.pingCount = 0;
        this.holds = 0;
        this.holdTicks = 0;
        this.maxHold = 0;
        this.usingTicks = 0;
        this.unslowedTicks = 0;
        this.longestUnslowedRun = 0;
    }

    private static String avg(float sum, int count) {
        return count == 0 ? "-" : String.format(Locale.ROOT, "%.2f", sum / count);
    }

    private void write(String line) {
        if (this.file == null) {
            this.file = new File(LOG_DIR, "rhythm-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        AsyncLog.append(this.file, LINE_STAMP.format(new Date()) + "  " + line);
    }
}
