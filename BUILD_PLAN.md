# Minecraft 26.3 Upgrade Plan

Target: Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.160.7+26.3, Java 25, and the current Fabric-recommended Loom 1.17 line with Gradle 9.6.0.

## Phase 0 — Branch and route

- [x] Create `update/mc-26-3` from the merged `origin/main` head.
- [x] Push the branch and set its upstream.
- [x] Point the `mod:happy-artillery` project route and test profile at the new branch.
- [x] Preserve unrelated untracked release-copy files without staging or editing them.

## Phase 1 — Modernize the build boundary

- [x] Replace the legacy remapping Loom plugin with `net.fabricmc.fabric-loom` for unobfuscated Minecraft.
- [x] Remove the custom metadata HTTP server, identity mapping dependency, and tracked 26.2 mapping artifacts.
- [x] Pin Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.160.7+26.3, a stable Loom 1.17 release, Gradle 9.6.0, and Java 25 compilation.
- [x] Update direct Fabric API module dependencies to the module versions shipped by Fabric API 0.160.7+26.3.
- [x] Keep `fabric.mod.json` environment `"*"`; do not add client-only entrypoints or synced registries.
- [x] Run dependency resolution and compile tests to expose real 26.3 API breaks.

## Phase 2 — Port production code

- [x] Fix only compile/runtime breaks introduced by Minecraft 26.3 and the current Fabric API.
- [x] Keep behavior in the existing owners defined by `ARCHITECTURE.md`; do not add parallel handlers, managers, state maps, or fallback paths.
- [x] Re-check mixin targets and injection requirements against 26.3.
- [x] Preserve config migration, persistent attachments, control containment, heat/cooldown, HUD, fire, cry, and detonation invariants.
- [x] Run focused tests after each owner changes.

Phase 2 verification: resolved 26.3 class signatures/bytecode checked for all five mixin targets;
Fabric Loader JUnit confirms all five handlers are applied. Registry fixtures use
`createWorldLookup()` and empty bundles use the no-argument mutable constructor.
Focused Controls/Persistence/Integration tests: 114 passed. `./gradlew clean test build` on
Java 25: 403 passed, zero failures/errors/skips. The built JAR contains `JAVA_25` mixin
compatibility and retains environment `"*"`. Live server startup and connected-player
checks, including predicted drops, remain Phase 4 work; these checks prove the automated
regression boundary, not live gameplay.

## Phase 3 — Verify the artifact

- [x] Run the complete Gradle test and build suite on Java 25.
- [x] Inspect the built JAR metadata and contents, including `environment = "*"` and the 26.3 dependency floor.
- [x] Confirm no obsolete 26.2 metadata or mapping artifact is packaged or referenced.
- [x] Review the complete diff against the architecture contract and obtain the required independent review receipt before committing source changes.

Phase 3 verification: exact committed source at `f5a6461` built successfully with 403 tests and
zero failures/errors/skips. `happy-artillery-1.2.0.jar` SHA-256 was
`28c8967f2e497bcc2af31ec5b969e34f2e243341a1d813b8974bd746b810ce50`; its metadata declares
environment `"*"`, Minecraft `~26.3`, Loader `>=0.19.5`, Fabric API `>=0.160.7+26.3`, Java
`>=25`, and mixin compatibility `JAVA_25`. The JAR and tracked files contain no obsolete 26.2
custom-metadata or identity-mapping references. Independent review passed the staged diff as a
direct fix rather than concealment.

## Phase 4 — Upgrade and exercise the test server

- [x] Rebuild the disposable `mod:happy-artillery` pyretest profile from the repository’s 26.3/0.19.5 pins.
- [x] Ensure exactly Fabric API 0.160.7+26.3 and the newly built Happy Artillery JAR are installed.
- [x] Start the server and verify a clean 26.3 Fabric startup with Happy Artillery loaded and no mixin, dependency, or config errors.
- [x] Exercise the available server-side gameplay checks; record anything requiring a connected player as still pending rather than pretending startup tested gameplay.
- [x] Stop the test profile when verification is complete.

Phase 4 verification: pyretest rebuilt the disposable instance with Fabric Installer 1.1.2,
Minecraft 26.3, and Loader 0.19.5. Its `mods/` directory contained exactly Fabric API
0.160.7+26.3 and the exact built Happy Artillery JAR; source and deployed SHA-256 values both
matched `28c8967f2e497bcc2af31ec5b969e34f2e243341a1d813b8974bd746b810ce50`. The server log
loaded Happy Artillery 1.2.0, reached `Done (1.707s)`, and contained no startup, dependency,
or mixin error. No connected player was available, so riding, firing, cry, overheat, HUD, and
predicted-drop gameplay remain manual acceptance work. The profile was stopped and port 25565
was verified free.

## Phase 5 — Handoff

- [ ] Commit coherent changes on `update/mc-26-3`, push the branch, and open or update a PR against `main`.
- [ ] Report exact test/build/server evidence and any remaining player test. Elijah decides whether to merge or release.
