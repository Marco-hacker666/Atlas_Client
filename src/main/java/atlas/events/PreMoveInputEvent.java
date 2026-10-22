package atlas.events;

import atlas.event.events.Event;

/**
 * Just before the local player reads the movement keys for this tick
 * (MovementInput.updatePlayerMoveState), after the game has processed this
 * tick's key presses (2026-10-19, for NullMove and InstantStop). A key state
 * changed here is what this tick's movement uses.
 */
public class PreMoveInputEvent implements Event {
}
