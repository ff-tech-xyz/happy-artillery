# Happy Artillery 1.2.0 Migration Plan

## Checkpoint contract

Build the settled `FEATURES.md` contract into the proposed `ARCHITECTURE.md` tree in the order below.
Released source is defect evidence, not code to copy. Every slice is delegated to one fresh writer,
independently reviewed by the coordinator, verified on its final bytes, committed as one coherent GREEN
checkpoint, and pushed to the existing non-`main` branch before the next slice starts.

The entrypoint must keep throwing the deliberate startup exception and the artifact/Fabric metadata
must remain visibly non-deployable through Slice 10. Partial gameplay is never registered or deployed.
Only Slice 11 may remove that guard after every owner is GREEN.

For every automatable behavior, use one vertical RED -> GREEN cycle at a time:

1. add one behavior assertion;
2. run the focused JUnit test and observe the expected behavior failure, not a compile/setup error;
3. add the minimum production behavior;
4. rerun focused and full tests, then refactor only while GREEN.

Before each commit: inspect status and complete diff; compare source/resource/test paths with
`ARCHITECTURE.md`; run focused tests, full tests, `./gradlew clean build`, `git diff --check`, and staged
diff checks; scan for old owners, static maps, wall clocks, duplicate callbacks, global scans, fallback
effects, silent catches, secrets, and unexpected dependency/toolchain changes. Any changed final byte
invalidates earlier evidence. Push only after the coordinator verifies the exact commit.

JUnit is an accepted requirement in the supplied specification. The build-support slice must pin the
smallest existing-compatible JUnit dependency and make `test` part of `check`; it may not change Loom,
Minecraft, Fabric, mappings, Gradle, or Java versions.

## Settled assumptions carried into implementation

- The proposed tree has thirteen production Java files: eleven named non-mixin owners plus the missing
  `Ammo` owner and the one `SlotGuardMixin`.
- `GhastState` includes per-ghast cry and pending-fuse timing. `Ammo` owns optional ammo transitions.
- Passengers receive read-only HUD; only the pilot advances state or abilities.
- Protection-visible explosions/fire placement, pre-drop restoration, and the hold-to-fire Java +
  Bedrock gate are mandatory, not later polish.
- The fail-loud requirement prevents a committed partial server from running. Attachment persistence is
  first proven automatically by codec/attachment round-trip. A disposable, never-committed instrumented
  spike may disable the guard for API/manual evidence, but the committed checkpoint must restore it.
  Exact-head restart persistence remains the first blocking runtime check after final activation.

## Dependency-ordered slices

### Slice 0 — Accept the settled documents

**Files:** `ARCHITECTURE.md`, `FEATURES.md`, `MIGRATION_PLAN.md` only.

Validate the tree, unique paths/owners, links, Markdown structure, and whitespace. No source, resource,
build, README, CHANGELOG, test-server, dependency, project-state, commit, or push mutation belongs to the
worker task. Coordinator checkpoint: `docs(architecture): settle 1.2.0 rebuild design`.

### Slice 1 — Realign the fail-loud scaffold

**Prerequisite:** Slice 0 is committed and remotely reachable so the architecture gate precedes moves.

Move the empty production/test shells to the proposed paths; add empty owners/resources only where the
tree requires them; remove superseded shells. Update package declarations and mixin metadata, but no
gameplay or callback behavior. Preserve deliberate startup failure and non-deployable names.

**GREEN evidence:** compilation, exact bidirectional architecture/filesystem comparison, runtime and
sources jar inspection, and isolated `runServer` log proving the declared entrypoint fails with the exact
deliberate exception. Commit `refactor(scaffold): align settled owner tree` and push. No human test.

### Slice 2 — Config, presets, and JUnit build support

Implement `Config` and `ConfigTest`; configure the accepted JUnit test task without changing the
toolchain. Cover the complete nested schema/defaults, preset-before-explicit precedence, missing file/key
rewrite, unknown-key removal, identifier/range/cross-field validation, strict startup failure, atomic
call-time reads, and failed-reload last-known-good behavior.

**RED:** focused config assertions fail against the empty owner. **GREEN:** focused config tests, full
tests, clean build, serialized complete-default comparison, and single config-I/O owner search. Commit
`feat(config): define 1.2.0 settings` and push. Remains non-deployable.

### Slice 3 — One biome classifier

Implement `BiomeClass` only. Test vanilla dimension-key identity, custom ids containing `nether`/`end`,
unknown-dimension temperature policy, exact 0.3/1.0 edges, all five profiles, and config reload reads.

**RED:** classifier tests fail against the shell. **GREEN:** focused/full tests, clean build, and search
proving no other production file classifies dimension or temperature. Commit
`feat(state): classify artillery biomes` and push. Remains non-deployable.

### Slice 4 — Persistent ghast and rider attachments

Implement immutable `GhastState` and `RiderState` records/codecs and register both persistent attachment
types from the composition root without registering gameplay. Include heat/ammo/shot/regen/cry/fuse
ticks, byte-exact two-stack stash, ridden id, input dedup tick, and serializable HUD cache.

**RED:** fresh-state, codec, ItemStack, attachment replacement, and encode/decode assertions fail first.
**GREEN:** focused/full tests, clean build, serialization round-trips, immutable replacement proof, and
search proving no static gameplay map or wall clock. If API names need proof, use a disposable
instrumented run and restore the fail-loud final bytes. Commit `feat(state): persist ghast and rider state`
and push. A real restart proof is still required at Slice 11 before any gameplay claim.

### Slice 5 — Pure heat and optional ammo

Implement `Heat`, `Ammo`, and their tests without world/entity access. Take one tracer behavior at a
time: each biome's sustained curve; exact-limit detonation; firing window; water-before-Nether ordering;
unload gaps; disabled ammo; independent complete-interval regeneration; caps; and spend.

**RED/GREEN:** every pure transition assertion must be observed failing then passing. Run focused heat
and ammo tests, full tests, clean build, and searches proving `Heat` owns the only limit comparison and
`Ammo` owns every ammo calculation. Commit `feat(state): implement heat and optional ammo` and push.
Remains non-deployable.

### Slice 6 — Resolve the hold-to-fire gate

This is a spike and decision checkpoint, not feature implementation. In a disposable runtime/worktree
build, confirm the exact 26.2 consumable component API and hold behavior. Test one Java client and one
Bedrock client through Geyser in the same session; measure accepted shots over time and require a steady
configured four per second without packet-rate dependence.

- If it passes, retain 0.25 seconds and the preferred per-shot defaults.
- If it fails, reject packet heuristics, select click-to-fire at 0.5 seconds, double every per-shot heat
  default, and update config/heat tests plus `FEATURES.md` in a reviewed GREEN checkpoint.

Remove disposable instrumentation and verify the pushed branch still fails loudly. Record the chosen
path before Slice 7. No hand-waved or Java-only result passes this gate.

### Slice 7 — Components, controls, and pre-drop restoration

Implement `Components`, `Controls`, `SlotGuardMixin`, mixin metadata, and tests. Cover component
persistence, pilot-only admission, both callbacks/hands, one-input-per-player-tick deduplication,
hold/click intent, exactly two mount/two restore writes, byte-exact stash, scoped creative cleanup,
pre-drop death restoration, and every click/drop/swap cancellation route.

**RED/GREEN:** automate owner logic and injection decisions first. Then run a disposable Java + Bedrock
Geyser session for the complete abuse list: named/full inventories, death, logout, ghast removal, hard
server stop, dimension change, all slot movements, two riders, creative duplication, plain-item denial,
and Bedrock ghost-item checks. The pre-drop API must be observed restoring before vanilla drops; a tick
backstop alone fails. Restore fail-loud final bytes, run focused/full tests and clean build, commit
`feat(controls): swap and protect pilot controls`, and push.

### Slice 8 — Abilities and feedback

Implement `Abilities` and `Feedback`. Vertical tests cover pilot/water/cooldown/ammo gates, sealed
results, exactly-once state transitions, normal projectile geometry/speed, no chunk loading/fallback,
per-ghast cry, silent cooldown feedback, visible other rejection, instant and fused overheat, sphere/fire
counts, ghast removal, and `killsGhast=false` recovery.

Before GREEN, prove the exact 26.2 protection-visible path with a real claims/protection integration:
normal projectile explosions, overheat explosion, and each fire placement must use the rider as cause
and permit veto. A veto skips that mutation; no direct bypass or fallback is allowed.

Run focused/full tests, clean build, mutation-owner accounting, and searches for alternate effect paths.
Commit `feat(abilities): implement artillery actions` and push. Remains non-deployable.

### Slice 9 — Read-only pilot and passenger HUD

Implement `Hud` with boss bars, four-tick action-bar throttling, dirty checks, warning particles, Nether
priority, and bounded handle cleanup. Update pilots and passengers from one post-transition snapshot;
passengers cannot advance or alter it.

**RED:** tests fail for creation/removal counts, no remove-add pair, changed-value updates, colors,
warning threshold, ammo-disabled text, passenger visibility, and teardown. Include a mutation guard proving
HUD cannot change state or classify again. **GREEN:** focused/full tests, clean build, and a packet capture
showing single-digit HUD updates per rider/second. Commit `feat(hud): show artillery status` and push.
Remains non-deployable.

### Slice 10 — Wire the complete driver while guarded

Implement the final `HappyArtillery` owner graph and integration tests behind the deliberate startup
guard. The designed runtime order is: reconcile all players; process each ridden ghast once through its
pilot using one biome context; then render pilot/passenger HUD from the resulting snapshot. Register each
callback, attachment, component, mixin path, death hook, and server-stop cleanup exactly once.

**RED:** integration tests fail on missing registrations/order. **GREEN:** focused/full tests, clean build,
registration enumeration, no-rider empty-loop proof, no world/entity sweep, and profiler harness showing
no measurable gameplay tick work with nobody riding. Commit `feat(integration): wire guarded artillery`
and push. The artifact must still fail loudly and remain undeployable.

### Slice 11 — Activate and prove the complete candidate

Prerequisites: Slices 1-10 are pushed GREEN, the hold path is recorded, protection and controls manual
gates passed, and independent architecture/behavior reviews found no hidden second owner.

**RED:** an integration assertion requires normal startup while the deliberate guard still fails.
**GREEN:** remove only the guard/non-deployable naming, register the already-complete graph, and retain
truthful 26.2-only metadata. Add the op-only `/ha reload`; do not add `/happytest`.

On final bytes run every focused test, full JUnit suite, `./gradlew clean build`, exact-tree/residue and
mutation accounting, runtime/sources jar inspection, embedded metadata validation, secret scan, and an
isolated server startup. Commit `feat: activate Happy Artillery 1.2.0` and push the exact non-main head.

The first runtime gate is attachment persistence: set heat and stash state using controlled test support,
restart/unload/reload, and prove exact restoration. Failure stops all later tests. Then deploy the exact
committed jar through `pyretest`, match source/deployed checksums, verify startup logs, and run the full
Java + Bedrock abuse list, normal fire, every biome/water curve, optional ammo, per-ghast cry, hold rate,
instant/fused overheat, protection vetoes, HUD packet rate, cleanup, and idle profiler tests. A startup
pass alone is not gameplay acceptance.

### Slice 12 — Ship-ready documentation only

After gameplay acceptance, update README and CHANGELOG to say Minecraft 26.2 only, describe the chosen
hold path, controls, presets, `/ha reload`, Geyser support, and observed behavior. Verify README claims
against the exact jar and tests. This slice opens no release, Modrinth, production, or `main` action;
those remain separate Elijah-gated release work.

## Ownership and deletion audit

Before activation, parse `ARCHITECTURE.md` and compare declared paths both directions with tracked and
untracked production/resource/test/build-support paths. Every path and owner must appear once.

Production/resources must contain no released `happy.artillery` package, lore markers, static UUID maps,
`System.currentTimeMillis`, inventory/world/entity sweep, delayed sync queue, injected player extension,
old accessor/mixin, `/happytest`, eager chunk load, instant-ray fallback, duplicate classifier, duplicate
callback effect, broad/sampled catch, routine per-tick logging, or silent placeholder. Enumerate every
inventory write, discard, projectile spawn, explosion, fire placement, sound, particle, boss/action-bar
send, attachment mutation, callback registration, and player/world/entity iteration; each must resolve to
the single owner in the architecture tree.

## Proposed project-state handoff

The worker does not edit routed state. After review/commit/push, the coordinator can record:

- **Decision:** `Accepted the settled Happy Artillery 1.2.0 contract and proposed tree. The complete tree has thirteen production Java files: the eleven named non-mixin owners, explicit Ammo, and SlotGuardMixin. GhastState includes per-ghast cry and fuse timing; passengers receive read-only HUD; protection vetoes, pre-drop restoration, and the Java+Bedrock hold-to-fire gate are mandatory.`
- **Tested:** `Validated ARCHITECTURE.md, FEATURES.md, and MIGRATION_PLAN.md with duplicate/path/owner checks and git diff --check. Only those three documentation files changed; no source, resource, build, dependency, README, CHANGELOG, project-state, test-server, commit, push, release, main, Modrinth, or production mutation occurred.`
- **History:** `Replaced obsolete G1-G9/open-decision planning with the supplied settled 1.2.0 design and dependency-ordered delegated GREEN slices. The branch remains deliberately fail-loud and non-deployable; Slice 1 architecture alignment is next after this docs checkpoint is accepted and pushed.`
