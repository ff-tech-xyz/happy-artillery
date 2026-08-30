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
- Happy Ghasts provide pilot-only fire and cry controls, heat/cooling/overheat, and status presentation
  for every rider.
- State persists across chunk unload and server restart. No static gameplay-state maps or wall clock are
  allowed; server ticks are the only clock.
- 1.2.0 supports only Minecraft 26.2. Older README publication claims are history, not build targets.

## Architecture decisions and explicit assumptions

- The complete smallest tree is **fourteen production Java files**: the eleven accepted non-mixin owners
  plus `DeathDropMixin`, `PlayerDropMixin`, and `SlotGuardMixin`. Minecraft 26.2 has no usable committed
  pre-drop Fabric event, so the death mixin wraps only the vanilla death-drop invocation. Container
  mutation and direct Q/drop converge in unrelated `AbstractContainerMenu` and `ServerPlayer` targets,
  so their fail-closed plumbing remains separate. All three mixins delegate policy to `Controls`.
- Persistent timing uses the Overworld's saved `gameTime` as the one canonical tick domain. It advances
  only with server ticks, survives restart without interpreting a new process-local counter, and provides
  one comparable value to every loaded dimension. No duration advances while the server is stopped.
- `GhastState` therefore stores heat plus its last-advanced game tick, firing-window end tick,
  independent per-ghast fire-ready and cry-ready ticks, and a paired pending-detonation deadline plus
  detonating-rider UUID. The abbreviated sample in the specification is not the complete record contract.
- `Hud` owns bounded process-local boss-bar handles. Serializable HUD dirty-check values live in the
  persistent `RiderState`; live packet objects do not become persistence data.
- `Components` is the sole fire/cry marker codec/helper owner. It stores the exact distinction in a
  namespaced tag inside vanilla `CUSTOM_DATA`, preserves unrelated custom data, and creates no custom
  synchronized registry entry. `HappyArtillery` has no component-registration seam.
- Passengers receive the same ghast heat/status HUD read-only. Only the controlling first passenger
  receives marked controls, advances state, or triggers abilities. No passenger action or feedback
  input is promised while marked controls are pilot-only and plain items are disabled.
- Startup config has a strict transaction boundary. A missing file is created from validated defaults,
  and missing known keys inherit defaults before a successful full-schema rewrite. An existing file
  with malformed JSON, an unknown preset, invalid identifier, non-finite number, impossible range, or
  cross-field violation aborts startup loudly; it is never replaced with defaults. `/ha reload` parses
  and validates a candidate before the atomic swap: failure reports the error and leaves both the prior
  valid in-memory value and the invalid file untouched. Unknown keys are discarded only by a successful
  load/rewrite.

## Configuration

Config is feature-grouped nested immutable values held in one `AtomicReference` and read at call time.
`preset` applies first; explicit keys override it. Every successful load rewrites the complete schema.
`/ha reload` is operator-only.

Defaults (eight groups, 39 declared keys):

| Group | Keys and values |
|---|---|
| preset | `pvp` |
| controls | `fireSlot=4`, `crySlot=5`, `fireItem=minecraft:fire_charge`, `cryItem=minecraft:ghast_tear`, `holdToFire=true`, `allowPlainItems=false`, `lockControlSlots=true` |
| fire | `shotCooldownSeconds=0.25`, `explosionPower=1` (strict integer) |
| heat | `limit=100.0`, `firingWindowSeconds=1.0`, `cold=(0.70,1.0)`, `base=(1.25,0.6)`, `hot=(2.00,0.5)`, `nether=(3.00,0.0)`, `end=(0.70,1.0)`, `coldMaxTemperature=0.3`, `hotMinTemperature=1.0`, `unknownDimensionUsesTemperature=true` |
| water | `coolPerSecond=5.0`, `floor=0.0`, `blocksFiring=true` |
| overheat | `fuseTicks=0`, `explosionPower=6.0`, `fireballCount=24`, `fireballSpeed=0.4`, `fireballPower=2` (strict integer), `fireAttempts=24`, `fireRadius=8.0`, `killsGhast=true`, `breaksBlocks=true` |
| cry | `enabled=true`, `volume=10.0`, `cooldownSeconds=10.0` |
| hud | `bossBar=true`, `actionBar=true`, `refreshTicks=4`, `warningFromPercent=85` |

Presets:

- `pvp`: the defaults above.
- `survival`: fire radius 4, fireball count 12, and overheat power 4.
- `off`: overheat block breaking and fire placement are disabled; normal fire still follows vanilla
  `mobGriefing`, and entity damage remains.

## Persistent state and time

- Every persisted deadline/anchor is expressed in saved Overworld `gameTime`, never
  `server.getTickCount()` and never wall time. The driver reads that clock once per server tick and passes
  the value through context. Restart resumes in the same tick domain; stopped time does not count.
- `GhastState` is an immutable persistent attachment containing `heat`, `heatAnchorTick`,
  `firingWindowEndTick`, `fireReadyTick`, `cryReadyTick`, optional `detonateAtTick`, and optional
  `detonatingRiderId`. The two detonation fields are present or absent together. The fire cooldown is
  not inferred from the firing window. Updates replace the attachment value.
- `RiderState` is an immutable persistent player attachment containing each byte-exact stashed ItemStack
  paired with the slot index from which it was removed, ridden-ghast UUID, `lastHandledTick`, and
  serializable HUD dirty-cache data.
- `Heat.advance` applies cooling only over the not-yet-accounted interval and always moves
  `heatAnchorTick` to `now`. Passive cooling uses
  `max(0, now - max(heatAnchorTick, firingWindowEndTick))`; water cooling instead uses
  `max(0, now - heatAnchorTick)` and therefore keeps its ordering priority. A shot first advances to
  `now`, adds heat, and extends `firingWindowEndTick`. Repeated driver calls cannot subtract the same
  elapsed interval twice. An unloaded ghast catches up once on return; Nether heat remains unchanged.

- Cry admission compares `now` with `cryReadyTick`; accepted cry sets a new absolute game-time deadline.
- A pending fuse detonates once when `now >= detonateAtTick`. `Abilities` is the only fuse scheduler:
  every accepted overheat, including `fuseTicks=0`, submits the persisted ghast/deadline/rider pair to
  `FuseQueue`. An already-due submission executes that exact owned task through the same private due-task
  routine as later queue runs and returns its exact outcome; a future deadline remains active. The ghast-load
  callback asks that same owner to re-establish an overdue or future task from persisted state. The
  queued task re-reads the attachment/deadline and resolves the persisted rider UUID through the server
  player list before acting. Rider dismount does not cancel it. Entity unload leaves pending state for the
  existing ghast-load wake-up. If only the rider is unavailable while the ghast remains loaded, the same
  ghast/deadline/rider task moves to one bounded rider-indexed deferred collection inside `FuseQueue`;
  player availability reactivates it exactly once for the next normal due-task run. Deferred rider work is
  keyed by the persisted ghast UUID, not a Java entity reference. A reload object for that UUID replaces
  the active or deferred task's entity reference without adding work; changed deadline/rider state replaces
  the old task. Deferred work is not retried by the player tick or any other per-tick poller. There is no
  world/entity scan or second queue.
  Alternating riders cannot shorten cry cooldown, and restart cannot reset or lengthen either deadline.
- Entity attachment removal owns ghast-state eviction. Rider stash survives disconnect/crash until
  reconciliation restores it; HUD handles are always process-local and explicitly removed.

## Controls and inventory safety

- The Happy Ghast's controlling first passenger is the pilot. Only the pilot receives control items
  and can fire or cry. Other riders see the HUD read-only; they have no safe action input while plain
  items are disabled, so the product does not promise unreachable `NOT_PILOT` feedback.
- Screen slots 5 and 6 (indexes 4 and 5 by default) become Fire Control and Cry Control while piloting.
  Both hands and ridden-entity/item-use callbacks route to one handler. `lastHandledTick` permits at
  most one accepted input per player tick whichever callback arrives first.
- Controls are fresh configured vanilla-item stacks carrying server-only-compatible namespaced markers
  in vanilla custom data, plus display names and glint. Raw items do nothing by default;
  `allowPlainItems` is the explicit opt-in.
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
- `SlotGuardMixin` cancels `AbstractContainerMenu` click, drag, shift-click, hotkey/number-key swap,
  pickup-all, and container `THROW` mutations affecting the two locked control slots while piloting.
  `PlayerDropMixin` separately cancels direct selected-slot Q/drop through `ServerPlayer.drop(boolean)`.
  Unrelated slots and non-pilots are untouched.

### Hold-to-fire gate

The preferred control uses a long-duration, no-animation/no-sound consumable component. While the
control remains in the server-observed using-item state, firing repeats at the configured cooldown.
Automated component and server-observed use-state seams establish the preferred implementation before
activation. The behavior is accepted only when the runnable exact candidate proves a steady four
shots/second on Java and Bedrock through Geyser in the same session.

If the preferred candidate's runtime hold test fails, click-rate heuristics are forbidden. The accepted
fallback changes the default shot cooldown to `0.5` seconds and doubles every heat-per-shot default
(`1.40`, `2.50`, `4.00`, `6.00`,
`1.40`) so time to detonation remains unchanged. The chosen branch and resulting defaults must be
recorded in this contract, independently reviewed, committed, and pushed as a replacement activation
candidate before that candidate is deployed or activation can pass. The preferred path may be the first
reviewed, committed, and pushed activation candidate; its Java and Bedrock result is recorded in the
acceptance evidence without changing the tested candidate bytes.

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
- At the default limit of 100, the sustained exact-crossing shot counts are 143 cold/end, 80 base,
  50 hot, and 34 Nether. The hold-to-fire fallback changes per-shot values as above to preserve elapsed
  time rather than these counts.

## Abilities and effects

Fire outcomes are sealed: fired, detonated/pending detonation, or rejected with `IN_WATER`,
`ON_COOLDOWN` or `NOT_PILOT`. `NOT_PILOT` remains an authorization-boundary result for
automated tests and defensive callback admission, not a passenger-facing input promise. Water, pilot,
and cooldown gates occur before state spend. Ordinary rejection changes no heat/timing. Cooldown and
`NOT_PILOT` are silent; `IN_WATER` maps through `Feedback` to one short action-bar line and distinct
sound.

Normal fire:

- Spawn the real vanilla `EntityTypes.FIREBALL` / `LargeFireball`, not a custom entity or wrapper.
  Construct it with the ridden Happy Ghast as the `LivingEntity` owner, the pilot's normalized view as
  the direction, and strict integer `fire.explosionPower=1` by default. Place it with vanilla ghast
  launch geometry: four blocks forward in the aiming direction and at
  `ghast.getY(0.5) + 0.5`. Use vanilla's shoot event `1016`. The pilot selects the aim, but movement is
  otherwise the untouched vanilla large-fireball constructor/tick path: initial directional movement
  `0.1`, air inertia `0.95`, and per-tick directional acceleration `0.1`. There is no configurable
  speed/spawn-distance override, eager chunk loading, instant ray, or direct-damage fallback.
- A direct entity hit deals vanilla's exact `6.0F` fireball damage before the same projectile performs
  its impact explosion. Default explosion power is vanilla ghast power `1`, represented as an integer
  because the mapped `LargeFireball(Level, LivingEntity, Vec3, int)` constructor requires `int`.
- Impact remains `LargeFireball.onHit`: it reads `GameRules.MOB_GRIEFING`, passes that value as the fire
  flag to `Level.explode`, uses `Level.ExplosionInteraction.MOB`, then discards itself. Happy Artillery
  does not preflight, replace, veto, or replay that path. Consequently vanilla `mobGriefing` rules and
  integrations targeting `EntityTypes.FIREBALL` or `LargeFireball` see the real vanilla entity. A mod
  that additionally requires its owner to be the hostile `Ghast` class may not recognize `HappyGhast`,
  which is an `Animal`; Happy Artillery does not disguise that owner relationship.
- Entity-add failure is reported as a failed effect and does not create a second mutation path.
  In-flight projectiles use vanilla entity persistence across chunk save/unload and server restart;
  the mod adds no projectile reconstruction queue or custom in-flight state.

Overheat:

- The crossing shot commits once. `FuseQueue` accepts both zero and positive fuses. With `fuseTicks=0`,
  its queue-owned submission executes immediately; otherwise persistent pending state blocks further shots
  while that same queue owns the absolute deadline. The same owner re-establishes the task when the ghast
  loads, including after restart.
- At the ghast position, make one best-effort effect pass: attempt the configured power-6 explosion,
  every one of the 24 evenly distributed sphere fireballs at speed 0.4/power 2, and each of up to 24 fire
  candidates within radius 8 without aborting later attempts after a rejection. Occupied or unsupported
  fire candidates are accepted skips; actual rejected explosion, entity-add, fire mutation, or removal
  attempts are reported truthfully as a consumed pass with failures. Once the ghast is loaded, the deadline
  is due, and the persisted rider resolves, the required attachment replacement durably consumes the
  deadline/rider pair before any explosion, fireball, fire, or removal attempt. If that write throws, the
  pass fails loudly before any world effect, and the queue restores exactly one active owner before
  propagating the exception. A later due run can therefore retry the still-pending pair without relying on
  entity replacement. If an unexpected effect exception occurs after consumption, it may propagate while
  the exact task remains the queue's one active owner. The next due run re-reads the empty persisted pair,
  cleans that stale ownership as ignored, and performs no effects; later load callbacks cannot recreate it.
  When `killsGhast=true`, consumption preserves ordinary heat
  and cooldowns, then effects run and ghast discard is attempted last. When `killsGhast=false`, pre-effect
  consumption keeps the ghast alive and resets heat and timing. The rider takes the blast normally.
- `overheat.breaksBlocks` is the sole block-mutation toggle. When false, the explosion has no block
  interaction and fire placement is skipped. Happy Artillery adds no claims adapter, veto, or bypass.
- Event consumption is one-shot in both branches: a successful pass and a consumed pass with rejected
  attempts both leave non-pending state, so stale or duplicate queued tasks cannot repeat effects. With
  `fuseTicks=0`, those outcomes map to `Detonated` and `Rejected(EFFECT_FAILED)` respectively. A pass
  deferred before any effect because the ghast or persisted rider is unavailable retains pending state
  and immediate fire reports `Rejected(EFFECT_FAILED)`.

Cry:

- Enabled pilot input outside water and cooldown plays one `GHAST_SCREAM` at the ghast, hostile source,
  volume 10.0, pitch 0.8. It has no damage, debuff, reveal, or heat effect.
- Cooldown is committed only when the sound effect is accepted and is stored on the ghast. Ordinary
  denial does not start it.

## HUD, lifecycle, and work bounds

- One boss bar per rider/ghast appears on mount and disappears on dismount/entity loss/disconnect/server
  stop. Passengers see it read-only. Progress is `heat / configured heat.limit`, bounded to the boss-bar
  range `[0, 1]`; `heat / 100` is only the default-limit example. Updates send only changed values and
  never a remove-then-add pair.
- Action bar is dirty-checked and limited to every configured four ticks. It shows heat/cooling status.
  `NETHER · NO COOLING` in red has highest priority.
- Boss color is red when that same bounded normalized progress reaches
  `clamp(hud.warningFromPercent / 100, 0, 1)`—equivalently, when heat reaches the configured
  `heat.limit` multiplied by that fraction. Otherwise it is gold in HOT/NETHER, blue in COLD, and green
  in BASE/END. Warning particles use the same configured normalized threshold (85% by default) and are
  sent only to riders in the ghast's region.
- The sole tick driver iterates online players, performs one bounded rider/status reconciliation check
  for each, advances each ridden ghast
  exactly once through its pilot, then updates HUD for pilot and passengers from the resulting snapshot.
  With online non-riders it is not an empty loop: it checks attachment/ride status once per player and
  touches inventory only if a stash requires restoration. There is no loaded-world/entity scan, routine
  inventory scan, or cleanup pass.
- Presentation never classifies again or mutates heat, cooldown, detonation, or inventory state.

## Released defects retained as regression evidence

Tests must prove the rewrite does not restore these released faults:

- lore-substring controls, mutation/loss of player item names or glint, unpersisted duplicate player
  stores, per-tick 41-slot scans, delayed 10 ms repair queues, and global death-drop scans;
- any-passenger/raw-fire-charge ability bypass, duplicate callbacks causing double fire, and silent
  ordinary rejection;
- static UUID maps, wall-clock timing, conflicting biome/heat classifiers, fixed 60-tick cooling,
  hardcoded overheat prediction, eager chunk requests, and instant-ray
  projectile fallback;
- per-tick remove/add boss-bar traffic, routine INFO spam, broad/sampled exception swallowing,
  `/happytest`, old mixins/accessors, and stale state/display handles.

## Manual acceptance boundary

After activation creates runnable exact-candidate bytes, and before release, run the complete controls
abuse list on that same checksum-matched candidate on Java and Bedrock through Geyser: named/full
inventory restoration; player and ghast death; logout; hard server kill; dimension change; every slot
movement/drop route; two-rider pilot authorization plus passenger HUD; creative duplication; plain-item
denial; live reload to different fire/cry slots during an active ride; and Bedrock mount/fire/dismount
without ghost items. The active ride must remain locked to and restore from its persisted original indexes;
the next ride must use the new indexes. Verify vanilla normal-fire `mobGriefing` on/off behavior and real
`LargeFireball` identity, both overheat `breaksBlocks` settings, persistent heat/stash
and an in-flight vanilla fireball across restart, paused
cooldown/fuse while stopped, one-time unload catch-up with no
repeated cooling, single-digit HUD packet updates per rider/second, bounded per-online-player idle work,
and README/jar version agreement. Automated seams may cover these contracts in guarded earlier slices,
but no Java/Bedrock, mod-compatibility, packet-capture, restart, or gameplay evidence is credited until
the complete graph is runnable.

## Minecraft 26.2 mapped behavior evidence

The pinned official-name merged jar used by this checkout establishes the normal-fire boundary:

- `Ghast` initializes its integer `explosionPower` to `1`; its shoot goal calls
  `new LargeFireball(level, ghast, direction.normalize(), ghast.getExplosionPower())`, then places the
  projectile four blocks along the view vector at `ghast.getY(0.5) + 0.5`.
- `LargeFireball(Level, LivingEntity, Vec3, int)` selects `EntityTypes.FIREBALL`; its superclass
  constructor calls `setOwner` with that `LivingEntity`. `HappyGhast` extends `Animal`, so the ridden
  Happy Ghast is a valid direct owner without a new production entity class.
- `AbstractHurtingProjectile` initializes `accelerationPower` to `0.1`, normalizes the constructor
  direction into initial delta movement scaled by `0.1`, and in air updates movement with inertia
  `0.95` and directional acceleration `0.1`.
- `LargeFireball.onHitEntity` applies exactly `6.0F` through the vanilla fireball damage source.
  `LargeFireball.onHit` converts its integer power to float only at `Level.explode`, reads
  `MOB_GRIEFING`, uses `ExplosionInteraction.MOB`, and discards the entity.
- Vanilla save/load already carries projectile `Owner`, `LeftOwner`, and `HasBeenShot`,
  `acceleration_power`, and byte `ExplosionPower`. That vanilla entity persistence is the accepted
  in-flight reload/restart behavior; Happy Artillery owns no duplicate persistence.
