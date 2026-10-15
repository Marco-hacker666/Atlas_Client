package myau.module.modules;

import me.ksyz.accountmanager.utils.Nan0EventRegister;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Mines faster, two ways.
 *
 * BreakSpeed (owner's method, 2026-10-15): Forge's PlayerEvent.BreakSpeed
 * sets the dig speed per block -- wool 4, bed 3, end stone and planks 1.56,
 * anything else x1.39 -- so every tick of the dig does more. Registered on
 * Forge's bus through Nan0EventRegister, as UpdateNotifier is: this client
 * is not a mod container, and a plain EVENT_BUS.register is what the owner
 * had tried without effect.
 *
 * FinishEarly: the 1.8 server breaks a block on the client's STOP_DESTROY
 * when the damage it works out from the ticks since START_DESTROY is at least
 * 0.7 (ItemInWorldManager.blockRemoving), not 1.0. speed 0-100 % runs the
 * finish point from 1.0 (vanilla) down to that 0.7 (30 % faster). Until
 * 2026-10-15 the module only raised the damage once at the first tick, to
 * 0.3 x speed: a head start nobody noticed.
 */
public class SpeedMine extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Lowest finish point a vanilla server accepts. */
    private static final float SERVER_FINISH = 0.7F;
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"BreakSpeed", "FinishEarly"});
    public final PercentProperty speed = new PercentProperty("speed", 15, () -> this.mode.getValue() == 1);
    public final IntProperty delay = new IntProperty("delay", 0, 0, 4);

    public SpeedMine() {
        super("SpeedMine", false);
        try {
            Nan0EventRegister.register(MinecraftForge.EVENT_BUS, new ForgeHook(this));
        } catch (Throwable ignored) {
            // No Forge bus (tests): BreakSpeed does nothing.
        }
    }

    /** The damage at which the dig is finished, for speed 0-100 (FinishEarly). */
    static float finishAt(int speedPercent) {
        float share = Math.max(0, Math.min(100, speedPercent)) / 100.0F;
        return 1.0F - (1.0F - SERVER_FINISH) * share;
    }

    /** The dig speed BreakSpeed gives a block, from the speed vanilla would use. */
    static float breakSpeed(Block block, float vanilla) {
        if (block == Blocks.wool) {
            return 4.0F;
        }
        if (block == Blocks.bed) {
            return 3.0F;
        }
        if (block == Blocks.end_stone || block == Blocks.planks) {
            return 1.56F;
        }
        return vanilla * 1.39F;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            if (!mc.playerController.isInCreativeMode()) {
                if (mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK) {
                    IAccessorPlayerControllerMP controller = (IAccessorPlayerControllerMP) mc.playerController;
                    controller.setBlockHitDelay(Math.min(controller.getBlockHitDelay(), this.delay.getValue() + 1));
                    if (this.mode.getValue() == 1 && controller.getIsHittingBlock()
                            && controller.getCurBlockDamageMP() >= finishAt(this.speed.getValue())) {
                        controller.setCurBlockDamageMP(1.0F);
                    }
                }
            }
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getValue() == 0 ? "BreakSpeed" : String.format("%d%%", this.speed.getValue())};
    }

    /** The Forge-bus side; public, as Forge's ASM handlers need. */
    public static final class ForgeHook {
        private final SpeedMine module;

        public ForgeHook(SpeedMine module) {
            this.module = module;
        }

        @SubscribeEvent
        public void onBreakSpeed(PlayerEvent.BreakSpeed event) {
            if (!this.module.isEnabled() || this.module.mode.getValue() != 0
                    || mc.thePlayer == null || event.entityPlayer != mc.thePlayer) {
                return;
            }
            event.newSpeed = breakSpeed(event.state.getBlock(), event.newSpeed);
        }
    }
}
