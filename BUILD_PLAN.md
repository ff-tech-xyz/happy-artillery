# Minecraft 26.3 Upgrade Plan

Target: Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.160.7+26.3, Java 25, and the current Fabric-recommended Loom 1.17 line with Gradle 9.6.0.

## Phase 0 — Branch and route

- [x] Create `update/mc-26-3` from the merged `origin/main` head.
- [x] Push the branch and set its upstream.
- [x] Point the `mod:happy-artillery` project route and test profile at the new branch.
- [x] Preserve unrelated untracked release-copy files without staging or editing them.

## Phase 1 — Modernize the build boundary

- [ ] Replace the legacy remapping Loom plugin with `net.fabricmc.fabric-loom` for unobfuscated Minecraft.
- [ ] Remove the custom metadata HTTP server, identity mapping dependency, and tracked 26.2 mapping artifacts.
- [ ] Pin Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.160.7+26.3, a stable Loom 1.17 release, Gradle 9.6.0, and Java 25 compilation.
- [ ] Update direct Fabric API module dependencies to the module versions shipped by Fabric API 0.160.7+26.3.
- [ ] Keep `fabric.mod.json` environment `"*"`; do not add client-only entrypoints or synced registries.
- [ ] Run dependency resolution and compile tests to expose real 26.3 API breaks.

## Phase 2 — Port production code

- [ ] Fix only compile/runtime breaks introduced by Minecraft 26.3 and the current Fabric API.
- [ ] Keep behavior in the existing owners defined by `ARCHITECTURE.md`; do not add parallel handlers, managers, state maps, or fallback paths.
- [ ] Re-check mixin targets and injection requirements against 26.3.
- [ ] Preserve config migration, persistent attachments, control containment, heat/cooldown, HUD, fire, cry, and detonation invariants.
- [ ] Run focused tests after each owner changes.

## Phase 3 — Verify the artifact

- [ ] Run the complete Gradle test and build suite on Java 25.
- [ ] Inspect the built JAR metadata and contents, including `environment = "*"` and the 26.3 dependency floor.
- [ ] Confirm no obsolete 26.2 metadata or mapping artifact is packaged or referenced.
- [ ] Review the complete diff against the architecture contract and obtain the required independent review receipt before committing source changes.

## Phase 4 — Upgrade and exercise the test server

- [ ] Rebuild the disposable `mod:happy-artillery` pyretest profile from the repository’s 26.3/0.19.5 pins.
- [ ] Ensure exactly Fabric API 0.160.7+26.3 and the newly built Happy Artillery JAR are installed.
- [ ] Start the server and verify a clean 26.3 Fabric startup with Happy Artillery loaded and no mixin, dependency, or config errors.
- [ ] Exercise the available server-side gameplay checks; record anything requiring a connected player as still pending rather than pretending startup tested gameplay.
- [ ] Stop the test profile when verification is complete.

## Phase 5 — Handoff

- [ ] Commit coherent changes on `update/mc-26-3`, push the branch, and open or update a PR against `main`.
- [ ] Report exact test/build/server evidence and any remaining player test. Elijah decides whether to merge or release.
