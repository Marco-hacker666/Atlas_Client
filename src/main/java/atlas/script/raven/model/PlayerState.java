package atlas.script.raven.model;

/**
 * Raven script API: what this tick's movement packet will say (2026-11-02). Given to onPreMotion; what the script
 * changes is sent: the look through the shared rotation engine, the position and ground flag on the packet itself,
 * sprint and sneak as the player's own state.
 */
public class PlayerState {
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    public boolean onGround;
    public boolean isSprinting;
    public boolean isSneaking;

    public PlayerState(double x, double y, double z, float yaw, float pitch, boolean onGround, boolean isSprinting,
                       boolean isSneaking) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
        this.isSprinting = isSprinting;
        this.isSneaking = isSneaking;
    }

    /** From {x, y, z, yaw, pitch, onGround, isSprinting, isSneaking}. */
    public PlayerState(Object[] state) {
        this(((Number) state[0]).doubleValue(), ((Number) state[1]).doubleValue(), ((Number) state[2]).doubleValue(),
                ((Number) state[3]).floatValue(), ((Number) state[4]).floatValue(), (Boolean) state[5],
                (Boolean) state[6], (Boolean) state[7]);
    }

    public Object[] asArray() {
        return new Object[]{this.x, this.y, this.z, this.yaw, this.pitch, this.onGround, this.isSprinting, this.isSneaking};
    }

    public boolean equals(PlayerState playerState) {
        return playerState != null && this.x == playerState.x && this.y == playerState.y && this.z == playerState.z
                && this.yaw == playerState.yaw && this.pitch == playerState.pitch
                && this.onGround == playerState.onGround && this.isSprinting == playerState.isSprinting
                && this.isSneaking == playerState.isSneaking;
    }

    public PlayerState copy() {
        return new PlayerState(this.asArray());
    }
}
