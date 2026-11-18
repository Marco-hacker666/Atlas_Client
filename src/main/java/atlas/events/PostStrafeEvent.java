package atlas.events;

import atlas.event.events.Event;

public class PostStrafeEvent implements Event {
    private final float strafe;
    private final float forward;
    private final float friction;
    private final boolean jump;

    public PostStrafeEvent(float strafe, float forward, float friction, boolean jump) {
        this.strafe = strafe;
        this.forward = forward;
        this.friction = friction;
        this.jump = jump;
    }

    public float getStrafe() {
        return this.strafe;
    }

    public float getForward() {
        return this.forward;
    }

    public float getFriction() {
        return this.friction;
    }

    public boolean isJump() {
        return this.jump;
    }
}
