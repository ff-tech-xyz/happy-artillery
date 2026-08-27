# Happy Artillery 1.2.0 Migration Plan

## Checkpoint contract

Build the settled `FEATURES.md` contract into the proposed `ARCHITECTURE.md` tree in the order below.
Released source is defect evidence, not code to copy. Every slice is delegated to one fresh writer,
independently reviewed by the coordinator, verified on its final bytes, committed as one coherent GREEN
checkpoint, and pushed to the existing non-`main` branch before the next slice starts.

The entrypoint must keep throwing the deliberate startup exception and the artifact/Fabric metadata
must remain visibly non-deployable through Slice 12. Partial gameplay is never registered or deployed.
Only Slice 13 may remove that guard after every owner is GREEN.

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

JUnit and Fabric's data-attachment API are accepted build requirements. The build-support slice must
pin the smallest existing-compatible JUnit dependency, make `test` part of `check`, and add explicit
compile support for the attachment API from the already-pinned Fabric API line before attachment source
is written. It may not change Loom, Minecraft, Fabric, mappings, Gradle, or Java versions.

## Settled assumptions carried into implementation

- The proposed tree has fourteen production Java files: the eleven declared non-mixin owners plus
  `DeathDropMixin`, `PlayerDropMixin`, and `SlotGuardMixin`. Minecraft 26.2 has no usable Fabric pre-drop
  event, so the death mixin wraps `ServerPlayer.die`'s committed `dropAllDeathLoot` invocation. Container
  mutation and direct Q/drop require separate fail-closed `AbstractContainerMenu` and `ServerPlayer`
  plumbing; all policy remains in `Controls`.
- The eight test files are grouped by risk: attachment codecs share `PersistenceTest`; component and
  mixin risks live in `ControlsTest`; feedback lives in
  `AbilitiesTest`; only coherent pure/config/model boundaries keep dedicated suites.
- `GhastState` includes per-ghast cry and pending-fuse timing. `Abilities` solely owns fuse task
  scheduling and load-time re-establishment.
- All persistent timing uses saved Overworld `gameTime`: heat has a consumed-through anchor and firing
  window end, while cry and fuse use durable deadlines. Process-local ticks and wall time are forbidden,
  and every advance consumes elapsed ticks exactly once.
- Each active RiderState stash persists its original fire/cry slot indexes. Reloaded slots apply only to
  a later ride; restoration, locking, and active control lookup use the persisted indexes.
- Passengers receive read-only HUD; only the pilot advances state or abilities. `NOT_PILOT` remains a
  silent authorization result, not an unreachable passenger-feedback promise.
- `Components` defines, catalogs, and registers its types; `HappyArtillery` only invokes that owner while
  composing the graph.
- Protection-visible explosions/fire placement, pre-drop restoration, Java + Bedrock hold behavior,
  and HUD packet bounds are mandatory final-candidate gates, not later polish. Guarded slices prove
  their seams automatically but do not claim integrated gameplay evidence.
- The fail-loud requirement prevents a committed partial server from running. Attachment persistence is
  first proven automatically by codec/attachment round-trip. A disposable, never-committed compile/API
  spike may disable the guard only to resolve framework signatures, but the committed checkpoint must
  restore it and cannot claim integrated gameplay evidence.
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

### Slice 2 — Config and required build support

Before attachment implementation, add explicit compile support for Fabric's data-attachment API from
the pinned Fabric API 26.2 line. Implement `Config` and `ConfigTest`; configure the accepted JUnit test
task without changing the toolchain. Cover the complete nested schema/defaults, preset-before-explicit
precedence, missing-file creation and missing-key rewrite, unknown-key removal after successful parsing,
identifier/range/cross-field validation, loud startup failure for every malformed/invalid existing-file
case, atomic call-time reads, distinct in-range fire/cry slots, and failed-reload retention of the exact
previous valid value without rewriting the invalid file.

**RED:** focused config assertions fail against the empty owner. **GREEN:** focused config tests, full
tests, clean build, attachment-API compile-resolution proof, serialized complete-default comparison, and
single config-I/O owner search. Commit
`feat(config): define 1.2.0 settings` and push. Remains non-deployable.

### Slice 3 — One biome classifier

Implement `BiomeClass` only. Test vanilla dimension-key identity, custom ids containing `nether`/`end`,
unknown-dimension temperature policy, exact 0.3/1.0 edges, all five profiles, and config reload reads.

**RED:** classifier tests fail against the shell. **GREEN:** focused/full tests, clean build, and search
proving no other production file classifies dimension or temperature. Commit
`feat(state): classify artillery biomes` and push. Remains non-deployable.

### Slice 4 — Persistent ghast and rider attachments

Implement immutable `GhastState` and `RiderState` records/codecs. Each state owner defines/registers its
persistent attachment type, and the composition root invokes those registration entries without owning
their definitions or registering gameplay. Include heat/heat-anchor/firing-window and
cry-ready/fuse-deadline Overworld game ticks, byte-exact two-stack stash with original slot indexes,
ridden id, input dedup tick, and serializable HUD cache.

**RED:** grouped `PersistenceTest` fresh-state, codec, indexed ItemStack, attachment replacement,
durable-tick continuity, and encode/decode assertions fail first. **GREEN:** focused/full tests, clean build,
serialization round-trips, immutable replacement proof, and search proving no static gameplay map,
process-local persisted tick, or wall clock. If API names need proof, use a disposable
instrumented run and restore the fail-loud final bytes. Commit `feat(state): persist ghast and rider state`
and push. A real restart proof is still required at Slice 13 before any gameplay claim.

### Slice 5 — Pure heat integration

Implement `Heat` and `HeatTest` without world/entity access. Take one tracer behavior at a time: each
biome's sustained curve; exact-limit detonation; firing window; water-before-Nether ordering; and
non-double-counted per-tick, unload, and restart advances in saved game time.

**RED/GREEN:** every pure transition assertion must be observed failing then passing. Run focused heat
tests, full tests, clean build, and searches proving `Heat` owns the only limit comparison. Commit
`feat(state): implement anchored heat` and push.
Remains non-deployable.

### Slice 6 — Prove the hold-input seam while guarded

Confirm the exact 26.2 consumable component API and implement an automated server-observed use-state seam
while the artifact remains guarded. Tests must prove long-duration use, no animation/sound side effect,
release cancellation, and tick/cooldown-based intent independent of packet frequency. A disposable API
spike may resolve mapped signatures, but Java/Bedrock rate evidence is deliberately deferred until the
complete graph produces runnable exact-candidate bytes in Slice 13.

Retain the preferred 0.25-second/default-heat contract provisionally. Remove disposable instrumentation;
run affected controls/config/heat tests, full tests, clean build, fail-loud startup, and exact diff
checks. Commit `feat(controls): establish hold input seam` and push. Packet-rate heuristics are forbidden.

### Slice 7 — Components, controls, and pre-drop restoration

Implement `Components`, `Controls`, `DeathDropMixin`, `PlayerDropMixin`, `SlotGuardMixin`, mixin
metadata, and grouped `ControlsTest`. Cover
component identity, persistence, and exactly-once registration through the Components-owned boundary;
pilot-only admission; both callbacks/hands; one-input-per-player-tick deduplication; hold/click intent;
exactly two mount/two restore writes; byte-exact indexed stash; active-ride reload retaining original
indexes for lookup, locking, and restoration; next-ride adoption of new indexes; scoped creative cleanup;
pre-drop death restoration; and every click/drop/swap cancellation route.

**RED/GREEN:** automate owner logic, injection decisions, and pre-drop-before-drop ordering through
framework seams. A tick backstop alone fails. Run focused/full tests and clean build, commit
`feat(controls): swap and protect pilot controls`, and push.

### Slice 8 — Normal fire admission and projectile

Implement only normal fire admission and its one projectile path in `Abilities`, one vertical behavior at
a time. Group tests in `AbilitiesTest`: pilot/water/cooldown gates, sealed fired/rejected outcomes,
advance-before-shot anchored heat, exactly-once heat/cooldown mutation, projectile geometry/speed,
entity-add failure, and absence of eager chunk loading or instant-ray/direct fallback.

**RED/GREEN:** each admission/projectile assertion and protection-adapter seam fails then passes. A veto
must suppress block damage without creating another projectile/effect path. Run focused/full tests,
clean build, mutation accounting, and alternate-fire-path searches. Commit
`feat(abilities): admit and fire projectiles` and push. Remains non-deployable.

### Slice 9 — Cry and rejection feedback

Implement per-ghast cry admission/effect and `Feedback`, with all feedback risks grouped in
`AbilitiesTest`. Cover pilot/water/disabled/cooldown gates, saved-game-time `cryReadyTick`,
accepted-sound commit only, no mechanical side effect, visible `IN_WATER` mapping, and silent
`ON_COOLDOWN`/`NOT_PILOT` authorization outcomes.

**RED/GREEN:** each gate, deadline, accepted effect, and feedback mapping fails then passes through
automated seams. Run focused/full tests, clean build, sound/attachment mutation accounting,
and searches for a second feedback or cry owner. Commit `feat(abilities): add cry and feedback` and push.
Remains non-deployable.

### Slice 10 — Overheat, fuse, and protection integration

Implement only overheat crossing, durable pending fuse, and detonation effects in `Abilities`. Cover the
single limit-comparison result, pending-shot lockout, saved-game-time `detonateAtTick` across unload and
restart, exactly-once explosion/sphere/fire effects, configured counts/geometry, conditional removal when
`killsGhast=true`, and retained-ghast heat/pending reset when `killsGhast=false`. `Abilities` must be the
only scheduling owner: fuse acceptance submits the
absolute deadline through the server task queue; a ghast-load entrypoint in the same owner re-establishes
an overdue or future task from the attachment; and execution re-reads the current deadline before acting.
Dismount leaves the task live. Unload may make a queued execution a no-op, with entity load as the one
bounded wake-up. Do not add a player-tick fuse check, parallel poller, loaded-entity scan, or second queue.

**RED/GREEN:** every transition, scheduling, reload wake-up, stale-task, and protection-adapter assertion
fails then passes. Simulate dismount, unload before deadline, load before/after deadline, duplicate load
callbacks, and restart reconstruction; each pending fuse must have one effective detonation and bounded
queued work. Run focused/full tests, clean build, complete effect-mutation accounting, and
alternate-effect/scheduler searches.
Commit `feat(abilities): integrate protected overheat` and push. Remains non-deployable.

### Slice 11 — Read-only pilot and passenger HUD

Implement `Hud` with boss bars, four-tick action-bar throttling, dirty checks, warning particles, Nether
priority, and bounded handle cleanup. Update pilots and passengers from one post-transition snapshot;
passengers cannot advance or alter it.

**RED:** tests fail for creation/removal counts, no remove-add pair, changed-value updates, colors,
warning threshold, heat-status text, passenger visibility, and teardown. Include a mutation guard proving
HUD cannot change state or classify again. **GREEN:** focused/full tests, clean build, and deterministic
packet-send-count assertions showing the configured throttle cannot exceed single digits per
rider/second. Real packet capture waits for Slice 13. Commit `feat(hud): show artillery status` and push.
Remains non-deployable.

### Slice 12 — Wire the complete driver while guarded

Implement the final `HappyArtillery` owner graph and integration tests behind the deliberate startup
guard. The designed runtime order is: read saved Overworld game time once; reconcile all players; process
each ridden ghast once through its pilot using one biome context; then render pilot/passenger HUD from the
result snapshot. Invoke each callback, attachment, and component owner's registration entry exactly
once; register the mixin path, death hook, ghast-load callback, and server-stop cleanup exactly once.
The load callback delegates pending-fuse wake-up to `Abilities`; the player driver never polls fuses.

**RED:** integration tests fail on missing registrations/order. **GREEN:** focused/full tests, clean build,
registration enumeration, durable clock-context proof, and a no-rider harness proving exactly one bounded
attachment/ride-status check per online player, no inventory scan unless restoration is required, and no
world/entity sweep. Profile and report that bounded baseline rather than claiming an empty online-player
loop. Commit `feat(integration): wire guarded artillery` and push. The artifact must still fail loudly and
remain undeployable.

### Slice 13 — Activate and prove the complete candidate

Prerequisites: Slices 1-12 are pushed GREEN and independent architecture/behavior reviews find no hidden
second owner. Integrated Java/Bedrock, claims, packet, restart, and gameplay evidence is intentionally
not a prerequisite because guarded bytes were not runnable.

**RED:** an integration assertion requires normal startup while the deliberate guard still fails.
**GREEN:** remove only the guard/non-deployable naming, register the already-complete graph, and retain
truthful 26.2-only metadata. Add the op-only `/ha reload`; do not add `/happytest`.

On the runnable candidate bytes run every focused test, full JUnit suite, `./gradlew clean build`,
exact-tree/residue and mutation accounting, runtime/sources jar inspection, embedded metadata validation,
secret scan, and an isolated server startup. Obtain a fresh independent architecture/behavior review of
those exact machine-green bytes. Resolve every blocker and rerun invalidated gates and review; then commit
`feat: activate Happy Artillery 1.2.0`, push it to the existing non-`main` branch, and verify the local,
tracking, and remote head identities. This reviewed, committed, and pushed head is the first activation
candidate. No uncommitted activation candidate may be deployed.

Build the runtime jar from that exact committed head, deploy it through `pyretest`, record the commit and
source-tree identity, match built/deployed checksums, and verify startup logs before manual testing. The
first integrated runtime gate is attachment persistence: set heat, cry cooldown, pending fuse, and indexed
stash state using controlled test support; unload/reload and hard-stop/restart; prove saved
Overworld-game-time continuity, no stopped-time advancement, one-time heat catch-up without double cooling,
exact deadlines, and byte-exact original-slot restoration. Failure stops all later tests. On that same
exact head, run the full Java + Bedrock abuse list, normal fire, every biome/water curve, per-ghast cry,
instant/fused overheat including rider dismount and entity unload/reload, real claims-plugin vetoes, HUD
packet capture, cleanup, and bounded idle-work profiler tests. In that session measure the preferred hold
path at a steady configured four shots/second on both clients without packet-rate dependence.

If the preferred hold path fails, add no heuristics. Select click-to-fire at `0.5` seconds; update
`FEATURES.md`, config/heat/controls code and tests; and double every heat-per-shot default (`1.40`, `2.50`,
`4.00`, `6.00`, `1.40`). If any gameplay test finds another defect, repair the applicable docs, code,
and tests instead. In either case, rerun every affected focused test plus the full machine gate and fresh
independent review, commit and push a replacement candidate, verify its exact remote head, then build,
deploy, and repeat the required runtime tests on that new exact committed head. Never redeploy dirty or
uncommitted candidate bytes.

The final accepted activation candidate is the one exact reviewed, committed, and pushed head whose
checksum-matched jar passed the complete applicable runtime gate. Record preferred-path success in the
acceptance evidence without editing that head; fallback selection is already part of its replacement
candidate contract. A startup pass alone is not gameplay acceptance.

### Slice 14 — Ship-ready documentation only

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

- **Decision:** `Repaired the settled Happy Artillery 1.2.0 contract around its lifecycle and ownership invariants. Persistent timing uses saved Overworld gameTime; heat advances one consumed-through anchor. Abilities alone schedules fused detonation through the server task queue and re-establishes it on ghast load. Failed startup config is loud and failed reload preserves the prior valid value. The proposed tree has fourteen production Java files and eight risk-grouped tests.`
- **Tested:** `Validated final ARCHITECTURE.md, FEATURES.md, and MIGRATION_PLAN.md with exact duplicate/path/owner checks, sequential Slice 0-14 checks, Markdown/fence/final-newline checks, canonical build, and git diff --check. Only the accepted documentation files changed in the groundwork checkpoint; source, resources, dependencies, runtime, release state, main, Modrinth, and production were untouched.`
- **History:** `Corrected the evidence-disproved mixin assumptions before source integration: committed death drops, container mutations, and direct selected-slot Q/drop now have distinct fail-closed plumbing targets while Controls remains the sole policy owner. Preserved fail-loud/non-deployable identity until final activation.`
