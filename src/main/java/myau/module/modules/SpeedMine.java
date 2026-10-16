package myau.module.modules;

import me.ksyz.accountmanager.utils.Nan0EventRegister;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.AsyncLog;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Mines faster, two ways.
 *
 * BreakSpeed: Forge's PlayerEvent.BreakSpeed, the dig speed the tool,
 * enchantments and potions give, times `multiplier`. Registered on Forge's
 * bus through Nan0EventRegister, as UpdateNotifier is (this client is no mod
 * container). 2026-10-15: first written with the fixed speeds the owner was
 * given (wool 4, end stone and planks 1.56, bed 3, else x1.39); fixed speeds
 * replace the tool's, so shears on wool (5) and any pickaxe on end stone
 * (2-8) got slower, and the owner found no difference. Now it multiplies.
 *
 * FinishEarly: the 1.8 server breaks a block on STOP_DESTROY when the damage
 * it works out from its own tick count is at least 0.7
 * (ItemInWorldManager.blockRemoving). speed 0-100 % runs the finish point
 * from 1.0 down to 0.7. The owner had 30 % (finish at 0.91, ~9 % faster).
 *
 * Both are limited by the server: past what it accepts it puts the block
 * back. `log` writes one line per block (config/Atlas/speedmine-*.txt):
 * ticks taken, the vanilla estimate, and whether the server KEPT the break or
 * REVERTED it, so the setting can be judged from evidence.
 */
public class SpeedMine extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Lowest finish point a vanilla server accepts. */
    private static final float SERVER_FINISH = 0.7F;
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"BreakSpeed", "FinishEarly"});
    public final FloatProperty multiplier = new FloatProperty("multiplier", 1.4F, 1.0F, 3.0F,
            () -> this.mode.getValue() == 0);
    public final PercentProperty speed = new PercentProperty("speed", 15, () -> this.mode.getValue() == 1);
    public final IntProperty delay = new IntProperty("delay", 0, 0, 4);
    public final BooleanProperty log = new BooleanProperty("log", true);

    private int tick;
    private boolean wasHitting;
    private BlockPos digPos;
    private String digBlock;
    private String digTool;
    private int digStart;
    private BlockPos watchPos;
    private int watchUntil;
    private String watchLine;
    private File logTarget;

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

    /** How many times faster than vanilla the current setting digs. */
    private float factor() {
        return this.mode.getValue() == 0 ? this.multiplier.getValue() : 1.0F / finishAt(this.speed.getValue());
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.tick++;
        IAccessorPlayerControllerMP controller = (IAccessorPlayerControllerMP) mc.playerController;
        this.watch();
        boolean hitting = controller.getIsHittingBlock();
        if (hitting && !this.wasHitting && mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK) {
            this.digPos = mc.objectMouseOver.getBlockPos();
            Block block = mc.theWorld.getBlockState(this.digPos).getBlock();
            this.digBlock = Block.blockRegistry.getNameForObject(block).toString();
            ItemStack held = mc.thePlayer.getHeldItem();
            this.digTool = held == null ? "hand" : String.valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(held.getItem()));
            this.digStart = this.tick;
        } else if (!hitting && this.wasHitting && this.digPos != null) {
            if (mc.theWorld.getBlockState(this.digPos).getBlock().getMaterial() == Material.air) {
                int ticks = this.tick - this.digStart;
                this.watchPos = this.digPos;
                this.watchUntil = this.tick + 20;
                this.watchLine = String.format("%s with %s | %s %s | %d ticks (vanilla ~%d)", this.digBlock, this.digTool,
                        this.mode.getModeString(), this.mode.getValue() == 0
                                ? String.format("x%.2f", this.multiplier.getValue())
                                : this.speed.getValue() + "%", ticks, Math.round(ticks * this.factor()));
            }
            this.digPos = null;
        }
        this.wasHitting = hitting;

        if (!mc.playerController.isInCreativeMode() && mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK) {
            controller.setBlockHitDelay(Math.min(controller.getBlockHitDelay(), this.delay.getValue() + 1));
            if (this.mode.getValue() == 1 && hitting
                    && controller.getCurBlockDamageMP() >= finishAt(this.speed.getValue())) {
                controller.setCurBlockDamageMP(1.0F);
            }
        }
    }

    /** A broken block that comes back within a second was refused by the server. */
    private void watch() {
        if (this.watchPos == null) {
            return;
        }
        boolean back = mc.theWorld.getBlockState(this.watchPos).getBlock().getMaterial() != Material.air;
        if (back || this.tick >= this.watchUntil) {
            this.writeLog(this.watchLine + " | " + (back ? "REVERTED by the server" : "KEPT"));
            this.watchPos = null;
        }
    }

    private void writeLog(String line) {
        if (!this.log.getValue()) {
            return;
        }
        if (this.logTarget == null) {
            this.logTarget = new File(LOG_DIR, "speedmine-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        AsyncLog.append(this.logTarget, LINE_STAMP.format(new Date()) + "  " + line);
    }

    @Override
    public void onDisabled() {
        this.digPos = null;
        this.watchPos = null;
        this.wasHitting = false;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getValue() == 0
                ? String.format("x%.1f", this.multiplier.getValue()) : String.format("%d%%", this.speed.getValue())};
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
            event.newSpeed *= this.module.multiplier.getValue();
        }
    }
}
