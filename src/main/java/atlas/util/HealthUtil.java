package atlas.util;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.Map;

public class HealthUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public static final int SLOT_LIST = 0;
    public static final int SLOT_BELOW_NAME = 2;

    public static final String SOURCE_TAB = "tab";
    public static final String SOURCE_BELOW = "below-name";
    public static final String SOURCE_ENTITY = "entity";
    public static final String SOURCE_NONE = "none";

    private HealthUtil() {
    }

    private static final Map<String, Float> cache = new HashMap<String, Float>();
    private static final Map<String, String> sources = new HashMap<String, String>();
    private static long cachedAt = Long.MIN_VALUE;

    private static void refresh() {
        if (mc.theWorld == null) {
            cache.clear();
            sources.clear();
            cachedAt = Long.MIN_VALUE;
            return;
        }
        long now = mc.theWorld.getTotalWorldTime();
        if (now == cachedAt) {
            return;
        }
        cachedAt = now;
        cache.clear();
        sources.clear();

        Scoreboard scoreboard = mc.theWorld.getScoreboard();
        if (scoreboard == null) {
            return;
        }

        collect(scoreboard, SLOT_BELOW_NAME, SOURCE_BELOW);
        collect(scoreboard, SLOT_LIST, SOURCE_TAB);
    }

    private static void collect(Scoreboard scoreboard, int slot, String source) {
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(slot);
        if (objective == null) {
            return;
        }
        boolean hearts = objective.getRenderType() == IScoreObjectiveCriteria.EnumRenderType.HEARTS;
        for (Score score : scoreboard.getSortedScores(objective)) {
            String name = score.getPlayerName();
            if (name == null || name.startsWith("#") || score.getScorePoints() <= 0) {
                continue;
            }
            float value = score.getScorePoints();

            if (!hearts && value > 20.0F) {
                continue;
            }
            cache.put(name, value);
            sources.put(name, source);
        }
    }

    public static Integer scoreIn(int slot, String name) {
        if (mc.theWorld == null || name == null) {
            return null;
        }
        Scoreboard scoreboard = mc.theWorld.getScoreboard();
        if (scoreboard == null) {
            return null;
        }
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(slot);
        if (objective == null) {
            return null;
        }
        for (Score score : scoreboard.getSortedScores(objective)) {
            if (name.equals(score.getPlayerName())) {
                return score.getScorePoints();
            }
        }
        return null;
    }

    private static boolean isHearts(int slot) {
        if (mc.theWorld == null) {
            return false;
        }
        Scoreboard scoreboard = mc.theWorld.getScoreboard();
        if (scoreboard == null) {
            return false;
        }
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(slot);
        return objective != null
                && objective.getRenderType() == IScoreObjectiveCriteria.EnumRenderType.HEARTS;
    }

    private static float fromSlot(int slot, String name, float maxHealth) {
        Integer score = scoreIn(slot, name);
        if (score == null || score <= 0) {
            return -1.0F;
        }
        float value = score.floatValue();

        if (!isHearts(slot) && value > maxHealth) {
            return -1.0F;
        }
        return value;
    }

    public static String source(EntityLivingBase entity) {
        if (entity == null) {
            return SOURCE_NONE;
        }
        if (entity instanceof EntityPlayer) {
            refresh();
            String source = sources.get(entity.getName());
            if (source != null) {
                return source;
            }
        }
        return SOURCE_ENTITY;
    }

    public static float resolve(EntityLivingBase entity) {
        if (entity == null) {
            return 0.0F;
        }
        if (entity instanceof EntityPlayer) {
            refresh();
            Float value = cache.get(entity.getName());
            if (value != null) {
                return Math.min(value.floatValue(), Math.max(1.0F, entity.getMaxHealth()));
            }
        }
        return entity.getHealth();
    }
}
