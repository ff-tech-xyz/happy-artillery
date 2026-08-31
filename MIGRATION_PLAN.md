# Happy Artillery 1.2.0 Migration Plan

## Checkpoint contract

Repair the runnable 1.2.0 candidate at `482a80edcbc3683cb873884c2ced623d3e7bf725` into the settled
`FEATURES.md` contract and proposed `ARCHITECTURE.md` tree in dependency order. This plan supersedes
the obsolete Slice 0-14 guarded rebuild: the owner graph, heat/fire/cry/fuse/config/HUD/lifecycle
implementation already exists, and the remaining work is a subtractive controls migration plus the
identified release blockers.

Each implementation phase uses one writer, strict vertical RED -> GREEN, coordinator review of final
bytes, focused tests, the full suite, canonical clean build, and one coherent non-`main` commit pushed
before dependent work begins. For each automatable behavior:

1. add one assertion that distinguishes the defect from the accepted behavior;
2. run the focused test and observe the expected behavioral failure, not a compile/setup failure;
3. implement the smallest complete change in the declared owner while deleting the superseded path;
4. rerun focused and full tests, then refactor only while GREEN.

Before every source commit, inspect status plus tracked, staged, unstaged, and untracked scope; compare
production/resource/test paths bidirectionally with `ARCHITECTURE.md`; run focused tests, full tests,
`./gradlew --no-daemon --no-parallel clean build`, `git diff --check`, staged diff checks, residue and
secret scans, and a fresh independent review of the exact final bytes. Any edit invalidates affected
evidence. The reviewer must identify the first broken invariant and say whether the diff removes the
old owner or hides it. Never bypass hooks, change dependencies/toolchain, touch `main`, or deploy dirty
bytes.

Groundwork and documentation checkpoints need machine verification but no gameplay stop. The completed
gameplay change receives one consolidated exact-head manual test in Phase 8. A release PR opens only
after every phase and release gate is GREEN; Elijah alone merges `main`, and official release/Modrinth
publication still requires his separate explicit `publish it` instruction.

## Settled architecture and behavior

- The proposed tree has exactly thirteen production Java files: eleven non-mixin owners plus the
  reshaped `PlayerDropMixin` and new `ExternalContainerMixin`. `DeathDropMixin` and `SlotGuardMixin`
  are deleted during Phase 3, after this docs checkpoint precedes those source moves.
- `RiderState` retains only ridden-ghast identity, `lastHandledTick`, and HUD cache. It persists no
  ItemStack, slot index, stash, or restoration data.
- `Components` markers contain control type, owner UUID, and ride UUID inside vanilla `CUSTOM_DATA`.
- `Controls` solely owns atomic allocation, one bounded active-pilot inventory snapshot over `0..35`
  plus offhand, held admission, transfer cleanup, ride transitions, and scoped control cleanup.
- Controls are disposable and movable inside the owning player's hotbar, main inventory, and offhand.
  There are no fixed slots, locks, exact-slot writes, stashes, restoration, click prediction, global
  scans, or same-ride regeneration. `allowPlainItems` changes admission only.
- HUD receives the shared snapshot. Pilot priority is missing control, then inventory-only control,
  then normal heat/status. Passengers see heat/status only.
- All unrelated accepted heat, fire, cry, fuse, config transaction, HUD, and lifecycle contracts in
  `FEATURES.md` remain in force unless a later named release-blocker phase changes one explicitly.

## Phase 0 — Resolved Minecraft mutation-boundary evidence

**Status:** complete evidence; no production mutation belongs to this phase.

Mapped Minecraft 26.2 bytecode and RED-capable route probes established:

1. `ServerPlayer.drop(ItemStack, boolean, boolean):ItemEntity` at `RETURN` covers direct Q, cursor
   drops, menu `THROW`, creative drops, and ordinary/offhand/equipment death drops. A marked returned
   `ItemEntity` can be discarded without changing ordinary drops.
2. External chest/container insertion does not reach `ServerPlayer.drop`. One additional narrow
   post-mutation hook is required at `Slot.setChanged():void` `HEAD`.
3. At that hook, `Controls` can inspect `Slot.getItem()` and `Slot.container`. It preserves a marked
   control only for an owner-matching player `Inventory` destination and consumes it from every other
   container. This covers ordinary pickup/placement, `QUICK_MOVE` empty/merge, number/offhand swaps,
   and `QUICK_CRAFT` without simulating `AbstractContainerMenu.doClick`.
4. `PICKUP_ALL` is inbound slot-to-cursor collection, not outbound insertion into a chest. It is not
   evidence for another external-container route or a reason to copy vanilla click prediction.
5. `Inventory.dropAll()` reaches the same three-argument drop boundary, so `DeathDropMixin` is
   unnecessary. The predictive `SlotGuardMixin` is also unnecessary.
6. The destructive restoration RED was observed against the current owner: replacing the former fixed
   control slot before dismount caused `Controls.restore` to overwrite the replacement. Phase 3 must
   recreate and retain that behavioral regression before deleting the stash path.

**Gate:** every outbound route names an observed method/descriptor, the proposed tree is fixed at
thirteen production Java files, and no future phase may substitute absence detection for actual
container deletion.

## Phase 1 — Update canonical contract and proposed tree

**Files:** `ARCHITECTURE.md`, `FEATURES.md`, `MIGRATION_PLAN.md` only.

1. Declare the thirteen-file tree before source moves: retain/reshape `PlayerDropMixin`, add
   `ExternalContainerMixin`, and omit `DeathDropMixin`/`SlotGuardMixin`.
2. Replace fixed-slot/stash/lock/restoration language with atomic first-two-free allocation,
   owner+ride markers, same-player mobility, bounded snapshot sharing, proven outbound consumption,
   no-overwrite, no same-ride regeneration, scoped cleanup, HUD priority, and passenger behavior.
3. Remove `fireSlot`, `crySlot`, and `lockControlSlots` from the schema. Document eight groups and
   36 declared keys; add no compatibility aliases.
4. Replace obsolete stash/index/lock/prediction test annotations and manual tests with the Phase 3/4
   automated contract and Phase 8 numbered acceptance.
5. Run deterministic tree/doc counts, UTF-8, fence, final-newline, contradiction, and whitespace checks.
   Do not run a source build merely for this worker checkpoint.

**Commit proposal:** `docs(architecture): replace locked control slots`

**Gate:** only these three docs differ; the architecture declares exactly thirteen production Java
files and eight tests; all three docs express one disposable movable-control model. The docs may
propose source paths not yet present because this checkpoint must precede their moves; report those
paths rather than treating the intentional ordering as a failure.

## Phase 2 — Repair configuration schema and lifecycle

**Owners/files:** `Config.java`, `HappyArtillery.java`, `ConfigTest.java`, and
`HappyArtilleryIntegrationTest.java`.

RED/GREEN slices:

1. Reject unknown root and nested keys recursively with full paths; preserve active config and invalid
   file bytes on startup/reload failure.
2. Reject removed `controls.fireSlot`, `controls.crySlot`, and `controls.lockControlSlots` with a clear
   removed-setting error. Do not silently discard them or retain compatibility behavior.
3. Reduce `Config.Controls` to `fireItem`, `cryItem`, `holdToFire`, and `allowPlainItems`; update the
   complete-default serialization and presets without changing unrelated defaults.
4. During parsing, validate item identifier syntax only. Resolve configured registry entries at a later
   server lifecycle point after mod initializers; missing items abort startup with exact path/id.
5. Reload on a running server resolves both items before atomic swap and rewrite. Failure preserves the
   previous object and exact file bytes.

**Commands:**

```bash
./gradlew --no-daemon --no-parallel test --tests xyz.pyrehaven.happyartillery.ConfigTest
./gradlew --no-daemon --no-parallel test --tests xyz.pyrehaven.happyartillery.HappyArtilleryIntegrationTest
./gradlew --no-daemon --no-parallel clean build
```

**Commit:** `fix(config): validate controls after mod startup`

**Gate:** the production schema contains no removed slot key, unknown keys never disappear silently,
and modded control item ids do not depend on initializer order.

## Phase 3 — Replace stash and locks atomically

**Owners/files:** `Components.java`, `RiderState.java`, `Controls.java`, `PlayerDropMixin.java`, new
`ExternalContainerMixin.java`, delete `DeathDropMixin.java` and `SlotGuardMixin.java`, update mixin
metadata, `PersistenceTest.java`, and `ControlsTest.java`.

Delete in one compile-coherent change before retaining a replacement path:

- `RiderState.StashedStack`, fire/cry stash fields, slot indexes, codecs, and copy helpers;
- fixed-slot mount writes, restoration, overwrite paths, active slot lookup, slot locks, and scoped
  sweeps whose purpose was restoration;
- `shouldCancelContainerMutation`, quick-craft snapshots, prediction/access interfaces, pickup/swap/
  pickup-all/can-quick-move simulation, and obsolete tests;
- pre-drop restoration access, `DeathDropMixin`, and `SlotGuardMixin`.

RED/GREEN slices:

1. **State shape:** `RiderState` round-trips only optional ride UUID, `lastHandledTick`, and HUD cache.
2. **Marker identity:** type + owner UUID + ride UUID round-trip in vanilla `CUSTOM_DATA`, preserving
   unrelated data and requiring no synchronized registry.
3. **Atomic allocation:** reserve two empty candidates in hotbar `0..8`, then main `9..35`; with zero
   or one empty, perform zero writes, record that ride, and emit one exact refusal message.
4. **Bounded snapshot:** inspect only `0..35` and offhand `40`; classify matching, stale/foreign,
   hand-accessible, main-inventory-only, and missing controls.
5. **Held admission and mobility:** matching controls work from any held hotbar slot or offhand. Every
   move among the owner's hotbar/main/offhand remains vanilla.
6. **Drop consumption:** the reshaped drop mixin at three-argument `ServerPlayer.drop` `RETURN`
   discards marked direct Q, cursor/menu `THROW`, creative, and death-drop entities; ordinary drops pass.
7. **External consumption:** the new `Slot.setChanged()` `HEAD` hook delegates to `Controls`, preserving
   only owner-matching player `Inventory` destinations and consuming marked stacks from every other
   container after ordinary placement, `QUICK_MOVE` empty/merge, number/offhand swap, and `QUICK_CRAFT`.
   `PICKUP_ALL` remains inbound slot-to-cursor and must not be mislabeled as chest insertion.
8. **No search/regeneration:** missing state comes from the bounded snapshot; no menu/world/entity/other-
   player search locates a token, and the same ride never regenerates it.
9. **Cleanup/no-overwrite:** dismount, lost pilot status, disconnect recovery, dimension transition,
   and ghast removal delete only matching owned controls and clear ride identity. Player death relies on
   vanilla inventory emptying plus drop consumption. No ordinary ItemStack is written or deleted.
10. **Foreign/stale/plain safety:** stolen/prior-ride controls never authorize. Cleanup is bounded to the
    owning rider's reconciliation or the current mutation boundary. Plain configured items are admission-
    only when enabled and are never deleted or counted for HUD presence.

**Commands:**

```bash
./gradlew --no-daemon --no-parallel test --tests xyz.pyrehaven.happyartillery.PersistenceTest
./gradlew --no-daemon --no-parallel test --tests xyz.pyrehaven.happyartillery.ControlsTest
./gradlew --no-daemon --no-parallel clean build
```

**Commit:** `feat(controls): use disposable movable controls`

**Gate:** production searches for stash/index/lock/predictive owner residue are empty; exactly the two
proposed mixins remain; every direct inventory mutation routes through `Controls`; same-player movement
is allowed and every proven outbound destination consumes the marked stack.

## Phase 4 — Share snapshots and repair callback/HUD ownership

**Owners/files:** `HappyArtillery.java`, `Hud.java`, `Controls.java`,
`HappyArtilleryIntegrationTest.java`, `HudTest.java`, and affected `ControlsTest.java` sections.

RED/GREEN slices:

1. Input callbacks inspect only the acting player and current ridden ghast, perform admission/transition,
   and make zero `onlinePlayers()` calls. HUD fan-out waits for the normal tick.
2. The tick obtains exactly one `Controls.InventorySnapshot` per active pilot and shares it with held
   admission and HUD; no second routine inventory scan exists.
3. Pilot action-bar priority is exact: missing -> singular/plural inventory-only -> heat/status. Missing
   wins over another token's inventory-only state. Control warnings are pilot-only and do not wait behind
   particle/boss-bar round-robin work.
4. Group riders by ghast. A ridden ghast without a controlling pilot has every rider HUD removed that
   tick; passengers never advance abilities.
5. Make warning-particle construction require a loaded server ghast/`ServerLevel`; remove the impossible
   cast failure instead of broadly catching it.
6. Recover only a named invalid persisted-rider-state result around that player: log once, remove owned
   controls, reset `RiderState`, and remove that HUD. Unexpected runtime/world/ability failures remain loud.
7. Remove unreachable `ControlIntent.NONE`, fake ignored intent, unused HUD fan-out/counter helpers, and
   tests that assert helper counts instead of observable behavior.

**Commands:**

```bash
./gradlew --no-daemon --no-parallel test --tests xyz.pyrehaven.happyartillery.HappyArtilleryIntegrationTest
./gradlew --no-daemon --no-parallel test --tests xyz.pyrehaven.happyartillery.HudTest
./gradlew --no-daemon --no-parallel test --tests xyz.pyrehaven.happyartillery.ControlsTest
./gradlew --no-daemon --no-parallel clean build
```

**Commit:** `fix(integration): bound controls and rider HUD work`

**Gate:** callbacks are actor-local; tick work is one online-player pass plus one bounded snapshot per
active pilot; passengers receive heat/status only; pilotless riders retain no frozen HUD.

## Phase 5 — Make ability outcomes truthful and launch clear of riders

**Owners/files:** `Abilities.java`, `Feedback.java`, `HappyArtillery.java`, `AbilitiesTest.java`,
`HappyArtilleryIntegrationTest.java`, and `FEATURES.md` for the settled launch and truthful-outcome contract.

RED/GREEN slices:

1. Make infallible cry and explosion adapters `void`; remove `CryRejection.EFFECT_FAILED` and rejected-
   attempt accounting for operations whose APIs expose no rejection. Retain truthful failures for
   `addFreshEntity`, `setBlockAndUpdate`, and ghast removal.
2. Present water blocking directly through `Feedback.presentWaterBlocked(player)` from fire and cry;
   never convert one ability's enum into another.
3. Compute one launch origin beyond the union of ghast/passenger collision bounds along normalized pilot
   aim, expanding those bounds by the spawned fireball's collision extents plus clearance. Test up, down,
   horizontal, diagonal, and top/bottom riders; assert the spawned fireball AABB is disjoint from every
   ghast/passenger AABB and retain an observed no-immediate-self-hit runtime gate.
4. Preserve vanilla `LargeFireball`, ridden Happy Ghast ownership, configured integer power, one entity-
   add attempt, success-only event 1016, movement, impact, `mobGriefing`, and persistence. Keep
   `FEATURES.md` aligned with the intentional collision-clear departure from fixed vanilla placement.
5. Collapse redundant fire/cry overload ladders and test-only queue counters while preserving one
   Minecraft adapter and one explicit-access core per behavior.

**Commands:**

```bash
./gradlew --no-daemon --no-parallel test --tests xyz.pyrehaven.happyartillery.AbilitiesTest
./gradlew --no-daemon --no-parallel test --tests xyz.pyrehaven.happyartillery.HappyArtilleryIntegrationTest
./gradlew --no-daemon --no-parallel clean build
```

**Commit:** `fix(abilities): report real outcomes and clear riders`

**Gate:** every failure outcome has an observable production source; every spawned fireball AABB is
disjoint from all ridden collision bounds for the tested aim/rider cases; and runtime observation shows
no immediate self-hit.

## Phase 6 — Finish subtractive seam cleanup

**Owners/files:** existing `Controls`, `Abilities`, `Hud`, and `HappyArtillery` owners plus tests that
call removed helpers. No new production file is allowed without a prior architecture-groundwork commit.

1. Enumerate production methods with no production callers and every overload ladder.
2. Keep one Minecraft-facing adapter and one explicit settings/access core for use callbacks and held
   sampling; delete forwarding combinations.
3. Prefer observable state/effect assertions over helper/count tests. Retain bytecode checks only for real
   Fabric/Minecraft boundaries that pure tests cannot exercise.
4. Re-measure after deleting copied click logic. Do not split an owner merely for line count.
5. Remove dead interfaces, enum members, imports, comments, and test-only helpers made obsolete by
   Phases 2-5. Run the complete suite after each removal cluster.

**Commands:**

```bash
./gradlew --no-daemon --no-parallel test
./gradlew --no-daemon --no-parallel clean build
git diff --check
```

**Commit:** `refactor(controls): remove obsolete test seams`

**Gate:** every retained abstraction has a production reason, no copied vanilla state machine or second
owner remains, and the implementation still matches the thirteen-file tree.

## Phase 7 — Repair public docs, changelog, and license

**Files:** `README.md`, `CHANGELOG.md`, `LICENSE`, optionally `fabric.mod.json` wording; verify
`gradle.properties`. Load `pyrehaven-mod-release-writing` first.

1. Remove ammo fiction and describe two-free-slot allocation, movable hand-held controls, outbound
   consumption, missing-control remount behavior, hold-to-fire, and cry accurately.
2. State Minecraft 26.2 only, Fabric Loader >=0.19.3, Fabric API, Java 21+, server installation, and
   Java/Bedrock-through-Geyser intent.
3. Document `/ha reload`, strict unknown/removed-key failure, and only the final 36-key schema.
4. Add a player/server-owner-facing `1.2.0` changelog against `v1.1.2.2`, not intermediate defects.
5. Match OMWH's settled licensing: copy its complete CC0 1.0 Universal legal code byte-for-byte,
   declare `CC0-1.0` in Fabric metadata, and link the repository license from the README.
6. Build and inspect runtime metadata and packaged license; add a dependency-free docs/artifact agreement
   check if it fits existing verification style.

**Commands:**

```bash
./gradlew --no-daemon --no-parallel clean build
unzip -p build/libs/happy-artillery-1.2.0.jar fabric.mod.json
unzip -p build/libs/happy-artillery-1.2.0.jar LICENSE_happy-artillery
git diff --check
```

**Commit:** `docs: describe Happy Artillery 1.2.0`

**Gate:** README, FEATURES, changelog, schema, Gradle metadata, embedded metadata, and license agree.
This phase opens no release, Modrinth, production, or `main` action.

## Phase 8 — Final verification, exact-head deployment, and manual acceptance

### Machine/review gate

1. Confirm branch/ownership and reset or prove the disposable `mod:happy-artillery` world has no active
   old unreleased stash codec before deploying the new `RiderState`.
2. Run all focused suites, full tests, canonical clean build, `git diff --check`, staged checks, secret/
   generated-artifact scans, and parse JUnit XML for exact test/failure/error/skip counts.
3. Compare actual production/resource/test files bidirectionally with `ARCHITECTURE.md`: exactly thirteen
   production Java files and eight test Java files, with only `PlayerDropMixin` and
   `ExternalContainerMixin` as mixins.
4. Search production/docs for stash/index/slot-lock/predictive-click residue, old schema keys, ammo/version
   fiction, fire-to-cry conversion, wall clock, static gameplay state, world/entity/container scans, and
   broad catches. Negative tests may name forbidden terms only when clearly asserting rejection.
5. Obtain fresh independent review on exact staged bytes. It must answer whether any control can escape
   consumption, ordinary item can be overwritten/deleted, callback inspects another player, failure outcome
   is impossible, or old owner remains. Record the diff-hashed receipt and let hooks run normally.
6. Commit and push the final non-`main` checkpoint; verify local, tracking, and fresh remote refs match.
   Build the runtime jar from that exact commit only.

### Exact-head runtime gate

1. Deploy only the exact committed runtime jar through `pyretest`; compare source/artifact/deployed SHA-256.
2. Verify one Happy Artillery 1.2.0 jar loads, startup reaches `Done`, registry/config logs are clean, and
   no unrelated active profile was disturbed.
3. Run this numbered control acceptance on the checksum-matched Java and Bedrock-through-Geyser candidate:
   1. two free hotbar slots allocate Fire then Cry into the first two candidates;
   2. one hotbar plus one main-inventory free slot allocates hotbar first, then main;
   3. zero/one total free candidate changes no inventory byte and sends one refusal message per ride;
   4. move controls through every hotbar slot, main inventory, and offhand; each works only when held;
   5. one/both in main inventory shows correct singular/plural inventory-only HUD;
   6. discard one while the other is in inventory; missing-control warning wins;
   7. direct Q, cursor/menu `THROW`, creative drop, and ordinary/offhand/equipment death drops leave no
      usable marked `ItemEntity`, while ordinary drops remain;
   8. ordinary chest placement, `QUICK_MOVE` empty and merge, number/offhand swap, and `QUICK_CRAFT`
      consume the destination control without deleting ordinary items; `PICKUP_ALL` is tested as inbound
      slot-to-cursor collection, not mislabeled as chest insertion;
   9. losing a control does not regenerate it until dismount/remount;
   10. dismount/remount with ordinary replacements in all former slots overwrites nothing and allocates
       two fresh controls only into current empty candidates;
   11. full inventory on remount refuses cleanly; player death drops ordinary inventory but no controls;
   12. logout/reconnect, hard stop/restart, dimension transition, and ghast death leave no duplicate or
       stranded matching controls;
   13. another player's/prior-ride control never authorizes; `allowPlainItems=true` permits admission only
       and never deletes/counts a plain item as a generated control;
   14. passengers receive heat/status only, and all passenger HUD disappears when the pilot leaves;
   15. straight-up/down/horizontal/diagonal fire produces a fireball AABB disjoint from every ghast/
       passenger AABB and does not immediately self-hit;
   16. modded control item ids resolve after initializer order; missing ids fail at the lifecycle gate;
   17. typoed/removed config keys fail while active config and file bytes remain unchanged;
   18. Java and Bedrock hold-to-fire sustain the accepted configured rate without packet heuristics.
4. On the same exact head, verify the preserved contract: biome/water heat curves, normal fire and real
   `LargeFireball` identity/ownership/`mobGriefing`, cry cooldown, instant/fused overheat through dismount
   and unload/reload, both `breaksBlocks` settings, heat/cooldown/fuse restart continuity with stopped-time
   pause and one-time catch-up, in-flight fireball persistence, HUD packet bounds, cleanup, bounded idle
   work, log noise, and README/jar agreement.
5. If any defect appears, repair docs/code/tests in the owning earlier phase, repeat RED/GREEN, machine
   gate, fresh review, commit/push/ref verification, rebuild, redeploy, and all affected runtime tests.
   Never deploy an uncommitted replacement.
6. If the preferred Java/Bedrock hold path fails, add no packet/click-rate heuristic. Select click-to-fire
   at `0.5` seconds, double every heat-per-shot default (`1.40`, `2.50`, `4.00`, `6.00`, `1.40`) so
   time to detonation remains unchanged, update FEATURES/config/heat/controls/tests, and produce a newly
   reviewed, committed, pushed, checksum-matched replacement candidate before repeating runtime tests.

**Release gate:** only one exact reviewed, committed, pushed head whose checksum-matched jar passes the
complete machine and runtime gates is release-ready. Then reconcile current `origin/main` into the
non-main branch, rerun all invalidated checks and exact-head deployment, open the release PR to `main`,
verify final-head CI/review/mergeability, and stop for Elijah. Merging does not deploy production.
Official GitHub release and Modrinth publication occur only after Elijah merges and explicitly says
`publish it`; production mutation remains a separate approved control request.

## Definition of done

- No ordinary ItemStack can be overwritten or deleted by mount, dismount, death, reload, or recovery.
- Two controls allocate atomically only when two valid free candidates exist.
- Controls move normally inside the owner inventory, work only while held, and carry type+owner+ride identity.
- Every proven outbound route consumes controls at the real mutation boundary without scans or click prediction.
- Missing controls do not regenerate during the same ride; cleanup is scoped and bounded.
- HUD priority, passenger behavior, callback locality, and one-snapshot tick ownership match `FEATURES.md`.
- Config is strict and lifecycle-safe; unrelated heat/fire/cry/fuse/HUD/lifecycle behavior remains GREEN.
- Exactly thirteen production Java files and eight tests match the proposed tree.
- Focused/full tests, canonical build, residue/tree/security checks, fresh review, exact-head deployment,
  and numbered acceptance pass with exact counts/checksums recorded.
- Work remains on a non-`main` branch. Release, Modrinth, `main`, and production gates remain intact.
