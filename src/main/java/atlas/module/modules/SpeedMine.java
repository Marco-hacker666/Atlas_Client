package atlas.module.modules;

import me.ksyz.accountmanager.utils.Nan0EventRegister;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.mixin.IAccessorPlayerControllerMP;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import atlas.util.AsyncLog;
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

public class SpeedMine extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

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
        }
    }

    static float finishAt(int speedPercent) {
        float share = Math.max(0, Math.min(100, speedPercent)) / 100.0F;
        return 1.0F - (1.0F - SERVER_FINISH) * share;
    }

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
