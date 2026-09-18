# Changelog

## [1.2.1] - Unreleased

### Fixed

- Generated controls can no longer be stored in bundles while using Creative mode.

### Compatibility

- Added support for Minecraft `26.3`, with Fabric Loader `0.19.5` or newer, Fabric API `0.160.7+26.3`, and Java 25 or newer.
- Minecraft `26.2` remains supported by Happy Artillery 1.2.0.
- Happy Artillery remains server-side. Players can join with an unmodded Java client.

## [1.2.0] - 2026-09-18

This section records the player-facing, server-admin, and compatibility changes from `main` version 1.1.2.2.

### Added

- Added a rider HUD that shows heat, firing state, and the effective cooling rate. Passengers see the same status, while only the pilot receives controls.
- Added `/ha reload` for admins with gamemaster permission level 2. A failed reload keeps the previous valid configuration active.
- Added an [annotated configuration reference](docs/happy-artillery-config.jsonc) with every default, unit, range, and accepted zero behavior.
- Added one-time migration for released 1.1.x flat configs. The exact original file is saved as `happy-artillery.json.v1.1.2.bak` before equivalent settings are moved to the nested format.

### Changed

- Replaced fixed control slots with temporary, ride-bound Fire and Cry controls. The mod places all enabled controls only when enough hotbar or main-inventory space is available, and it never overwrites ordinary items.
- Controls can move through their owner's hotbar, main inventory, offhand, and cursor. Dropping one or moving it into a container or crafting grid consumes it. Bundles reject controls, and controls cannot be used as crafting ingredients. A missing control returns only after dismounting and riding again.
- Fire supports hold-to-fire by default. Cry remains click-only, has its own cooldown, and is always blocked while the ghast is touching water.
- Heat, cooldowns, and pending overheat fuses now persist across chunk unloads and server restarts. Cooling uses the ghast's current dimension, biome, and firing state.
- Normal shots now use vanilla large fireballs launched clear of the Happy Ghast and its passengers.
- `overheat.breaksBlocks=false` now prevents terrain damage from the central explosion and skips direct fire placement. When enabled, both follow the vanilla `mobGriefing` rule. Emitted vanilla fireballs retain their normal impact behavior in either mode.
- Configuration now uses nested feature groups with defaults plus individual overrides. Valid sparse files remain unchanged during startup and reload; malformed values, invalid ranges, removed settings, and unknown keys fail clearly instead of being ignored.
- Fire cooldown can be set to zero. Heat and overheat still limit firing.
- HUD firing color and zero, slow, normal, and fast cooling bands are configurable. Firing defaults to gold, while the heat warning remains red.

### Fixed

- Fire Control now starts hold-to-fire while aiming at a nearby block without igniting that block.
- Allowed plain Fire items now activate artillery instead of vanilla block ignition when an authorized pilot aims at a block.
- Control-item validation now rejects `minecraft:air`, which cannot create a usable control stack.
- Generated controls can no longer be loaded into projectile weapons, used as their underlying vanilla item, or moved into armor slots.

### Removed

- Removed ammunition and passive ammunition refill. Firing is governed by cooldown, heat, and overheat instead.
- Removed water cooling. Water blocks Fire by default through `water.blocksFiring`, and it always blocks Cry.
- Removed the root `preset` configuration model and obsolete 1.1.x fixed-slot, stash, and item-restoration behavior.

### Compatibility

- Updated the supported game version to Minecraft `26.2`, with Fabric Loader `0.19.3` or newer, Fabric API, and Java 25 or newer.
- Happy Artillery remains server-side. Players can join with an unmodded Java client.
- Earlier 1.2.0 development config names for firing delay, biome thresholds, custom-dimension classification, and fire placement are rejected with the required replacement name.
- Changed the project license from MIT to [CC0 1.0 Universal](LICENSE).

## [1.1.2.2] - 2026-08-04

### Fixed

- Stopped routine inventory cleanup checks from flooding the server log while retaining messages when temporary control items are actually removed.

## [1.0.0] - 2026-03-01

### Added

- Initial public release
- Fireball shooting from Happy Ghasts (right-click with Fire Charge while riding)
- Ghast Cry ability (right-click with Ghast Tear)
- Ammo system with passive regeneration (200 max, 1 per 5 min)
- Heat/overheat mechanics with biome-specific behaviour
- Water cooling mechanic
- JSON config file at `config/happy-artillery.json`
