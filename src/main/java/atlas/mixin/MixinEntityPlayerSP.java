package atlas.mixin;

import atlas.Atlas;
import atlas.event.EventManager;
import atlas.event.types.EventType;
import atlas.events.LivingUpdateEvent;
import atlas.events.MoveInputEvent;
import atlas.events.PlayerUpdateEvent;
import atlas.events.UpdateEvent;
import atlas.management.RotationState;
import atlas.module.modules.AntiDebuff;
import atlas.module.modules.NoSlow;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(value = {EntityPlayerSP.class}, priority = 9999)
public abstract class MixinEntityPlayerSP extends MixinEntityPlayer {
    @Unique
    private float overrideYaw = Float.NaN;
    @Unique
    private float overridePitch = Float.NaN;
    @Unique
    private float pendingYaw;
    @Unique
    private float pendingPitch;
    @Shadow
    private float lastReportedYaw;
    @Shadow
    private float lastReportedPitch;
    @Shadow
    public float renderArmYaw;
    @Shadow
    public float prevRenderArmYaw;

    @Inject(
            method = {"onUpdate"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void onUpdate(CallbackInfo callbackInfo) {
        if (atlas.module.modules.TickBase.skipPlayerTick) {
            /* A tick TickBase owes back for the ones it ran ahead (2026-10-04). */
            callbackInfo.cancel();
            return;
        }
        if (this.worldObj.isBlockLoaded(new BlockPos(this.posX, 0.0, this.posZ))) {
            UpdateEvent event = new UpdateEvent(EventType.PRE, this.lastReportedYaw, this.lastReportedPitch, this.rotationYaw, this.rotationPitch);
            EventManager.call(event);
            /* K1 (2026-10-07): KillAura's hit, after every module's rotation. */
            atlas.module.modules.KillAura aura = (atlas.module.modules.KillAura)
                    atlas.Atlas.moduleManager.modules.get(atlas.module.modules.KillAura.class);
            if (aura != null) {
                aura.afterRotations(event);
            }
            atlas.module.modules.KillAura2 aura2 = (atlas.module.modules.KillAura2)
                    atlas.Atlas.moduleManager.modules.get(atlas.module.modules.KillAura2.class);
            if (aura2 != null) {
                aura2.afterRotations(event);
            }
            RotationState.applyState(event.isRotated() && !this.isRiding(), event.getNewYaw(), event.getNewPitch(), event.getPreYaw(), event.isRotating());
            if (event.isRotated()) {
                this.pendingYaw = this.rotationYaw;
                this.pendingPitch = this.rotationPitch;
                this.overrideYaw = event.getNewYaw();
                this.overridePitch = event.getNewPitch();
            } else {
                this.pendingYaw = Float.NaN;
                this.pendingPitch = Float.NaN;
                this.overrideYaw = Float.NaN;
                this.overridePitch = Float.NaN;
            }
        }
    }

    @Inject(
            method = {"onUpdate"},
            at = {@At("RETURN")}
    )
    private void postUpdate(CallbackInfo callbackInfo) {
        if (this.worldObj.isBlockLoaded(new BlockPos(this.posX, 0.0, this.posZ))) {
            if (!Float.isNaN(this.pendingYaw) && !Float.isNaN(this.pendingPitch)) {
                this.lastReportedYaw = this.rotationYaw;
                this.lastReportedPitch = this.rotationPitch;
                this.rotationYaw = this.rotationYaw + MathHelper.wrapAngleTo180_float(this.pendingYaw - this.rotationYaw);
                this.rotationPitch = this.pendingPitch;
                this.prevRotationYaw = this.rotationYaw;
                this.prevRotationPitch = this.rotationPitch;
                this.prevRenderArmYaw = this.rotationYaw - (this.renderArmYaw - this.prevRenderArmYaw) * 2.0F;
                this.renderArmYaw = this.rotationYaw;
            }
            EventManager.call(new UpdateEvent(EventType.POST, this.lastReportedYaw, this.lastReportedPitch, this.rotationYaw, this.rotationPitch));
        }
    }

    @Redirect(
            method = {"onUpdate"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/entity/EntityPlayerSP;isRiding()Z"
            )
    )
    private boolean onRidding(EntityPlayerSP entityPlayerSP) {
        if (!Float.isNaN(this.overrideYaw) && !Float.isNaN(this.overridePitch)) {
            this.rotationYaw = this.overrideYaw;
            this.rotationPitch = this.overridePitch;
        }
        return entityPlayerSP.isRiding();
    }

    @Inject(
            method = {"onUpdate"},
            at = {@At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/entity/EntityPlayerSP;onUpdateWalkingPlayer()V"
            )}
    )
    private void onMotionUpdate(CallbackInfo callbackInfo) {
        EventManager.call(new PlayerUpdateEvent());
    }

    @Inject(
            method = {"onLivingUpdate"},
            at = {@At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/entity/AbstractClientPlayer;onLivingUpdate()V"
            )}
    )
    private void onLivingUpdate(CallbackInfo callbackInfo) {
        EventManager.call(new LivingUpdateEvent());
    }

    @Inject(
            method = {"onLivingUpdate"},
            at = {@At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/MovementInput;updatePlayerMoveState()V"
            )}
    )
    private void beforeUpdateMove(CallbackInfo callbackInfo) {
        EventManager.call(new atlas.events.PreMoveInputEvent());
    }

    @Inject(
            method = {"onLivingUpdate"},
            at = {@At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/MovementInput;updatePlayerMoveState()V",
                    shift = At.Shift.AFTER
            )}
    )
    private void updateMove(CallbackInfo callbackInfo) {
        EventManager.call(new MoveInputEvent());
    }

    @Redirect(
            method = {"onLivingUpdate"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/entity/EntityPlayerSP;isUsingItem()Z"
            )
    )
    private boolean isUsing(EntityPlayerSP entityPlayerSP) {
        if (atlas.module.modules.KillAuraLB.rhythmRuns()) {
            /* 2026-10-27: Silent Aura's Rhythm block is a real block the server sees; it moves exactly as vanilla
               moves with the sword up (slowed, no sprint), never as NoSlow would -- except while Rhythm-Lag holds the
               movement: the server gets that movement only after the sword is down, so it is made unslowed. */
            return !atlas.module.modules.KillAuraLB.rhythmUnslowed() && entityPlayerSP.isUsingItem();
        }
        NoSlow noSlow = (NoSlow) Atlas.moduleManager.modules.get(NoSlow.class);
        if (noSlow.isEnabled()) {
            if (noSlow.isMiauAnyActive()) {
                return !noSlow.shouldCancelMiauSlowdown();
            }
            if (noSlow.isAnyActive()) {
                return false;
            }
        }
        return entityPlayerSP.isUsingItem();
    }

    @Redirect(
            method = {"onLivingUpdate"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/entity/EntityPlayerSP;isPotionActive(Lnet/minecraft/potion/Potion;)Z"
            )
    )
    private boolean checkPotion(EntityPlayerSP entityPlayerSP, Potion potion) {
        if (potion == Potion.confusion && Atlas.moduleManager != null) {
            AntiDebuff antiDebuff = (AntiDebuff) Atlas.moduleManager.modules.get(AntiDebuff.class);
            if (antiDebuff.isEnabled() && antiDebuff.nausea.getValue()) {
                return false;
            }
        }
        return ((IAccessorEntityLivingBase) entityPlayerSP).getActivePotionsMap().containsKey(potion.id);
    }
}
