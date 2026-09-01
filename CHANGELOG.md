# Changelog

## [1.2.0] - Unreleased

Compared with the latest stable release, v1.1.2, this update replaces fixed-slot controls and ammunition with movable, ride-bound controls, persistent heat, clearer rider status, and stricter live configuration.

### Changed

- Pilots now receive temporary Fire and Cry controls in the first two free hotbar or main-inventory slots. The controls can move within the owner's inventory and offhand without overwriting ordinary items.
- Fire supports hold-to-fire by default. Cry is click-only, keeps its own cooldown, and is always blocked underwater.
- Dropping a generated control or moving it into an external container consumes it. Lost controls stay missing until the pilot dismounts and rides again.
- Removed the ammunition pool and passive refill. Firing is now limited by cooldown, heat, and overheat behavior.
- Heat, cooldowns, and pending overheat fuses now survive chunk unloads and server restarts. Cooling reflects the ghast's current location, water exposure, and firing state.
- Every rider now sees the ghast's heat HUD. It reports firing, configured no-cooling text, or the effective cooling rate; only the pilot receives controls.
- Normal shots use vanilla large fireballs launched clear of the ridden ghast and its passengers.
- `overheat.breaksBlocks=false` keeps the central explosion from damaging terrain and skips direct fire placement. When enabled, the central explosion and direct fire placement still obey `mobGriefing`. Emitted vanilla fireballs keep their normal impact behavior in either mode.

### Server and configuration

- Updated support to Minecraft 26.2 with Fabric Loader 0.19.3 or newer, Fabric API, and Java 21 or newer.
- Happy Artillery remains server-side. Unmodded Java clients and Bedrock players joining through Geyser are supported.
- Added `/ha reload` for admins with gamemaster permission level 2.
- Configuration now uses defaults plus individual overrides only. The removed root `preset` key, unknown keys, malformed values, and invalid ranges fail clearly; a failed reload keeps the active configuration and invalid file unchanged.
- Added configurable zero, slow, normal, and fast cooling text/color bands under `hud.cooling`. `hud.refreshTicks` must be at least 4.

## [1.1.2.2] - 2026-08-04 (pre-release)

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
