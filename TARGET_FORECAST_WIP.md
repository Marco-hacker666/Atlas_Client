# Target forecast (PR A) - work in progress

Branch `target-forecast`. Stopped mid-way on 2026-11-13. Remove this file before the PR.
Plan: the "Atlas 對手移動預測計劃書" doc. This branch is PR A: pure logic in `atlas.sim`, no module wired.

## What is here

- `TargetFit`: finds the keys that took another player from its last state to the cell the server now names.
  Solves the yaw (rigid turn of the keys' share), tries 1-4 moves, jump or not, an expected impulse, and
  `solve()` finds the motion that must have replaced the player's when nothing fits.
- `TargetTrack`: the shadow of one player. `observe()` per position, `forecast()` runs it on. Run-long yaw
  (`yawFromTheRun`), turn rate from the yaws sent, straight line when not locked.
- `TargetForecast`: the answer.
- `src/test/java/atlas/sim/TargetBench.java`: NOT a test. A `main` that emulates EntityTrackerEntry
  (every 2nd tick, floor to 1/32, 4/32 threshold, yaw 4/256 threshold, teleport on a ground flip) and prints
  the forecast error against the straight line for 17 scripted players. Pass a scenario name for a trace.

Compiles against the local stubs. No JUnit tests yet. CI has not been run on this branch. Not tested in game.

## Numbers (mean level error in blocks, engine / straight line, 5 moves ahead)

Before the last change (`solve` + second reading):
straight sprint 0.008 / 0.055, turning 6 deg/tick 0.072 / 0.506, circle strafe 0.071 / 0.565,
sprint jump 0.090 / 0.391, into a wall 0.047 / 0.100, knockback 0.221 / 0.300, sneak 0.007 / 0.052,
sprint with network jitter 0.010 / 0.240, strafe A/D 0.662 / 0.775, w-tap 0.382 / 0.346 (the one case behind).

## Open problem to start from

Adding `TargetTrack.unexplained()` (solve the replaced motion, keep a second reading) improved knockback
(0.221 -> 0.189, relock 2 positions sooner) but made sprint jump worse: 0.090 -> 0.171 level,
0.144 -> 0.323 height. Trace `TargetBench "sprint jump"`: misses in mid-air (moves 34, 46) after the keys flap
between W, W+A and W+D in the air, where they barely show. Suspect: on those misses `solve` replaces a height
and fall that were exact with estimates; the old path kept the simulated height when it was inside the cell.
Try: in `unexplained`, when the best try's height is inside the cell, solve the level motion only and keep its
height and fall. Also consider not changing keys at all in the air unless the old ones are outside the slack.

## Then

1. Turn the bench into `TargetFitTest` and `TargetTrackTest` with the numbers fixed: straight no worse than the
   line; turning, jump, wall, knockback clearly better; a speed cheat never locks (falls back to the line).
   Unit tests for `candidates`, `fallAfter`, `vanillaHit`, `Seen.outside/under`, `solve`.
2. Delete `TargetBench` and this file. Add the classes to `package-info.java`.
3. CI, self-review, PR (English, say it is not tested in game).

## Facts found on the way (checked in the 1.8.9 source)

- A melee hit's knockback is sent only to the player hit (EntityPlayer.attackTargetEntityWithCurrentItem
  resets velocityChanged). Other knockback (arrows, rods, explosions) goes to everyone. The plan doc says
  "the server broadcasts it": that line is wrong for melee and has to be corrected.
- The server computes a melee knockback from its own motion for the player, which is only the resting fall:
  (-dx/d * 0.4, 0.3608, -dz/d * 0.4), plus (0.5 * extra along the attacker's yaw, +0.1) for a sprint hit.
- Tracker: position sent only when 4/32 from the last sent or every 60 ticks; a ground flip sends a teleport
  with the exact cell; a jump (isAirBorne) or a flag change forces an update that tick.
- The player's true yaw is from 3/256 below the yaw sent to just under 4/256 above (TargetFit.YAW_MID/HALF).
- W-tap is the case the engine does not beat: after a release it assumes the keys stay released.
