package atlas.script.raven.packet.clientbound;

import atlas.script.raven.model.Vec3;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** An explosion: where, how strong, the blocks it took, and the push it gives this player. */
public class S27 extends SPacket {
    public float strength;
    public Vec3 position;
    public Vec3 motion;
    public List<Vec3> affectedBlockPositions;

    public S27(float strength, Vec3 position, Vec3 motion, List<Vec3> affectedBlockPositions) {
        super("S27PacketExplosion");
        this.strength = strength;
        this.position = position;
        this.motion = motion;
        this.affectedBlockPositions = affectedBlockPositions == null ? new ArrayList<Vec3>() : affectedBlockPositions;
    }

    static S27 of(S27PacketExplosion packet) {
        List<Vec3> blocks = new ArrayList<Vec3>();
        for (BlockPos pos : packet.getAffectedBlockPositions()) {
            blocks.add(new Vec3(pos));
        }
        S27 out = new S27(packet.getStrength(), new Vec3(packet.getX(), packet.getY(), packet.getZ()),
                new Vec3(packet.func_149149_c(), packet.func_149144_d(), packet.func_149147_e()), blocks);
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        List<BlockPos> blocks = new ArrayList<BlockPos>();
        for (Vec3 pos : this.affectedBlockPositions) {
            blocks.add(Vec3.getBlockPos(pos));
        }
        return new S27PacketExplosion(this.position.x, this.position.y, this.position.z, this.strength, blocks,
                Vec3.getVec3(this.motion));
    }
}
