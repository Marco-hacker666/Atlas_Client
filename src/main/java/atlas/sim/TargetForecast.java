package atlas.sim;

import net.minecraft.util.AxisAlignedBB;

/**
 * Where another player will be, move by move (2026-11-13): what {@link TargetTrack#forecast} answers. Index 0 is
 * the player at the last position the server sent; index n is n of its moves later. A move is one of its client's
 * ticks, which is one of this client's, give or take the network.
 */
public final class TargetForecast {
    public final double[] x;
    public final double[] y;
    public final double[] z;
    /** Standing on ground after that move. Only known from the simulation: in a straight-line answer, as last seen. */
    public final boolean[] onGround;
    /**
     * True when the positions are the simulation's (the keys were found and run on); false when they are a straight
     * line from the last two positions at the last height, because the player's moves did not fit a 1.8 player's.
     */
    public boolean simulated;
    /** 0 to 1: the share of the last positions the simulation explained; 0 when the answer is the straight line. */
    public double confidence;

    TargetForecast(int moves) {
        this.x = new double[moves + 1];
        this.y = new double[moves + 1];
        this.z = new double[moves + 1];
        this.onGround = new boolean[moves + 1];
    }

    /** How many moves ahead it reaches. */
    public int moves() {
        return this.x.length - 1;
    }

    /** The player's box after {@code move} moves (0.6 wide, 1.8 tall, as the game builds it around the feet). */
    public AxisAlignedBB box(int move) {
        double half = (double) (0.6F / 2.0F);
        return new AxisAlignedBB(this.x[move] - half, this.y[move], this.z[move] - half, this.x[move] + half,
                this.y[move] + (double) 1.8F, this.z[move] + half);
    }
}
