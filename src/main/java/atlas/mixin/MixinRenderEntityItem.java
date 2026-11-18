package atlas.mixin;

import atlas.module.modules.ItemPhysics;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemTransformVec3f;
import net.minecraft.client.renderer.entity.RenderEntityItem;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

@SideOnly(Side.CLIENT)
@Mixin(RenderEntityItem.class)
public abstract class MixinRenderEntityItem {
    @Shadow
    private Random field_177079_e;

    @Shadow
    protected abstract int func_177078_a(ItemStack stack);

    @Unique
    private Random atlas$vanillaRandom;

    @Inject(
            method = "func_177077_a(Lnet/minecraft/entity/item/EntityItem;DDDFLnet/minecraft/client/resources/model/IBakedModel;)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private void atlas$itemPhysics(EntityItem item, double x, double y, double z, float partialTicks,
                                  IBakedModel model, CallbackInfoReturnable<Integer> cir) {
        ItemPhysics physics = ItemPhysics.instance;
        if (physics == null) {
            atlas$restoreRandom();
            return;
        }

        boolean gui3d;
        int copies;
        float tx;
        float ty;
        float tz;
        float lift;
        float yaw;
        float spin;
        try {
            ItemStack stack = item.getEntityItem();
            if (stack == null || stack.getItem() == null) {
                atlas$restoreRandom();
                return;
            }
            gui3d = model.isGui3d();
            copies = this.func_177078_a(stack);
            ItemTransformVec3f ground = model.getItemCameraTransforms()
                    .getTransform(ItemCameraTransforms.TransformType.GROUND);

            float outer = gui3d ? 0.5F : 1.0F;
            tx = ground.translation.x * outer;
            ty = ground.translation.y * outer;
            tz = ground.translation.z * outer;

            lift = gui3d
                    ? 0.5F * ground.scale.y * outer
                    : 0.5F * (1.0F / 16.0F) * ground.scale.z + 0.005F;

            yaw = item.hoverStart * (180.0F / (float) Math.PI);
            spin = item.onGround ? 0.0F
                    : ((item.getAge() + partialTicks) * 20.0F * physics.getRotationSpeed()) % 360.0F;
        } catch (RuntimeException e) {
            atlas$restoreRandom();
            return;
        }

        if (this.atlas$vanillaRandom == null) {
            this.atlas$vanillaRandom = this.field_177079_e;
        }
        this.field_177079_e = ItemPhysics.STILL;

        GlStateManager.translate((float) x, (float) y + lift, (float) z);
        GlStateManager.rotate(yaw, 0.0F, 1.0F, 0.0F);
        if (gui3d) {
            GlStateManager.rotate(spin, 1.0F, 0.0F, 0.0F);
        } else {
            GlStateManager.rotate(90.0F + spin, 1.0F, 0.0F, 0.0F);
        }

        GlStateManager.translate(-tx, -ty, -tz);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        cir.setReturnValue(copies);
    }

    private void atlas$restoreRandom() {
        if (this.atlas$vanillaRandom != null) {
            this.field_177079_e = this.atlas$vanillaRandom;
            this.atlas$vanillaRandom = null;
        }
    }
}
