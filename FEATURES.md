# Happy Artillery 1.2.0 Feature Contract

## Authority and status

This document is the settled behavior contract for the ground-up 1.2.0 rewrite. The supplied design
specification overrides the earlier G1-G9 questions. Released 1.1.2.2 behavior appears below only when
it is useful regression evidence; defects are not compatibility requirements.

The current branch is still structural groundwork. Until the final integration checkpoint removes the
deliberate startup failure and non-deployable identity, no source shell represents playable behavior.

## Product boundary

- Fabric server mod for Minecraft 26.2, Fabric Loader >=0.19.3, Fabric API, official mappings, and
  Java >=21. Clients do not install the mod. Java and Bedrock-through-Geyser are supported together.
- The mod id remains `happy-artillery`; config remains `config/happy-artillery.json`.
- Happy Ghasts provide pilot-only fire and cry controls, heat/cooling/overheat, optional ghast-bound
  ammo, and status presentation for every rider.
- State persists across chunk unload and server restart. No static gameplay-state maps or wall clock are
  allowed; server ticks are the only clock.
- 1.2.0 supports only Minecraft 26.2. Older README publication claims are history, not build targets.

## Architecture decisions and explicit assumptions

- The specification says “eleven files and exactly one mixin” but names eleven non-mixin Java owners,
  references an unlisted `Ammo.regen`, and separately names `SlotGuardMixin`. The complete smallest
  tree is therefore **thirteen production Java files**: the eleven named non-mixin files, `Ammo.java`,
  and the mixin. Hiding ammo inside an unrelated owner to preserve the claimed count is rejected.
- Persistent timing uses the Overworld's saved `gameTime` as the one canonical tick domain. It advances
  only with server ticks, survives restart without interpreting a new process-local counter, and provides
  one comparable value to every loaded dimension. No duration advances while the server is stopped.
- `GhastState` therefore stores heat plus its last-advanced game tick, firing-window end tick, ammo plus
  its regeneration anchor, per-ghast cry-ready tick, and a pending-detonation deadline. The abbreviated
  four-field sample in the specification is not the complete record contract.
- `Ammo` is the sole optional-ammo transition owner. `GhastState` stores its values; `Abilities` asks
  `Ammo` for availability/spend; the tick driver asks it for regeneration. Firing never changes the
  regeneration anchor.
- `Hud` owns bounded process-local boss-bar handles. Serializable HUD dirty-check values live in the
  persistent `RiderState`; live packet objects do not become persistence data.
- `Components` defines, catalogs, and registers both component types through one idempotence-checked
  registration entry. `HappyArtillery` invokes that owner during composition; it does not register the
  component types itself.
- Passengers receive the same ghast heat/status HUD read-only. Only the controlling first passenger
  advances state or triggers abilities. The sample loop's `Hud.clear` for non-pilots is superseded by
  the settled passenger-HUD decision.
- The config specification does not define malformed-value recovery. Startup uses strict validation:
  malformed JSON, unknown presets, invalid identifiers, non-finite numbers, impossible ranges, and
  cross-field violations fail loudly rather than clamp or silently default. A failed `/ha reload`
  reports the error and retains the previous valid atomic config. Missing files/keys use defaults and
  are rewritten with the full schema; unknown keys are discarded on rewrite.

## Configuration

Config is feature-grouped nested immutable values held in one `AtomicReference` and read at call time.
`preset` applies first; explicit keys override it. Every successful load rewrites the complete schema.
`/ha reload` is operator-only.

Defaults:

| Group | Keys and values |
|---|---|
| preset | `pvp` |
| controls | `fireSlot=4`, `crySlot=5`, `fireItem=minecraft:fire_charge`, `cryItem=minecraft:ghast_tear`, `holdToFire=true`, `allowPlainItems=false`, `lockControlSlots=true` |
| fire | `shotCooldownSeconds=0.25`, `explosionPower=2.0`, `speed=0.35`, `spawnDistance=2.0`, `breaksBlocks=true`, `respectProtection=true` |
| heat | `limit=100.0`, `firingWindowSeconds=1.0`, `cold=(0.70,1.0)`, `base=(1.25,0.6)`, `hot=(2.00,0.5)`, `nether=(3.00,0.0)`, `end=(0.70,1.0)`, `coldMaxTemperature=0.3`, `hotMinTemperature=1.0`, `unknownDimensionUsesTemperature=true` |
| water | `coolPerSecond=5.0`, `floor=0.0`, `blocksFiring=true` |
| overheat | `fuseTicks=0`, `explosionPower=6.0`, `fireballCount=24`, `fireballSpeed=0.4`, `fireballPower=2.0`, `fireAttempts=24`, `fireRadius=8.0`, `killsGhast=true`, `breaksBlocks=true`, `respectProtection=true` |
| cry | `enabled=true`, `volume=10.0`, `cooldownSeconds=10.0` |
| ammo | `enabled=false`, `max=200`, `costPerShot=1`, `regenIntervalSeconds=300` |
| hud | `bossBar=true`, `actionBar=true`, `refreshTicks=4`, `warningFromPercent=85` |

Presets:

- `pvp`: the defaults above.
- `survival`: protection remains required, fire radius 4, fireball count 12, and overheat power 4.
- `off`: both block-breaking switches false and fire placement disabled; entity damage remains.

## Persistent state and time

- Every persisted deadline/anchor is expressed in saved Overworld `gameTime`, never
  `server.getTickCount()` and never wall time. The driver reads that clock once per server tick and passes
  the value through context. Restart resumes in the same tick domain; stopped time does not count.
- `GhastState` is an immutable persistent attachment containing `heat`, `heatAnchorTick`,
  `firingWindowEndTick`, `ammo`, `ammoRegenAnchorTick`, `cryReadyTick`, and optional
  `detonateAtTick`. Updates replace the attachment value.
- `RiderState` is an immutable persistent player attachment containing each byte-exact stashed ItemStack
  paired with the slot index from which it was removed, ridden-ghast UUID, `lastHandledTick`, and
  serializable HUD dirty-cache data.
- `Heat.advance` applies cooling only over the not-yet-accounted interval and always moves
  `heatAnchorTick` to `now`. Passive cooling uses
  `max(0, now - max(heatAnchorTick, firingWindowEndTick))`; water cooling instead uses
  `max(0, now - heatAnchorTick)` and therefore keeps its ordering priority. A shot first advances to
  `now`, adds heat, and extends `firingWindowEndTick`. Repeated driver calls cannot subtract the same
  elapsed interval twice. An unloaded ghast catches up once on return; Nether heat remains unchanged.
- Optional ammo regeneration consumes complete intervals since `ammoRegenAnchorTick`, advances that
  anchor by exactly the consumed interval count (retaining a partial interval), caps at maximum, and is
  independent of firing. Reducing maximum below current ammo clamps on the next ammo transition. Enabled
  ammo requires positive maximum, cost, and interval; disabled ammo does no work.
- Cry admission compares `now` with `cryReadyTick`; accepted cry sets a new absolute game-time deadline.
  A pending fuse similarly detonates once when `now >= detonateAtTick`. Alternating riders cannot shorten
  cry cooldown, and restart cannot reset or lengthen either deadline.
- Entity attachment removal owns ghast-state eviction. Rider stash survives disconnect/crash until
  reconciliation restores it; HUD handles are always process-local and explicitly removed.

## Controls and inventory safety

- The Happy Ghast's controlling first passenger is the pilot. Only the pilot receives control items
  and can fire or cry. Other riders see the HUD and get `NOT_PILOT` feedback when attempting an action.
- Screen slots 5 and 6 (indexes 4 and 5 by default) become Fire Control and Cry Control while piloting.
  Both hands and ridden-entity/item-use callbacks route to one handler. `lastHandledTick` permits at
  most one accepted input per player tick whichever callback arrives first.
- Controls are fresh Fire Charge/Ghast Tear stacks carrying registered persistent data components,
  display names, and glint. Raw items do nothing by default; `allowPlainItems` is the explicit opt-in.
- On mount, the pilot's two complete ItemStacks are copied into persistent `RiderState`, then replaced
  with controls: exactly two inventory writes. Each stash entry records its original configured index.
  Existing items are never decorated or mutated.
- On dismount or loss of pilot status, the two stashed stacks are restored byte-for-byte, marked control
  items in that player's inventory are removed as a creative-duplication guard, and the stash clears:
  exactly two restoration writes plus the scoped marker sweep. Restoration always uses the persisted
  original indexes, never the current config.
- A successful live reload may change `fireSlot`/`crySlot`, but an active stash keeps its original indexes
  for restoration, lock decisions, and control lookup until that ride reconciles and clears. New indexes
  apply only to the next stash. Validation still requires distinct hotbar indexes. This avoids a global
  search for active stashes and makes disconnected/crash-recovery riders safe.
- The pre-drop player-death hook restores the stash **before vanilla creates inventory drops**. Tick
  reconciliation is only a backstop. Death drops therefore contain the player's real items, never
  controls. Disconnect, ghast death/removal, dimension change, kick, and crash recovery converge through
  the same invariant without any all-world item scan.
- `SlotGuardMixin` cancels click, drag, shift-click, hotkey/number-key swap, and drop paths affecting
  the two locked control slots while piloting. Unrelated slots and non-pilots are untouched.

### Hold-to-fire gate

The preferred control uses a long-duration, no-animation/no-sound consumable component. While the
control remains in the server-observed using-item state, firing repeats at the configured cooldown.
This API behavior is not accepted on assumption: one disposable 26.2 spike must prove a steady four
shots/second on Java and Bedrock through Geyser in the same session.

If that spike fails, click-rate heuristics are forbidden. The accepted fallback changes the default
shot cooldown to `0.5` seconds and doubles every heat-per-shot default (`1.40`, `2.50`, `4.00`, `6.00`,
`1.40`) so time to detonation remains unchanged. The chosen branch and resulting defaults must be
recorded in this contract, independently reviewed, committed, and pushed before controls, abilities, or
HUD implementation continues. A successful preferred-path result must record its Java and Bedrock
evidence just as durably as selection of the fallback.

## Biome and heat

- Classify dimension by `ResourceKey` identity first: vanilla Nether -> NETHER, vanilla End -> END.
  Other dimensions use biome base temperature when enabled: `<=0.3` COLD, `>=1.0` HOT, otherwise BASE.
  An id such as `nether_expanded` is not Nether merely because of its text.
- The driver classifies once per riding ghast/tick and shares that context with transition and HUD code.
- Heat is one pure authority. Its order is water cooling; no-cooling biome; firing-window hold; passive
  cooling. Water can cool in a no-cooling custom dimension and reaches floor 0. Passive Nether cooling
  never occurs. No cooling occurs until the one-second firing window has elapsed.
- Shot heat is COLD/END 0.70, BASE 1.25, HOT 2.00, NETHER 3.00. Limit is 100. `Heat.addShot` performs
  the codebase's only heat-limit comparison and reports detonation on the exact crossing shot.
- At the preferred 0.25-second cadence, approximate sustained detonation points are 143 cold/end,
  80 base, 50 hot, and 33 Nether shots. The hold-to-fire fallback changes per-shot values as above to
  preserve elapsed time rather than these counts.

## Abilities and effects

Fire outcomes are sealed: fired, detonated/pending detonation, or rejected with `IN_WATER`,
`ON_COOLDOWN`, `NO_AMMO`, or `NOT_PILOT`. Water, pilot, cooldown, and optional-ammo gates occur before
state spend. Ordinary rejection changes no heat/ammo/timing. Cooldown rejection is intentionally silent;
the other reasons map through `Feedback` to one short action-bar line and distinct sound.

Normal fire:

- Spawn one large fireball two configured blocks ahead at ghast eye height along the pilot's normalized
  view, speed 0.35, power 2.0, and one shoot sound. There is no eager chunk loading and no instant-ray
  fallback. Entity-add failure is reported as a failed effect; it does not create a second mutation path.
- Block damage follows `fire.breaksBlocks`. With `respectProtection`, the rider is the recorded cause and
  the normal vetoable explosion/block path must allow protection plugins to deny block changes.

Overheat:

- The crossing shot commits once. With `fuseTicks=0`, detonation is immediate; otherwise persistent
  pending state blocks further shots and detonates once at the deadline.
- At the ghast position, attempt the configured power-6 explosion with the rider as cause, spawn 24
  evenly distributed sphere fireballs at speed 0.4/power 2, attempt up to 24 supported fire placements
  within radius 8, then remove the ghast. The rider takes the blast normally.
- Every explosion and each fire placement independently honors `breaksBlocks` and, when enabled,
  `respectProtection` through the normal protection-visible path. A veto skips only that block mutation;
  it does not create a fallback explosion or bypass. Exact 26.2 and claim-plugin integration must be
  proven before this owner can be called GREEN.
- Ghast removal makes overheat one-shot and removes its attachment. `killsGhast=false` remains a valid
  server override; in that mode the post-effect state resets heat to zero and clears pending detonation.

Cry:

- Enabled pilot input outside water and cooldown plays one `GHAST_SCREAM` at the ghast, hostile source,
  volume 10.0, pitch 0.8. It has no damage, debuff, reveal, heat, or ammo effect.
- Cooldown is committed only when the sound effect is accepted and is stored on the ghast. Ordinary
  denial does not start it.

## HUD, lifecycle, and work bounds

- One boss bar per rider/ghast appears on mount and disappears on dismount/entity loss/disconnect/server
  stop. Passengers see it read-only. Progress is heat/100; updates send only changed values and never a
  remove-then-add pair.
- Action bar is dirty-checked and limited to every configured four ticks. It shows heat/cooling and
  optional ammo only when ammo is enabled. `NETHER · NO COOLING` in red has highest priority.
- Boss color is red in the configured warning band, gold in HOT/NETHER, blue in COLD, and green in
  BASE/END. Warning particles begin at 85% and are sent only to riders in the ghast's region.
- The sole tick driver iterates online players, performs one bounded rider/status reconciliation check
  for each, advances each ridden ghast
  exactly once through its pilot, then updates HUD for pilot and passengers from the resulting snapshot.
  With online non-riders it is not an empty loop: it checks attachment/ride status once per player and
  touches inventory only if a stash requires restoration. There is no loaded-world/entity scan, routine
  inventory scan, or cleanup pass.
- Presentation never classifies again or mutates heat, ammo, cooldown, detonation, or inventory state.

## Released defects retained as regression evidence

Tests must prove the rewrite does not restore these released faults:

- lore-substring controls, mutation/loss of player item names or glint, unpersisted duplicate player
  stores, per-tick 41-slot scans, delayed 10 ms repair queues, and global death-drop scans;
- any-passenger/raw-fire-charge ability bypass, duplicate callbacks causing double fire, and silent
  ordinary rejection;
- static UUID maps, wall-clock timing, firing postponing ammo regeneration, conflicting biome/heat
  classifiers, fixed 60-tick cooling, hardcoded overheat prediction, eager chunk requests, and instant-ray
  projectile fallback;
- per-tick remove/add boss-bar traffic, routine INFO spam, broad/sampled exception swallowing,
  `/happytest`, old mixins/accessors, and stale state/display handles.

## Manual acceptance boundary

Before release, run the complete controls abuse list on Java and Bedrock through Geyser: named/full
inventory restoration; player and ghast death; logout; hard server kill; dimension change; every slot
movement/drop route; two-rider pilot authorization plus passenger HUD; creative duplication; plain-item
denial; live reload to different fire/cry slots during an active ride; and Bedrock mount/fire/dismount
without ghost items. The active ride must remain locked to and restore from its persisted original indexes;
the next ride must use the new indexes. Verify protection vetoes with a real claims integration,
persistent heat/stash across restart, paused cooldown/fuse while stopped, one-time unload catch-up with no
repeated cooling, single-digit HUD packet updates per rider/second, bounded per-online-player idle work,
and README/jar version agreement.
