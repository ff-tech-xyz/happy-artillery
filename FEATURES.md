# Happy Artillery 1.2.0 Feature Contract

## Purpose and authority

This is the behavior contract for the 1.2.0 clean implementation. It preserves player-facing behavior
accepted in released documentation, records what the released code actually did (including defects),
and leaves contradictory policy unresolved rather than selecting a cleaner answer without approval.
`ARCHITECTURE.md` assigns owners; it does not change this behavior.

The current branch remains non-deployable structural groundwork: its owner shells and test layout are
not gameplay. Nothing in the scaffold is evidence that a feature below has been implemented.

Terms used below:

- **Accepted** — product behavior the rebuild must provide unless Elijah changes it.
- **Released evidence** — behavior of the `origin/main` / 1.1.2.2 implementation that must be covered
  by characterization or an explicit migration decision; a defect is not silently promoted to policy.
- **Open** — the rebuild must not guess. Resolve the decision and add a regression test before enabling
  the affected path.

## Product and support boundary

- **Accepted:** server-authoritative Fabric mod; clients can join and use it without installing the
  mod. Happy Ghasts become mounted artillery with fire, cry, ammo, heat, cooling, overheat, and rider
  presentation. Gameplay configuration is server-owned.
- **1.2.0 target:** Minecraft 26.2, Fabric Loader >=0.19.3, Fabric API, and Java >=21. The mod id and
  config path remain `happy-artillery` and `config/happy-artillery.json`.
- **Released metadata:** environment `*`, main entrypoint only, MIT license, author `OG Moo-cow`, icon
  `assets/happy-artillery/icon.png`, homepage `https://pyrehaven.xyz`, and source repository
  `https://github.com/ff-tech-xyz/happy-artillery`.
- `origin/main` packages only the `~26.2` runtime. README also claims historical/current publication
  for 26.1.2 and 1.21.11; that is distribution history, not a requirement that 1.2.0 remain multi-target.
- **Accepted runtime state:** ammo, heat, shot timing, environment mode, cry cooldowns, and display
  handles are memory-only. A server/process restart resets them. No world/player NBT contract exists.

## Mounted controls and authorization

### Accepted controls

- Hotbar slot 5 (inventory index 4) is **Fire Control**; slot 6 (index 5) is **Cry Control**.
- Right-clicking Fire Control attempts fire. Right-clicking Cry Control attempts cry. Inputs may come
  from either hand through item use; clicking the ridden ghast also reaches the same action path.
- Control items are routing tokens, not ammunition. Firing does not consume the held Fire Charge or
  decorated item; ammo is the Happy Ghast's separate state pool.
- Released fixed identifiers are `minecraft:happy_ghast`, `minecraft:fire_charge`, and
  `minecraft:ghast_tear`. Two additional fixed strings, `§cFire` and `§bCry`, are unused residue; the
  visible control names come from the decoration path below.
- Empty control slots receive one temporary Fire Charge or Ghast Tear. Occupied slots retain their
  item and receive the corresponding control decoration.

### Released authorization conflict — open

- Per-tick setup, display, and teardown recognize only the Happy Ghast's **first passenger** as driver.
- The released interaction callbacks accept **any passenger** whose current vehicle is a Happy Ghast.
  They do not check first-passenger status.
- A marked item is accepted, but so is any ordinary `minecraft:fire_charge` or
  `minecraft:ghast_tear`; therefore a non-driver passenger can invoke an ability without a managed
  control item.
- **Open:** choose first-passenger-only versus any-passenger authorization, and choose whether raw
  Fire Charges/Ghast Tears remain compatibility inputs. Apply the same policy to setup, interaction,
  display, and cleanup.

## Control-item marking, movement, and cleanup

### Released marker catalog

The released server-readable markers are three hidden/obfuscated lore substrings, in this catalog:
`FireControl`, `CryControl`, `Temporary`. Detection and removal use substring matching, not exact
line/namespace ownership. Other lore lines are retained unless they contain one of those substrings.

- Fire decoration: custom name `§c🔥 Fire Control`, fire marker, and forced glint.
- Cry decoration: custom name `§5👻 Cry Control`, cry marker, and forced glint.
- Mod-created controls also receive the `Temporary` marker.

### Accepted lifecycle

- While an authorized controller rides, empty slots are populated and occupied slots are decorated.
  Inventory movement must converge to one Fire Control in slot 5 and one Cry Control in slot 6.
- A temporary control moved out of its assigned slot is deleted, not retained as a free item.
- On dismount, death, loss/removal of the Happy Ghast, or control-slot movement, temporary controls are
  deleted and surviving player-owned items lose only Happy Artillery-owned presentation.
- Death cleanup includes Happy Artillery-marked item drops created by that player's death. Cleanup must
  not mutate unrelated players', mobs', or pre-existing world items.

### Released cleanup evidence and defects — open where noted

- Driver setup runs every server tick. A separate once-per-second inventory scan removes control
  markers outside indexes 4/5 and deletes temporary marked controls there.
- Any mounted passenger briefly gets markers applied to occupied indexes 4/5, but the same tick's
  driver-only path cleans a non-first passenger's controls. Empty slots are created only for the first
  passenger.
- Indexes 4/5 are treated as valid for either marker. The released code can leave cross-markers or both
  markers on one item; its delayed 10 ms slot-sync queue is scheduled by a screen mixin but never
  processed. “Exactly one correct marker per control” is accepted; the released mechanism is not.
- Normal cleanup deletes a `Temporary` stack. For a non-temporary stack it removes fire/cry marker
  lore, then unconditionally removes the entire custom-name and glint-override components. It does not
  restore values that existed before riding.
- Death is queued after respawn, waits at least 100 ms wall time, then scans **all item entities in all
  loaded worlds** and cleans every marked stack without checking owner or death location. A second
  whole-world drop/mob-hand cleanup method exists but is never registered.
- **Open:** define collision-proof marker ownership and exact restoration of prior name/glint/lore;
  define disconnect, death, drop, mob-hand, and world-item boundaries without global collateral cleanup.

## Fire ability

### Acceptance order and state transition

A fire input is evaluated server-side in this released order:

1. Happy Ghast is in water: apply the released water-cooling attempt and return failure.
2. Current heat is already at/above the direct path's selected limit: fail.
3. Per-ghast shot cooldown is active: fail.
4. Per-ghast ammo is below configured cost: fail.
5. Consume configured ammo, add configured heat, and record shot/cooling/ammo timing once.
6. Produce either the predicted overheat result or one aimed normal shot.

**Accepted:** ordinary denials consume no ammo and do not restart shot timing; cry state is untouched.
The water denial may change heat. A state-accepted shot consumes its state transition exactly once.

### Normal shot

- Launch one `LargeFireball` two blocks forward in X/Z from the Happy Ghast and at its eye height,
  aimed along the invoking rider's normalized view vector with released launch-vector scale `0.5`.
- The Happy Ghast is the owner (the player is only a fallback if the mount is not living), explosion
  power is configurable, and a hostile Ghast shoot sound plays at volume/pitch `1.0/1.0`.
- Released code synchronously requests 16 chunk samples at distances 0 through 120 blocks along the
  128-block forward path after a successful entity add. These are ordinary chunk requests, not durable
  force-load tickets.
- If entity add returns false, released code has already spent ammo/heat/timing and played the sound;
  it then traces 48 blocks against block colliders (ignoring fluids), emits a flame trail, creates a
  fire-making MOB-interaction explosion at the hit/end point, and plays the shoot sound a second time.
- **Open:** retain or remove eager chunk loading; and choose fail/partial outcome versus the instant-ray
  fallback when projectile add fails. Do not keep two world-mutation owners accidentally.

## Ammo and timing

- State is keyed by Happy Ghast UUID. An unseen ghast reads as full.
- Defaults: maximum `200`, cost `1`, regeneration `1` per `5` minutes, shot cooldown `0.25` seconds,
  and post-shot cooling restart delay `0.5` seconds.
- Regeneration adds all complete elapsed intervals, caps at the configured maximum, and is advanced by
  queries plus a global per-server-tick pass over ghasts already present in the ammo map.
- Released `recordShot` resets the ammo-delivery timestamp. Sustained firing can indefinitely postpone
  the next delivery, contradicting the public “regenerating passively” wording.
- **Open:** decide whether firing postpones delivery or regeneration uses an independent cadence. Define
  behavior for a runtime maximum reduced below current ammo and for zero/negative cost/interval values
  together with config validation.

## Heat and environment classification

### Released finite mode catalog and configured defaults

| Mode | Intended selection | Heat/shot | State/display limit | Configured cooling interval |
|---|---|---:|---:|---:|
| `COLD` | Overworld temperature <=0.0 | 0.5 | `coldBiomeOverheatLimit` (60) | 1.5 s per -1 |
| `BASE` | Other Overworld | 1.0 | `baseOverheatLimit` (60) | 3.0 s per -1 |
| `HOT` | Hot Overworld; threshold conflict below | 2.0 | `hotBiomeOverheatLimit` (60) | 6.0 s per -1 |
| `NETHER` | dimension id contains Nether | 3.0 | `netherOverheatLimit` (60) | none when `netherNoCooldown=true`; otherwise base interval |
| `END` | dimension id contains End | 0.5 | **released state/display uses base limit** (60) | cold interval |

Dimension checks precede temperature in the display/state classifier. Heat is represented as a
non-negative decimal. Accepted intent is that the selected mode consistently owns heat gain, limit,
cooling, warnings, and labels.

### Released classification/cooling contradictions — open

- Driver display/state classifies HOT at temperature `>=1.0`; direct fire-limit prediction uses
  `>=1.5`.
- Direct fire-limit prediction maps Nether to the **hot** limit and End to the **cold** limit, while
  state/display use Nether's own limit and End's **base** limit. `addFireballHeat` computes its own
  mode limit, but the caller ignores its overheat result.
- Only the driver display refreshes a ghast's mode. A non-driver/raw-item action may use stale or BASE
  state while its direct limit uses the current world.
- Elapsed-time cooling honors configured mode intervals, but a second loaded-entity scan subtracts one
  heat whenever world game time is divisible by 60 after the restart delay. That second path ignores
  mode and configuration, cools Nether despite `netherNoCooldown=true`, and can combine with the first
  path. A third configurable cooling method exists but is never called.
- **Open:** select HOT threshold; select End limit; use Nether's own limit; and replace competing paths
  with one classification/cooling schedule. Public/configured outcomes (including no passive Nether
  cooling by default) are the accepted intent, not the accidental fixed 60-tick subtraction.

## Water cooling

- Fire and cry use `Entity.isInWater()` as the released water test. Both are denied in water; only a
  fire attempt invokes water cooling.
- Default water rate is `8` heat per whole elapsed second and floor is `5`. The elapsed anchor is the
  shared last-heat-update timestamp; sub-second attempts do nothing.
- Released clamping uses `max(floor, current - amount)`, so a zero-heat ghast can be raised to 5 after
  enough elapsed time. Entering water alone does not cool; repeated fire attempts drive cooling.
- **Open:** decide attempt-driven versus continuous cooling, exact water predicate, and whether the
  floor is only a lower bound for decreasing positive heat. Water denial never spends ammo or records
  a shot.

## Overheat

- **Accepted spectacle:** the threshold-triggering shot spends ammo/adds heat/records timing once, then
  creates a configurable main explosion two blocks forward at mount eye height, 48 outward large
  fireballs in deterministic golden-spiral order, a hostile Ghast shoot sound at `2.0/0.8`, and up to
  15 random supported fire placements within radius 5 and roughly +/-1 Y.
- Main explosion uses TNT interaction, null source, configurable power (default `4.0`), and configurable
  fire creation (default `true`). Sphere fireballs use normal fireball power (default `2`) and the
  Happy Ghast as owner; individual add failures are ignored.
- Released prediction is `currentHeat + 1 >= directLimit`, regardless of configured heat per shot.
  Actual heat addition uses the configured mode amount. A configured amount greater than 1 can cross
  the state limit without triggering spectacle; the next input is then permanently denied.
- Released overheat does not reset heat. At or above the direct limit all later fire inputs are denied,
  so recovery depends on whatever passive/water cooling happens to run.
- **Open:** use one configured threshold calculation; define post-overheat heat/recovery; and define
  partial failure semantics for explosion, sphere projectiles, and fire placement before enabling it.

## Cry ability

- A valid cry plays `GHAST_SCREAM` at the Happy Ghast position, hostile sound source, pitch `0.8`, and
  configured volume (default `3.0`). It consumes no ammo and adds no heat.
- Water denies cry. Cooldown is keyed by invoking player UUID, defaults to `10.0` seconds, and is
  recorded immediately before world lookup/sound playback.
- **Accepted:** ordinary denial does not start cooldown. **Released partial-failure evidence:** a
  world-access or sound exception after `recordCry` can consume cooldown without producing sound.

## Rider presentation

- The first passenger receives one per-player/per-ghast heat boss bar while recognized as driver.
  Progress is `min(1, heat/limit)` and title is `Heat: current/limit`, with heat rounded to nearest 0.5
  and a trailing `.0` omitted.
- Color priority: red at <=5 heat remaining; yellow at <=15; otherwise yellow for HOT/NETHER, blue for
  COLD, and green for BASE/END. Thus released End presentation is green, not cold-blue.
- Every tick the action bar shows `Ammo: current/max`: green at >=100, gold at 50-99, red below 50.
  After the restart delay it appends no/fast/slow/normal cooling for NETHER/COLD/HOT/other. Released
  End says **Normal cooling** even though its state uses the cold interval.
- It appends `OVERHEATING!` at <=5 remaining or `⚠ Warning` at <=15. At <=10 remaining it emits
  FIREWORK particles, `(11 - remaining) * 2` per tick, at the mount's eye region.
- Presentation reads state; it must not mutate combat policy. Released display currently updates the
  ghast mode and therefore influences later combat.
- Bars are removed when a connected player is observed not driving or the displayed vehicle is
  null/removed. There is no explicit disconnect/server-stop/entity-removal eviction; static maps can
  retain stale state. Exact lifecycle cleanup is open.

## Configuration contract

`config/happy-artillery.json` has exactly these 24 released fields and defaults:

| Field | Default | Released consumer |
|---|---:|---|
| `fireballAmmoMax` | `200` | initial/capped per-ghast ammo |
| `fireballAmmoCost` | `1` | accepted fire/overheat cost |
| `ammoDeliveryIntervalMin` | `5` | minutes per regenerated ammo |
| `shootCooldownSeconds` | `0.25` | per-ghast fire interval |
| `fireRestartDelaySeconds` | `0.5` | delay before passive cooling |
| `cryCooldownSeconds` | `10.0` | per-player cry interval |
| `baseOverheatLimit` | `60` | BASE and released END state/display limit |
| `baseHeatPerShot` | `1.0` | BASE heat |
| `baseCoolIntervalSeconds` | `3.0` | BASE; Nether when cooling enabled |
| `hotBiomeOverheatLimit` | `60` | HOT and released direct Nether limit |
| `hotBiomeHeatPerShot` | `2.0` | HOT heat |
| `hotBiomeCoolIntervalSeconds` | `6.0` | HOT cooling |
| `coldBiomeOverheatLimit` | `60` | COLD and released direct End limit |
| `coldBiomeHeatPerShot` | `0.5` | COLD/END heat |
| `coldBiomeCoolIntervalSeconds` | `1.5` | COLD/END cooling |
| `netherOverheatLimit` | `60` | NETHER state/display limit |
| `netherHeatPerShot` | `3.0` | NETHER heat |
| `netherNoCooldown` | `true` | intended NETHER passive-cooling switch |
| `waterCooldownRate` | `8` | heat per whole elapsed second |
| `waterCooldownLimit` | `5` | released water clamp floor |
| `fireballExplosionPower` | `2` | normal/fallback/sphere projectile power |
| `overheatExplosionPower` | `4.0` | main overheat explosion power |
| `overheatExplosionCreatesFire` | `true` | main overheat fire flag |
| `cryVolume` | `3.0` | successful cry volume |

Released load/save semantics:

- Load once during mod initialization. Missing file, empty input/JSON `null`, or omitted fields use
  Java defaults. Unknown fields are ignored and disappear on rewrite.
- Every successful/defaulted load rewrites a pretty-printed file containing all known fields. A read
  `IOException` logs a warning, uses defaults, then attempts that rewrite.
- Malformed/type-invalid JSON is not caught by the I/O-only handler and can abort initialization.
  A save `IOException` is logged and startup continues with in-memory settings.
- No ranges, finiteness, signs, cross-field relationships, or runtime reload are validated. Gson's
  released coercion/duplicate-key behavior has not been accepted as a 1.2.0 schema promise.
- **Open:** validation and failure policy. Do not silently clamp, coerce, repair, or default malformed
  configured values without an accepted decision.

## Lifecycle, failure, commands, and operational residue

- Released callbacks: two item/entity interaction callbacks; two end-server-tick callbacks; one
  after-respawn callback; and one inventory-click mixin. The rebuild must register one owner per
  accepted behavior and remove competing/dead paths.
- Gameplay maps are static/process-wide, UUID keyed, shared across dimensions, and never evicted.
  Loaded-world entity and item scans can be global. Bounded iteration and cleanup are required design
  decisions, but no persistence or eviction behavior is implicitly accepted.
- Interaction `PASS` means unrelated input; expected ability denial returns `FAIL`; state-accepted
  fire/cry returns `SUCCESS`. Released fire state is committed before world mutation and has no
  rollback. Runtime exceptions can therefore leave partial state/world effects.
- Tick/inventory/display paths catch broad exceptions and continue. The second global tick callback
  catches `Throwable` and logs only a random ~0.1% sample. This is released diagnostic behavior, not
  an accepted silent-failure policy.
- Released `/happytest` is an unrestricted, player-only debug command. It can create/decorate controls
  in indexes 4/5 regardless of riding state, reports details to the player, and logs inventory data.
  It has no production feature or permission contract and must not ship in 1.2.0 unless explicitly
  accepted. No other commands or permission nodes exist.
- Released routine logging includes per-tick INFO setup messages for each driver. 1.1.2.2 removed only
  one routine cleanup-slot inspection INFO line; actual cleanup messages remain. Per-tick debug/log
  residue is not accepted presentation.
- Unused released residue includes duplicate injected player inventory state with no persistence,
  empty entity/state shells, unused imported callbacks, unprocessed delayed slot-sync state, unused
  position state, and unregistered cleanup/cooling helpers. None is a feature or compatibility API.

## Open decisions that block implementation

1. First passenger versus any passenger; marked-only versus raw-item compatibility.
2. Exact marker ownership/restoration and inventory/drop/death/disconnect cleanup boundaries.
3. HOT threshold, End limit/presentation, Nether limit, and a single environment/cooling owner.
4. Ammo delivery cadence relative to firing.
5. Attempt-driven versus continuous water cooling, water predicate, and non-increasing floor behavior.
6. Configured overheat prediction and post-overheat recovery.
7. Projectile-add failure, eager chunk requests, and partial world-mutation outcomes.
8. Config validation/coercion/startup failure policy.
9. Runtime state/boss-bar eviction and bounded loaded-world work.

## Provenance and audit notes

- Primary released baseline: `origin/main` at `8da306889e6b156f38a1061fc0f90ddbd0f5aedf`
  (merged 1.1.2.2 implementation and metadata).
- Release history checked: `v1.1.2` (tag object `d3101428f9565cd16e609c2ce22c161d486adc4b`,
  commit `2545d27`) and `v1.1.2.2` (`4eae0d63bf2d160ed15845d6c9357d2b822c9acd`).
  The only gameplay-source delta after v1.1.2 is removal of one routine cleanup log line.
- Public evidence checked: released/current README, CHANGELOG, Fabric metadata, Gradle target/version,
  and the full removed Java/mixin implementation via `git show`.
- Mechanical catalog audit: 24/24 config fields/defaults, 5/5 ordered `BiomeType` values
  (`COLD, BASE, HOT, NETHER, END`), 3/3 lore marker values, and 5/5 fixed identifiers/names were
  extracted from released source and represented here. The catalog names/counts are completeness
  evidence; their contradictory consumers remain explicitly documented above.
