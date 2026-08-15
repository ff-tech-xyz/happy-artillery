# Happy Artillery

Happy Artillery turns the Happy Ghast into a rideable siege machine. Once you are in the saddle, the ghast can launch fireballs, let out a scream, and overheat if you push it too hard.

The mod runs on the server, so players do not need to install it on their clients to join and use it.

## What it does

- Adds fireball controls while riding a Happy Ghast.
- Adds a Ghast Cry ability with its own cooldown.
- Gives each Happy Ghast an ammo pool that refills over time.
- Tracks heat while you fire. Keep shooting for too long and the ghast overheats in a messy burst of fireballs.
- Changes heat behavior by biome and dimension. Hot places are riskier, cold places cool faster, and the Nether is exactly as bad an idea as it sounds.
- Lets server owners tune the numbers in `config/happy-artillery.json`.

## How to use it

Mount a Happy Ghast and use the control slots in your hotbar:

- Slot 5 becomes Fire Control. Right-click with it to shoot a fireball.
- Slot 6 becomes Cry Control. Right-click with it to make the ghast scream.

If a control slot is empty, the mod creates a temporary Fire Charge or Ghast Tear for you. If there is already an item in the slot, the mod marks it as the control while you are riding and cleans it up when you dismount.

## Heat and ammo

Every shot costs ammo and adds heat. Ammo refills passively, but heat depends on where you are flying.

| Area | Default behavior |
|---|---|
| Normal biomes | Standard heat gain and cooling |
| Hot biomes | More heat per shot and slower cooling |
| Cold biomes / End | Less heat per shot and faster cooling |
| Nether | High heat and no passive cooling |

Water cools a ghast quickly, but you cannot fire while submerged.

## Requirements

- Minecraft `26.1.2` / `26.2` line, with current releases also published for `1.21.11`
- Fabric Loader `0.19.2` or newer
- Fabric API
- Java 21+

Install it on the server. Clients do not need the mod.

## Configuration

A default config is created at `config/happy-artillery.json` on first launch. Server owners can adjust ammo, cooldowns, heat limits, biome behavior, explosion power, water cooling, and cry volume.

Changes take effect after a server restart.

## Building from source

```bash
git clone https://github.com/ff-tech-xyz/happy-artillery
cd happy-artillery
./gradlew build
```

Built jars are written to `build/libs/`.

## License

MIT

## Credits

- OG Moo-cow, author
- PyreHaven, https://pyrehaven.xyz
