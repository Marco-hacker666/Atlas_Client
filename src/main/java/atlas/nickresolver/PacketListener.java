package atlas.nickresolver;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S0CPacketSpawnPlayer;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class PacketListener {
    private static final Logger LOG = LogManager.getLogger("NicknameResolver");
    public static final String HANDLER_NAME = "nickresolver_listener";

    private final IdentityResolver resolver;
    private final CustomPayloadInspector payloads;
    private final NickSettings settings;

    public PacketListener(IdentityResolver resolver, CustomPayloadInspector payloads, NickSettings settings) {
        this.resolver = resolver;
        this.payloads = payloads;
        this.settings = settings;
    }

    public void attach(NetworkManager manager) {
        if (manager == null) {
            return;
        }
        final Channel channel = manager.channel();
        if (channel == null) {
            return;
        }
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.get(HANDLER_NAME) != null) {
                    return;
                }
                if (pipeline.get("packet_handler") == null) {
                    LOG.warn("No packet_handler in the pipeline; NicknameResolver cannot listen on this connection");
                    return;
                }
                pipeline.addBefore("packet_handler", HANDLER_NAME, new Handler());
            }
        });
    }

    public void onPacket(Packet<?> packet) {
        if (!this.settings.enabled || packet == null) {
            return;
        }
        try {
            if (packet instanceof S38PacketPlayerListItem) {
                this.onPlayerList((S38PacketPlayerListItem) packet);
            } else if (packet instanceof S0CPacketSpawnPlayer) {
                S0CPacketSpawnPlayer spawn = (S0CPacketSpawnPlayer) packet;
                this.resolver.onSpawnPlayer(spawn.getEntityID(), spawn.getPlayer());
            } else if (packet instanceof S13PacketDestroyEntities) {
                this.resolver.onDestroyEntities(((S13PacketDestroyEntities) packet).getEntityIDs());
            } else if (packet instanceof S3FPacketCustomPayload) {
                S3FPacketCustomPayload payload = (S3FPacketCustomPayload) packet;
                this.payloads.inspect(payload.getChannelName(), payload.getBufferData(), this.settings.inspectPayloads);
            } else if (packet instanceof S07PacketRespawn || packet instanceof S01PacketJoinGame) {
                this.resolver.onWorldChange();
            }
        } catch (RuntimeException e) {
            LOG.debug("Could not read {}", packet.getClass().getSimpleName(), e);
        }
    }

    private void onPlayerList(S38PacketPlayerListItem packet) {
        S38PacketPlayerListItem.Action action = packet.getAction();
        for (S38PacketPlayerListItem.AddPlayerData entry : packet.getEntries()) {
            if (entry.getProfile() == null) {
                continue;
            }
            switch (action) {
                case ADD_PLAYER:
                    this.resolver.onTabAdd(entry.getProfile(), entry.getDisplayName());
                    break;
                case UPDATE_DISPLAY_NAME:
                    this.resolver.onTabDisplayName(entry.getProfile().getId(), entry.getDisplayName());
                    break;
                case REMOVE_PLAYER:
                    this.resolver.onTabRemove(entry.getProfile().getId());
                    break;
                default:
                    break;
            }
        }
    }

    @ChannelHandler.Sharable
    private final class Handler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Packet) {
                PacketListener.this.onPacket((Packet<?>) msg);
            }
            super.channelRead(ctx, msg);
        }
    }
}
