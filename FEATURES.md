# Happy Artillery Feature Contract

## Status

This file preserves the product behavior required for Happy Artillery's next implementation. The
current branch is structural groundwork only: the owner classes named in `ARCHITECTURE.md` compile,
but no controls, abilities, state, displays, or server callbacks are active. The five test files
establish the accepted suite layout; they do not yet contain executable gameplay tests. Do not deploy
this groundwork as a working release.

## Runtime scope

- Happy Artillery is server-authoritative. A Fabric server runs every gameplay rule and inventory
  mutation; joining players do not need the mod on their clients.
- The released control setup gives controls only to the Happy Ghast's first passenger, but the released
  interaction callback can accept another mounted passenger. The rebuild must choose one authorization
  policy before controls are implemented.
- The rebuild targets Minecraft 26.2, Fabric Loader 0.19.3 or newer, Fabric API, and Java 21.
- Gameplay state is process-memory state keyed by UUID unless a later accepted feature explicitly
  introduces persistence. Restarting the server resets ammo, heat, and cooldown state.

## Rider controls

- While controlling a Happy Ghast, hotbar slot 5 (inventory index 4) is Fire Control and slot 6
  (inventory index 5) is Cry Control.
- Right-clicking Fire Control attempts the fireball ability. Right-clicking Cry Control attempts the
  cry ability. The released interaction path also accepts an ordinary Fire Charge or Ghast Tear held
  by a mounted passenger even without a control marker; retaining that compatibility is an open decision.
- If a control slot is empty, Happy Artillery creates a temporary Fire Charge or Ghast Tear for that
  slot. The temporary item is visibly named for its control and has an enchantment glint.
- If a control slot already contains an item, Happy Artillery decorates that item as the relevant
  control without consuming or replacing it.
- Released control markers are server-readable lore data. Hidden player mixin state and a second
  inventory copy existed as unused residue and are not accepted features.
- Moving an item into or out of a control slot while still riding updates the marker after the
  inventory action completes. At most one fire marker and one cry marker may remain active for that
  rider.
- On dismount, death, loss of the Happy Ghast, or movement outside the control slots, the released code
  deletes temporary controls and removes its marker, custom name, and glint from ordinary items. It does
  not preserve a pre-existing custom name or glint; safe restoration is an open rebuild decision.
- Cleanup covers the rider's inventory and Happy Artillery-marked drops created by death. Temporary
  controls that were moved out of their assigned slots must not survive as ordinary inventory items.

## Fireball ability

- A valid fire input is refused while the Happy Ghast is submerged, while its shot cooldown is active,
  when it lacks the configured ammo cost, or when it is already at its current overheat limit.
- A normal accepted shot consumes the configured ammo cost, adds the heat amount for the current
  environment, records the shot cooldown/restart delay, and launches one aimed large fireball from in
  front of the Happy Ghast.
- The fireball uses the Happy Ghast as its owner when possible, travels along the controlling rider's
  view direction, plays the Ghast shoot sound, and uses the configured explosion power.
- The released implementation loads 16 sampled chunks along the first 128 blocks of an accepted
  projectile path. Whether the rebuild retains that eager loading or relies on normal projectile and
  chunk behavior remains a rebuild decision because the public feature description promises only a
  launched fireball, not a chunk-loading policy.
- If Minecraft refuses to add the projectile, the released implementation substitutes a 48-block
  ray, flame trail, and explosion. Retaining that fallback or treating projectile creation as a failed
  shot remains a rebuild decision; it must not survive accidentally as a second mutation path.

## Ammo and shot timing

- Each Happy Ghast has its own ammo pool, keyed by the ghast UUID.
- The default maximum is 200 ammo and a normal shot costs 1 ammo.
- Ammo regenerates passively by 1 every 5 minutes until the configured maximum is reached.
- The released implementation resets the delivery timestamp after each shot, so sustained firing can
  postpone regeneration. README promises passive refill; exact delivery timing is an open correction.
- New or previously unseen Happy Ghasts begin with a full ammo pool.
- The default minimum interval between accepted shots is 0.25 seconds.
- Cooling cannot begin until the default 0.5-second firing restart delay has elapsed after the latest
  accepted shot.
- Denied inputs do not consume ammo or restart the shot timer.

## Heat and environments

`ArtilleryState` owns one heat value and one current environment mode per Happy Ghast. Ability and
display code read that owner; they do not calculate competing biome modes or cooling schedules.

| Mode | Selection | Default heat per shot | Default overheat limit | Default passive cooling |
|---|---|---:|---:|---:|
| Normal | Other Overworld biomes | 1.0 | 60 | 1 heat per 3.0 seconds |
| Hot | Threshold unresolved: released paths use 1.0 and 1.5 | 2.0 | 60 | 1 heat per 6.0 seconds |
| Cold | Overworld biome temperature at most 0.0 | 0.5 | 60 | 1 heat per 1.5 seconds |
| Nether | Nether dimension, regardless of biome temperature | 3.0 | 60 | None by default |
| End | End dimension, regardless of biome temperature | 0.5 | 60 | 1 heat per 1.5 seconds |

- Dimension rules take priority over biome temperature in the released classification paths. The new
  implementation must select one mode once and share it with display, shot acceptance, and cooling.
- Intended heat does not drop below zero. Released passive cooling has competing elapsed-time and fixed
  tick paths; the rebuild must retain the configured outcomes through one state owner.
- When submerged, a Happy Ghast cannot fire. A fire attempt applies water cooling at 8 heat per elapsed
  second by default. Released clamping can raise heat below the configured floor back to 5; continuous
  cooling and non-increasing floor behavior remain open corrections.
- Entering water does not spend ammo or start a shot cooldown.

## Overheat

- The shot that reaches the current environment's overheat limit triggers the overheat result instead
  of launching a normal aimed fireball.
- Released overheat prediction uses hard-coded `currentHeat + 1`, then applies the configured heat
  amount. Using the configured amount consistently is an open correction.
- The default overheat result creates a power-4 explosion two blocks in front of the Happy Ghast and
  creates fire when `overheatExplosionCreatesFire` is enabled.
- The established spectacle also emits 48 outward large fireballs in a deterministic sphere pattern
  and attempts a small ring of nearby fire placements on supported air blocks.
- The triggering shot consumes its configured ammo and records its shot timing exactly once.
- The rebuild must define and test the post-overheat heat value before enabling gameplay. The old code
  leaves the ghast at or above its limit indefinitely; that is a known incomplete behavior, not an
  accepted recovery rule.

## Cry ability

- Cry Control plays the Happy Ghast scream sound at pitch 0.8 and the configured volume, which defaults
  to 3.0.
- Cry is blocked while the Happy Ghast is submerged.
- Cry has a 10-second default cooldown keyed by the controlling player's UUID.
- Only a successful cry starts the cooldown. Cry does not consume fireball ammo or add heat.

## Rider display

- The controlling rider receives one heat boss bar while controlling a Happy Ghast. Its value is the
  current heat divided by the current environment's overheat limit.
- The boss bar title shows `Heat: current/limit`. It is green normally, blue in cold conditions, yellow
  in hot conditions or within 15 heat of overheat, and red within 5 heat of overheat.
- The action bar shows current ammo and maximum ammo. When the ghast is not in its firing restart delay,
  it also shows normal, fast, slow, or no cooling according to the same environment mode used by state.
- The action bar warns within 15 heat of overheat and shows an urgent overheat warning within 5.
- Warning particles increase near overheat. They are presentation only and never mutate heat or decide
  whether an ability is accepted.
- Released boss bars are removed when the rider is observed no longer driving. Complete disconnect,
  death, entity-removal, and map-eviction behavior is not guaranteed and remains a rebuild decision.

## Configuration

`HappyArtilleryConfig` is the only owner of `config/happy-artillery.json`. A missing file creates a
complete default file. Configuration is read during server startup; changes take effect after restart.
The released loader is permissive: omitted fields keep their Java defaults, an empty JSON document or
an I/O read failure uses defaults, and every startup rewrites the file with every known field. Malformed
JSON can fail startup. Numeric ranges and relationships are not currently validated; stricter schema
validation is a separate rebuild decision, not preserved behavior.

| Field | Default | Contract |
|---|---:|---|
| `fireballAmmoMax` | `200` | Per-ghast ammo capacity and initial ammo |
| `fireballAmmoCost` | `1` | Ammo consumed by an accepted shot or overheat trigger |
| `ammoDeliveryIntervalMin` | `5` | Minutes per point of passive ammo regeneration |
| `shootCooldownSeconds` | `0.25` | Minimum interval between accepted shots |
| `fireRestartDelaySeconds` | `0.5` | Delay after firing before passive heat cooling resumes |
| `cryCooldownSeconds` | `10.0` | Successful cry cooldown per controlling player |
| `baseOverheatLimit` | `60` | Normal-mode heat limit |
| `baseHeatPerShot` | `1.0` | Normal-mode heat added per shot |
| `baseCoolIntervalSeconds` | `3.0` | Seconds per heat removed in normal mode |
| `hotBiomeOverheatLimit` | `60` | Hot-mode heat limit |
| `hotBiomeHeatPerShot` | `2.0` | Hot-mode heat added per shot |
| `hotBiomeCoolIntervalSeconds` | `6.0` | Seconds per heat removed in hot mode |
| `coldBiomeOverheatLimit` | `60` | Cold/End heat limit |
| `coldBiomeHeatPerShot` | `0.5` | Cold/End heat added per shot |
| `coldBiomeCoolIntervalSeconds` | `1.5` | Seconds per heat removed in cold/End mode |
| `netherOverheatLimit` | `60` | Nether heat limit |
| `netherHeatPerShot` | `3.0` | Nether heat added per shot |
| `netherNoCooldown` | `true` | Disables passive Nether cooling when true |
| `waterCooldownRate` | `8` | Heat removed per elapsed second in water |
| `waterCooldownLimit` | `5` | Lowest heat reachable through water cooling |
| `fireballExplosionPower` | `2` | Normal and emitted overheat-fireball power |
| `overheatExplosionPower` | `4.0` | Main overheat explosion power |
| `overheatExplosionCreatesFire` | `true` | Whether the main overheat explosion creates fire |
| `cryVolume` | `3.0` | Ghast scream volume for a successful cry |

## Failure and lifecycle contract

- Expected denials leave inventory ownership, ammo, heat, cooldowns, and the world unchanged except
  that a submerged ghast may receive its accepted water-cooling update.
- Each server callback has one named owner. The entrypoint wires it once; no mixin, tick handler, or
  fallback registers a competing path for the same behavior.
- The released implementation catches several tick/inventory exceptions and continues, and one global
  tick path samples repeated error logging. The rebuild must decide where recovery is valid and where
  startup or the current action should fail; silent catch-all handling is not automatically preserved.
- The released runtime maps do not have a complete removal lifecycle and its passive work scans loaded
  entities. Bounded state cleanup and narrower iteration are required architecture decisions before
  implementation, not features already promised by the released mod.
- The `/happytest` debug command is not part of the released product contract and is not retained in
  the clean architecture.

## Rebuild decisions still open

These implementation conflicts are deliberately not settled by the structural checkpoint:

- Hot-biome selection is inconsistent: fire acceptance treats temperature `>= 1.5` as hot while the
  display/state path uses `>= 1.0`. Implementation must select one before executable behavior lands.
- Control setup is driver-only, while released interaction callbacks also permit other mounted
  passengers and unmarked Fire Charges/Ghast Tears. Authorization and compatibility must be explicit.
- Released cleanup can erase a pre-existing item name/glint and uses broad lore-substring markers.
  Safe marker ownership and restoration need an accepted contract and regression tests.
- Released overheat prediction uses hard-coded `+1` rather than the configured heat-per-shot value.
- Released shots reset the ammo-delivery timestamp, which can postpone otherwise passive regeneration.
- Released water cooling is triggered only by submerged fire attempts and can raise heat to its floor.
- The post-overheat heat value and recovery path are undefined in the released code.
- Projectile add failure currently becomes an instant-ray explosion, and successful shots eagerly load
  a 128-block path. Neither behavior appears in the public description.
- Config range/schema validation, runtime-state eviction, and broad inventory/world cleanup boundaries
  need explicit tests before the new owners are implemented.
