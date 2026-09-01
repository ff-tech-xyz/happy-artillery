# Happy Artillery

Happy Artillery turns the Happy Ghast into a rideable siege machine. Pilots can fire vanilla fireballs, use a Ghast Cry, and push the ghast into a dangerous overheat. Every rider gets a HUD showing the ghast's current heat and cooling state.

Happy Artillery runs on the server. Players can join with an unmodded Java client or through Geyser on Bedrock.

## Controls

When you become the pilot, the first two free slots found in your hotbar and main inventory receive temporary Fire Control and Cry Control items. If fewer than two slots are free, the mod leaves the inventory alone and tells you that two free slots are needed.

The generated controls can move normally within their owner's hotbar, main inventory, and offhand. Hold one in either hand to use it:

- Fire Control fires on right-click and supports hold-to-fire by default.
- Cry Control is click-only. It plays the Happy Ghast's cry and has its own cooldown, but it cannot be used underwater.

Controls are tied to their owner and the current ride. Trying to drop one or place it in an external container consumes it. A lost or consumed control does not regenerate during the same ride; dismount and ride again to receive a new pair. Ordinary items are never overwritten to make room.

## Heat, cooling, and overheat

Each shot adds heat. Heat gain and passive cooling depend on the dimension and biome: cold areas and the End heat more slowly and cool faster, hot areas heat faster and cool more slowly, and the Nether has no passive cooling by default. Water cools the ghast directly. Cry is always blocked underwater; Fire is blocked there by default and follows `water.blocksFiring`.

The rider HUD shows effective cooling rather than a generic biome label. It distinguishes firing, no cooling, and the current cooling rate. Passengers see the same heat and cooling status without receiving controls.

Reaching the heat limit triggers the configured overheat effects. With `overheat.breaksBlocks=false`, the central explosion does not damage terrain and Happy Artillery skips its direct fire placement. With it set to `true`, that explosion follows vanilla mob rules and the mod attempts direct fire placement only while `mobGriefing` is enabled. The emitted vanilla fireballs keep their normal impact behavior in either mode, including the `mobGriefing` gamerule.

## Requirements

- Minecraft `26.2`
- Fabric Loader `0.19.3` or newer
- Fabric API
- Java 21 or newer

Install the mod and Fabric API on the server. Clients do not install Happy Artillery.

## Configuration

Happy Artillery creates `config/happy-artillery.json` from its defaults. Server owners can override individual settings for controls, fire, heat profiles, water cooling, overheat, Cry, and the HUD. The full schema and default values are documented in [FEATURES.md](FEATURES.md#configuration).

Admins with gamemaster permission level 2 can apply changes without restarting:

```text
/ha reload
```

Configuration is strict. Unknown keys, removed keys, malformed values, and invalid ranges fail instead of being ignored. The old root `preset` key has been removed; defaults plus individual overrides are the only configuration model. A failed reload reports the error and keeps the current active configuration without replacing the invalid file.

`hud.refreshTicks` has a minimum of `4`. The `hud.cooling` section controls the zero-rate text and color (`noCoolingText`, `noCoolingColor`) and the slow, normal, and fast cooling bands (`slowMaxPerSecond`, `slowColor`, `normalMaxPerSecond`, `normalColor`, `fastColor`).

## Building from source

```bash
git clone https://github.com/ff-tech-xyz/happy-artillery
cd happy-artillery
./gradlew build
```

Built jars are written to `build/libs/`.

## License

[CC0 1.0 Universal](LICENSE)

## Credits

- OG Moo-cow, author
- [PyreHaven](https://pyrehaven.xyz)
