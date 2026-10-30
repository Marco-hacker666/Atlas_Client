package atlas.mixin;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.GenericFutureListener;
import atlas.Atlas;
import atlas.event.EventManager;
import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import atlas.util.ActionLedger;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Future;

@SideOnly(Side.CLIENT)
@Mixin(value = {NetworkManager.class}, priority = 9999)
public abstract class MixinNetworkManager {
    @Inject(
            method = {"channelRead0*"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void channelRead0(ChannelHandlerContext channelHandlerContext, Packet<?> packet, CallbackInfo callbackInfo) {
        /* 2026-10-30 (audit): this runs for every connection, the server list's pings too. A ping's answer went
           through the play pipeline, KnockbackDelay held it and replayed it into the play handler
           (ClassCastException ... INetHandlerStatusClient). Status connections are left alone; at login only the
           event runs (LogUploader reads the login kick), nothing holds or delays. */
        net.minecraft.network.INetHandler handler = ((NetworkManager) (Object) this).getNetHandler();
        if (handler instanceof net.minecraft.network.status.INetHandlerStatusClient) {
            return;
        }
        boolean play = handler instanceof INetHandlerPlayClient;
        if (!play) {
            PacketEvent event = new PacketEvent(EventType.RECEIVE, packet);
            EventManager.call(event);
            return;
        }
        if (!packet.getClass().getName().startsWith("net.minecraft.network.play.client")) {
            /* 2026-10-28 LagGuard: a knockback or a correction lets the delay modules' holds go before anyone sees it. */
            atlas.management.LagGuard.onReceive(packet);
            if (Atlas.delayManager != null && Atlas.delayManager.shouldDelay((Packet<INetHandlerPlayClient>) packet)) {
                callbackInfo.cancel();
            } else {
                PacketEvent event = new PacketEvent(EventType.RECEIVE, packet);
                EventManager.call(event);
                if (event.isCancelled()) {
                    callbackInfo.cancel();
                }
            }
        }
    }

    @Inject(
            method = {"sendPacket(Lnet/minecraft/network/Packet;)V"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void sendPacket(Packet<?> packet, CallbackInfo callbackInfo) {
        if (!packet.getClass().getName().startsWith("net.minecraft.network.play.server")) {
            /* 2026-10-28 LagGuard: our own dig or placement lets the outgoing holds go ahead of it. */
            atlas.management.LagGuard.onSend(packet);
            /* Who sent this. The game loop sends movement every tick and says
               nothing; a module sending an attack, a placement or a dig of its
               own is exactly what a correction usually needs explaining by, and
               the stack is the only record of it that does not require every
               module to be changed to announce itself. Only a handful of
               packets a second are examined. */
            if (ActionLedger.isAttributable(packet)) {
                ActionLedger.note(ActionLedger.callerModule(), ActionLedger.kindOf(packet));
            } else if (packet instanceof net.minecraft.network.play.client.C03PacketPlayer) {
                /* 2026-10-11: a movement packet a module made itself (NoFall,
                   Criticals, Fly, Blink...) -- the game loop's own has no
                   module on the stack and is not recorded. */
                ActionLedger.note(ActionLedger.callerModule(), "move-packet");
            }
            PacketEvent event = new PacketEvent(EventType.SEND, packet);
            EventManager.call(event);
            if (event.isCancelled()) {
                callbackInfo.cancel();
            } else if (Atlas.playerStateManager != null && Atlas.blinkManager != null && Atlas.lagManager != null) {
                if (!Atlas.lagManager.isFlushing()) {
                    Atlas.playerStateManager.handlePacket(packet);
                    /* NoItemRelease: dropped after every listener and the
                       state manager have seen it, before Blink / Lag could
                       hold it. */
                    if (atlas.module.modules.NoItemRelease.filter(packet)) {
                        callbackInfo.cancel();
                        return;
                    }
                    if (Atlas.blinkManager.isBlinking()) {
                        if (Atlas.blinkManager.offerPacket(packet)) {
                            callbackInfo.cancel();
                            return;
                        }
                    }
                    if (Atlas.lagManager.handlePacket(packet)) {
                        callbackInfo.cancel();
                    }
                }
            }
        }
    }

    @Inject(
            method = {"sendPacket(Lnet/minecraft/network/Packet;Lio/netty/util/concurrent/GenericFutureListener;[Lio/netty/util/concurrent/GenericFutureListener;)V"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void sendPacket2(
            Packet<?> packet,
            GenericFutureListener<? extends Future<? super Void>> genericFutureListener,
            GenericFutureListener<? extends Future<? super Void>>[] arr,
            CallbackInfo callbackInfo
    ) {
        if (!packet.getClass().getName().startsWith("net.minecraft.network.play.server")) {
            if (Atlas.playerStateManager != null && Atlas.blinkManager != null && Atlas.lagManager != null) {
                if (!Atlas.lagManager.isFlushing()) {
                    Atlas.playerStateManager.handlePacket(packet);
                    if (Atlas.blinkManager.isBlinking()) {
                        if (Atlas.blinkManager.offerPacket(packet)) {
                            callbackInfo.cancel();
                            return;
                        }
                    }
                    if (Atlas.lagManager.handlePacket(packet)) {
                        callbackInfo.cancel();
                    }
                }
            }
        }
    }
}
