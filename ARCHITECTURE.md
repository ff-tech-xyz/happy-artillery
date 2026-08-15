# Happy Artillery Structure

Happy Artillery has one server-side path from a mounted player's control input to the selected Happy
Ghast ability. `HappyGhastControls`, `ArtilleryState`, and `ControlItems` own input routing, combat
state, and temporary inventory mutation respectively. The ability and display owners consume those
results; they do not keep parallel copies of state.

```text
happy-artillery/
├── src/
│   ├── main/
│   │   ├── java/xyz/pyrehaven/happyartillery/
│   │   │   ├── HappyArtillery.java
│   │   │   │   # Fabric entrypoint and composition root. Loads config, constructs the owners below,
│   │   │   │   # and registers server callbacks. No gameplay or mutable state lives here.
│   │   │   ├── HappyArtilleryConfig.java
│   │   │   │   # Sole owner of config/happy-artillery.json: defaults, loading, writing, failure
│   │   │   │   # semantics, and the immutable settings consumed by the other owners.
│   │   │   ├── HappyGhastControls.java
│   │   │   │   # Sole interaction owner. Applies the accepted passenger/item authorization policy,
│   │   │   │   # then routes one accepted input to FireballAbility or CryAbility.
│   │   │   ├── ControlItems.java
│   │   │   │   # Sole inventory-mutation owner for slots 5 and 6: temporary controls, existing-item
│   │   │   │   # decoration, movement handling, decoration removal, death cleanup, and stale tags.
│   │   │   ├── ArtilleryState.java
│   │   │   │   # Sole UUID-keyed runtime-state owner for per-ghast ammo/heat/shot timing and
│   │   │   │   # per-player cry cooldowns, plus biome mode and passive regeneration.
│   │   │   ├── FireballAbility.java
│   │   │   │   # Owns fire acceptance and world mutation. Requests the single ammo/heat/timing
│   │   │   │   # transition from ArtilleryState, then launches or performs overheat effects.
│   │   │   ├── CryAbility.java
│   │   │   │   # Owns cry acceptance and the configured Happy Ghast scream sound.
│   │   │   └── RiderDisplay.java
│   │   │       # Sole presentation owner for the rider's heat boss bar, ammo/cooling action bar,
│   │   │       # warning particles, and cleanup when control ends.
│   │   └── resources/
│   │       ├── fabric.mod.json
│   │       │   # Fabric metadata, dependencies, version, icon, and server entrypoint.
│   │       └── assets/happy-artillery/icon.png
│   │           # Packaged Happy Artillery icon. No mixin or injected-interface metadata is retained;
│   │           # a later feature must justify any such file before it enters this tree.
│   └── test/
│       └── java/xyz/pyrehaven/happyartillery/
│           ├── ConfigTest.java
│           │   # Defaults, complete-file creation, loading failures, malformed input, and round trips.
│           ├── ControlItemsTest.java
│           │   # Mount/dismount, occupied and empty slots, movement, death, and cleanup outcomes.
│           ├── CombatStateTest.java
│           │   # Ammo, cooldowns, heat modes, water cooling, regeneration, and state transitions.
│           ├── AbilitiesTest.java
│           │   # Driver/input checks, fireball and cry outcomes, overheat, and failed mutation rules.
│           └── RiderDisplayTest.java
│               # Boss-bar/action-bar values, biome labels, warning thresholds, and cleanup.
├── ARCHITECTURE.md
│   # This proposed file tree. Every rebuild change is checked against its owners and paths.
├── FEATURES.md
│   # Accepted product behavior and defaults preserved while the implementation is rebuilt.
├── README.md
│   # Installation, use, configuration, and the current non-deployable groundwork status.
├── CHANGELOG.md
├── LICENSE
├── .gitignore
│   # Keeps Gradle output, IDE state, and local runtime files out of source.
├── build.gradle
│   # Fabric Loom build and verification tasks.
├── gradle.properties
│   # Minecraft, Fabric, Java, artifact, and preserved Maven-coordinate versions.
├── settings.gradle
├── gradlew
├── gradlew.bat
└── gradle/
    ├── minecraft/
    │   ├── 26.2-custom.json
    │   │   # Loom-compatible Minecraft 26.2 metadata used by the build.
    │   └── identity-official-26.2.jar
    │       # Pinned official-name mapping input used by the build.
    └── wrapper/
        ├── gradle-wrapper.jar
        └── gradle-wrapper.properties
```
