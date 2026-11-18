package atlas.script.raven.model;

public class MovementInput {
    public float forward;
    public float strafe;
    public boolean jump;
    public boolean sneak;

    public MovementInput(float forward, float strafe, boolean jump, boolean sneak) {
        this.forward = forward;
        this.strafe = strafe;
        this.jump = jump;
        this.sneak = sneak;
    }

    public MovementInput(Object[] state) {
        this(((Number) state[0]).floatValue(), ((Number) state[1]).floatValue(), (Boolean) state[2], (Boolean) state[3]);
    }

    public Object[] asArray() {
        return new Object[]{this.forward, this.strafe, this.jump, this.sneak};
    }

    public boolean equals(MovementInput input) {
        return input != null && this.forward == input.forward && this.strafe == input.strafe && this.jump == input.jump
                && this.sneak == input.sneak;
    }
}
