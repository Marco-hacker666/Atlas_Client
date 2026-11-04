package atlas.script.raven.packet.serverbound;

import net.minecraft.network.Packet;

/**
 * Raven script API: a packet this client sends (2026-11-02). The typed ones (C03, C08 ...) carry their fields;
 * any other kind comes as a plain CPacket with its name. convert() gives the game's packet, made from the fields
 * as they are now, so a script may change them before client.sendPacket.
 *
 * Wrapping a game packet goes through PacketHandler, never a public constructor taking one: a script's
 * {@code new C03(true)} must not make the compiler look at a Minecraft class it cannot see.
 */
public class CPacket {
    public String name;
    public Packet packet;

    public CPacket(Packet packet) {
        this.packet = packet;
        this.name = packet == null ? this.getClass().getSimpleName() : packet.getClass().getSimpleName();
    }

    /** For the typed packets' own constructors. */
    CPacket(String name) {
        this.name = name;
    }

    public Packet convert() {
        return this.packet;
    }

    @Override
    public String toString() {
        return this.name;
    }
}
