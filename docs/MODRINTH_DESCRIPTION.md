# Happy Artillery

Happy Artillery turns Happy Ghasts into rideable artillery, with fireballs, a Ghast Cry, and heat that builds as you fire. It runs on the server; players don't need to install anything on their Java clients.

## Flying and firing

- **Fire Control:** right-click to launch a fireball, or hold to keep firing.
- **Cry Control:** right-click to make the ghast scream. It's a sound, not a damage ability, and has its own cooldown.
- **Rider HUD:** everyone aboard can see the ghast's heat and cooling status. Only the pilot can use its abilities.

Controls are temporary items given to the pilot when a ride begins. Move them to whichever hotbar slots you prefer, or use them from your offhand. Your existing items stay untouched.

There's no ammunition to collect or refill. Heat is the limit: fire a burst, ease off, and watch the cooling rate before firing again. Cold areas and the End cool faster, hot areas cool more slowly, and the Nether has no passive cooling by default.

## Before you install

- **Overheating destroys the Happy Ghast by default.** Reaching the heat limit triggers an explosion and a burst of fireballs. Don't treat the red warning as decoration.
- **Artillery can damage terrain.** Normal shots and overheat fireballs use vanilla fireball behavior. `overheat.breaksBlocks=false` disables terrain damage from the central explosion and skips direct fire placement; it does not make the emitted fireballs harmless. Vanilla `mobGriefing` still matters.
- **Leave room for controls.** With both abilities enabled, the pilot needs two free slots in the hotbar or main inventory. If there's not enough room, no controls are added and no existing items are overwritten.
- **Controls belong to one player and one ride.** Dropping one or moving it into a container, crafting grid, or armor slot consumes it. They can't be stored in bundles or used as ordinary items. Dismount and ride again to replace a missing control.
- **Water doesn't cool the ghast.** Touching water blocks firing by default and always blocks Cry.
- **Heat and cooldowns survive chunk unloads and server restarts.** Stopped-server time doesn't count as cooling time, and dismounting doesn't cancel an overheat fuse.
- **Upgrading? Remove the old Happy Artillery jar first.** Keep only one version in `mods`.

## Server setup

Controls, firing speed, heat profiles, overheat effects, Cry, and HUD settings live in `config/happy-artillery.json`. Every setting and default is documented in the [configuration reference](https://github.com/ff-tech-xyz/happy-artillery/blob/main/docs/happy-artillery-config.jsonc).

Admins with permission level 2 can apply changes with `/ha reload`. A failed reload reports the problem and keeps the previous working settings. The runtime file must be strict JSON; the commented reference is documentation, not a ready-to-use config.

Released 1.1.x configs migrate on startup, with the original saved as `happy-artillery.json.v1.1.2.bak`. Customized settings that no longer have an equivalent, such as ammunition or water cooling, stop migration with an error rather than being silently discarded. See the [upgrade notes](https://github.com/ff-tech-xyz/happy-artillery/blob/main/README.md#upgrading-from-11x).

Happy Artillery supports **Minecraft 26.2 and 26.3**. Use version **1.2.0** for Minecraft 26.2 or version **1.2.1** for Minecraft 26.3. The 26.3 release needs Fabric Loader **0.19.5 or newer**, [Fabric API](https://modrinth.com/mod/fabric-api), and **Java 25 or newer**. Install Happy Artillery and Fabric API on the server. Players can join with an unmodded Java client.

## Links

- [Source code](https://github.com/ff-tech-xyz/happy-artillery)
- [Issues and suggestions](https://github.com/ff-tech-xyz/happy-artillery/issues)
- [PyreHaven Discord](https://discord.gg/tZ6Hx2ETA3)

Made by PyreHaven. Find out more about us at [PyreHaven.xyz](https://pyrehaven.xyz).
