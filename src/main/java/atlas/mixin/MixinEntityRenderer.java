package atlas.mixin;

import atlas.Atlas;
import atlas.data.Box;
import atlas.event.EventManager;
import atlas.events.PickEvent;
import atlas.events.RaytraceEvent;
import atlas.events.Render3DEvent;
import atlas.module.modules.*;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.List;

@SideOnly(Side.CLIENT)
@Mixin(value = {EntityRenderer.class}, priority = 9999)
public abstract class MixinEntityRenderer {
    @Unique
    private Box<Integer> slot = null;
    @Unique
    private Box<ItemStack> using = null;
    @Unique
    private Box<Integer> useCount = null;
    @Shadow
    private Minecraft mc;
    @Shadow
    private float thirdPersonDistance;

    @Inject(
            method = {"updateCameraAndRender"},
            at = {@At("HEAD")}
    )
    private void updateCameraAndRender(float float1, long long2, CallbackInfo callbackInfo) {
        if (this.mc.thePlayer != null) {
            Scaffold scaffold = (Scaffold) Atlas.moduleManager.modules.get(Scaffold.class);
            if (scaffold.isEnabled() && scaffold.itemSpoof.getValue()) {
                int slot = scaffold.getSlot();
                if (slot >= 0) {
                    this.slot = new Box<>(this.mc.thePlayer.inventory.currentItem);
                    this.mc.thePlayer.inventory.currentItem = slot;
                }
            }
            /* 2026-10-16: AutoBlockIn's item-spoof was applied in updateRenderer
               (each tick) but not here (each frame), unlike Scaffold's: the hand
               showed the blocks on frames and the spoofed item on ticks, and the
               equip animation restarted over and over. */
            AutoBlockIn autoBlockIn = (AutoBlockIn) Atlas.moduleManager.modules.get(AutoBlockIn.class);
            if (this.slot == null && autoBlockIn.isEnabled() && autoBlockIn.itemSpoof.getValue()) {
                int abSlot = autoBlockIn.getSlot();
                if (abSlot >= 0) {
                    this.slot = new Box<>(this.mc.thePlayer.inventory.currentItem);
                    this.mc.thePlayer.inventory.currentItem = abSlot;
                }
            }
            atlas.module.modules.Clutch clutch = (atlas.module.modules.Clutch) Atlas.moduleManager.modules.get(atlas.module.modules.Clutch.class);
            int clutchSlot = clutch == null ? -1 : clutch.getSpoofSlot();
            if (clutchSlot >= 0 && this.slot == null) {
                this.slot = new Box<>(this.mc.thePlayer.inventory.currentItem);
                this.mc.thePlayer.inventory.currentItem = clutchSlot;
            }
            KillAura killAura = (KillAura) Atlas.moduleManager.modules.get(KillAura.class);
            if (killAura.isEnabled() && killAura.isBlocking()) {
                this.using = new Box<>(((IAccessorEntityPlayer) this.mc.thePlayer).getItemInUse());
                ((IAccessorEntityPlayer) this.mc.thePlayer).setItemInUse(this.mc.thePlayer.inventory.getCurrentItem());
                this.useCount = new Box<>(((IAccessorEntityPlayer) this.mc.thePlayer).getItemInUseCount());
                ((IAccessorEntityPlayer) this.mc.thePlayer).setItemInUseCount(69000);
            }
        }
    }

    @Inject(
            method = {"updateCameraAndRender"},
            at = {@At("RETURN")}
    )
    private void postUpdateCameraAndRender(float float1, long long2, CallbackInfo callbackInfo) {
        if (this.slot != null) {
            this.mc.thePlayer.inventory.currentItem = this.slot.value;
            this.slot = null;
        }
        if (this.using != null) {
            ((IAccessorEntityPlayer) this.mc.thePlayer).setItemInUse(this.using.value);
            this.using = null;
        }
        if (this.useCount != null) {
            ((IAccessorEntityPlayer) this.mc.thePlayer).setItemInUseCount(this.useCount.value);
            this.useCount = null;
        }
    }

    @Inject(
            method = {"updateRenderer"},
            at = {@At("HEAD")}
    )
    private void updateRenderer(CallbackInfo callbackInfo) {
        Scaffold scaffold = (Scaffold) Atlas.moduleManager.modules.get(Scaffold.class);
        if (scaffold.isEnabled() && scaffold.itemSpoof.getValue()) {
            int slot = scaffold.getSlot();
            if (slot >= 0) {
                this.slot = new Box<>(this.mc.thePlayer.inventory.currentItem);
                this.mc.thePlayer.inventory.currentItem = slot;
            }
        }

        AutoBlockIn autoBlockIn = (AutoBlockIn) Atlas.moduleManager.modules.get(AutoBlockIn.class);
        if (this.slot == null && autoBlockIn.isEnabled() && autoBlockIn.itemSpoof.getValue()) {
            /* Not over Scaffold's spoof: overwriting this.slot lost the real slot. */
            int slot = autoBlockIn.getSlot();
            if (slot >= 0) {
                this.slot = new Box<>(this.mc.thePlayer.inventory.currentItem);
                this.mc.thePlayer.inventory.currentItem = slot;
            }
        }
        atlas.module.modules.Clutch clutch = (atlas.module.modules.Clutch) Atlas.moduleManager.modules.get(atlas.module.modules.Clutch.class);
        int clutchSlot = clutch == null ? -1 : clutch.getSpoofSlot();
        if (clutchSlot >= 0 && this.slot == null) {
            this.slot = new Box<>(this.mc.thePlayer.inventory.currentItem);
            this.mc.thePlayer.inventory.currentItem = clutchSlot;
        }
    }

    @Inject(
            method = {"updateRenderer"},
            at = {@At("RETURN")}
    )
    private void postUpdateRenderer(CallbackInfo callbackInfo) {
        if (this.slot != null) {
            this.mc.thePlayer.inventory.currentItem = this.slot.value;
            this.slot = null;
        }
    }

    @Inject(
            method = {"renderWorldPass"},
            at = {@At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;renderHand:Z",
                    shift = At.Shift.BEFORE
            )}
    )
    private void renderWorldPass(int integer, float float2, long long3, CallbackInfo callbackInfo) {
        EventManager.call(new Render3DEvent(float2));
    }

    @ModifyConstant(
            method = {"hurtCameraEffect"},
            constant = {@Constant(
                    floatValue = 14.0F,
                    ordinal = 0
            )}
    )
    private float hurtCameraEffect(float float1) {
        if (Atlas.moduleManager == null) {
            return float1;
        } else {
            NoHurtCam noHurtCam = (NoHurtCam) Atlas.moduleManager.modules.get(NoHurtCam.class);
            return noHurtCam.isEnabled() ? float1 * (float) noHurtCam.multiplier.getValue().intValue() / 100.0F : float1;
        }
    }

    @ModifyConstant(
            method = {"getMouseOver"},
            constant = {@Constant(
                    doubleValue = 3.0,
                    ordinal = 1
            )}
    )
    private double getMouseOver(double range) {
        PickEvent event = new PickEvent(range);
        EventManager.call(event);
        return event.getRange();
    }

    @ModifyVariable(
            method = {"getMouseOver"},
            at = @At("STORE"),
            name = {"d0"}
    )
    private double storeMouseOver(double range) {
        RaytraceEvent event = new RaytraceEvent(range);
        EventManager.call(event);
        return event.getRange();
    }

    @Inject(
            method = {"getMouseOver"},
            at = {@At(
                    value = "INVOKE",
                    target = "Ljava/util/List;size()I",
                    ordinal = 0
            )},
            locals = LocalCapture.CAPTURE_FAILSOFT
    )
    private void a(
            float float1,
            CallbackInfo callbackInfo,
            Entity entity,
            double double4,
            double double5,
            Vec3 vec36,
            boolean boolean7,
            int integer8,
            Vec3 vec39,
            Vec3 vec310,
            Vec3 vec311,
            float float12,
            List<Entity> list,
            double double14,
            int integer15
    ) {
        if (Atlas.moduleManager != null) {
            GhostHand event = (GhostHand) Atlas.moduleManager.modules.get(GhostHand.class);
            if (event.isEnabled()) {
                list.removeIf(event::shouldSkip);
            }
        }
    }

    @Redirect(
            method = {"orientCamera"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/Vec3;distanceTo(Lnet/minecraft/util/Vec3;)D"
            )
    )
    private double v(Vec3 vec31, Vec3 vec32) {
        if (Atlas.moduleManager == null) {
            return vec31.distanceTo(vec32);
        } else {
            return Atlas.moduleManager.modules.get(ViewClip.class).isEnabled() ? (double) this.thirdPersonDistance : vec31.distanceTo(vec32);
        }
    }

    @Redirect(
            method = {"setupFog"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/block/Block;getMaterial()Lnet/minecraft/block/material/Material;"
            )
    )
    private Material x(Block block) {
        if (Atlas.moduleManager == null) {
            return block.getMaterial();
        } else {
            /* isNoClipActive, not isEnabled: the "no-clip" setting and the
               VANILLA mode have to be able to leave fog alone, or the setting
               is inert while the module is on. */
            return ((ViewClip) Atlas.moduleManager.modules.get(ViewClip.class)).isNoClipActive() ? Material.air : block.getMaterial();
        }
    }

    @Redirect(
            method = {"updateFogColor"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/EntityLivingBase;isPotionActive(Lnet/minecraft/potion/Potion;)Z"
            )
    )
    private boolean y(EntityLivingBase entityLivingBase, Potion potion) {
        if (potion == Potion.blindness && Atlas.moduleManager != null) {
            AntiDebuff antiDebuff = (AntiDebuff) Atlas.moduleManager.modules.get(AntiDebuff.class);
            if (antiDebuff.isEnabled() && antiDebuff.blindness.getValue()) {
                return false;
            }
        }
        return ((IAccessorEntityLivingBase) entityLivingBase).getActivePotionsMap().containsKey(potion.id);
    }

    @Redirect(
            method = {"setupFog"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/EntityLivingBase;isPotionActive(Lnet/minecraft/potion/Potion;)Z"
            )
    )
    private boolean q(EntityLivingBase entityLivingBase, Potion potion) {
        if (potion == Potion.blindness && Atlas.moduleManager != null) {
            AntiDebuff antiDebuff = (AntiDebuff) Atlas.moduleManager.modules.get(AntiDebuff.class);
            if (antiDebuff.isEnabled() && antiDebuff.blindness.getValue()) {
                return false;
            }
        }
        return ((IAccessorEntityLivingBase) entityLivingBase).getActivePotionsMap().containsKey(potion.id);
    }

    @Redirect(
            method = {"setupCameraTransform"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/entity/EntityPlayerSP;isPotionActive(Lnet/minecraft/potion/Potion;)Z"
            )
    )
    private boolean c(EntityPlayerSP entityPlayerSP, Potion potion) {
        if (potion == Potion.confusion && Atlas.moduleManager != null) {
            AntiDebuff antiDebuff = (AntiDebuff) Atlas.moduleManager.modules.get(AntiDebuff.class);
            if (antiDebuff.isEnabled() && antiDebuff.nausea.getValue()) {
                return false;
            }
        }
        return ((IAccessorEntityLivingBase) entityPlayerSP).getActivePotionsMap().containsKey(potion.id);
    }
    /* Free look swaps the view entity's rotation for the camera's only while
       the camera transform is being built, then puts it straight back. Every
       other consumer of those fields -- the movement packet, the player model,
       the hand, the attack raytrace -- runs outside this window and so still
       sees the direction the body is actually facing. */
    @Unique
    private float atlas$realYaw;
    @Unique
    private float atlas$realPitch;
    @Unique
    private float atlas$realPrevYaw;
    @Unique
    private float atlas$realPrevPitch;
    @Unique
    private boolean atlas$freeLookSwapped;

    @Inject(
            method = {"orientCamera"},
            at = {@At("HEAD")}
    )
    private void atlas$freeLookCamera(float partialTicks, CallbackInfo callbackInfo) {
        this.atlas$freeLookSwapped = false;
        if (Atlas.moduleManager == null || this.mc.thePlayer == null) {
            return;
        }
        FreeLook freeLook = (FreeLook) Atlas.moduleManager.modules.get(FreeLook.class);
        if (freeLook == null || !freeLook.isFreeLooking()) {
            return;
        }
        Entity view = this.mc.getRenderViewEntity();
        if (view != this.mc.thePlayer) {
            return;
        }
        this.atlas$realYaw = view.rotationYaw;
        this.atlas$realPitch = view.rotationPitch;
        this.atlas$realPrevYaw = view.prevRotationYaw;
        this.atlas$realPrevPitch = view.prevRotationPitch;
        view.rotationYaw = freeLook.getCameraYaw();
        view.rotationPitch = freeLook.getCameraPitch();
        view.prevRotationYaw = freeLook.getPrevCameraYaw();
        view.prevRotationPitch = freeLook.getPrevCameraPitch();
        this.atlas$freeLookSwapped = true;
    }

    @Inject(
            method = {"orientCamera"},
            at = {@At("RETURN")}
    )
    private void atlas$freeLookRestore(float partialTicks, CallbackInfo callbackInfo) {
        if (!this.atlas$freeLookSwapped) {
            return;
        }
        this.atlas$freeLookSwapped = false;
        Entity view = this.mc.getRenderViewEntity();
        if (view == null) {
            return;
        }
        view.rotationYaw = this.atlas$realYaw;
        view.rotationPitch = this.atlas$realPitch;
        view.prevRotationYaw = this.atlas$realPrevYaw;
        view.prevRotationPitch = this.atlas$realPrevPitch;
    }

}
