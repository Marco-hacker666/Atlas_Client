package atlas.module.modules;

import com.google.common.base.CaseFormat;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import atlas.events.StrafeEvent;
import atlas.events.UpdateEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.KeyBindUtil;
import atlas.util.MoveUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S12PacketEntityVelocity;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public class Jesus extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final DecimalFormat df = new DecimalFormat("#.##", new DecimalFormatSymbols(Locale.US));
    private boolean firstInWater = true;
    private int waterTicks = 0;
    private double waterPosY = 50.0;
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"HYPIXEL", "KARHU", "OLD_NCP", "VULCAN"});
    public final FloatProperty speed = new FloatProperty("speed", 2.5F, 0.0F, 3.0F);
    public final BooleanProperty noPush = new BooleanProperty("no-push", true);
    public final BooleanProperty groundOnly = new BooleanProperty("ground-only", true);
    public final FloatProperty dip = new FloatProperty("dip", 0.15F, 0.0F, 0.3F, () -> mode.getValue() <= 2);
    public final BooleanProperty solidity = new BooleanProperty("solidity", true, () -> mode.getValue() <= 2);
    public final FloatProperty strafeBoost = new FloatProperty("strafe-boost", 0.34F, 0.0F, 1.0F, () -> mode.getValue() == 3);
    public final FloatProperty hopHeight = new FloatProperty("hop-height", 0.6F, 0.0F, 1.0F, () -> mode.getValue() == 3);

    public Jesus() {
        super("Jesus", false, false, "Lets you walk and move on water and lava.");
    }

    private boolean inLiquid() {
        return mc.thePlayer != null && (mc.thePlayer.isInWater() || mc.thePlayer.isInLava());
    }

    private boolean sneakHeld() {
        return mc.gameSettings.keyBindSneak.isKeyDown();
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        if (!this.inLiquid()) {
            if (!this.firstInWater) {
                this.firstInWater = true;
            }
            return;
        }
        if (this.groundOnly.getValue() && !mc.thePlayer.onGround && this.mode.getValue() != 3) {
            return;
        }
        switch (this.mode.getValue()) {
            case 0: // Donor HypixelJesus: alternate dip offsets, spoil ground.
                mc.thePlayer.motionY = 0.0;
                mc.thePlayer.posY -= mc.thePlayer.ticksExisted % 2 == 0 ? this.dip.getValue().doubleValue() : 0.10625;
                break;
            case 1: // Donor KarhuJesus: tiny even-tick dip.
                mc.thePlayer.motionY = 0.0;
                if (mc.thePlayer.ticksExisted % 2 == 0) {
                    mc.thePlayer.posY -= 0.015625;
                }
                break;
            case 2: // Donor OldNCPJesus: dip only on even ticks.
                if (mc.thePlayer.ticksExisted % 2 == 0) {
                    mc.thePlayer.posY -= this.dip.getValue().doubleValue() / 10.0;
                }
                break;
            case 3: // Donor VulcanJesus: pin to entry height, hop out on jump.
                if (this.firstInWater) {
                    this.waterPosY = mc.thePlayer.posY - 0.85;
                    this.firstInWater = false;
                    this.waterTicks = 0;
                }
                if (!this.sneakHeld()) {
                    mc.thePlayer.motionY = 0.0;
                    if (MoveUtil.isMoving()) {
                        MoveUtil.setSpeed(MoveUtil.getAllowedHorizontalDistance() - Math.random() / 1000.0 + this.strafeBoost.getValue().doubleValue());
                    }
                    mc.thePlayer.setPosition(mc.thePlayer.posX, this.waterPosY - 0.2, mc.thePlayer.posZ);
                }
                if (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode()) && this.waterTicks > 4) {
                    mc.thePlayer.motionY = this.hopHeight.getValue().doubleValue();
                }
                this.waterTicks++;
                break;
            default:
                break;
        }
    }

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (this.inLiquid() && (!this.groundOnly.getValue() || mc.thePlayer.onGround || this.mode.getValue() == 3)) {
            event.setFriction((float) (0.1527 * this.speed.getValue().doubleValue()));
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        // Donor HypixelJesus: swallow self velocity while floating.
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || mc.thePlayer == null) {
            return;
        }
        if (this.mode.getValue() == 0 && this.noPush.getValue() && event.getPacket() instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();
            if (packet.getEntityID() == mc.thePlayer.getEntityId() && (this.solidity.getValue() || this.inLiquid())) {
                event.setCancelled(true);
            }
        }
    }

    @Override
    public void onEnabled() {
        this.firstInWater = true;
        this.waterTicks = 0;
        this.waterPosY = mc.thePlayer != null ? mc.thePlayer.posY : 50.0;
    }

    @Override
    public void onDisabled() {
        this.firstInWater = true;
        this.waterTicks = 0;
        this.waterPosY = 50.0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString()), df.format(this.speed.getValue())};
    }
}
