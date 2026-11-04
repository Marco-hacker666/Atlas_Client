package atlas.script.raven.packet.clientbound;

import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S04PacketEntityEquipment;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.network.play.server.S25PacketBlockBreakAnim;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.network.play.server.S29PacketSoundEffect;
import net.minecraft.network.play.server.S2APacketParticles;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.network.play.server.S3APacketTabComplete;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.network.play.server.S45PacketTitle;
import net.minecraft.network.play.server.S48PacketResourcePackSend;

/** The game's server packets as script packets (2026-11-02); used through PacketHandler. */
public final class ClientPackets {
    private ClientPackets() {
    }

    public static SPacket convert(Packet packet) {
        if (packet == null) {
            return null;
        }
        try {
            if (packet instanceof S14PacketEntity) {
                return S14.of((S14PacketEntity) packet);
            }
            if (packet instanceof S12PacketEntityVelocity) {
                return S12.of((S12PacketEntityVelocity) packet);
            }
            if (packet instanceof S23PacketBlockChange) {
                return S23.of((S23PacketBlockChange) packet);
            }
            if (packet instanceof S29PacketSoundEffect) {
                return S29.of((S29PacketSoundEffect) packet);
            }
            if (packet instanceof S2APacketParticles) {
                return S2A.of((S2APacketParticles) packet);
            }
            if (packet instanceof S0BPacketAnimation) {
                return S0B.of((S0BPacketAnimation) packet);
            }
            if (packet instanceof S04PacketEntityEquipment) {
                return S04.of((S04PacketEntityEquipment) packet);
            }
            if (packet instanceof S02PacketChat) {
                return S02.of((S02PacketChat) packet);
            }
            if (packet instanceof S06PacketUpdateHealth) {
                return S06.of((S06PacketUpdateHealth) packet);
            }
            if (packet instanceof S08PacketPlayerPosLook) {
                return S08.of((S08PacketPlayerPosLook) packet);
            }
            if (packet instanceof S25PacketBlockBreakAnim) {
                return S25.of((S25PacketBlockBreakAnim) packet);
            }
            if (packet instanceof S27PacketExplosion) {
                return S27.of((S27PacketExplosion) packet);
            }
            if (packet instanceof S2FPacketSetSlot) {
                return S2F.of((S2FPacketSetSlot) packet);
            }
            if (packet instanceof S3APacketTabComplete) {
                return S3A.of((S3APacketTabComplete) packet);
            }
            if (packet instanceof S3EPacketTeams) {
                return S3E.of((S3EPacketTeams) packet);
            }
            if (packet instanceof S45PacketTitle) {
                return S45.of((S45PacketTitle) packet);
            }
            if (packet instanceof S48PacketResourcePackSend) {
                return S48.of((S48PacketResourcePackSend) packet);
            }
            return new SPacket(packet);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
