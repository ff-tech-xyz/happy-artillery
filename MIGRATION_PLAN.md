# Happy Artillery 1.2.0 Migration Plan

## Goal and checkpoint rules

Rebuild the accepted `FEATURES.md` contract into the proposed package/owner tree in
`ARCHITECTURE.md`, one dependency-ordered GREEN slice at a time. The released implementation is
read-only evidence, not code to copy back.

The **immediate next checkpoint remains a non-deployable scaffold**. Its only purpose is to move the
existing empty owner shells and empty test layout into the accepted package tree after this architecture
change is accepted. It must still throw the deliberate startup exception, retain non-deployable Fabric
metadata/archive naming, provide no gameplay callbacks, and never be deployed to `pyretest`.

No gameplay slice may start until its open product decisions are accepted in `FEATURES.md`. This plan
names gates and dependencies but does not choose their outcomes.

## Fixed working discipline

1. One writer owns the checkout. Start every slice from a clean, pushed non-`main` checkpoint and
   verify `HEAD`, branch, staged, unstaged, and untracked state.
2. Read `AGENTS.md`, `FEATURES.md`, `ARCHITECTURE.md`, and this plan before changing a slice.
3. Keep the fail-loud entrypoint and non-deployable artifact identity through every internal slice.
   No partially rebuilt behavior is registered or presented as playable.
4. Use strict vertical TDD: add one behavior assertion, run the focused suite and observe the expected
   RED caused by missing behavior, implement only enough to reach GREEN, then refactor while GREEN.
   Do not add a pile of tests after production code.
5. Released contradictions receive characterization evidence, not accidental preservation. For each
   open conflict, record the released paths from `origin/main`; after Elijah decides, write the new
   acceptance test and observe RED against the scaffold before implementation. If a test cannot run
   against the old Minecraft owner, use the pinned released source/bytecode in a disposable detached
   worktree as evidence and label it characterization-by-trace rather than claiming an executed RED.
6. Use the dependency-free `HappyArtilleryRegressionSuite` and Gradle `JavaExec` task; do not add a
   test library or other dependency without explicit approval. The suite accepts one risk-slice name,
   so focused commands use `./gradlew regressionTest --args='<slice>'`; no arguments run all suites.
7. Before every commit: run the focused suite, `./gradlew clean build`, the exact-tree/no-fallback
   checks below, inspect tracked and untracked changes, and run `git diff --check` plus
   `git diff --cached --check` after staging. Commit only that coherent GREEN slice. Do not push,
   deploy, or update project state until the coordinator accepts the commit.
8. Any edit after the final focused/build gate invalidates its evidence. Rerun the affected focused
   suite and canonical build on the final bytes.

## Open-decision gates

Each gate is satisfied only when `FEATURES.md` is changed in a reviewable docs-only commit that states
the selected behavior and the expected regression. Do not hide multiple policy choices in an
implementation commit.

| Gate | Required decision from `FEATURES.md` | Blocks |
|---|---|---|
| G1 Controller admission | first passenger vs any passenger; marked controls vs raw Fire Charge/Ghast Tear compatibility | `ControllerPolicy`, callback routing, setup/display/cleanup eligibility |
| G2 Control ownership | collision-proof ownership/restoration and exact inventory/drop/death/disconnect/world-item boundaries | `ControlItems`, restoration records, death/drop cleanup |
| G3 Environment | HOT threshold, End limit/presentation, Nether limit, and the single cooling schedule | `EnvironmentPolicy`, heat transitions, presentation |
| G4 Ammo cadence | whether firing postpones delivery; reduced maxima; zero/negative cost/interval behavior with validation | ammo state and fire admission |
| G5 Water cooling | attempt-driven vs continuous, water predicate, and non-increasing floor behavior | heat state, `FireAction`, lifecycle tick work |
| G6 Overheat | configured threshold calculation, post-overheat heat/recovery, and partial-effect semantics | fire state transition and `OverheatEffect` |
| G7 Projectile failure | eager chunk requests and projectile-add fail/partial vs instant-ray behavior | `ProjectileFire` and final fire result |
| G8 Config policy | validation, coercion, malformed/invalid/read/write startup outcomes | `HappyArtilleryConfig` and every settings consumer |
| G9 Eviction/work bounds | runtime state/display eviction and bounded loaded-world work | `ArtilleryState`, `RiderPresentation`, `ArtilleryLifecycle` |

Suggested atomic decision commit subjects, used only after the corresponding answer exists:

- `docs(contract): resolve controller admission policy`
- `docs(contract): resolve control item ownership policy`
- `docs(contract): resolve environment and cooling policy`
- `docs(contract): resolve ammo cadence policy`
- `docs(contract): resolve water cooling policy`
- `docs(contract): resolve overheat recovery policy`
- `docs(contract): resolve projectile failure policy`
- `docs(contract): resolve configuration failure policy`
- `docs(contract): resolve lifecycle eviction bounds`

## Dependency-ordered rebuild slices

### Slice 0 — Accept architecture and migration documents

**Files:** modify `ARCHITECTURE.md`; create `MIGRATION_PLAN.md` only.

**Boundary:** validate links/tree paths and whitespace; no source, resource, build, feature, or README
change; no build is required for docs-only bytes. Coordinator commit after review:
`docs(architecture): define 1.2.0 rebuild owners`.

**Gate to continue:** the architecture commit is accepted and reachable remotely. The source-layout
hook requires this earlier checkpoint before Slice 1 moves files.

### Slice 1 — Align the non-deployable scaffold to the proposed packages

**Files:** move/create only the empty production and test shells listed by `ARCHITECTURE.md`; update
package declarations and the scaffold entrypoint reference in `fabric.mod.json` if needed. Do not add
logic. Do not change `FEATURES.md`, release coordinates, dependencies, or playable metadata.

**Verification:** compile all shells with `./gradlew clean build`; mechanically compare all tracked
production/test paths with the architecture tree; inspect runtime and sources jars; run isolated
`./gradlew runServer` far enough to prove Fabric invokes the declared entrypoint and the deliberate
`IllegalStateException` is the observed failure. A Gradle zero exit without that log evidence is not
success. Confirm the artifact and embedded Fabric name/description still say non-deployable.

**Commit boundary:** one GREEN structural commit,
`refactor(scaffold): align rebuild owner packages`. No `pyretest` and no human gameplay handoff.
This is the immediate next checkpoint.

### Slice 2 — Establish the executable config contract and regression harness

**Prerequisite:** G8.

**RED:** implement `HappyArtilleryRegressionSuite` dispatch and config assertions first; wire a
`regressionTest` `JavaExec` into `check`, remove the temporary no-discovered-tests exception when the
executable suite owns verification, then run `./gradlew regressionTest --args='config'`. Require an
expected failure against the empty config shell.

**GREEN:** implement only `config/HappyArtilleryConfig.java`: all 24 fields/defaults, one immutable
settings view, path/load/rewrite behavior, and the accepted G8 validation/failure policy. No other
owner reads JSON or Fabric's config directory.

**Verification/commit:** focused config suite, all regression suites, `./gradlew clean build`, config
catalog count/default comparison, and diff checks. Commit
`feat(config): implement the 1.2.0 configuration contract`. Keep the artifact non-deployable.

### Slice 3 — Implement one environment classifier/profile

**Prerequisites:** G3 and Slice 2.

**RED:** `./gradlew regressionTest --args='environment'` must fail on five-mode classification,
dimension precedence, selected threshold edges, and profile consistency.

**GREEN:** implement only `state/EnvironmentPolicy.java`. It returns one finite profile consumed by
state, fire, and presentation; no caller rechecks dimension ids or biome temperature.

**Verification/commit:** focused environment suite, full regression suite, clean build, classification
owner search, diff checks. Commit `feat(state): add environment heat profiles`. Non-deployable.

### Slice 4 — Implement ammo, shot timing, and cry cooldown state

**Prerequisites:** G4, G8, and Slice 2.

**RED:** `./gradlew regressionTest --args='state-timing'` must fail for unseen-full ammo, complete
elapsed intervals, caps, selected firing/cadence behavior, shot/restart timestamps, denial-safe
queries, and player-keyed cry cooldowns. Inject a controllable clock into the concrete owner; do not
create a clock interface or wrapper file for one caller.

**GREEN:** add only these records/transitions to `state/ArtilleryState.java`. No world access, effects,
or callback registration. Queries may advance elapsed state only where the accepted contract says so.

**Verification/commit:** focused state suite, full suite, clean build, map/state owner search, diff
checks. Commit `feat(state): implement ammo and cooldown timing`. Non-deployable.

### Slice 5 — Implement heat, cooling, restoration records, and eviction APIs

**Prerequisites:** G2's restoration shape, G3, G5, G6's recovery transition, G9, and Slices 3–4.

**RED:** `./gradlew regressionTest --args='state-heat'` must fail for configured heat amounts/limits,
restart delay, the one passive schedule, selected water behavior, threshold/recovery transition,
control-decoration snapshots, restart-reset behavior, and explicit scoped eviction.

**GREEN:** extend only `ArtilleryState`; callers receive snapshots/results rather than direct maps.
`EnvironmentPolicy` remains the classification owner. Do not add another timer, mode cache, or state
map in fire, inventory, lifecycle, or presentation.

**Verification/commit:** focused heat/state suite, full suite, clean build, state/config/classification
owner searches, diff checks. Commit `feat(state): implement heat and lifecycle records`. Non-deployable.

### Slice 6 — Implement shared controller admission and callback routing

**Prerequisites:** G1 and Slices 2–5.

**RED:** `./gradlew regressionTest --args='controls'` must fail for both hands/callbacks, Happy Ghast
classification, selected controller/token matrix, ridden-entity matching, and PASS/FAIL/SUCCESS
routing. Tests use recording fire/cry actions only at the callback boundary; they do not duplicate
ability policy.

**GREEN:** implement `controls/ControllerPolicy.java` and `controls/HappyGhastControls.java`.
ControllerPolicy is called by input, inventory, presentation, and lifecycle; callback routing invokes
fire/cry actions but contains no fire/cry mechanics.

**Verification/commit:** focused controls suite, full suite, clean build, admission duplication search,
diff checks. Commit `feat(controls): route authorized mounted inputs`. The composition root still does
not register gameplay.

### Slice 7 — Implement control-item mutation and cleanup

**Prerequisites:** G1, G2, G9, and Slices 5–6.

**RED:** `./gradlew regressionTest --args='control-items'` must fail for empty/occupied slots, exact
one-control convergence, moved temporary deletion, owner-safe decoration restoration, dismount/death/
drop scope, unrelated lore/items/entities, and both positive and negative collateral cases.

**GREEN:** implement only `inventory/ControlItems.java`, using `ControllerPolicy` and restoration
records in `ArtilleryState`. All inventory/item entity mutations route through this owner. Do not add a
mixin, delayed queue, global world scan, raw substring cleanup, or second marker helper.

**Verification/commit:** focused inventory suite, full suite, clean build, mutation/no-mixin/residue
searches, diff checks. Commit `feat(inventory): manage mounted control items`. Non-deployable.

### Slice 8 — Implement cry as the first complete action path

**Prerequisites:** Slices 4 and 6. G1 controls which riders can reach it; G5 supplies the shared water
predicate if the accepted decision changes the released test.

**RED:** `./gradlew regressionTest --args='cry'` must fail for water/cooldown denials, no ammo/heat
mutation, exactly-once cooldown commitment, sound position/source/volume/pitch, and the documented
post-commit world-failure boundary.

**GREEN:** implement only `cry/CryAction.java`; it owns the one scream effect and calls state once.
No retry, swallowed exception, alternate sound path, or controller check is added.

**Verification/commit:** focused cry suite, full suite, clean build, sound/state mutation searches,
diff checks. Commit `feat(cry): implement mounted ghast cry`. It remains unavailable at runtime until
final activation, so no `pyretest` handoff yet.

### Slice 9 — Implement fire admission and exactly-once state transition

**Prerequisites:** G4, G5, G6 and Slices 3–6.

**RED:** `./gradlew regressionTest --args='fire-action'` must fail for the exact accepted order:
water, at-limit, shot cooldown, ammo, one committed transition, then normal/overheat selection. Assert
ordinary denials do not consume/restart and water may only apply the selected water transition.

**GREEN:** implement only `fire/FireAction.java`. It receives one `EnvironmentPolicy` profile and one
`ArtilleryState` transition result; it does not spawn, explode, play sound, classify, or recheck
controller admission.

**Verification/commit:** focused fire-action suite, full suite, clean build, state/classification/world
mutation searches, diff checks. Commit `feat(fire): implement fire admission transition`.
Non-deployable.

### Slice 10 — Implement the normal projectile effect

**Prerequisite:** G7 and Slice 9.

**RED:** `./gradlew regressionTest --args='projectile'` must fail for exact launch origin, normalized
rider view and 0.5 scale, owner, power, one sound, entity-add result, selected chunk request behavior,
and selected failed-add outcome. Exercise the production method through narrow recording world calls,
not a duplicate geometry helper.

**GREEN:** implement only `fire/ProjectileFire.java`. It is the only normal-shot world-mutation owner;
remove any fallback branch not selected by G7 rather than retaining it dormant.

**Verification/commit:** focused projectile suite, full suite, clean build, projectile/explosion/chunk
mutation accounting, diff checks. Commit `feat(fire): launch normal artillery projectiles`.
Non-deployable.

### Slice 11 — Implement the overheat effect

**Prerequisite:** G6 and Slice 9.

**RED:** `./gradlew regressionTest --args='overheat'` must fail for the configured main explosion,
exact 48-entry golden-spiral order, sphere projectile owner/power, sound 2.0/0.8, at-most-15 supported
fire placements within bounds, deterministic seeded testing, and selected partial failures.

**GREEN:** implement only `fire/OverheatEffect.java`. Keep one ordered mutation path; do not surround
individual failures with a second compensating explosion/projectile/fire path.

**Verification/commit:** focused overheat suite, full suite, clean build, mutation count/owner search,
diff checks. Commit `feat(fire): implement overheat spectacle`. Non-deployable.

### Slice 12 — Implement read-only rider presentation

**Prerequisites:** G1, G3, G9 and Slices 3, 5–7.

**RED:** `./gradlew regressionTest --args='presentation'` must fail for bar key/lifetime, heat rounding
and progress, color thresholds/modes, ammo colors, cooling labels, warnings, particle count/location,
and cleanup. Include a test proving a display refresh cannot change mode, heat, ammo, or timing.

**GREEN:** implement only `presentation/RiderPresentation.java`. It consumes state snapshots and the
already-selected environment profile. Its world mutations are limited to its named output effects.

**Verification/commit:** focused presentation suite, full suite, clean build, prove no state mutator or
environment classification call originates here, diff checks. Commit
`feat(presentation): show artillery rider status`. Non-deployable.

### Slice 13 — Integrate bounded lifecycle orchestration

**Prerequisites:** G2, G5, G9 and Slices 3–12.

**RED:** `./gradlew regressionTest --args='lifecycle'` must fail for one callback registration each,
bounded tick candidates/work, ordering, and dismount/death/drop/disconnect/entity-loss/server-stop
cleanup. Assert no all-world item/entity scan and no stale gameplay/display record after teardown.

**GREEN:** implement only `lifecycle/ArtilleryLifecycle.java`. It invokes existing owners; it does not
reimplement controller policy, item cleanup, cooling, regeneration, display, or state eviction.

**Verification/commit:** focused lifecycle suite, full suite, clean build, callback and loaded-world
iteration accounting, diff checks. Commit `feat(lifecycle): coordinate bounded artillery cleanup`.
The root still fails loudly, so no runtime deployment yet.

### Slice 14 — Activate the complete candidate and restore truthful metadata

**Prerequisites:** all nine gates and Slices 1–13 GREEN.

**RED/integration proof:** add/enable an integration assertion that the composition root constructs and
registers each owner exactly once, then observe failure while `HappyArtillery` still throws. Do not
weaken the deliberate guard before the complete owner graph is ready.

**GREEN:** make `HappyArtillery.java` wiring-only and remove its scaffold exception; register controls
and lifecycle once; restore truthful playable Fabric name/description and normal archive naming;
remove the scaffold-only no-test setting if any remains. Do not add `/happytest`, mixins, access
wideners, compatibility wrappers, or release actions.

**Machine gate before commit:** run every focused suite, `./gradlew clean build`, exact-tree and residue
checks, inspect runtime and sources jars, validate embedded `fabric.mod.json`, and run an isolated
Fabric server startup to prove the mod initializes normally. Obtain fresh independent architecture,
behavior-contract, and final-byte reviews. Commit one integration checkpoint:
`feat: activate Happy Artillery 1.2.0 gameplay`.

**Runtime gate after commit:** push only the accepted non-main commit through the coordinator's normal
flow, then use `pyretest switch mod:happy-artillery` (or `pyretest deploy` if already active). Verify
logs identify `happy-artillery` 1.2.0 and compare the deployed jar checksum with the exact committed
build. A startup-only pass is not gameplay acceptance.

## No-fallback, deletion, and ownership checks

Run these after each relevant slice and all of them before activation. Scope negative-test strings
carefully; tests may name forbidden behavior to prove rejection, but production must not contain it.

1. **Exact tree:** parse the fenced `ARCHITECTURE.md` paths and compare them in both directions with
   the filesystem plus tracked/untracked production, resource, test, documentation, and required
   build-support files. Missing declared paths and undeclared indexed paths must both be empty.
2. **Old owner residue:** production/resources must contain none of the released package or symbols:
   `happy.artillery`, `CooldownTracker`, `ModItems`, `EntityClickHandler`, `ControlSlotTagSyncer`,
   `CustomDataComponents`, `ExtendedInventory`, injected player extensions, `/happytest`, or delayed
   tag-sync state.
3. **No injection residue:** no mixin/access-widener file, Fabric metadata key, injected interface,
   accessor, or root-level duplicate mixin JSON may exist unless a later accepted architecture commit
   first proves it necessary.
4. **Single owners:** config-directory/JSON I/O appears only in `HappyArtilleryConfig`; dimension and
   temperature classification only in `EnvironmentPolicy`; gameplay maps/records only in
   `ArtilleryState`; controller/Happy-Ghast/token classification only in `ControllerPolicy`.
5. **Mutation accounting:** enumerate every direct `addFreshEntity`, `explode`, `setBlock`,
   `playSound`, particle send, inventory `setItem`, item/entity discard, boss-bar mutation, and action-
   bar send. Each must live only in the effect owner named in `ARCHITECTURE.md`; simple success or
   failure branches do not bypass that owner.
6. **Callbacks/work:** enumerate every Fabric callback registration and every loaded-player/world/
   entity/item iteration. Registrations originate only in the wiring/lifecycle/control boundary, and
   work matches G9's accepted bounds. No duplicate tick scheduler or global collateral scan remains.
7. **Failure quality:** search production for `catch (Throwable`, sampled/suppressed logging, empty or
   broad catches, `fallback`/`compat` branches, silent `null` recovery, placeholder/TODO behavior, and
   state rollback after world mutation. Remove unselected paths; do not rename them to evade searches.
8. **Artifact:** runtime and sources jars contain only proposed classes/resources, no old package,
   mixin/access-widener, debug command, duplicate metadata, generated runtime config, or scaffold name
   after activation. Before activation they must retain the deliberate non-deployable identity.

## Final pyretest and human feature tests

Run these only after Slice 14's exact commit is deployed and startup/checksum verification passes.
Record the exact commit, source jar SHA-256, deployed jar SHA-256, profile, server log line, tester, and
observed result for every point.

1. **Control authorization and cleanup:** test every accepted passenger/raw-item case from G1; verify
   slot 5/6 setup, occupied-item restoration, moved temporary deletion, dismount, disconnect, death,
   drop, and entity-loss cleanup without touching an unrelated player or nearby pre-existing item.
2. **Cry:** verify both hands and ridden-entity click route to one cry; water/cooldown denial returns no
   sound and starts no new cooldown; success uses accepted volume and ten-second default timing.
3. **Normal fire:** verify denial order externally where observable, no ammo spend on denials, exact
   ammo/cooldown timing, rider aim, ghast ownership behavior, explosion power, and G7's selected chunk/
   add-failure outcome.
4. **Environment/ammo/water:** test threshold-edge biomes plus Nether and End; compare heat gain, limit,
   cooling label/rate, passive ammo cadence, restart delay, selected water behavior, and no competing
   fixed 60-tick cooling.
5. **Overheat:** trigger with configured heat amounts (including a non-1 amount), verify exactly one
   threshold shot spend, main explosion, sphere/fire/sound spectacle, selected recovery, and continued
   behavior after the recovery path.
6. **Presentation/lifecycle:** verify heat rounding/colors, ammo bands, warnings and particles; prove
   presentation does not change combat classification; disconnect/reconnect, ghast removal, dimension
   change, and server restart leave no stale bar and reset memory-only state as contracted.
7. **Config startup:** in an isolated profile/copy, verify missing/default creation and each accepted G8
   malformed/invalid/read/write outcome. Restore the normal test config before continuing.

A failed human point returns to a focused RED test and the owning slice; do not stack a guard or
fallback in a later owner. After repair, rebuild, commit, redeploy that exact head, recheck the checksum,
and rerun the affected human point plus any dependent points. No release PR, GitHub release, Modrinth
publish, production deploy, or `main` mutation is part of this plan.

## Proposed project-state evidence for the coordinator

Do not write project state from an unaccepted worker checkout. After the architecture commit is
reviewed and pushed, the coordinator can record:

- **Decision/history:** `Accepted ARCHITECTURE.md as the complete proposed 1.2.0 owner/package tree and MIGRATION_PLAN.md as the dependency-ordered rebuild plan. The next checkpoint is package-aligned, fail-loud, non-deployable scaffold only; gameplay remains blocked by FEATURES.md gates G1-G9.`
- **Tested:** `Validated the final documentation bytes with a clean path/link/tree uniqueness check and git diff --check; only ARCHITECTURE.md and MIGRATION_PLAN.md changed. No source/resource/build/config/FEATURES/README, branch, commit, push, pyretest, release, production, or project-state mutation was performed by the worker.`
- **Current state after coordinator commit:** exact non-main branch, commit SHA/subject, remote containment,
  clean status, and the fact that Slice 1—not gameplay—is next.
