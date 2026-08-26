# Happy Artillery 1.2.0 Proposed Structure

Dependency direction is `Config` -> pure state/policy -> controls/abilities/HUD -> `HappyArtillery`.
Minecraft effects stay at the outer call sites; pure transitions never call the world. The entrypoint
registers and drives owners but does not duplicate them.

```text
happy-artillery/
├── src/
│   ├── main/
│   │   ├── java/xyz/pyrehaven/happyartillery/
│   │   │   ├── HappyArtillery.java
│   │   │   │   # Composition root, attachment/component/event registration, op-only reload command,
│   │   │   │   # and the sole player-iteration tick driver; no gameplay policy.
│   │   │   ├── Config.java
│   │   │   │   # Sole config schema/codec, defaults, presets, validation, atomic live value, load,
│   │   │   │   # rewrite, and reload owner.
│   │   │   ├── BiomeClass.java
│   │   │   │   # Sole dimension/temperature classifier and finite heat-profile selector.
│   │   │   ├── GhastState.java
│   │   │   │   # Immutable persistent Happy Ghast attachment value and codec: heat, ammo, shot,
│   │   │   │   # regeneration, cry, and pending-detonation ticks.
│   │   │   ├── Ammo.java
│   │   │   │   # Pure optional-ammo authority: independent elapsed regeneration, availability,
│   │   │   │   # and spend transitions; inactive when disabled.
│   │   │   ├── Heat.java
│   │   │   │   # Pure heat authority: water/passive cooling, firing-window timing, shot addition,
│   │   │   │   # and the codebase's only heat-limit comparison.
│   │   │   ├── RiderState.java
│   │   │   │   # Immutable persistent player attachment value and codec for stashed slots, ridden
│   │   │   │   # ghast id, input deduplication, and serializable HUD dirty-check state.
│   │   │   ├── Components.java
│   │   │   │   # Sole registration/catalog owner for fire-control and cry-control data components.
│   │   │   ├── Controls.java
│   │   │   │   # Sole pilot/input and control-item owner: swap/stash/restore, pre-drop restoration,
│   │   │   │   # slot locking helpers, callback deduplication, and hold/click intent.
│   │   │   ├── Abilities.java
│   │   │   │   # Sole fire/cry/detonation gate and effect owner, including sealed outcomes, projectile,
│   │   │   │   # protection-vetoed explosions/fire placement, sound, and ghast removal.
│   │   │   ├── Hud.java
│   │   │   │   # Sole boss/action-bar and warning-particle owner for pilots and read-only passengers;
│   │   │   │   # owns and evicts bounded process-local display handles.
│   │   │   ├── Feedback.java
│   │   │   │   # Sole rejection-reason to action-bar/sound mapping; cooldown rejection stays silent.
│   │   │   └── mixin/
│   │   │       └── SlotGuardMixin.java
│   │   │           # Sole mixin; cancels control-slot clicks and control-item drops while the pilot rides.
│   │   └── resources/
│   │       ├── fabric.mod.json
│   │       │   # Fabric identity, dependencies, entrypoint, mixin declaration, version, and icon.
│   │       ├── happy-artillery.mixins.json
│   │       │   # Declares only SlotGuardMixin and its injection requirements.
│   │       └── assets/happy-artillery/icon.png
│   │           # Packaged Happy Artillery icon.
│   └── test/
│       └── java/xyz/pyrehaven/happyartillery/
│           ├── ConfigTest.java
│           │   # Config defaults, presets, validation, round-trip, rewrite, and reload-failure contract.
│           ├── BiomeClassTest.java
│           │   # Dimension identity, custom-dimension, temperature-edge, and profile tests.
│           ├── GhastStateTest.java
│           │   # Fresh state, codec round-trip, attachment replacement, and persisted tick fields.
│           ├── AmmoTest.java
│           │   # Disabled mode, independent elapsed regeneration, caps, and spend tests.
│           ├── HeatTest.java
│           │   # All class curves, firing window, water ordering, unload gaps, and detonation edge tests.
│           ├── RiderStateTest.java
│           │   # ItemStack stash codec, ridden id, input tick, and HUD-cache round-trip tests.
│           ├── ComponentsTest.java
│           │   # Component identity, persistence codec, marker separation, and serialization tests.
│           ├── ControlsTest.java
│           │   # Pilot admission, callbacks, swap/restore, death ordering, slot guards, and dedup tests.
│           ├── AbilitiesTest.java
│           │   # Fire/cry gates, sealed outcomes, effects, vetoes, fuse, and exactly-once detonation tests.
│           ├── HudTest.java
│           │   # Pilot/passenger visibility, dirty checks, throttling, priority, particles, and teardown tests.
│           ├── FeedbackTest.java
│           │   # Visible rejection mappings and silent cooldown tests.
│           ├── SlotGuardMixinTest.java
│           │   # Injection decision tests for click, shift, drop, swap, and unrelated-slot inputs.
│           └── HappyArtilleryIntegrationTest.java
│               # Registration uniqueness, one-driver ordering, no-rider idle work, and complete wiring tests.
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
│   # MIT license packaged into the artifact.
├── .gitignore
│   # Excludes generated Gradle, IDE, run, world, log, and jar output.
├── build.gradle
│   # Loom/Java/JUnit build, resource processing, checks, sources jar, and publication definition.
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
