package atlas.module.modules;

import com.google.common.base.CaseFormat;
import atlas.Atlas;
import atlas.enums.BlinkModules;
import atlas.event.EventTarget;
import atlas.event.types.Priority;
import atlas.events.KeyEvent;
import atlas.events.PlayerUpdateEvent;
import atlas.module.Module;
import atlas.util.PlayerUtil;
import atlas.util.RandomUtil;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition;
import net.minecraft.util.AxisAlignedBB;

public class AntiVoid extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private boolean isInVoid = false;
    private boolean wasInVoid = false;
    private double[] lastSafePosition = null;
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"BLINK"});
    public final FloatProperty distance = new FloatProperty("distance", 5.0F, 0.0F, 16.0F);

    public final BooleanProperty combatOnly = new BooleanProperty("not-in-combat", true);
    public final FloatProperty combatRange = new FloatProperty("combat-range", 8.0F, 2.0F, 30.0F,
            this.combatOnly::getValue);

    public final IntProperty combatTicks = new IntProperty("combat-ticks", 60, 10, 400,
            this.combatOnly::getValue);

    private int ticksSinceHurt = 999;

    private void resetBlink() {
        Atlas.blinkManager.setBlinkState(false, BlinkModules.ANTI_VOID);
        this.lastSafePosition = null;
    }

    private boolean canUseAntiVoid() {
        LongJump longJump = (LongJump) Atlas.moduleManager.modules.get(LongJump.class);
        if (longJump != null && longJump.isJumping()) {
            return false;
        }
        if (placing()) {
            return false;
        }
        return !this.combatOnly.getValue() || !inCombat();
    }

    private boolean inCombat() {
        if (mc.thePlayer.hurtTime > 0 || this.ticksSinceHurt < this.combatTicks.getValue()) {
            return true;
        }
        double range = this.combatRange.getValue();
        for (Object object : mc.theWorld.playerEntities) {
            if (!(object instanceof net.minecraft.entity.player.EntityPlayer)) {
                continue;
            }
            net.minecraft.entity.player.EntityPlayer player =
                    (net.minecraft.entity.player.EntityPlayer) object;
            if (player == mc.thePlayer || player.deathTime > 0
                    || atlas.util.TeamUtil.isFriend(player)
                    || atlas.util.TeamUtil.isSameTeam(player)
                    || atlas.util.TeamUtil.isBot(player)) {
                continue;
            }
            if (mc.thePlayer.getDistanceToEntity(player) <= range) {
                return true;
            }
        }
        return false;
    }

    private boolean placing() {
        return mc.gameSettings.keyBindUseItem.isKeyDown() && atlas.util.ItemUtil.isHoldingBlock();
    }

    public AntiVoid() {
        super("AntiVoid", false);
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(PlayerUpdateEvent event) {
        if (this.isEnabled()) {
            this.ticksSinceHurt = mc.thePlayer.hurtTime > 0 ? 0
                    : Math.min(999, this.ticksSinceHurt + 1);
            this.isInVoid = !mc.thePlayer.capabilities.allowFlying && PlayerUtil.isInWater();
            if (this.mode.getValue() == 0) {
                if (!this.isInVoid || placing() && Atlas.blinkManager.getBlinkingModule() == BlinkModules.ANTI_VOID) {
                    this.resetBlink();
                }
                if (this.lastSafePosition != null) {
                    float subWidth = mc.thePlayer.width / 2.0F;
                    float height = mc.thePlayer.height;
                    if (PlayerUtil.checkInWater(
                            new AxisAlignedBB(
                                    this.lastSafePosition[0] - (double) subWidth,
                                    this.lastSafePosition[1],
                                    this.lastSafePosition[2] - (double) subWidth,
                                    this.lastSafePosition[0] + (double) subWidth,
                                    this.lastSafePosition[1] + (double) height,
                                    this.lastSafePosition[2] + (double) subWidth
                            )
                    )) {
                        this.resetBlink();
                    }
                }
                if (!this.wasInVoid && this.isInVoid && this.canUseAntiVoid()) {
                    Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                    if (Atlas.blinkManager.setBlinkState(true, BlinkModules.ANTI_VOID)) {
                        this.lastSafePosition = new double[]{mc.thePlayer.prevPosX, mc.thePlayer.prevPosY, mc.thePlayer.prevPosZ};
                    }
                }
                if (Atlas.blinkManager.getBlinkingModule() == BlinkModules.ANTI_VOID
                        && this.lastSafePosition != null
                        && this.lastSafePosition[1] - (double) this.distance.getValue().floatValue() > mc.thePlayer.posY) {
                    Atlas.blinkManager
                            .blinkedPackets
                            .offerFirst(
                                    new C04PacketPlayerPosition(
                                            this.lastSafePosition[0], this.lastSafePosition[1] - RandomUtil.nextDouble(10.0, 20.0), this.lastSafePosition[2], false
                                    )
                            );
                    this.resetBlink();
                }
            }
            this.wasInVoid = this.isInVoid;
        }
    }

    @EventTarget
    public void onKey(KeyEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (event.getKey() == mc.gameSettings.keyBindUseItem.getKeyCode()) {
            ItemStack currentItem = mc.thePlayer.inventory.getCurrentItem();
            if (currentItem != null && (currentItem.getItem() instanceof ItemEnderPearl
                    || currentItem.getItem() instanceof net.minecraft.item.ItemBlock)) {
                this.resetBlink();
            }
        }
    }

    @Override
    public void onEnabled() {
        this.isInVoid = false;
        this.wasInVoid = false;
        this.resetBlink();
    }

    @Override
    public void onDisabled() {
        Atlas.blinkManager.setBlinkState(false, BlinkModules.ANTI_VOID);
    }

    @Override
    public void verifyValue(String mode) {
        if (this.isEnabled()) {
            this.onDisabled();
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}
