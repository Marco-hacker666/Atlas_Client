package atlas.sim;

import net.minecraft.util.AxisAlignedBB;

public final class TargetForecast {
    public final double[] x;
    public final double[] y;
    public final double[] z;

    public final boolean[] onGround;

    public boolean simulated;

    public double confidence;

    TargetForecast(int moves) {
        this.x = new double[moves + 1];
        this.y = new double[moves + 1];
        this.z = new double[moves + 1];
        this.onGround = new boolean[moves + 1];
    }

    public int moves() {
        return this.x.length - 1;
    }

    public AxisAlignedBB box(int move) {
        double half = (double) (0.6F / 2.0F);
        return new AxisAlignedBB(this.x[move] - half, this.y[move], this.z[move] - half, this.x[move] + half,
                this.y[move] + (double) 1.8F, this.z[move] + half);
    }
}
