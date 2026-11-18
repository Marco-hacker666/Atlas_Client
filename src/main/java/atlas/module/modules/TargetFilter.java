package atlas.module.modules;

import atlas.Atlas;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

public class TargetFilter extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final BooleanProperty friends = new BooleanProperty("skip-friends", true);
    public final BooleanProperty teammates = new BooleanProperty("skip-teammates", true);
    public final BooleanProperty bots = new BooleanProperty("skip-bots", true);

    public final BooleanProperty invisible = new BooleanProperty("skip-invisible", false);

    public final BooleanProperty sleeping = new BooleanProperty("skip-sleeping", true);
    public final BooleanProperty shops = new BooleanProperty("skip-shops", true);

    public final FloatProperty maxDistance = new FloatProperty("max-distance", 24.0F, 3.0F, 64.0F);

    public final IntProperty settleTicks = new IntProperty("settle-ticks", 10, 0, 60);

    public TargetFilter() {
        super("TargetFilter", false, false,
                "One shared answer to whether a player counts as a target, used by the combat and latency modules");
    }

    public static boolean accepts(EntityPlayer player) {
        if (player == null || mc.thePlayer == null || mc.theWorld == null) {
            return false;
        }
        if (player == mc.thePlayer || player == mc.thePlayer.ridingEntity) {
            return false;
        }
        if (player.isDead || player.deathTime > 0 || player.getHealth() <= 0.0F) {
            return false;
        }
        TargetFilter filter = instance();
        if (filter == null || !filter.isEnabled()) {
            return !safeFriend(player);
        }
        return filter.test(player);
    }

    private boolean test(EntityPlayer player) {
        try {
            if (this.friends.getValue() && safeFriend(player)) {
                return false;
            }
            if (this.teammates.getValue() && TeamUtil.isSameTeam(player)) {
                return false;
            }
            if (this.bots.getValue() && TeamUtil.isBot(player)) {
                return false;
            }
            if (this.invisible.getValue() && player.isInvisible()) {
                return false;
            }
            if (this.sleeping.getValue() && player.isPlayerSleeping()) {
                return false;
            }
            if (this.shops.getValue() && TeamUtil.isShop(player)) {
                return false;
            }
            if (mc.thePlayer.getDistanceToEntity(player) > this.maxDistance.getValue()) {
                return false;
            }
            if (player.ticksExisted < this.settleTicks.getValue()) {
                return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean safeFriend(EntityPlayer player) {
        try {
            return TeamUtil.isFriend(player);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static TargetFilter instance() {
        try {
            return (TargetFilter) Atlas.moduleManager.modules.get(TargetFilter.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public String[] getSuffix() {
        if (mc.theWorld == null || mc.thePlayer == null) {
            return new String[0];
        }
        int accepted = 0;
        int seen = 0;
        try {
            for (Object object : mc.theWorld.playerEntities) {
                if (!(object instanceof EntityPlayer) || object == mc.thePlayer) {
                    continue;
                }
                seen++;
                if (accepts((EntityPlayer) object)) {
                    accepted++;
                }
            }
        } catch (Exception ignored) {
            return new String[0];
        }

        return new String[]{accepted + "/" + seen};
    }
}
