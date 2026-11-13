/**
 * The movement simulation: 1.8 movement -- this player's own, and another player's as far as the server tells of it
 * -- run ahead of the game or run again after it, with no game needed to run it.
 *
 * <h3>The pieces</h3>
 * <ul>
 *   <li>{@link atlas.sim.PlayerSim}: the engine. One tick as the game runs it. Pure: the world is an interface.</li>
 *   <li>{@link atlas.sim.SimWorlds}: a world built from another (a block about to be placed).</li>
 *   <li>{@link atlas.sim.SelfCheck}: a re-simulating anticheat's question asked of one's own move: does any input
 *       explain it?</li>
 *   <li>{@link atlas.sim.Impulses}: what else may explain a move -- the attacks, knockback and explosions between two
 *       movement packets -- and which move each is offered to.</li>
 *   <li>{@link atlas.sim.MoveAudit}: the self check tick after tick -- which state a move is checked from, when, and
 *       with which packet's look. Its tests run the game's tick order.</li>
 *   <li>{@link atlas.sim.MoveModel}: the older, level-only model; MoveCheck's look check uses it.</li>
 *   <li>{@link atlas.sim.TargetFit}, {@link atlas.sim.TargetTrack}, {@link atlas.sim.TargetForecast}: another
 *       player's movement. The server sends little of it and coarsely; the keys are found from the positions, and
 *       the player is run ahead by them. {@link atlas.sim.TargetScore} holds the forecasts against what came.</li>
 *   <li>{@link atlas.sim.SimTargets}: the game's side of those -- the players near this one, followed while
 *       something asks. So far only the measuring asks (.targets, and the run recorder's engineErr column); no
 *       module aims by it yet.</li>
 *   <li>{@link atlas.sim.SimPlayer}: the game's side -- the player, its keys and the world, all of one moment of the
 *       tick. The only class here that needs the game.</li>
 * </ul>
 * Used by management.MoveCheck (the background check and its chat lines) and by Scaffold, Eagle and BlockLadder (where
 * the next step ends).
 *
 * <h3>The tick in this client</h3>
 * EntityPlayerSP.onUpdate with MixinEntityPlayerSP, read from the code on 2026-11-12. A state is taken at one of
 * three stages ({@link atlas.sim.PlayerSim.Stage}), and its first tick runs only what the game has not done yet.
 * <pre>
 *   between ticks       the server's packets are applied (a knockback sets the motion); the game's own tick: a
 *                       click's attack takes the motion across to 0.6; TickEvent
 *   UpdateEvent PRE     modules turn the look; nothing has moved yet                               Stage.START
 *   onEntityUpdate      the water's flow pushes
 *   onLivingUpdate      the sprint's count; the keys are read (MoveInputEvent; the shared look     Stage.INPUT
 *                       turns them at LOWEST); an item's slowdown; the push out of blocks; the
 *                       sprint rule
 *   LivingUpdateEvent   the keys and the sprint are final                                          Stage.MOVE
 *   the move            the jump, the keys' push (StrafeEvent), the collisions, gravity and
 *                       friction, the speeds the next tick uses
 *   the packets         sprint and sneak changes, then the position and the look
 *   UpdateEvent POST    ActionWatch.beforeUpdatePost first -- MoveCheck's end of the tick, which is
 *                       where a server's tick ends too -- then the modules
 * </pre>
 * UpdateEvent PRE is before the move, not after it. The self check once took it for "right after the move" and
 * checked every move against the next packet's look; see MoveCheck, and MoveAuditTest for the test that now holds
 * the order.
 *
 * <h3>For whoever changes it</h3>
 * <ul>
 *   <li>PlayerSim follows the game's code step by step, a float where the game uses a float. A change names the place
 *       in the game's code it comes from and has a test with the game's number.</li>
 *   <li>Only SimPlayer and SimTargets may use more of the game than AxisAlignedBB and MathHelper; the rest is tested
 *       with no game running.</li>
 *   <li>Ask for the player with {@link atlas.sim.SimPlayer#snapshot} and the stage you are at. The state and the keys
 *       of two different moments do not go together.</li>
 *   <li>What it does not see: what a module does to the move (it is what the check reports), and what only the
 *       server knows.</li>
 * </ul>
 */
package atlas.sim;
