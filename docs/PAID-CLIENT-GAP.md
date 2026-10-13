# The gap between OpenMyau+ and the paid clients (2026-09-25)

Written before the next game, as a road map for later improvements.

**Sources and limits**
- The paid clients are closed source, so only what they make **public** can be compared: Slinky's module docs (docs.slinky.gg), its website, and Rise's feature list.
- On our side the basis is the source code and these weeks' logs (flags / clutch / fakelag / perf).
- Anything about "how a paid client does it inside" is inference, marked (inferred).

---

## Summary: where the gap is

The **number** of modules is not the problem: we have 152 classes, Slinky about 50+. The gap is mainly four things:

1. **The verification loop.** Paid clients have a team and many users testing continuously against particular anticheats, so they know quickly when something breaks (inferred). We have one player's logs:
   - 674 refused blocks, and the cause is still not found.
   - place-log was only just added and has no data yet.
2. **A shared foundation.** Every Slinky module uses the same building blocks: "Weapons" definitions, "Conditions" for when it runs, "Randomization", "Select blocks", aiming (base speed / acceleration / multipoint).
   - We implement these in each module separately.
   - GCD quantisation and the acceleration cap exist only in Clutch; Scaffold, KillAura and AimAssist each compute their own.
3. **Trimming and polish.**
   - Slinky has about 50 modules, and every setting is documented with what it does and its risk.
   - We have 152, some of them duplicates or half finished (KillAura/PlainAura, WaterMark/WaterMark2, ClickGUIModule/RiseClickGUIModule…), and no user documentation at all.
4. **Injection and compatibility.**
   - Slinky is injected and supports Vanilla / Forge / Lunar / Badlion.
   - We are a Forge mod in the mods folder and run only on Forge.

---

## Module by module (going by Slinky's public docs)

### Clutch

| Slinky has | We have | Gap |
|---|---|---|
| Range: counted as "how many blocks to place to reach it" | `reach` (a distance) | We use distance, not the number of blocks to place |
| FOV limit, only blocks in view | none | Missing. Without an FOV limit it turns through large angles, a possible source of flags |
| Minimum height: at least N blocks of air below to start | `void-only` / `safe-drop` | We only tell "void or not", with no adjustable block count |
| Click speed + Randomization | `place-interval`, `rotation-random` | Mostly there |
| Select blocks: No / On depletion / Always | `auto-switch` / `switch-back` | Missing "On depletion" |
| Only place sideways (keep the Y level) | none | Missing |
| Aiming: base speed / acceleration / acceleration strength | `speed` / `max-speed`, plus GCD and the acceleration cap | Comparable; our acceleration strength is not adjustable |
| Multipoint: aim at the closest point on the block | a fixed point (inferred less natural) | To check; possibly missing |
| Snap-back: delay / duration / keep jump direction / disable afterwards | `snapback-speed` | Missing delay, duration and "straighten at once on a jump" |
| Start conditions: in the air, just hit, walking backwards | `trigger` / `combat-window` / `counter-knockback` | Comparable |

### Backtrack

| Slinky has | We have | Gap |
|---|---|---|
| A target distance band (e.g. 1.0–4.0) | `range` | Missing the lower bound; holding at point-blank range is pointless and only adds risk |
| Maximum delay | `normal-delay` / `adaptive-delay` | There, plus adaptive |
| Maximum hurt time: no hold while the opponent's invulnerability is high | none | Missing. Would cut holding for nothing |
| Cooldown: wait after stopping before starting again | none | Missing. Unbroken delay is the easiest to catch by timing analysis |
| Real position indicator: colour, line width, fill, **head direction** | a smoothly fading ESP using the Theme | Missing the head direction |
| Disable on hit (release on knockback) | `release-on-hit` | There |
| Conditions: only while holding a weapon | none | Missing (needs global Weapons first) |

### Fake Lag

| Slinky has | We have | Gap |
|---|---|---|
| Static / Pulse modes | `mode`, plus gradual taper / catch-up release | Comparable; the gradual release is our advantage |
| **Inbound and outbound delay set separately** | outbound only | **Missing inbound delay** |
| Realtime damage: damage packets are not delayed while inbound is | none | Missing (goes with inbound) |
| Require attack: lag only after a recent attack | `release-on-attack` / `range` | A different concept; could be added |
| Holding weapon / In-game conditions | partly (screen / using-item count as soft reasons) | Missing the weapon condition |

### Bridge Assist (our Eagle / SafeWalk / Scaffold's eagle)
- Slinky's Bridge Assist has these settings:
  - edge offset and unsneak delay can both be **randomised**.
  - Sneak on jump.
  - **Avoid double-sneaking**: when bridging diagonally, delay the placement so one block is not sneaked on twice.
  - Start conditions: sneak held, holding blocks, looking down, not walking forward.
- Our Eagle is basic, and Scaffold's `edge-distance` / `sneak-delay` are not randomised.

### Modules we do not have
- **Movement**: Null Move (handling left+right / forward+back held together), Instant Stop, Fast Accel.
- **Utility**: Auto Rod, Auto Pot / Auto Soup (of little use in BedFight), Item Use Fix, No Item Release, No Use Delay, Ping Fix.
- **Visual**: Pointers (arrows at the screen edge pointing at opponents), Notifications (module toggles, flag alerts).
- **Global**: Weapons (what counts as a weapon, shared by every module); Friends and Teams we already have.

### What we have and Slinky does not
These are our advantages and should be kept.
- FlagDetector + place-log + PerfLog + clutch / fakelag traces: data-driven debugging tools.
- Arbiter: coordination between modules, so Clutch is not held back by Blink / FakeLag while catching.
- The Theme tab: 14 groups, all customisable.
- More blatant modules (Fly, Speed, LongJump, BedNuker…). Slinky is positioned as a ghost client and does not make these anyway.

---

## Our own known problems (from the logs)

1. **Refused blocks**: 674 (over 15 days).
   - 61% with no lagback nearby.
   - Most refusals arrive 4–6 ticks after placing (one round trip).
   - Pillaring up gets the most refusals in a row, followed by being pulled back down.
   - Cause unknown → waiting for place-log data.
2. **Clutch is still refused 20% of the time even with GCD and acceleration done**, with no distinguishing factor found.
   - Possibly the same cause as point 1.
   - Possibly the FOV / turn size (Slinky has an FOV limit, we do not).
3. **Performance**: the HUD's blur / glow shaders take about 200 µs a frame, the largest cost in our own code.
   - Paid clients usually cache the blur in a framebuffer (inferred).
4. **Backtrack**: while the network thread holds a lock it schedules tasks into scheduledTasks; the ordering is fragile (no incident so far).

---

## Suggested priorities

Ordered by "effect on flags / win rate ÷ effort":

1. **Read place-log to find why blocks are refused** (after the next game). This is the biggest unknown, and it can affect every other change.
2. ✅ (14:17 build) **A shared RotationEngine**: take Clutch's GCD, acceleration cap and speed drift out for Scaffold / KillAura / AimAssist to share, then add multipoint and an FOV limit.
3. ✅ (14:17 build; snap-back duration replaced by snapback-speed) **Complete Clutch**:
   - FOV limit
   - minimum height (in blocks)
   - only sideways
   - switch blocks on depletion
   - snap-back delay / duration and straightening at once on a jump
4. ✅ (14:17 build) **Complete Backtrack**:
   - lower distance bound
   - max hurt time
   - cooldown
   - weapon condition
   - head direction in the ESP
5. **Global Weapons + Conditions**: defined once, shared by every combat / utility module.
6. **FakeLag inbound delay + realtime damage.**
7. **Bridge Assist**: randomised edge offset / unsneak delay, plus avoid double-sneak.
8. **Trim the modules**: merge or hide duplicate and broken modules; write a page of instructions for the common ones.
9. **Cache the HUD blur**: redraw only when the content changes.
10. **New small modules**: Pointers, Notifications, Null Move, Instant Stop.

## Roughly how big the gap is (subjective)

- **Features**: the common combat / block modules are about 70–80% of Slinky's; what is missing is mostly "fineness of settings", not whole modules.
- **Stability / reliability against anticheats**: the biggest gap, and the hardest to estimate. Paid clients fix things from many users' reports (inferred). We find causes ourselves from logs: slower, but every fix has evidence behind it.
- **Completeness** (docs, consistent UI, no duplicates): a clear gap, and pure work, no research needed.

Sources:
- [Slinky](https://slinky.gg/)
- [Slinky module docs](https://docs.slinky.gg/modules/combat/velocity/): the Clutch, Backtrack, Fake Lag and Bridge Assist pages
- [Rise features](https://riseclient.org/features)

---

# Compared with Vape (2026-09-25 evening)

**Sources and limits**
- Vape's official docs, [docs.vape.gg](https://docs.vape.gg/features/modules/). Only module names and setting descriptions are visible, not how it works inside.
- Many "Vape V4 download" GitHub pages online are fake or malware; none were used.

## Combat modules

| Vape | Key settings | We have | Gap |
|---|---|---|---|
| AimAssist | Simple / Adaptive, **Strafe Increase** (turns faster while strafing), Target Area: centre / closest point, Target Mode: distance / angle / armour / threat / health | AimAssist: multipoint (= closest point), fov, speed | Missing Adaptive, Strafe Increase, and picking targets by armour / threat |
| AutoClicker | — | AutoClicker (left/right, hit-box assist) | Comparable |
| BlockHit | Manual / Auto / **Predict** (block before the opponent swings) / **Lag** | BlockHit: Helper, with Predict-like settings | Mostly comparable, missing the Lag mode |
| HitSelect | Pause / **Active** (picks when to hit by the situation, favouring less knockback taken or crits) | HitSelect: SECOND / CRITICALS / W_TAP | Missing Active's dynamic judgement |
| Reach | range, chance, Advanced chance, **Misplace** (so recordings look plausible), vertical check, sprint only | Reach: range, chance | Missing Advanced chance, Misplace, the vertical check |
| **WTap** | chance, **release delay**, **re-press delay**, **Select Hits** (W-tap only when it pays) | SprintReset LEGIT + min-gap (20:28 build) | Missing chance, both delays, Select Hits |
| **JumpReset** | chance, accuracy (misses on purpose now and then), only with the opponent near the crosshair, off in water | Velocity's Jump mode (whether it also changes motion is not confirmed) | **Missing a separate, jump-only JumpReset** |
| Triggerbot | clicks automatically with the crosshair on an opponent | none | Missing |
| RightClicker | right-click spam | AutoClicker's right click | Comparable |
| HitFlick / SilentAura / Sprint | — | Hitflick / KillAura / Sprint | Comparable |
| Network: BackTrack / Blink / FakeLag / KnockbackDelay | setting details not visible | all present, with logs to tune them | Details cannot be compared |

## Conclusions
- **Kinds of combat module**: about 85% have a counterpart. Truly missing: **JumpReset** and **Triggerbot**.
- **Fineness of settings**: a bigger gap than the kinds. Many Vape modules have three kinds of setting — "chance", "miss on purpose" and "only when it pays" — that make behaviour look irregular rather than machine-like; we mostly lack them.
- **The invisible gap, and the hardest to chase**: Vape has per-anticheat configs rotated daily (its own marketing claim). We can only tune one by one from our own logs.
- **What we have that Vape's docs do not mention**: measurement tools like FightLog, FlagDetector and place-log.

## Added after looking at Vape 4.21 (20:48 build, not tested yet)

| Item | Status |
|---|---|
| Tracking the delay from a hit to the opponent being hurt (HitTimer) | ✅ added, shared by HitSelect / SprintReset / BlockHit |
| WTap: chance, release delay, release length, only when it pays | ✅ added to SprintReset |
| HitSelect Active | ✅ new ACTIVE mode |
| BlockHit Predict (block on the rhythm of one's own hurt time) | ✅ new Rhythm mode |
| AimAssist: predict the opponent's movement, faster while strafing | ✅ `lead`, `strafe-increase` |
| AimAssist: a velocity model, mouse movement added every frame | ❌ not done yet |
| Reach | ❌ not doing it: Vape's own docs tell people not to use it |
| JumpReset, Triggerbot | ❌ not done yet |

## FakeLag compared (2026-09-25 evening)

**Sources**: Vape 4.21 (a FakeLag technical-analysis file on the owner's desktop plus static reading of the jar), LiquidBounce's source ModuleFakeLag.kt, Slinky's docs.

**What we already have**: four modes — LATENCY, DYNAMIC, REPEL, LIQUID — plus:
- gradual release (taper / catch-up)
- a cool-down after being hit before holding again (recoil)
- coordination with Clutch (Arbiter)
- no holding while holding blocks; placement packets queued in order
- a log line for every hold and release

**Missing (by priority)**
1. **A random delay per hold.** LiquidBounce redraws it between 300–600 ms each time; Vape Repel adds 0–99 ms at random. Ours is a fixed 250 ms.
2. **Delaying received packets while showing damage in real time** (Slinky). Only our Backtrack delays received packets.
3. **Hold only while holding a weapon** (Slinky).
4. **Vape Repel**: hold the attack packet and send it when the opponent's hurt time is ≤ the tick HitTimer expects. Our REPEL is really Vape Dynamic's "is the position getting closer" check.
5. **Pulse mode** (Slinky).
6. **Arrow dodge**: release only up to the position that dodges an arrow (LiquidBounce); low value.

---

# Compared with the Rise 6.9.5 source (2026-09-28)

**Sources and limits**
- `<reference clients>\Rise\Rise-6.9.5-main`: decompiled source, about 1300 Java files.
  - Some names are recovered, some still obfuscated (`aEg`, `wo()`).
  - Its README itself says it may differ from the original.
- Source read only; `Rise.jar` was not run, and no code was copied.
- We may take its ideas, but the implementation is our own (the same rule as for Vape).
- Unlike Vape and Slinky, this time the **internals** are visible, so everything below is read from code, not inferred.

## Summary

1. **Reliability and observability: we are ahead.**
   - Rise is full of mutable `public static` fields and has no tests.
   - It has no value ownership (Property override), connection lifecycle (ServerSession) or staged shutdown (Shutdown), and none of the "find the cause from data" tools like FlagDetector / Attribution / Brain / FightLog.
2. **Shared foundation: Rise has three designs we lack and should learn from.**
   - Leases for packet holds.
   - A per-tick gate for action packets.
   - Value ranges (min–max).
   - Details in the next section.
3. **The combat rotation model: the biggest gap.**
   - KillAura's Advanced mode is a whole human-like mouse model with about 20 parameters.
   - Our RotationEngine has the mouse grid (GCD), the acceleration cap, speed drift and multipoint, but no reaction time, overshoot, dead zone or pauses.
4. **Kinds of module: combat and ghost modules are about even.**
   - What Rise has in addition is mainly KeepRange (S-Tap), AimBacktrack, and knocking opponents toward hazards (Knockback Displacement).
   - The rest are blatant, exploit or server-specific.

## Foundations compared

### 1. Packet holding: BlinkComponent versus our six holders

**How Rise does it**
- Every hold goes into **one ordered queue**.
- Each calling class has its own channel, named from the stack trace.
- Each held packet records "which tickets still hold it".
  - It does not go out while any ticket is live.
  - A packet is not released before the ones ahead of it, so the order is always right.
- **Leases**: a channel not renewed for 100 ms is released automatically.
  - A module must call `blink()` every tick to keep holding.
  - A module that errors, is switched off or forgets to release lets go within 100 ms at most.
- Each channel has its own maximum hold time.
- Everything is released on a world change or the terrain-loading screen.
- It can release tick by tick: it records how many were held each tick, then releases N ticks' worth at a time.

**How we do it**
- LagManager, BlinkManager, DelayManager, BackTrack, ServerLag and KnockbackDelay each have their own queue.
- Arbiter decides first come, first served; PacketHolds only "watches".

**The gap**
- **No leases.** A holder that never reaches its release code leaves its packets stuck.
  - That is exactly the root of the reliability pass's "every module must guarantee its own release" class of problems.
- Two modules cannot hold the same packet at once. Today the second is refused, and the order between different holders is not guaranteed.

**What not to copy**
- Rise puts sent and received packets in the same queue, so one direction stuck blocks the other.
- It takes a stack trace for every held packet, which is costly. We take `callerModule` once at `setDelay`, which is better.

**Suggestion**
- No need to merge the six holders into one (that is an architecture rewrite).
- Adding **leases** to the existing managers gets most of the benefit: release automatically when not renewed, and log a "lease-expired" entry.

### 2. Rotations: RotationComponent

**How Rise does it**
- Rotations are leased too: if nobody sets a target angle this tick, it starts returning to the player's real view.
  - The return is snapped to the mouse grid (`applySensitivityPatch`), not a jump.
- Three movement fixes: NORMAL, TRADITIONAL, BACKWARDS_SPRINT.
  - BACKWARDS_SPRINT means: no sprint when the turned direction and the movement direction differ by more than 45°.
- **Verified noise**: the aim point random-walks over the hitbox, each step checked by raycast that it still hits. If not, it changes direction, and if still not, it returns to the centre.
  - So the noise never makes an attack miss.

**How we do it**
- RotationEngine has GCD, acceleration, drift, multipoint and FOV.
- RotationManager / RotationState handle priorities.

**Missing**
- Verified noise.
- A smooth return to the real view.
- The sprint direction fix.

### 3. A per-tick gate for action packets: BadPacketsComponent

**How Rise does it**
- Records which packets were already sent this tick: slot change (C09), attack (C02), placement (C08), digging (C07), window clicks.
- Reset when the next C03 movement packet is sent.
- Modules ask before sending. For example, TickBase does not blink if an attack was already made this tick.

**How we do it**
- Not centralised; only NoSlow and Velocity check for themselves.

**Why it matters**
- Many anticheats' BadPackets checks catch exactly "actions in the same tick that should not be together".
- The gate is little work, and FlagDetector can verify directly whether it helps.

### 4. Value ranges (BoundsNumberValue)

- Many Rise settings are ranges, with a random value drawn inside the range each time they are used.
  - For example CPS 8–14, rotation speed 5–10, Blink delay, placement delay.
- Ours are mostly single values, AutoClicker's min / max-cps being the exception; other randomisation is written by each module itself.
- The same conclusion as the Slinky and Vape comparisons: "randomisation" should be a shared foundation, not done by each module separately.

## Combat modules compared (Hypixel-related)

| Rise | How it works | We have | Gap |
|---|---|---|---|
| KillAura **Advanced** rotations | A WindMouse-like mouse model. Gravity, wind, slow-down distance, max and min step; **overshoot** (77% chance, a ratio, at most 17°); **reaction time** 180 ms ± 44; **dead zone** 1°; **pauses** 2 ticks; **Flick Guard** (at most 29° in one tick); pace jitter / burst | RotationEngine: GCD, acceleration, drift | **The biggest gap.** No reaction time, overshoot, dead zone or pauses |
| Velocity: 23 modes | Per anticheat. Three relate to Hypixel: Watchdog (cancel or stack knockback, plus holding transactions), **Watchdog Reduce** (hold its own S12 and transactions until you attack or land), Watchdog Prediction (let part of the knockback through) | Vanilla / Jump / Hypixel / Slap_Attack | Watchdog Reduce is basically our KnockbackDelay that failed many times; **not recommended again**. Holding transactions is high risk |
| Legit / Jump Reset | On the ground, an S12 with motionY > 0 → jump on the next input; chance, "only while swinging" and Legit Timing | Velocity's Jump mode: on the ground, sprinting, no jump boost, not in liquid | Comparable. Rise adds "only while swinging", i.e. jump only when also attacking |
| **KeepRange (S-Tap)** | **While comboing the opponent** (they keep getting hurt, you are not hit), stop or back off when closer than a set distance to keep the spacing. Off near a cliff edge. The combo must last a few hits before it starts | none | **Missing.** Extends one's own combos and cuts counter-hits. Can be verified with FightLog's combo length |
| **AimBacktrack** | Keeps the views of the last N ticks. If the current view cannot hit but an earlier one could, use that | none | Missing. Useful when the opponent strafes suddenly |
| **Knockback Displacement** / ManualKBDisplacement | After a hit, adjust the server-side view (while still hitting) so the opponent's knockback points at the void, lava or fire. Each candidate direction gets a score (hazard, score); only done at a score ≥ 45 | none | Missing. Useful in BedWars and fights near edges |
| LagBreak | Blink while closing on the target, release on attack (dispatch on attack); at most 9 ticks held; random release when the opponent is not looking at you; progress bar, circle and text displays | FakeLag (4 modes incl. LIQUID) | Mostly comparable. Missing an on-screen "how much is still held" |
| Reach / KeepSprint "Buffer Abuse" | Active only for the first N hits, then recovers at the anticheat's buffer decay rate | Reach: chance only | Missing. The idea is "keep violations within the anticheat's allowance"; needs FlagDetector data to tune |
| TickBase | Post / Legit, very short | PAST / FUTURE, with balance and pause-on-flag | **Ours is more complete** |
| AimAssist | speed, sticky, require swinging, require mouse movement | lead, strafe-increase, multipoint; assists only for a while after a click | Comparable. Missing only "assist only when the mouse moves" |
| WTap | Legit (release W while the opponent's hurtTime ≥ 6), Silent (no sprint on the tick the opponent's hurtTime == 9) | SprintReset: chance, two delays, only when it pays | **Ours is finer** |
| CheatDetector | Checks **other players** client-side: AutoBlock A/B, VelocityCancel, FlightPrediction, SpeedLimit, TowerWatchdog | none | Low value. Could mark opponents as cheating in FightLog and leave them out of the statistics |
| Mimic | Copies the opponent's view and clicks | none | A gimmick; not doing it |

## Not adopted
- Exploits such as Crasher, ComboOneHit (sends 50–1000 attack packets at once), Disabler, TeleportAura, GodMode.
  - Not ghost PvP; equivalent to asking for a ban.
- Remote scripts, IRC, Spotify, account services and other features unrelated to fighting.

## Suggested priorities

Ordered by effect on reliability / flag risk / win rate divided by effort, following the "foundations first" principle:

1. **Hold leases** (in the existing managers, not merged).
   - Release automatically when not renewed, and log an entry.
   - This belongs directly to the reliability pass: state corruption resistance.
2. **A per-tick gate for action packets** (BadPackets).
   - Little work, verifiable with FlagDetector.
3. **RotationEngine gets reaction time, overshoot, dead zone and pauses, plus verified noise.**
   - Shared by KillAura, AimAssist and Clutch.
   - A/B with FlagDetector's rotation flags.
4. **KeepRange (S-Tap).**
   - Verified with FightLog's combo length and "THEY FELL / WE FELL".
5. **Value ranges (min–max) as a shared Property type.**
6. AimBacktrack.
7. Knockback Displacement.

The JumpReset that was planned: Rise's version is basic and our Velocity Jump is already comparable, missing only the "only while swinging" condition.

### Progress (2026-09-28)
- ✅ 1. Hold leases (PacketHolds leases)
- ✅ 2. Per-tick action packet gate (TickActions; KillAura already wired in)
- ✅ 3. KillAura Rotations "Advanced": the same approach as Rise (util/AdvancedAim)
- ✅ 4. KeepRange (S-Tap)
- ✅ 5. Value ranges: IntRange / FloatRange; KillAura's and AutoClicker's CPS already use them
- ✅ 6. AimBacktrack
- ✅ 7. Knockback direction: the KBDisplacement module, plus KillAura's KBDisplace (OFF / SAFE / FULL)
