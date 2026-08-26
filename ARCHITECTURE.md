# Happy Artillery Structure

This is the proposed 1.2.0 ownership tree. `config` is the leaf; `EnvironmentPolicy` reads config;
`ArtilleryState` reads config/environment; fire and cry read state/environment/config; callback routing
reads `ControllerPolicy` and invokes fire/cry; inventory and presentation read controller policy plus
state; lifecycle invokes those owners; and the composition root only constructs/registers them. No
arrower owner calls lifecycle or the composition root, fire/cry never call controls, and presentation
never changes gameplay state or classification.

```text
happy-artillery/
├── src/
│   ├── main/
│   │   ├── java/xyz/pyrehaven/happyartillery/
│   │   │   ├── HappyArtillery.java
│   │   │   │   # Fabric composition root only: loads the one config instance, constructs owners in
│   │   │   │   # dependency order, and registers them. It owns no gameplay decision, state, or effect.
│   │   │   ├── config/
│   │   │   │   └── HappyArtilleryConfig.java
│   │   │   │       # Sole owner of the 24-field config schema, released defaults, JSON load/rewrite,
│   │   │   │       # config-path selection, and the accepted validation/startup-failure policy.
│   │   │   ├── controls/
│   │   │   │   ├── ControllerPolicy.java
│   │   │   │   │   # Sole reusable classifier for Happy Ghast controllers and fire/cry routing tokens;
│   │   │   │   │   # setup, input, presentation, and cleanup all invoke the accepted policy here.
│   │   │   │   └── HappyGhastControls.java
│   │   │   │       # Owns item-use and ridden-entity-use callback admission, hand/result semantics,
│   │   │   │       # and dispatch of one admitted input to FireAction or CryAction; it performs no action.
│   │   │   ├── fire/
│   │   │   │   ├── FireAction.java
│   │   │   │   │   # Owns fire's ordered water/heat/cooldown/ammo admission and exactly-once state
│   │   │   │   │   # transition, then selects normal projectile or overheat mechanics without mutating the world.
│   │   │   │   ├── ProjectileFire.java
│   │   │   │   │   # Sole normal-shot world-mutation path: launch geometry, owner/power/sound, projectile
│   │   │   │   │   # add result, and the accepted chunk-loading/failure outcome.
│   │   │   │   └── OverheatEffect.java
│   │   │   │       # Sole overheat world-mutation path: main explosion, deterministic 48-fireball sphere,
│   │   │   │       # overheat sound, supported random fire placement, and accepted partial-failure semantics.
│   │   │   ├── cry/
│   │   │   │   └── CryAction.java
│   │   │   │       # Owns cry's water/cooldown admission, exactly-once cooldown transition, and the one
│   │   │   │       # GHAST_SCREAM world-effect path with its accepted failure outcome.
│   │   │   ├── state/
│   │   │   │   ├── ArtilleryState.java
│   │   │   │   │   # Sole owner of memory-only gameplay state and transitions: per-ghast ammo, heat,
│   │   │   │   │   # shot/cooling/ammo timing, per-player cry cooldown, control restoration records,
│   │   │   │   │   # regeneration/cooling advancement, snapshots, and explicit eviction.
│   │   │   │   └── EnvironmentPolicy.java
│   │   │   │       # Sole environment classifier and profile owner: dimension/temperature -> finite mode
│   │   │   │       # plus that mode's heat gain, limit, cooling interval, warning, color, and label inputs.
│   │   │   ├── inventory/
│   │   │   │   └── ControlItems.java
│   │   │   │       # Sole control-item mutation path for indexes 4/5 and scoped death/drop cleanup: creates
│   │   │   │       # temporary controls, applies owned decoration, converges movement, and restores/removes it.
│   │   │   ├── presentation/
│   │   │   │   └── RiderPresentation.java
│   │   │   │       # Sole rider-output path for heat boss bars, ammo/cooling action bars, warning particles,
│   │   │   │       # and display-handle cleanup; reads snapshots/profiles and never changes combat policy.
│   │   │   └── lifecycle/
│   │   │       └── ArtilleryLifecycle.java
│   │   │           # Owns bounded server-tick orchestration and death, disconnect, entity-loss, and server-stop
│   │   │           # teardown; calls the named owners and never duplicates their policy, state, or mutations.
│   │   └── resources/
│   │       ├── fabric.mod.json
│   │       │   # Fabric identity, dependencies, universal/server-authoritative entrypoint, version, and icon.
│   │       └── assets/happy-artillery/icon.png
│   │           # Packaged Happy Artillery icon. No mixin, access-widener, language, or data resource is
│   │           # proposed because accepted 1.2.0 behavior does not require one.
│   └── test/
│       └── java/xyz/pyrehaven/happyartillery/
│           ├── HappyArtilleryRegressionSuite.java
│           │   # Dependency-free Gradle test dispatcher: runs all suites or one named risk slice and
│           │   # returns a failing process for assertion failures; it contains no behavior oracle itself.
│           ├── config/
│           │   └── HappyArtilleryConfigTest.java
│           │       # Characterizes all 24 defaults and complete rewrites; tests missing/null/unknown,
│           │       # malformed/type-invalid/I/O cases and the accepted validation/failure decision.
│           ├── controls/
│           │   └── ControllerPolicyTest.java
│           │       # Covers Happy Ghast/controller/token classification, both hands and callbacks,
│           │       # PASS/FAIL/SUCCESS routing, and the accepted driver/raw-item authorization matrix.
│           ├── fire/
│           │   ├── FireActionTest.java
│           │   │   # Covers exact denial order, no-spend denials, water transition, exactly-once accepted
│           │   │   # shot state, configured threshold selection, and normal-versus-overheat dispatch.
│           │   ├── ProjectileFireTest.java
│           │   │   # Covers launch position/vector/owner/power/sound, add success/failure, and the accepted
│           │   │   # bounded chunk-request and failed-projectile world-mutation outcome.
│           │   └── OverheatEffectTest.java
│           │       # Covers explosion arguments, 48-entry golden-spiral order, sound, supported fire bounds,
│           │       # add failures, and the accepted partial-effect contract without a second effect path.
│           ├── cry/
│           │   └── CryActionTest.java
│           │       # Covers water and cooldown denials, player-keyed timing, sound arguments, no ammo/heat
│           │       # mutation, and the accepted world/sound-failure boundary.
│           ├── state/
│           │   ├── ArtilleryStateTest.java
│           │   │   # Covers unseen/full ammo, elapsed-interval regeneration, shot/restart timing, heat/water
│           │   │   # transitions, cry cooldowns, snapshots, restart semantics, and explicit eviction.
│           │   └── EnvironmentPolicyTest.java
│           │       # Covers all five modes, dimension precedence, threshold edges, and one consistent profile
│           │       # for fire, cooling, warnings, colors, and labels after the environment gate is resolved.
│           ├── inventory/
│           │   └── ControlItemsTest.java
│           │       # Covers empty/occupied slots, exact one-control convergence, movement deletion, owned
│           │       # restoration, dismount/death/drop scope, and no collateral item/player/world mutation.
│           ├── presentation/
│           │   └── RiderPresentationTest.java
│           │       # Covers heat formatting/progress/color, ammo bands, cooling labels, warnings/particles,
│           │       # first/accepted controller visibility, read-only snapshots, and handle teardown.
│           └── lifecycle/
│               └── ArtilleryLifecycleTest.java
│                   # Covers one registration per callback, bounded tick work, ordering among owners, and
│                   # disconnect/death/entity-loss/server-stop cleanup with no stale process-wide records.
├── AGENTS.md
│   # Repository-specific routing, structure, commit, and runtime rules for contributors and agents.
├── ARCHITECTURE.md
│   # This complete proposed ownership tree; structural groundwork must update it before source moves.
├── FEATURES.md
│   # Accepted 1.2.0 behavior, released evidence, and product decisions; architecture does not override it.
├── MIGRATION_PLAN.md
│   # Dependency-ordered rebuild slices, decision gates, RED/GREEN evidence, and commit/test boundaries.
├── README.md
│   # Player/server-owner installation, use, configuration, support, and current deployability status.
├── CHANGELOG.md
│   # Player-facing released-version history; not an implementation work log.
├── LICENSE
│   # MIT license packaged into the artifact by the build.
├── .gitignore
│   # Excludes Gradle output/cache, local runtimes, IDE state, and OS files.
├── build.gradle
│   # Fabric Loom build, source/resource processing, dependency-free regression JavaExec, clean/build gate,
│   # sources jar, and publication definition; scaffold identity remains until gameplay activation.
├── gradle.properties
│   # Pinned Minecraft/Fabric/Loom/Java-facing versions plus artifact version and preserved Maven coordinate.
├── settings.gradle
│   # Build plugin repositories used to resolve Fabric Loom.
├── gradlew
│   # Unix Gradle wrapper launcher.
├── gradlew.bat
│   # Windows Gradle wrapper launcher.
└── gradle/
    ├── minecraft/
    │   ├── 26.2-custom.json
    │   │   # Loom-compatible Minecraft 26.2 metadata consumed by the local metadata server in the build.
    │   └── identity-official-26.2.jar
    │       # Pinned official-name mapping input consumed directly by the mappings configuration.
    └── wrapper/
        ├── gradle-wrapper.jar
        │   # Source-controlled Gradle wrapper bootstrap binary.
        └── gradle-wrapper.properties
            # Pinned Gradle distribution and wrapper settings.
```

Generated `build/`, `.gradle/`, `run/`, IDE files, local worlds/configs/logs, test-server artifacts,
and release jars are intentionally excluded.
