package atlas.script.raven.packet.clientbound;

import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S3EPacketTeams;

import java.util.ArrayList;
import java.util.Collection;

/**
 * A scoreboard team: action 0 create, 1 remove, 2 update, 3 add players, 4 remove players. Read only: a script
 * cannot make one of these for client.processPacket (the game builds them from a team object).
 */
public class S3E extends SPacket {
    public String name;
    public String displayName;
    public String prefix;
    public String suffix;
    public String nametagVisibility;
    public Collection<String> playerList;
    public int action;
    public int friendlyFlags;
    public int color;

    public S3E(Packet packet, String name, String displayName, String prefix, String suffix, String nametagVisibility,
               Collection<String> playerList, int action, int friendlyFlags, int color) {
        super(packet);
        this.name = name;
        this.displayName = displayName;
        this.prefix = prefix;
        this.suffix = suffix;
        this.nametagVisibility = nametagVisibility;
        this.playerList = playerList == null ? new ArrayList<String>() : playerList;
        this.action = action;
        this.friendlyFlags = friendlyFlags;
        this.color = color;
    }

    static S3E of(S3EPacketTeams packet) {
        return new S3E(packet, packet.getName(), packet.getDisplayName(), packet.getPrefix(), packet.getSuffix(),
                packet.getNameTagVisibility(), new ArrayList<String>(packet.getPlayers()), packet.getAction(),
                packet.getFriendlyFlags(), packet.getColor());
    }
}
