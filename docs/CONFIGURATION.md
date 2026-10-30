# Configuration and upgrades

Happy Artillery creates `config/happy-artillery.json` on first launch. Keep the generated file or include only the settings you want to change. Existing valid files retain their contents during startup and reload.

The [annotated reference](happy-artillery-config.jsonc) lists every setting, default, unit and range. The runtime file must be strict JSON: don't copy the reference's comments into it. Unknown keys, duplicate keys, invalid types and out-of-range values are rejected.

Run `/ha reload` with permission level 2 to apply changes. If validation fails, the error identifies the problem and the previous settings remain active; the invalid file is not replaced.

## Terrain damage

Normal shots and fireballs released during overheat use vanilla fireball behavior, including `mobGriefing`.

`overheat.breaksBlocks=false` disables terrain damage from the central explosion and skips direct fire placement. It does **not** make the emitted fireballs harmless. When `true`, central explosion terrain damage and direct fire placement also follow `mobGriefing`.

## Controls

The pilot needs one free hotbar or main-inventory slot per enabled ability. With both enabled, leave two free slots. If there isn't enough room, no controls are added and existing items stay untouched.

Controls belong to one player and one ride. They work from either hand and can move between the hotbar, main inventory and offhand. Dropping them or moving them into a container, crafting grid or armor slot consumes them. Bundles reject them, and they cannot be used as crafting ingredients, ammunition or ordinary items. Dismount and ride again to replace a missing control.

## Heat and water

Cold areas and the End add less heat per shot and cool faster; hot areas add more heat and cool more slowly. The Nether has no passive cooling by default. Water does not cool the ghast. Touching water blocks firing unless `water.blocksFiring` is disabled, and always blocks Cry.

Heat and cooldowns survive chunk unloads and server restarts. Time with the server stopped does not count as cooling. Dismounting does not cancel a pending overheat explosion.

## Upgrading from 1.1.x

Remove the old JAR before installing the replacement.

On startup, Happy Artillery converts a released 1.1.x flat config to the nested format and saves the exact original as `config/happy-artillery.json.v1.1.2.bak`. Settings with direct equivalents carry forward.

Migration stops without changing either file when a customized old setting has no replacement. This includes removed ammunition or water-cooling settings and different biome-specific heat limits. Correct the reported setting and restart.

Earlier 1.2.0 development configs must replace these names:

- `heat.firingWindowSeconds` → `heat.coolingDelayAfterShotSeconds`
- `heat.coldMaxTemperature` → `heat.coldBiomeMaxTemperature`
- `heat.hotMinTemperature` → `heat.hotBiomeMinTemperature`
- `heat.unknownDimensionUsesTemperature` → `heat.otherDimensionsUseBiomeTemperature`
- `overheat.fireAttempts` → `overheat.firePlacementAttempts`
- `overheat.fireRadius` → `overheat.firePlacementRadius`

The old names are rejected, not treated as aliases.
