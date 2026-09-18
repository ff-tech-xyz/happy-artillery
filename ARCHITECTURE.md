# Happy Artillery 1.2.0 / Minecraft 26.3 Structure

The annotated tree is the complete proposed source-controlled shape: sixteen production Java files
and eight risk-grouped test files.

```text
happy-artillery/
├── src/
│   ├── main/
│   │   ├── java/xyz/pyrehaven/happyartillery/
│   │   │   ├── HappyArtillery.java
│   │   │   │   # Composition root that invokes each owner's registration, reload command, durable
│   │   │   │   # Overworld-game-time context, sole player tick driver, UUID-resolution access,
│   │   │   │   # use/block/entity callbacks, ghast-load callback, and bounded player-availability
│   │   │   │   # wake-up; no gameplay policy.
│   │   │   ├── Config.java
│   │   │   │   # Sole config schema/codec, released-flat-config migration, defaults, individual overrides,
│   │   │   │   # validation, backup/replacement, and atomic live-value owner; preserves exact bytes for
│   │   │   │   # successful current sparse loads/reloads, while unsupported legacy settings fail safely.
│   │   │   ├── BiomeClass.java
│   │   │   │   # Sole dimension/temperature classifier and finite heat-profile selector.
│   │   │   ├── GhastState.java
│   │   │   │   # Immutable persistent Happy Ghast attachment value/codec: heat anchor plus independent
│   │   │   │   # fire-ready and cry-ready ticks, paired pending-detonation deadline/rider identity, and
│   │   │   │   # shared saturated seconds-to-tick deadline arithmetic.
│   │   │   ├── Heat.java
│   │   │   │   # Pure heat authority: anchored, non-double-counted profile cooling, firing window,
│   │   │   │   # shot addition, and the codebase's only heat-limit comparison.
│   │   │   ├── RiderState.java
│   │   │   │   # Immutable persistent player attachment value/codec for ridden-ghast identity,
│   │   │   │   # input deduplication, and HUD dirty-check state only.
│   │   │   ├── Components.java
│   │   │   │   # Sole fire/cry marker codec/helper owner using namespaced vanilla CUSTOM_DATA with
│   │   │   │   # control type, owner UUID, and ride UUID; preserves unrelated custom data.
│   │   │   ├── Controls.java
│   │   │   │   # Sole control owner: enabled-aware atomic free-slot allocation, marked block-use policy,
│   │   │   │   # one bounded active-pilot inventory snapshot, held admission, cleanup, and ride transitions.
│   │   │   ├── Abilities.java
│   │   │   │   # Sole fire/cry/detonation gate, truthful effect/removal outcome, collision-clear launch,
│   │   │   │   # and UUID-only fuse-task owner; resolves entities at execution, isolates due tasks,
│   │   │   │   # consumes pending state durably, and owns vanilla fireballs, effects, sound, and removal.
│   │   │   ├── Hud.java
│   │   │   │   # Sole typed boss/action-bar/warning presentation path for pilots and read-only passengers;
│   │   │   │   # consumes shared control/effective-cooling context and owns bounded display handles.
│   │   │   ├── Feedback.java
│   │   │   │   # Sole visible rejection to action-bar/sound mapping; cooldown and authorization stay silent.
│   │   │   └── mixin/
│   │   │       ├── PlayerDropMixin.java
│   │   │       │   # Observes ServerPlayer.drop(ItemStack, boolean, Prediction) at RETURN and discards
│   │   │       │   # returned marked ItemEntity drops while leaving ordinary drops unchanged.
│   │   │       ├── ExternalContainerMixin.java
│   │   │       │   # Transforms Slot.set(ItemStack) writes, consuming marked controls outside their
│   │   │       │   # owner's inventory, including inventory and table crafting inputs.
│   │   │       ├── BundleContentsMixin.java
│   │   │       │   # Delegates bundle insertion eligibility to Controls before vanilla accepts a stack.
│   │   │       ├── HeldProjectileMixin.java
│   │   │       │   # Excludes marked controls from projectile-weapon held-ammunition selection.
│   │   │       └── PlayerProjectileMixin.java
│   │   │           # Excludes marked controls from player-inventory ammunition selection while allowing
│   │   │           # the search to continue to ordinary compatible ammunition.
│   │   └── resources/
│   │       ├── fabric.mod.json
│   │       │   # Fabric identity, dependencies, entrypoint, mixin declaration, version, and icon.
│   │       ├── happy-artillery.mixins.json
│   │       │   # Declares the narrow mixins with their fail-closed injection requirements.
│   │       └── assets/happy-artillery/icon.png
│   │           # Packaged Happy Artillery icon.
│   └── test/
│       └── java/xyz/pyrehaven/happyartillery/
│           ├── ConfigTest.java
│           │   # Config defaults, individual overrides, removed/unknown-key rejection, validation,
│           │   # rename diagnostics, path-aware type errors, cooling-theme thresholds/colors,
│           │   # sparse-byte preservation, released-config migration/backup, missing-file publication,
│           │   # reference parity,
│           │   # registry-lifecycle resolution, and reload-failure contract.
│           ├── BiomeClassTest.java
│           │   # Dimension identity, custom-dimension, temperature-edge, and profile tests.
│           ├── HeatTest.java
│           │   # Curves, anchored non-double cooling, firing window, restart/unload gaps,
│           │   # and exact detonation-edge tests.
│           ├── PersistenceTest.java
│           │   # Ghast/Rider fresh values, codecs, immutable attachment replacement, durable tick anchors,
│           │   # ride identity, input tick, and HUD-cache round trips with no ItemStack/index persistence.
│           ├── ControlsTest.java
│           │   # Owner/ride marker identity, atomic allocation, bounded snapshot, held admission,
│           │   # same-player mobility, outbound consumption, cleanup, no-overwrite, and dedup tests.
│           ├── AbilitiesTest.java
│           │   # Fire/cry gates, truthful outcomes, vanilla fireballs, shared collision clearance,
│           │   # UUID-only failure-isolated fuse scheduling/resolution, wake-up, and detonation tests.
│           ├── HudTest.java
│           │   # Typed pilot/passenger presentation, effective-rate text/colors, control-warning
│           │   # priority, dirty checks, throttling, particles, snapshot sharing, and teardown tests.
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
│   # Historical rebuild record; not a second active behavior or execution authority.
├── docs/
│   └── happy-artillery-config.jsonc
│       # Documentation-only annotated admin reference kept in parity with Config defaults; runtime
│       # remains strict JSON and does not parse this file.
├── README.md
│   # Installation, controls, configuration, and supported-version documentation.
├── CHANGELOG.md
│   # Public released-version history, not intermediate rewrite state.
├── LICENSE
│   # Complete CC0 1.0 Universal legal text packaged into the artifact.
├── .gitignore
│   # Excludes generated Gradle, IDE, run, world, log, and jar output.
├── BUILD_PLAN.md
│   # Phased Minecraft 26.3 upgrade checklist and verification boundary.
├── build.gradle
│   # Non-remapping Loom/Java/JUnit and Fabric API dependencies, resources, checks, jars, and publication.
├── gradle.properties
│   # Pinned Minecraft, Fabric, Loom, Java-facing, artifact, and Maven-coordinate values.
├── settings.gradle
│   # Plugin-resolution repositories and project identity.
├── gradlew
│   # Unix Gradle wrapper launcher.
├── gradlew.bat
│   # Windows Gradle wrapper launcher.
└── gradle/
    └── wrapper/
        ├── gradle-wrapper.jar
        │   # Source-controlled Gradle wrapper bootstrap.
        └── gradle-wrapper.properties
            # Pinned Gradle distribution and wrapper settings.
```
