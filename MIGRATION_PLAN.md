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

JUnit is an accepted requirement in the supplied specification. The build-support slice must pin the
smallest existing-compatible JUnit dependency and make `test` part of `check`; it may not change Loom,
Minecraft, Fabric, mappings, Gradle, or Java versions.

## Settled assumptions carried into implementation

- The proposed tree has thirteen production Java files: eleven named non-mixin owners plus the missing
  `Ammo` owner and the one `SlotGuardMixin`.
- The nine test files are grouped by risk: attachment codecs share `PersistenceTest`; component and mixin
  risks live in `ControlsTest`; feedback lives in `AbilitiesTest`; only pure/config/model boundaries keep
  dedicated suites.
- `GhastState` includes per-ghast cry and pending-fuse timing. `Ammo` owns optional ammo transitions.
- All persistent timing uses saved Overworld `gameTime`: heat has a consumed-through anchor and firing
  window end; ammo has a complete-interval anchor; cry and fuse use durable deadlines. Process-local
  ticks and wall time are forbidden, and every advance consumes elapsed ticks exactly once.
- Each active RiderState stash persists its original fire/cry slot indexes. Reloaded slots apply only to
  a later ride; restoration, locking, and active control lookup use the persisted indexes.
- Passengers receive read-only HUD; only the pilot advances state or abilities.
- `Components` defines, catalogs, and registers its types; `HappyArtillery` only invokes that owner while
  composing the graph.
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
call-time reads, distinct in-range fire/cry slot validation, and failed-reload last-known-good behavior.

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

Implement immutable `GhastState` and `RiderState` records/codecs. Each state owner defines/registers its
persistent attachment type, and the composition root invokes those registration entries without owning
their definitions or registering gameplay. Include heat/heat-anchor/firing-window, ammo/regen-anchor,
cry-ready/fuse-deadline Overworld game ticks, byte-exact two-stack stash with original slot indexes,
ridden id, input dedup tick, and serializable HUD cache.

**RED:** grouped `PersistenceTest` fresh-state, codec, indexed ItemStack, attachment replacement, durable
tick continuity, and encode/decode assertions fail first. **GREEN:** focused/full tests, clean build,
serialization round-trips, immutable replacement proof, and search proving no static gameplay map,
process-local persisted tick, or wall clock. If API names need proof, use a disposable
instrumented run and restore the fail-loud final bytes. Commit `feat(state): persist ghast and rider state`
and push. A real restart proof is still required at Slice 13 before any gameplay claim.

### Slice 5 — Pure heat and optional ammo

Implement `Heat`, `Ammo`, and their tests without world/entity access. Take one tracer behavior at a
time: each biome's sustained curve; exact-limit detonation; firing window; water-before-Nether ordering;
non-double-counted per-tick advance; unload/restart continuity in saved game time; disabled ammo;
independent complete-interval regeneration with retained remainder; caps; and spend.

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

Whichever result is selected, update `FEATURES.md` with the exact chosen contract, defaults, Java and
Bedrock observations, and retained/fallback test expectations. Remove disposable instrumentation; run
affected config/heat tests, full tests, clean build, fail-loud startup, and exact diff checks; obtain a
fresh final-byte review; commit `docs(controls): settle hold-to-fire path` (including any required
config/heat changes) and push it. Slice 7 cannot start until that exact decision checkpoint is remotely
reachable. No hand-waved, Java-only, unreviewed, or merely local result passes this gate.

### Slice 7 — Components, controls, and pre-drop restoration

Implement `Components`, `Controls`, `SlotGuardMixin`, mixin metadata, and grouped `ControlsTest`. Cover
component identity, persistence, and exactly-once registration through the Components-owned boundary;
pilot-only admission; both callbacks/hands; one-input-per-player-tick deduplication; hold/click intent;
exactly two mount/two restore writes; byte-exact indexed stash; active-ride reload retaining original
indexes for lookup, locking, and restoration; next-ride adoption of new indexes; scoped creative cleanup;
pre-drop death restoration; and every click/drop/swap cancellation route.

**RED/GREEN:** automate owner logic and injection decisions first. Then run a disposable Java + Bedrock
Geyser session for the complete abuse list: named/full inventories, death, logout, ghast removal, hard
server stop, dimension change, all slot movements, two riders, creative duplication, plain-item denial,
live slot reload during a ride followed by a new ride, and Bedrock ghost-item checks. The pre-drop API
must be observed restoring before vanilla drops; a tick
backstop alone fails. Restore fail-loud final bytes, run focused/full tests and clean build, commit
`feat(controls): swap and protect pilot controls`, and push.

### Slice 8 — Normal fire admission and projectile

Implement only normal fire admission and its one projectile path in `Abilities`, one vertical behavior at
a time. Group tests in `AbilitiesTest`: pilot/water/cooldown/ammo gates, sealed fired/rejected outcomes,
advance-before-shot anchored heat, exactly-once heat/ammo/cooldown mutation, projectile geometry/speed,
entity-add failure, and absence of eager chunk loading or instant-ray/direct fallback.

**RED/GREEN:** each automatable admission/projectile assertion fails then passes. Manually prove on a
disposable Java + Bedrock session that the selected input contract produces one admitted shot at the
configured cadence and the normal projectile explosion presents the rider as cause to a real claims
integration. A protection veto may suppress block damage without creating another projectile/effect path.
Run focused/full tests, clean build, mutation accounting, and alternate-fire-path searches. Commit
`feat(abilities): admit and fire projectiles` and push. Remains non-deployable.

### Slice 9 — Cry and rejection feedback

Implement per-ghast cry admission/effect and `Feedback`, with all feedback risks grouped in
`AbilitiesTest`. Cover pilot/water/disabled/cooldown gates, saved-game-time `cryReadyTick`, accepted-sound
commit only, no mechanical side effect, visible `IN_WATER`/`NO_AMMO`/`NOT_PILOT` mappings, and silent
`ON_COOLDOWN`.

**RED/GREEN:** each gate, deadline, accepted effect, and feedback mapping fails then passes. Manually
confirm Java + Bedrock hear one ghast-position hostile scream and ordinary rejection feedback while
cooldown polling remains silent. Run focused/full tests, clean build, sound/attachment mutation accounting,
and searches for a second feedback or cry owner. Commit `feat(abilities): add cry and feedback` and push.
Remains non-deployable.

### Slice 10 — Overheat, fuse, and protection integration

Implement only overheat crossing, durable pending fuse, and detonation effects in `Abilities`. Cover the
single limit-comparison result, pending-shot lockout, saved-game-time `detonateAtTick` across unload and
restart, exactly-once explosion/sphere/fire/removal, configured counts/geometry, and
`killsGhast=false` reset.

**RED/GREEN:** every automatable transition/adapter assertion fails then passes. Before GREEN, prove exact
Minecraft 26.2 and real claims-plugin behavior for the overheat explosion and each fire placement: rider
cause, independent veto, no direct block mutation, bypass, fallback, or silent recovery. Also hard-stop and
restart a pending fuse and observe that stopped time does not count and the resumed deadline detonates once.
Run focused/full tests, clean build, complete effect-mutation accounting, and alternate-effect-path searches.
Commit `feat(abilities): integrate protected overheat` and push. Remains non-deployable.

### Slice 11 — Read-only pilot and passenger HUD

Implement `Hud` with boss bars, four-tick action-bar throttling, dirty checks, warning particles, Nether
priority, and bounded handle cleanup. Update pilots and passengers from one post-transition snapshot;
passengers cannot advance or alter it.

**RED:** tests fail for creation/removal counts, no remove-add pair, changed-value updates, colors,
warning threshold, ammo-disabled text, passenger visibility, and teardown. Include a mutation guard proving
HUD cannot change state or classify again. **GREEN:** focused/full tests, clean build, and a packet capture
showing single-digit HUD updates per rider/second. Commit `feat(hud): show artillery status` and push.
Remains non-deployable.

### Slice 12 — Wire the complete driver while guarded

Implement the final `HappyArtillery` owner graph and integration tests behind the deliberate startup
guard. The designed runtime order is: read saved Overworld game time once; reconcile all players; process
each ridden ghast once through its pilot using one biome context; then render pilot/passenger HUD from the
resulting snapshot. Invoke each callback, attachment, and component owner's registration entry exactly
once; register the mixin path, death hook, and server-stop cleanup exactly once.

**RED:** integration tests fail on missing registrations/order. **GREEN:** focused/full tests, clean build,
registration enumeration, durable clock-context proof, and a no-rider harness proving exactly one bounded
attachment/ride-status check per online player, no inventory scan unless restoration is required, and no
world/entity sweep. Profile and report that bounded baseline rather than claiming an empty online-player
loop. Commit `feat(integration): wire guarded artillery` and push. The artifact must still fail loudly and
remain undeployable.

### Slice 13 — Activate and prove the complete candidate

Prerequisites: Slices 1-12 are pushed GREEN, the hold path is durably reviewed/committed/pushed,
protection and controls manual gates passed, and independent architecture/behavior reviews found no hidden
second owner.

**RED:** an integration assertion requires normal startup while the deliberate guard still fails.
**GREEN:** remove only the guard/non-deployable naming, register the already-complete graph, and retain
truthful 26.2-only metadata. Add the op-only `/ha reload`; do not add `/happytest`.

On final bytes run every focused test, full JUnit suite, `./gradlew clean build`, exact-tree/residue and
mutation accounting, runtime/sources jar inspection, embedded metadata validation, secret scan, and an
isolated server startup. Commit `feat: activate Happy Artillery 1.2.0` and push the exact non-main head.

The first runtime gate is attachment persistence: set heat, ammo remainder, cry cooldown, pending fuse,
and indexed stash state using controlled test support; unload/reload and hard-stop/restart; prove saved
Overworld-game-time continuity, no stopped-time advancement, one-time heat catch-up without double cooling,
exact deadlines, and byte-exact original-slot restoration. Failure stops all later tests. Then deploy the
exact committed jar through `pyretest`, match source/deployed checksums, verify startup logs, and run the
full Java + Bedrock abuse list, normal fire, every biome/water curve, optional ammo, per-ghast cry, hold rate,
instant/fused overheat, protection vetoes, HUD packet rate, cleanup, and bounded idle-work profiler tests.
A startup pass alone is not gameplay acceptance.

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

- **Decision:** `Repaired the settled Happy Artillery 1.2.0 contract around four exact invariants. Persistent heat/ammo/cry/fuse timing uses saved Overworld gameTime; heat advances a consumed-through anchor so elapsed cooling is applied once. Active RiderState stashes persist their original slot indexes across reload. Components owns component definition/catalog/registration and HappyArtillery only invokes it. The proposed tree remains thirteen production Java files and now groups risks into nine test files.`
- **Tested:** `Validated final ARCHITECTURE.md, FEATURES.md, and MIGRATION_PLAN.md with exact duplicate/path/owner checks, sequential Slice 0-14 checks, Markdown/fence/final-newline checks, and git diff --check. Only those three documentation files differ from 76b44ce; no untracked, source, resource, build, dependency, README, CHANGELOG, project-state, test-server, commit, push, release, main, Modrinth, or production mutation occurred.`
- **History:** `Repaired the blocked settled-design checkpoint: split abilities into normal fire, cry/feedback, and overheat/protection GREEN checkpoints; made either hold-to-fire outcome a reviewed committed/pushed contract gate; corrected bounded online-player idle reconciliation; and preserved fail-loud/non-deployable identity through guarded Slice 12 until activation Slice 13.`
