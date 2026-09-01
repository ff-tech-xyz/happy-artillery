# Happy Artillery 1.2.0 Migration Record

## Status and authority

The ground-up 1.2.0 rebuild and the disposable movable-control migration described by this file are
complete. This is a historical record, not an active execution plan. The canonical target behavior is
`FEATURES.md`, and the annotated proposed ownership tree is `ARCHITECTURE.md`.

The `.hermes/plans/` files are historical implementation intake. They do not override the current
behavior in `FEATURES.md`, and the retired phases below are not a second queue of instructions.

## Completed rebuild

The rebuild established one owner graph with thirteen production Java files, eight risk-grouped tests,
and exactly two narrow mixins: `PlayerDropMixin` and `ExternalContainerMixin`.

| Historical checkpoint | Completed result |
|---|---|
| Boundary evidence and canonical docs | Mapped Minecraft 26.2 routes established the three-argument `ServerPlayer.drop` return boundary and post-mutation `Slot.setChanged` external-container boundary. |
| Strict configuration lifecycle | `Config` became the sole schema, codec, validation, registry-resolution, atomic publication, and active-state owner. Unknown and removed settings fail transactionally. |
| Disposable movable controls | Fixed slots, stashes, restoration, locks, predictive click logic, `DeathDropMixin`, and `SlotGuardMixin` were removed. Controls allocate atomically into the first two free inventory candidates and carry type, owner, and ride identity. |
| Bounded integration and HUD | Input callbacks became actor-local; the normal tick shares one bounded pilot inventory snapshot, groups riders by ghast, removes pilotless HUD, and preserves the presentation packet bounds. |
| Truthful ability boundaries | Fire and cry report only observable outcomes. Normal fire remains a real Happy-Ghast-owned vanilla `LargeFireball` launched clear of the complete ridden collision graph. |
| Exact-candidate activation groundwork | The rebuilt owner graph became runnable and machine-tested; release remained gated on exact-head review, deployment, and manual acceptance. |

The old dependency-ordered implementation Phases 2 through 5 are therefore evidence of completed work,
not tasks to repeat. Their accepted results remain part of `FEATURES.md` unless the current audit intake
changes them explicitly.

## Accepted behavior carried forward

- `RiderState` contains ride identity, input deduplication, and serializable HUD dirty state only. It
  contains no ItemStack, slot index, stash, or restoration model.
- `Components` owns generated-control identity in vanilla `CUSTOM_DATA`. `Controls` owns allocation,
  held admission, one bounded snapshot, ride/transfer cleanup, and every control inventory mutation.
- Generated controls move normally inside their owner's hotbar, main inventory, and offhand. Missing
  controls do not regenerate during the same ride, and ordinary stacks are never overwritten.
- Drop and external-container mixins contain no policy. They delegate to `Controls` at the observed
  mutation boundaries; ordinary drops, ordinary items, and same-owner player-inventory movement remain
  vanilla.
- The sole tick driver uses saved Overworld `gameTime`, advances each ridden ghast once, shares one
  biome/profile context and one pilot snapshot, and performs no world/entity/container scan.
- Heat, normal fire, cry, persistent fuse state, rider presentation, and lifecycle cleanup retain the
  ownership and regression contracts in `FEATURES.md`.

## Current canonical target deltas

The current audit intake updates the final target without reopening the completed rebuild:

- Configuration has seven top-level groups, 35 direct declared settings, and 46 recursively expanded
  scalar leaves. Defaults plus individual overrides are the only model. Missing files receive complete
  defaults; existing valid sparse files preserve their exact bytes through load and reload. Root `preset`
  is a removed setting and fails transactionally. The six draft heat/overheat names are rejected with
  their canonical replacements, Fire cooldown accepts zero, and full dotted paths identify type errors.
  `docs/happy-artillery-config.jsonc` is the documentation-only annotated reference; runtime remains
  strict JSON and never parses it.
- `hud.cooling` supplies configurable zero/slow/normal/fast text and vanilla colors. HUD receives one
  typed mode: firing-window status or the selected profile rate; water does not affect cooling.
  It does not re-derive timing or biome policy, and action cadence and packet bounds remain unchanged.
- `Abilities.FuseQueue` owns UUID-only tasks, resolves entities at execution, removes stale attachment
  ownership, isolates each due task, and never creates an unconditional permanent 20 Hz retry loop.
- Overheat with `breaksBlocks=false` changes no terrain and starts no fire. With `breaksBlocks=true`, it
  uses `ExplosionInteraction.MOB`, leaving terrain damage to vanilla `mobGriefing`. All configured sphere
  fireballs use the one authoritative complete-passenger-union collision-clear launch calculation.
- External-container consumption uses cheap empty/custom-data preflight and removes a marked destination
  through `Slot.set(ItemStack.EMPTY)`, not an in-notification count mutation.
- Launch rejection and ghast removal expose only truthful outcomes, and production HUD teardown routes
  through the same typed implementation as normal tested presentation.

## Retired instructions

The former future Phase 6 subtractive-seam cleanup, Phase 7 public-doc/license rewrite, and Phase 8
combined release gate were written before the current audit. They are superseded and intentionally not
preserved as executable steps here. Some underlying goals remain accepted, but their present scope and
ordering belong only to the current audit-repair intake.

Earlier architecture-ordering instructions are retired. `ARCHITECTURE.md` describes accepted target
ownership in present tense; it is not a migration diary or a claim that completed files are pending.

## Durable verification record

The completed rebuild's deterministic gates established:

- thirteen declared and actual production Java paths;
- eight declared and actual test Java paths;
- only `PlayerDropMixin` and `ExternalContainerMixin` in production and mixin metadata;
- strict transactional config loading and reload;
- bounded per-player/per-pilot work and presentation traffic;
- focused suites plus the canonical clean Gradle build at each accepted checkpoint.

Those results are historical baseline evidence, not proof for later bytes. The current audit intake
requires fresh mechanical counts, contradiction searches, focused tests, canonical clean builds,
exact-byte review, and exact-head runtime evidence for every target it changes.
