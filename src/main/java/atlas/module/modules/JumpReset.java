package atlas.module.modules;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.AttackEvent;
import atlas.events.KnockbackEvent;
import atlas.events.LoadWorldEvent;
import atlas.events.MoveInputEvent;
import atlas.events.PacketEvent;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import atlas.util.ActionLedger;
import atlas.util.KeyBindUtil;
import atlas.util.RandomUtil;
import atlas.util.RotationUtil;
import atlas.util.TeamUtil;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;

public class JumpReset extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final PercentProperty chance = new PercentProperty("chance", 60);

    public final PercentProperty maxRate = new PercentProperty("max-rate", 50);
    public final IntProperty maxStreak = new IntProperty("max-streak", 3, 0, 20);
    public final BooleanProperty requireSprint = new BooleanProperty("require-sprint", true);

    public final BooleanProperty onlyIfHelps = new BooleanProperty("only-if-helps", true);
    public final PercentProperty minReduction = new PercentProperty("min-reduction", 3, this.onlyIfHelps::getValue);
    public final BooleanProperty movingForward = new BooleanProperty("moving-forward", true);
    public final BooleanProperty mouseDown = new BooleanProperty("mouse-down", false);
    public final BooleanProperty aimingOnPlayer = new BooleanProperty("aiming-on-player", true);
    public final FloatProperty range = new FloatProperty("range", 5.0F, 0.0F, 10.0F, this.aimingOnPlayer::getValue);
    public final IntProperty fov = new IntProperty("fov", 180, 0, 360, this.aimingOnPlayer::getValue);
    public final IntProperty minInterval = new IntProperty("min-interval", 0, 0, 20);
    public final ModeProperty pauseWhen = new ModeProperty("pause-when", 2, new String[]{"Flag", "NoCombat", "Both", "None"});
    public final IntProperty flagTicks = new IntProperty("flag-ticks", 10, 0, 40, () -> this.pauseWhen.getValue() == 0 || this.pauseWhen.getValue() == 2);
    public final IntProperty combatTicks = new IntProperty("combat-ticks", 100, 0, 400, () -> this.pauseWhen.getValue() == 1 || this.pauseWhen.getValue() == 2);

    private boolean pending;
    private int pendingAge;
    private int streak;

    private final java.util.ArrayDeque<Boolean> recent = new java.util.ArrayDeque<>();

    private int streakLimit;
    private int ticksSinceFlag = 9999;
    private int ticksSinceAttack = 9999;
    private int ticksSinceReset = 9999;

    public JumpReset() {
        super("JumpReset", false, false, "Jumps when hurt to reduce incoming knockback.");
    }

    @EventTarget
    public void onKnockback(KnockbackEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null || event.isCancelled()) {
            return;
        }
        if (event.getY() <= 0.0D || !mc.thePlayer.onGround) {
            return;
        }
        Velocity velocity = (Velocity) Atlas.moduleManager.getModule(Velocity.class);
        if (velocity != null && velocity.polarActive()) {
            return;
        }
        if (!this.conditionsMet() || !this.allowed() || !this.helps(event.getX(), event.getZ())) {
            this.streak = 0;
            this.remember(false);
            return;
        }

        if (RandomUtil.nextInt(1, 100) > this.chance.getValue() || this.streakBroken() || this.rateFull()) {
            this.streak = 0;
            this.remember(false);
            return;
        }
        this.remember(true);
        this.pending = true;
        this.pendingAge = 0;
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || !this.pending || mc.thePlayer == null) {
            return;
        }
        this.pending = false;
        if (!mc.thePlayer.onGround || this.blocked()) {
            this.streak = 0;
            if (!this.recent.isEmpty()) {
                this.recent.removeLast();
                this.recent.addLast(false);
            }
            return;
        }
        mc.thePlayer.movementInput.jump = true;
        this.ticksSinceReset = 0;
        this.streak++;
        ActionLedger.note(this.getName(), "move");
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (this.isEnabled() && event.getType() == EventType.RECEIVE
                && event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.ticksSinceFlag = 0;
            this.pending = false;
            this.streak = 0;
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (this.isEnabled()) {
            this.ticksSinceAttack = 0;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (this.ticksSinceFlag < 10000) {
            this.ticksSinceFlag++;
        }
        if (this.ticksSinceAttack < 10000) {
            this.ticksSinceAttack++;
        }
        if (this.ticksSinceReset < 10000) {
            this.ticksSinceReset++;
        }

        if (this.pending && ++this.pendingAge > 1) {
            this.pending = false;
        }
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        this.resetState();
    }

    private boolean conditionsMet() {
        if (this.requireSprint.getValue() && !mc.thePlayer.isSprinting()) {
            return false;
        }
        if (this.movingForward.getValue() && !KeyBindUtil.isKeyDown(mc.gameSettings.keyBindForward.getKeyCode())) {
            return false;
        }
        if (this.mouseDown.getValue() && !KeyBindUtil.isKeyDown(-100)) {
            return false;
        }
        return !this.aimingOnPlayer.getValue() || this.nearestTarget(this.range.getValue()) != null;
    }

    private boolean helps(double kbX, double kbZ) {
        if (!this.onlyIfHelps.getValue()) {
            return true;
        }
        double kb = Math.sqrt(kbX * kbX + kbZ * kbZ);
        if (kb < 1.0E-3) {
            return false;
        }
        float yaw = atlas.management.RotationState.isActived()
                ? atlas.management.RotationState.getSmoothedYaw() : mc.thePlayer.rotationYaw;
        double rad = Math.toRadians(yaw);
        double x = kbX - Math.sin(rad) * 0.2;
        double z = kbZ + Math.cos(rad) * 0.2;
        double after = Math.sqrt(x * x + z * z);
        return (kb - after) / kb * 100.0 >= this.minReduction.getValue();
    }

    private boolean blocked() {
        if (mc.thePlayer.isPotionActive(Potion.jump) || mc.thePlayer.isInWater() || mc.thePlayer.isInLava()
                || mc.thePlayer.isOnLadder() || mc.thePlayer.capabilities.isFlying) {
            return true;
        }
        if (mc.currentScreen instanceof GuiInventory) {
            return true;
        }
        Module scaffold = Atlas.moduleManager.getModule(Scaffold.class);
        if (scaffold != null && scaffold.isEnabled()) {
            return true;
        }
        BlockPos at = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        return mc.theWorld.getBlockState(at).getBlock().getMaterial() == Material.web;
    }

    private boolean allowed() {
        if (this.blocked()) {
            return false;
        }
        if (this.ticksSinceReset < this.minInterval.getValue()) {
            return false;
        }
        switch (this.pauseWhen.getValue()) {
            case 0:
                return this.ticksSinceFlag >= this.flagTicks.getValue();
            case 1:
                return this.ticksSinceAttack <= this.combatTicks.getValue();
            case 2:
                return this.ticksSinceFlag >= this.flagTicks.getValue()
                        && this.ticksSinceAttack <= this.combatTicks.getValue();
            default:
                return true;
        }
    }

    private void remember(boolean reset) {
        this.recent.addLast(reset);
        while (this.recent.size() > 10) {
            this.recent.removeFirst();
        }
    }

    private boolean rateFull() {
        int made = 0;
        for (Boolean reset : this.recent) {
            if (reset) {
                made++;
            }
        }
        int window = Math.min(10, this.recent.size() + 1);
        return (made + 1) * 100 > this.maxRate.getValue() * window && window >= 4;
    }

    private boolean streakBroken() {
        int max = this.maxStreak.getValue();
        if (max <= 0) {
            return false;
        }
        if (this.streak == 0) {
            this.streakLimit = RandomUtil.nextInt(Math.max(1, (max + 1) / 2), max);
        }
        return this.streak >= this.streakLimit;
    }

    private EntityPlayer nearestTarget(double maxRange) {
        List<EntityPlayer> inRange = mc.theWorld.loadedEntityList.stream()
                .filter(entity -> entity instanceof EntityPlayer)
                .map(entity -> (EntityPlayer) entity)
                .filter(entity -> this.isValidTarget(entity, maxRange))
                .sorted(Comparator.comparingDouble(RotationUtil::distanceToEntity))
                .collect(Collectors.toList());
        if (inRange.isEmpty()) {
            return null;
        }
        EntityPlayer best = inRange.get(0);
        if (RotationUtil.angleToEntity(best) > this.fov.getValue() / 2.0F) {
            return null;
        }
        return best;
    }

    private boolean isValidTarget(EntityPlayer entity, double maxRange) {
        if (entity == mc.thePlayer || entity == mc.thePlayer.ridingEntity || entity.deathTime > 0) {
            return false;
        }
        if (RotationUtil.distanceToEntity(entity) > maxRange) {
            return false;
        }
        return !TeamUtil.isFriend(entity) && !TeamUtil.isSameTeam(entity) && !TeamUtil.isBot(entity);
    }

    private void resetState() {
        this.pending = false;
        this.pendingAge = 0;
        this.streak = 0;
        this.recent.clear();
        this.ticksSinceFlag = 9999;
        this.ticksSinceAttack = 9999;
        this.ticksSinceReset = 9999;
    }

    @Override
    public void onEnabled() {
        this.resetState();
    }

    @Override
    public void onDisabled() {
        this.resetState();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.chance.getValue() + "%"};
    }
}
