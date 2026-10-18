package myau.events;

import myau.event.events.Event;

/**
 * Right after the local player's moveFlying: the input has been added to the
 * motion for this tick, before it is moved (2026-10-19, for FastAccel and
 * Strafe). Carries the inputs moveFlying was given.
 */
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

    /** moveFlying's third argument: how much the input adds this tick. */
    public float getFriction() {
        return this.friction;
    }

    /** Within ten ticks of a jump with the jump key still held. */
    public boolean isJump() {
        return this.jump;
    }
}
