# Happy Artillery 1.2.0 Proposed Structure

The annotated tree below is the complete proposed source-controlled shape. It contains fourteen
production Java files and eight risk-grouped test files.

```text
happy-artillery/
├── src/
│   ├── main/
│   │   ├── java/xyz/pyrehaven/happyartillery/
│   │   │   ├── HappyArtillery.java
│   │   │   │   # Composition root that invokes each owner's registration, op-only reload command,
│   │   │   │   # durable Overworld-game-time context, sole player tick driver, and ghast-load callback;
│   │   │   │   # no gameplay policy or second fuse poller.
│   │   │   ├── Config.java
│   │   │   │   # Sole config schema/codec, defaults, presets, validation, atomic live value, load,
│   │   │   │   # rewrite, and reload owner.
│   │   │   ├── BiomeClass.java
│   │   │   │   # Sole dimension/temperature classifier and finite heat-profile selector.
│   │   │   ├── GhastState.java
│   │   │   │   # Immutable persistent Happy Ghast attachment value/codec: heat anchor plus independent
│   │   │   │   # fire-ready, cry-ready, and pending-detonation Overworld-game-time deadlines.
│   │   │   ├── Heat.java
│   │   │   │   # Pure heat authority: anchored, non-double-counted water/passive cooling, firing window,
│   │   │   │   # shot addition, and the codebase's only heat-limit comparison.
│   │   │   ├── RiderState.java
│   │   │   │   # Immutable persistent player attachment value/codec for stashed stacks plus their original
│   │   │   │   # slot indexes, ridden ghast id, input deduplication, and HUD dirty-check state.
│   │   │   ├── Components.java
│   │   │   │   # Defines, catalogs, and registers fire/cry control data components exactly once; exposes
│   │   │   │   # one registration call for HappyArtillery composition.
│   │   │   ├── Controls.java
│   │   │   │   # Sole pilot/input and control-item owner: swap/stash/restore, pre-drop restoration,
│   │   │   │   # slot locking helpers, callback deduplication, and hold/click intent.
│   │   │   ├── Abilities.java
│   │   │   │   # Sole fire/cry/detonation gate, effect, and fuse-scheduling owner, including server-queue
│   │   │   │   # deadlines, load-time re-establishment, vanilla LargeFireball spawning, configured protected
│   │   │   │   # overheat effects, sound, and removal; normal fire has no custom projectile or veto path.
│   │   │   ├── Hud.java
│   │   │   │   # Sole boss/action-bar and warning-particle owner for pilots and read-only passengers;
│   │   │   │   # owns and evicts bounded process-local display handles.
│   │   │   ├── Feedback.java
│   │   │   │   # Sole visible rejection to action-bar/sound mapping; cooldown and authorization stay silent.
│   │   │   └── mixin/
│   │   │       ├── DeathDropMixin.java
│   │   │       │   # Wraps ServerPlayer's committed-death loot invocation so Controls restores the
│   │   │       │   # persistent stash before vanilla snapshots or emits inventory drops.
│   │   │       ├── PlayerDropMixin.java
│   │   │       │   # Intercepts ServerPlayer's direct Q/drop path and delegates the selected-slot
│   │   │       │   # protection decision to Controls.
│   │   │       └── SlotGuardMixin.java
│   │   │           # Intercepts AbstractContainerMenu mutation routes and delegates locked player-slot
│   │   │           # decisions to Controls.
│   │   └── resources/
│   │       ├── fabric.mod.json
│   │       │   # Fabric identity, dependencies, entrypoint, mixin declaration, version, and icon.
│   │       ├── happy-artillery.mixins.json
│   │       │   # Declares all three narrow mixins with their fail-closed injection requirements.
│   │       └── assets/happy-artillery/icon.png
│   │           # Packaged Happy Artillery icon.
│   └── test/
│       └── java/xyz/pyrehaven/happyartillery/
│           ├── ConfigTest.java
│           │   # Config defaults, presets, validation, round-trip, rewrite, and reload-failure contract.
│           ├── BiomeClassTest.java
│           │   # Dimension identity, custom-dimension, temperature-edge, and profile tests.
│           ├── HeatTest.java
│           │   # Curves, anchored non-double cooling, firing window, water ordering, restart/unload gaps,
│           │   # and exact detonation-edge tests.
│           ├── PersistenceTest.java
│           │   # Ghast/Rider fresh values, codecs, immutable attachment replacement, durable tick anchors,
│           │   # indexed ItemStack stashes, ridden id, input tick, and HUD-cache round trips.
│           ├── ControlsTest.java
│           │   # Component registration/serialization, pilot admission, callbacks, indexed swap/restore,
│           │   # live slot reload, death ordering, SlotGuardMixin decisions, and dedup tests.
│           ├── AbilitiesTest.java
│           │   # Fire/cry gates, sealed outcomes, vanilla LargeFireball identity/ownership/defaults,
│           │   # feedback/effects, server-queue fuse scheduling/load wake-up, and detonation tests.
│           ├── HudTest.java
│           │   # Pilot/passenger visibility, dirty checks, throttling, priority, particles, and teardown tests.
│           └── HappyArtilleryIntegrationTest.java
│               # Registration uniqueness, one-driver ordering, bounded online-player idle reconciliation,
│               # no world/entity scan, and complete wiring tests.
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
