package atlas.mixin;

import atlas.module.modules.ExploitFixer;
import atlas.util.InboundGuard;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;

/**
 * Chat, titles, signs, tab header and kick text all decode here. Gson parses nesting by recursion, so a few
 * thousand nested arrays overflow the stack on the network thread and the connection hangs. Same reads as vanilla
 * (readStringFromBuffer(32767), then the serializer); only a component past the limits is replaced.
 */
@SideOnly(Side.CLIENT)
@Mixin(PacketBuffer.class)
public abstract class MixinPacketBuffer {

    @Inject(method = "readChatComponent", at = @At("HEAD"), cancellable = true)
    private void atlas$guardNesting(CallbackInfoReturnable<IChatComponent> cir) throws IOException {
        if (!ExploitFixer.guardsChatNesting()) {
            return;
        }
        String json = ((PacketBuffer) (Object) this).readStringFromBuffer(32767);
        if (InboundGuard.jsonTooComplex(json, InboundGuard.MAX_JSON_DEPTH, InboundGuard.MAX_JSON_OPENS)) {
            cir.setReturnValue(new ChatComponentText("[ExploitFixer: oversized text blocked]"));
            return;
        }
        cir.setReturnValue(IChatComponent.Serializer.jsonToComponent(json));
    }
}
