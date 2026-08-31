# Happy Artillery 1.2.0 Proposed Structure

The annotated tree below is the complete proposed source-controlled shape. It contains thirteen
production Java files and eight risk-grouped test files. This proposed tree precedes the source
moves: `DeathDropMixin` and `SlotGuardMixin` are absent; the reshaped `PlayerDropMixin` and new
`ExternalContainerMixin` are the only mixins.

```text
happy-artillery/
├── src/
│   ├── main/
│   │   ├── java/xyz/pyrehaven/happyartillery/
│   │   │   ├── HappyArtillery.java
│   │   │   │   # Composition root that invokes each owner's registration, op-only reload command,
│   │   │   │   # durable Overworld-game-time context, sole player tick driver, ghast-load callback,
│   │   │   │   # and bounded player-availability wake-up; no gameplay policy or second fuse poller.
│   │   │   ├── Config.java
│   │   │   │   # Sole config schema/codec, defaults, presets, validation, atomic live value, load,
│   │   │   │   # rewrite, and reload owner.
│   │   │   ├── BiomeClass.java
│   │   │   │   # Sole dimension/temperature classifier and finite heat-profile selector.
│   │   │   ├── GhastState.java
│   │   │   │   # Immutable persistent Happy Ghast attachment value/codec: heat anchor plus independent
│   │   │   │   # fire-ready and cry-ready ticks, plus paired pending-detonation deadline/rider identity.
│   │   │   ├── Heat.java
│   │   │   │   # Pure heat authority: anchored, non-double-counted water/passive cooling, firing window,
│   │   │   │   # shot addition, and the codebase's only heat-limit comparison.
│   │   │   ├── RiderState.java
│   │   │   │   # Immutable persistent player attachment value/codec for ridden-ghast identity,
│   │   │   │   # input deduplication, and HUD dirty-check state only.
│   │   │   ├── Components.java
│   │   │   │   # Sole fire/cry marker codec/helper owner using namespaced vanilla CUSTOM_DATA with
│   │   │   │   # control type, owner UUID, and ride UUID; preserves unrelated custom data.
│   │   │   ├── Controls.java
│   │   │   │   # Sole control owner: atomic free-slot allocation, one bounded active-pilot inventory
│   │   │   │   # snapshot, held admission, owner/ride cleanup, transfer cleanup, and ride transitions.
│   │   │   ├── Abilities.java
│   │   │   │   # Sole fire/cry/detonation gate, effect, and fuse-scheduling owner, including active and
│   │   │   │   # bounded rider-deferred task states keyed by persisted ghast UUID, load/player wake-ups,
│   │   │   │   # durable pre-effect consumption, vanilla LargeFireball spawning, effects, sound, and removal.
│   │   │   ├── Hud.java
│   │   │   │   # Sole boss/action-bar and warning-particle owner for pilots and read-only passengers;
│   │   │   │   # consumes the shared control snapshot and owns bounded process-local display handles.
│   │   │   ├── Feedback.java
│   │   │   │   # Sole visible rejection to action-bar/sound mapping; cooldown and authorization stay silent.
│   │   │   └── mixin/
│   │   │       ├── PlayerDropMixin.java
│   │   │       │   # Observes ServerPlayer.drop(ItemStack, boolean, boolean) at RETURN and discards
│   │   │       │   # returned marked ItemEntity drops while leaving ordinary drops unchanged.
│   │   │       └── ExternalContainerMixin.java
│   │   │           # Observes Slot.setChanged() at HEAD after menu mutation and delegates removal of
│   │   │           # marked controls from non-owner container destinations to Controls.
│   │   └── resources/
│   │       ├── fabric.mod.json
│   │       │   # Fabric identity, dependencies, entrypoint, mixin declaration, version, and icon.
│   │       ├── happy-artillery.mixins.json
│   │       │   # Declares both narrow mixins with their fail-closed injection requirements.
│   │       └── assets/happy-artillery/icon.png
│   │           # Packaged Happy Artillery icon.
│   └── test/
│       └── java/xyz/pyrehaven/happyartillery/
│           ├── ConfigTest.java
│           │   # Config defaults, presets, removed/unknown-key rejection, validation, round-trip,
│           │   # rewrite, registry-lifecycle resolution, and reload-failure contract.
│           ├── BiomeClassTest.java
│           │   # Dimension identity, custom-dimension, temperature-edge, and profile tests.
│           ├── HeatTest.java
│           │   # Curves, anchored non-double cooling, firing window, water ordering, restart/unload gaps,
│           │   # and exact detonation-edge tests.
│           ├── PersistenceTest.java
│           │   # Ghast/Rider fresh values, codecs, immutable attachment replacement, durable tick anchors,
│           │   # ride identity, input tick, and HUD-cache round trips with no ItemStack/index persistence.
│           ├── ControlsTest.java
│           │   # Owner/ride marker identity, atomic allocation, bounded snapshot, held admission,
│           │   # same-player mobility, outbound consumption, cleanup, no-overwrite, and dedup tests.
│           ├── AbilitiesTest.java
│           │   # Fire/cry gates, sealed outcomes, vanilla LargeFireball identity/ownership/defaults,
│           │   # feedback/effects, server-queue fuse scheduling/load wake-up, and detonation tests.
│           ├── HudTest.java
│           │   # Pilot/passenger visibility, control-warning priority, dirty checks, throttling,
│           │   # particles, snapshot sharing, and teardown tests.
│           └── HappyArtilleryIntegrationTest.java
│               # Registration uniqueness, one-driver ordering, actor-local callbacks, one snapshot per
│               # active pilot, pilotless-rider cleanup, no world/container/entity scan, and complete wiring.
├── AGENTS.md
│   # Repository routing, structure, commit, verification, and runtime rules.
├── ARCHITECTURE.md
│   # This complete proposed ownership tree.
├── FEATURES.md
│   # Settled 1.2.0 behavior and compatibility/regression contract.
├── MIGRATION_PLAN.md
│   # Dependency-ordered RED/GREEN, manual, commit, push, and activation checkpoints.
├── README.md
│   # Installation, controls, configuration, Geyser support, and supported-version documentation.
├── CHANGELOG.md
│   # Public released-version history, not intermediate rewrite state.
├── LICENSE
│   # Complete CC0 1.0 Universal legal text packaged into the artifact.
├── .gitignore
│   # Excludes generated Gradle, IDE, run, world, log, and jar output.
├── build.gradle
│   # Loom/Java/JUnit and Fabric attachment-API compile support, resources, checks, jars, and publication.
├── gradle.properties
│   # Pinned Minecraft, Fabric, Loom, Java-facing, artifact, and Maven-coordinate values.
├── settings.gradle
│   # Plugin-resolution repositories and project identity.
├── gradlew
│   # Unix Gradle wrapper launcher.
├── gradlew.bat
│   # Windows Gradle wrapper launcher.
└── gradle/
    ├── minecraft/
    │   ├── 26.2-custom.json
    │   │   # Loom-compatible Minecraft 26.2 metadata used by the local build metadata server.
    │   └── identity-official-26.2.jar
    │       # Pinned official-name mapping input.
    └── wrapper/
        ├── gradle-wrapper.jar
        │   # Source-controlled Gradle wrapper bootstrap.
        └── gradle-wrapper.properties
            # Pinned Gradle distribution and wrapper settings.
```
