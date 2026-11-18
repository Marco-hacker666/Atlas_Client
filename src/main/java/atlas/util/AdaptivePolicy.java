package atlas.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AdaptivePolicy {
    private static final long DAY_MS = 24L * 3600L * 1000L;

    private AdaptivePolicy() {
    }

    public static final class Settings {
        public double minExcess = 0.35;
        public double minConfidence = 0.55;

        public long minSamples = 8L;

        public double minFlagsOn = 3.0;

        public long warmupMs = 3L * 60_000L;

        public double staleDays = Brain.HALF_LIFE_DAYS;

        public int minSessionFlags = 1;

        public int maxActs = 2;

        public double reactMinutes = 20.0;
    }

    public static final class Epoch {
        public final String module;
        public volatile String kind;
        public volatile long at;

        public volatile double rateOffAtAct;
        public volatile double minutesOn;
        public volatile double flagsOn;
        public volatile int acts;

        public Epoch(String module) {
            this.module = module;
        }

        public void acted(String kind, long at, double rateOff) {
            this.kind = kind;
            this.at = at;
            this.rateOffAtAct = rateOff;
            this.minutesOn = 0.0;
            this.flagsOn = 0.0;
            this.acts++;
        }

        public double rateOnSince() {
            return this.minutesOn <= 0.0 ? 0.0 : this.flagsOn / this.minutesOn;
        }
    }

    public interface Context {
        long now();

        long sessionStartedAt();

        String ineligible(String module);

        Epoch epoch(String module);
    }

    public static final class Outcome {
        public final Brain.Suspect chosen;

        public final String reason;

        public final Map<String, Integer> skipped;

        Outcome(Brain.Suspect chosen, String reason, Map<String, Integer> skipped) {
            this.chosen = chosen;
            this.reason = reason;
            this.skipped = skipped;
        }

        public boolean acts() {
            return this.chosen != null;
        }

        public String skippedSummary() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> entry : this.skipped.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(entry.getKey()).append(' ').append(entry.getValue());
            }
            return sb.toString();
        }
    }

    public static boolean mayProbe(Settings settings, Context context) {
        long started = context.sessionStartedAt();
        return started > 0L && context.now() - started >= settings.warmupMs;
    }

    public static Outcome decide(List<Brain.Suspect> suspects, Settings settings, Context context) {
        Map<String, Integer> skipped = new LinkedHashMap<String, Integer>();
        long started = context.sessionStartedAt();
        if (started <= 0L) {
            return new Outcome(null, "not connected", skipped);
        }
        long age = context.now() - started;
        if (age < settings.warmupMs) {
            return new Outcome(null, "warming up, " + (settings.warmupMs - age) / 1000L + "s left", skipped);
        }
        for (Brain.Suspect suspect : suspects) {
            String why = check(suspect, settings, context);
            if (why == null) {
                return new Outcome(suspect, "strongest suspect passing every rule", skipped);
            }
            Integer seen = skipped.get(why);
            skipped.put(why, seen == null ? 1 : seen + 1);
        }
        return new Outcome(null, suspects.isEmpty() ? "no suspects" : "no suspect passes the rules", skipped);
    }

    static String check(Brain.Suspect suspect, Settings settings, Context context) {
        if (suspect.excess() < settings.minExcess) {
            return "below-threshold";
        }
        if (suspect.confidence < settings.minConfidence) {
            return "low-confidence";
        }
        if (suspect.samples < settings.minSamples) {
            return "few-samples";
        }
        if (suspect.flagsOn < settings.minFlagsOn) {
            return "few-flags-on";
        }
        if (suspect.lastSeen > 0L && context.now() - suspect.lastSeen > settings.staleDays * DAY_MS) {
            return "stale";
        }
        if (suspect.session < settings.minSessionFlags) {
            return "not-this-session";
        }
        String ineligible = context.ineligible(suspect.module);
        if (ineligible != null) {
            return ineligible;
        }
        Epoch epoch = context.epoch(suspect.module);
        if (epoch != null && epoch.acts > 0) {
            if (epoch.acts >= settings.maxActs) {
                return "act-cap";
            }
            if (epoch.minutesOn < settings.reactMinutes) {
                return "awaiting-evidence";
            }
            if (epoch.rateOnSince() - epoch.rateOffAtAct < settings.minExcess) {
                return "resolved";
            }
        }
        return null;
    }
}
