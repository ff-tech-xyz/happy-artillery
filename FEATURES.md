# Happy Artillery 1.2.0 Feature Contract

## Authority and status

This document is the settled behavior contract for the ground-up 1.2.0 rewrite. The supplied design
specification overrides the earlier G1-G9 questions. Released 1.1.2.2 behavior appears below only when
it is useful regression evidence; defects are not compatibility requirements.

The current branch contains a runnable 1.2.0 candidate. `MIGRATION_PLAN.md` records the completed
rebuild; the current audit-repair execution intake is
`.hermes/plans/2026-08-31_034734-happy-artillery-audit-repair-and-preset-removal.md`.
The candidate is not release-ready until that intake's exact-head review, deployment, and manual
acceptance gates pass.

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

- The complete smallest tree is **thirteen production Java files**: the eleven accepted non-mixin owners
  plus `PlayerDropMixin` and `ExternalContainerMixin`. Mapped Minecraft 26.2 evidence proves that
  `ServerPlayer.drop(ItemStack, boolean, boolean):ItemEntity` at `RETURN` covers direct Q, cursor drops,
  menu `THROW`, creative drops, and ordinary/offhand/equipment death drops. External chest/container
  insertion does not reach that method, so `ExternalContainerMixin` observes the single post-mutation
  `Slot.setChanged():void` boundary at `HEAD`. `Controls` inspects `Slot.getItem()` and `Slot.container`,
  preserves only owner-matching player `Inventory` destinations, and consumes marked controls from every
  other container. This covers ordinary placement, `QUICK_MOVE` empty/merge, number/offhand swaps, and
  `QUICK_CRAFT` without reproducing `AbstractContainerMenu.doClick`. `PICKUP_ALL` is inbound
  slot-to-cursor collection, not outbound chest insertion. Both mixins delegate policy to `Controls`;
  there is no `DeathDropMixin` or predictive `SlotGuardMixin` in the proposed tree.
- Persistent timing uses the Overworld's saved `gameTime` as the one canonical tick domain. It advances
  only with server ticks, survives restart without interpreting a new process-local counter, and provides
  one comparable value to every loaded dimension. No duration advances while the server is stopped.
- `GhastState` therefore stores heat plus its last-advanced game tick, firing-window end tick,
  independent per-ghast fire-ready and cry-ready ticks, and a paired pending-detonation deadline plus
  detonating-rider UUID. The abbreviated sample in the specification is not the complete record contract.
- `Hud` owns bounded process-local boss-bar handles. Serializable HUD dirty-check values live in the
  persistent `RiderState`; live packet objects do not become persistence data. One bounded active-pilot
  inventory snapshot is created by `Controls` and shared with admission and HUD each tick.
- `Components` is the sole fire/cry marker codec/helper owner. It stores the exact distinction in a
  namespaced tag inside vanilla `CUSTOM_DATA`, preserves unrelated custom data, and creates no custom
  synchronized registry entry. `HappyArtillery` has no component-registration seam.
- Passengers receive the same ghast heat/status HUD read-only. Only the controlling first passenger
  receives marked controls, advances state, or triggers abilities. No passenger action or feedback
  input is promised while marked controls are pilot-only and plain items are disabled.
- Startup config has a strict transaction boundary. A missing file is created from validated defaults,
  and missing known keys inherit defaults before a successful full-schema rewrite. An existing file
  with malformed JSON, unknown or removed keys, invalid identifier syntax, non-finite
  number, impossible range, or cross-field violation aborts startup loudly; it is never replaced with
  defaults. Unknown keys are rejected recursively with their full path. Item identifier syntax is checked
  while parsing, and configured registry entries are resolved at the later server lifecycle point after
  mod initializers. `/ha reload` parses, validates, and resolves a candidate before the atomic swap:
  failure reports the error and leaves both the prior valid in-memory value and invalid file untouched.
  Every successful load rewrites the complete known schema.

## Configuration

Config is feature-grouped nested immutable values held in one `AtomicReference` and read at call time.
Validated defaults are the only baseline; an operator supplies individual known-key overrides. Every
successful load rewrites the complete schema. A root `preset` key is a removed setting: startup or
reload fails transactionally, preserving the active object and existing file bytes.
`/ha reload` requires gamemaster permission level 2.

Defaults (seven top-level groups, 36 declared settings and 47 scalar leaves):

| Group | Keys and values |
|---|---|
| controls | `fireItem=minecraft:fire_charge`, `cryItem=minecraft:ghast_tear`, `holdToFire=true`, `allowPlainItems=false` |
| fire | `shotCooldownSeconds=0.25`, `explosionPower=1` (strict integer) |
| heat | `limit=100.0`, `firingWindowSeconds=1.0`, `cold=(0.70,1.0)`, `base=(1.25,0.6)`, `hot=(2.00,0.5)`, `nether=(3.00,0.0)`, `end=(0.70,1.0)`, `coldMaxTemperature=0.3`, `hotMinTemperature=1.0`, `unknownDimensionUsesTemperature=true` |
| water | `coolPerSecond=5.0`, `floor=0.0`, `blocksFiring=true` |
| overheat | `fuseTicks=0`, `explosionPower=6.0`, `fireballCount=24`, `fireballSpeed=0.4`, `fireballPower=2` (strict integer), `fireAttempts=24`, `fireRadius=8.0`, `killsGhast=true`, `breaksBlocks=true` |
| cry | `enabled=true`, `volume=10.0`, `cooldownSeconds=10.0` |
| hud | `bossBar=true`, `actionBar=true`, `refreshTicks=4`, `warningFromPercent=85`, `cooling=(noCoolingText=NO COOLING, noCoolingColor=RED, slowMaxPerSecond=0.5, slowColor=GOLD, normalMaxPerSecond=1.0, normalColor=GREEN, fastColor=BLUE)` |

The declared-setting count is the sum of direct members in the seven groups
(`4 + 2 + 10 + 3 + 9 + 3 + 5 = 36`); recursively expanding five heat profiles and `hud.cooling`
produces 47 scalar leaves. `hud.cooling` thresholds are finite, non-negative, and strictly increasing
(`slowMaxPerSecond < normalMaxPerSecond`); colors are valid vanilla boss-bar color names.

## Persistent state and time

- Every persisted deadline/anchor is expressed in saved Overworld `gameTime`, never
  `server.getTickCount()` and never wall time. The driver reads that clock once per server tick and passes
  the value through context. Restart resumes in the same tick domain; stopped time does not count.
- `GhastState` is an immutable persistent attachment containing `heat`, `heatAnchorTick`,
  `firingWindowEndTick`, `fireReadyTick`, `cryReadyTick`, optional `detonateAtTick`, and optional
  `detonatingRiderId`. The two detonation fields are present or absent together. The fire cooldown is
  not inferred from the firing window. Updates replace the attachment value.
- `RiderState` is an immutable persistent player attachment containing only the ridden-ghast UUID,
  `lastHandledTick`, and serializable HUD dirty-cache data. It stores no ItemStack, inventory index,
  restoration data, or second control-state model.
- `Heat.advance` applies cooling only over the not-yet-accounted interval and always moves
  `heatAnchorTick` to `now`. Passive cooling uses
  `max(0, now - max(heatAnchorTick, firingWindowEndTick))`; water cooling instead uses
  `max(0, now - heatAnchorTick)` and therefore keeps its ordering priority. A shot first advances to
  `now`, adds heat, and extends `firingWindowEndTick`. Repeated driver calls cannot subtract the same
  elapsed interval twice. An unloaded ghast catches up once on return; Nether heat remains unchanged.

- Cry admission compares `now` with `cryReadyTick`; accepted cry sets a new absolute game-time deadline.
- A pending fuse detonates once when `now >= detonateAtTick`. `Abilities.FuseQueue` is the only fuse
  scheduler. Every task stores only ghast UUID, absolute deadline, and rider UUID; it never retains a live
  entity, level, or passenger graph. Execution resolves the currently loaded ghast by UUID, then re-reads
  and matches the persisted deadline/rider pair before acting. `fuseTicks=0` uses the same owned due-task
  routine as later deadlines. Ghast-load and bounded player-availability callbacks wake UUID ownership;
  neither callback installs another scheduler, and rider dismount does not cancel the fuse.
- A task whose ghast is unavailable remains bounded UUID data for lifecycle wake-up. A resolved ghast with
  no attachment or a missing/mismatched persisted pair is stale: the task is ignored and its queue
  ownership is removed without effects. Each due task is isolated so a stale or unexpectedly failing task
  cannot prevent unrelated due tasks from being attempted in the same batch. If an unexpected exception
  leaves the persisted pair executable, the queue retains exactly one UUID owner and reports the first
  error after the batch, but does not retry it from an unconditional 20 Hz loop; only a lifecycle wake-up
  or an explicitly rescheduled deadline makes it runnable again. Exceptions are never swallowed.
  Alternating riders cannot shorten cry cooldown, and restart cannot reset or lengthen either deadline.
- Entity attachment removal owns ghast-state eviction and makes matching queued ownership stale on its next
  resolution. Rider ride identity survives only for bounded reconciliation and cleanup; typed HUD handles
  are process-local and explicitly removed through the one presentation path.

## Controls and inventory safety

- The Happy Ghast's controlling first passenger is the pilot. Only the pilot receives control items
  and can fire or cry. Other riders see the HUD read-only; they have no safe action input while plain
  items are disabled, so the product does not promise unreachable `NOT_PILOT` feedback.
- On becoming the pilot, `Controls` searches insertion candidates in exact order: hotbar indexes `0..8`,
  then main-inventory indexes `9..35`. Armor and offhand are not allocation destinations. It reserves two
  empty candidates before writing either control. With fewer than two, it writes nothing, preserves every
  inventory byte, records the ride as reconciled to prevent retries/spam, and sends exactly one direct
  message: `Controls need 2 free slots.` in red.
- Controls are fresh configured vanilla-item stacks carrying namespaced markers in vanilla custom data,
  plus display names and glint. Each marker contains control type, owner UUID, and ridden-ghast UUID.
  A marked stack authorizes only its owner during that exact ride. Raw configured items do nothing by
  default; `allowPlainItems=true` is admission-only. Plain items are never deleted and never satisfy the
  generated-control HUD presence check.
- A matching generated control works only while the active pilot holds it in main hand or offhand. Its
  hotbar index is irrelevant. A control in main inventory is retained but cannot activate until moved to
  a hand through ordinary Minecraft inventory behavior. Both hands and ridden-entity/item-use callbacks
  route to one handler; `lastHandledTick` permits at most one accepted input per player tick whichever
  callback arrives first.
- Controls move freely among the owning player's hotbar, main inventory, and offhand. There are no fixed
  slots, stashes, restoration writes, or locked slots. No mount, dismount, reload, death, or recovery path
  overwrites an ordinary ItemStack.
- `Controls` creates one bounded snapshot per active pilot tick over inventory indexes `0..35` plus
  offhand index `40`, and shares it with held admission and HUD. It distinguishes matching active,
  stale/foreign, inventory-only, and missing controls. It never scans menus, containers, worlds, item
  entities, or other players to locate a missing control.
- Missing controls are not regenerated during the same ride. Dismounting and remounting is the only
  regeneration path. Dismount, loss of pilot status, disconnect recovery, dimension transition, and
  ghast removal remove only marked controls owned by that rider/ride and clear ride identity.
- `PlayerDropMixin` observes the returned `ItemEntity` from the three-argument `ServerPlayer.drop`
  boundary and discards marked control drops. This covers direct Q, cursor and menu `THROW`, creative,
  and ordinary/offhand/equipment death drops while ordinary drops remain vanilla. Player death lets
  vanilla empty the inventory; there is no pre-drop restoration.
- `ExternalContainerMixin` observes `Slot.setChanged()` after menu mutation. At that boundary,
  `Controls` first returns for an empty stack or one without vanilla `CUSTOM_DATA`, before any marker
  decode or custom-data copy. It preserves an owner-matching control only when the destination container
  is that owner's player `Inventory`; it consumes a marked control in every other container through the
  menu mutation owner with `Slot.set(ItemStack.EMPTY)`, never by changing the observed stack's count.
  Ordinary placement,
  `QUICK_MOVE` empty/merge, number/offhand swaps, and `QUICK_CRAFT` are covered without predicting or
  cancelling `doClick`. `PICKUP_ALL` is inbound slot-to-cursor collection, not outbound container
  insertion. Same-player movement and ordinary items remain vanilla.
- Because 1.2.0 is unreleased, no legacy-stash compatibility alias or restoration layer is added. The
  disposable test world must be reset, or otherwise proven free of an active old stash, before the new
  `RiderState` codec is deployed.

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

Fire outcomes are sealed as `Fired`, `Detonated`, `DetonationPending`, or `Rejected`. Rejection reasons
are exactly `IN_WATER`, `ON_COOLDOWN`, `NOT_PILOT`, `DETONATION_PENDING`, and `EFFECT_FAILED`.
`DetonationPending` is the accepted crossing shot whose future fuse now owns persisted pending state;
`Rejected(DETONATION_PENDING)` is a later fire attempt refused while that state remains pending.
`NOT_PILOT` remains an authorization-boundary result for automated tests and defensive callback
admission, not a passenger-facing input promise. Water, pilot, pending, and cooldown gates occur before
state spend. Ordinary rejection changes no heat/timing. Cooldown, pending, and `NOT_PILOT` are silent;
`IN_WATER` maps through `Feedback` to one short action-bar line and distinct sound. `EFFECT_FAILED`
reports an accepted effect path whose observable launch/detonation mutation failed; it has no fallback
effect path.

Normal fire:

- Spawn the real vanilla `EntityTypes.FIREBALL` / `LargeFireball`, not a custom entity or wrapper.
  Construct it with the ridden Happy Ghast as the `LivingEntity` owner, the pilot's normalized view as
  the direction, and strict integer `fire.explosionPower=1` by default. Compute one launch origin along
  that normalized aim beyond the union of the Happy Ghast and complete passenger-tree collision bounds,
  expanded by the spawned fireball's collision extents plus a small clearance. The resulting fireball
  AABB must be disjoint from every ridden entity AABB for upward, downward, horizontal, and diagonal aim.
  Use vanilla's shoot event `1016`. The pilot selects the aim, but movement is otherwise the untouched
  vanilla large-fireball constructor/tick path: initial directional movement `0.1`, air inertia `0.95`,
  and per-tick directional acceleration `0.1`. There is no configurable speed/spawn-distance override,
  eager chunk loading, instant ray, or direct-damage fallback.
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
  candidates within radius 8 without aborting later attempts after a rejection. Each sphere direction
  uses the same authoritative launch calculation as normal fire, against one union of the ghast and its
  complete passenger-tree collision bounds, so every real fireball AABB starts disjoint from that union.
  The 24 directions and spawn positions remain distinct. Occupied or unsupported
  fire candidates are accepted skips. The explosion API supplies no rejection result: invoke it once and
  do not count a fictitious failure. Rejected entity insertion or fire mutation is reported truthfully
  as a consumed pass with failures. Once the ghast is loaded, the deadline
  is due, and the persisted rider resolves, the required attachment replacement durably consumes the
  deadline/rider pair before any explosion, fireball, fire, or removal attempt. If that write throws, the
  pass records the first exception before any world effect, retains exactly one dormant UUID owner, and
  continues attempting unrelated due tasks before reporting. A later lifecycle wake can retry the
  still-pending pair without relying on entity replacement. If an unexpected effect exception occurs
  after consumption, the batch attempts its other due tasks, retains one dormant UUID owner, and reports
  the first exception. The task is not retried every tick; its next lifecycle wake re-reads the empty pair,
  removes stale ownership, and performs no effects. Later load callbacks cannot recreate consumed work.
  When `killsGhast=true`, consumption preserves ordinary heat and cooldowns, then effects run and ghast
  discard occurs last through the synchronous removal owner; no fictional removal-rejection branch is
  reported. When `killsGhast=false`, pre-effect consumption keeps the ghast alive and resets heat and
  timing. A geometry or entity-add rejection is an explicit failed effect with no fallback launch path;
  genuinely impossible non-finite internal state remains loud. The rider takes the blast normally.
- `overheat.breaksBlocks` is the sole block-mutation toggle. When false, the explosion uses a
  non-terrain interaction and fire placement is skipped: it changes no terrain and starts no fire. When
  true, the explosion uses `ExplosionInteraction.MOB`, so vanilla `mobGriefing` decides terrain damage,
  and configured fire placement is attempted. Happy Artillery adds no claims adapter, veto, or bypass.
- Event consumption is one-shot in both branches: a successful pass and a consumed pass with rejected
  attempts both leave non-pending state, so stale or duplicate queued tasks cannot repeat effects. With
  `fuseTicks=0`, those outcomes map to `Detonated` and `Rejected(EFFECT_FAILED)` respectively. A pass
  deferred before any effect because the ghast or persisted rider is unavailable retains pending state
  and immediate fire reports `Rejected(EFFECT_FAILED)`.

Cry:

- Enabled pilot input outside water and cooldown plays one `GHAST_SCREAM` at the ghast, hostile source,
  volume 10.0, pitch 0.8. It has no damage, debuff, reveal, or heat effect.
- Sound playback supplies no rejection result. The cooldown is committed after the infallible sound call
  completes and is stored on the ghast. Ordinary admission denial does not start it.

## HUD, lifecycle, and work bounds

- One boss bar per rider/ghast appears on mount and disappears on dismount/entity loss/disconnect/server
  stop. Passengers see it read-only. Progress is `heat / configured heat.limit`, bounded to the boss-bar
  range `[0, 1]`; `heat / 100` is only the default-limit example. Updates send only changed values and
  never a remove-then-add pair.
- The action bar has its own configured four-tick cadence, independent of boss-bar and warning-particle
  convergence. A fresh-session control warning may appear immediately; after that, changed or unchanged
  control warnings and active status send no more than once per cadence. A due particle or dirty boss
  value cannot consume that action-bar tick. The separate boss/particle work uses a minimum five-tick
  cadence and still converges during continuous fire, keeping total presentation traffic below ten
  packets in every sliding 20-tick window. Pilot control status has exact
  priority: if either generated control is absent, show
  `CONTROL MISSING · DISMOUNT AND REMOUNT`; otherwise, if either is in main inventory rather than a
  hand-accessible hotbar/offhand location, show `CONTROL IN INVENTORY` or `CONTROLS IN INVENTORY` as
  appropriate; otherwise show the normal heat/cooling line. Missing wins when one control is absent and
  the other is merely in inventory. Control warnings are pilot-only and are delivered on the next eligible
  action-bar update without waiting behind another presentation channel. Passengers retain heat/status
  presentation.
- The integration/heat context computes one typed presentation mode for the tick and passes it through the
  sole typed HUD path. Precedence is explicit: active water cooling produces
  `COOLING(water.coolPerSecond)`; otherwise an active firing window produces `FIRING`; otherwise the mode
  is `COOLING(currentProfile.coolPerSecond)`. `Hud` receives that mode and rate together and does not infer
  firing state, biome, dimension, or cooling policy. A zero rate in `COOLING` mode displays configured
  `hud.cooling.noCoolingText` with `noCoolingColor`. Every positive cooling rate displays the
  exact shape `COOLING <rate>/s` with deterministic numeric formatting: at or below
  `slowMaxPerSecond` it uses `slowColor`, at or below `normalMaxPerSecond` it uses `normalColor`, and above
  that it uses `fastColor`. Threshold equality belongs to the lower band.
- Boss color is red when that same bounded normalized progress reaches
  `clamp(hud.warningFromPercent / 100, 0, 1)`—equivalently, when heat reaches the configured
  `heat.limit` multiplied by that fraction. Otherwise firing retains its firing theme and cooling uses
  the effective-rate theme above; biome identity never hardcodes HUD text or color. Warning particles use
  the same configured normalized threshold (85% by default) and are sent only to riders in the ghast's
  region.
- The sole tick driver groups ridden players by ghast, performs one bounded rider/status reconciliation
  check per online player, obtains one bounded `0..35` plus offhand inventory snapshot per active pilot,
  advances each ridden ghast exactly once through its pilot, and shares the post-transition snapshot with
  pilot admission and HUD. A ridden group without a controlling pilot has every rider HUD removed that
  tick. Non-riders receive only the bounded attachment/ride-status check and scoped cleanup when ride
  identity requires it. There is no loaded-world/entity/container scan, second pilot inventory scan, or
  global cleanup pass. Input callbacks inspect only the acting player and current ridden ghast; HUD fan-out
  waits for the normal tick.
- Presentation never classifies again or mutates heat, cooldown, detonation, or inventory state.
  Production removal, disconnect teardown, and server clear are thin typed calls through the same tested
  session implementation; stored viewer identity owns handle removal, with no parallel teardown path.

## Released defects retained as regression evidence

Tests must prove the rewrite does not restore these released faults:

- lore-substring controls, mutation/loss of player item names or glint, unpersisted duplicate player
  stores, destructive stash restoration, fixed-slot locks, copied menu-click prediction, delayed 10 ms
  repair queues, and global death-drop scans;
- any-passenger/raw-fire-charge ability bypass, duplicate callbacks causing double fire, and silent
  ordinary rejection;
- static UUID maps, wall-clock timing, conflicting biome/heat classifiers, fixed 60-tick cooling,
  hardcoded overheat prediction, eager chunk requests, and instant-ray
  projectile fallback;
- per-tick remove/add boss-bar traffic, routine INFO spam, broad/sampled exception swallowing,
  `/happytest`, old mixins/accessors, and stale state/display handles.

## Manual acceptance boundary

Before release, run the current audit-repair intake's final exact-head and gameplay handoff gates on the
same checksum-matched candidate on Java and Bedrock through Geyser. Acceptance must cover atomic
first-two-free allocation; zero/one-free refusal with no writes; arbitrary
hotbar and offhand held use; inventory-only and missing-control HUD priority; same-player movement; direct
Q, cursor/menu `THROW`, creative, ordinary/offhand/equipment death-drop consumption; external-container
placement, `QUICK_MOVE` empty/merge, number/offhand swap, and `QUICK_CRAFT` consumption at the proven
post-mutation boundary; inbound `PICKUP_ALL` slot-to-cursor behavior; no same-ride regeneration; dismount/
remount regeneration without overwrite; scoped cleanup across logout, hard stop, dimension change, and
ghast removal; two-rider pilot authorization plus passenger HUD; plain-item admission-only behavior; and
Bedrock mount/fire/dismount without ghost items. Verify vanilla normal-fire `mobGriefing` on/off behavior
and real `LargeFireball` identity, both overheat `breaksBlocks` settings, persistent heat
and an in-flight vanilla fireball across restart; paused cooldown/fuse while stopped; one-time unload
catch-up with no repeated cooling; single-digit HUD packet updates per rider/second; bounded per-online-
player idle work; effective-rate zero/slow/normal/fast/water HUD text and color; UUID-only fuse isolation;
external slot-owned removal; and README/jar version agreement. Automated seams may cover these contracts
earlier, but no Java/Bedrock, mod-compatibility, packet-capture, restart, or gameplay evidence is credited
until the complete graph is runnable.

## Minecraft 26.2 mapped behavior evidence

The pinned official-name merged jar used by this checkout establishes the normal-fire boundary:

- `Ghast` initializes its integer `explosionPower` to `1`; its shoot goal calls
  `new LargeFireball(level, ghast, direction.normalize(), ghast.getExplosionPower())`, then uses a fixed
  vanilla placement four blocks along the view vector at `ghast.getY(0.5) + 0.5`. Happy Artillery
  intentionally replaces only that placement with the collision-clear ridden-entity calculation above.
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
