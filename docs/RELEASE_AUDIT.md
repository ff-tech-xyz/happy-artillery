# Release audit — 2026-09-15

The original release audit below found a reproducible control-crafting exploit. The control-containment
follow-up records its fix and the bundle fix; release acceptance is still pending.

## Verified

- Canonical `./gradlew clean build` passed with 396 tests across eight suites, zero failures and errors.
- `git diff --cached --check` passed. Existing staged work was preserved.
- Candidate metadata is version 1.2.0, Minecraft 26.2, Fabric Loader >=0.19.3, Java >=25,
  and `environment="*"`; server-code-only behavior must remain compatible with unmodded Java clients.
- GitHub is not archived. The Modrinth project is archived. No publication or unarchive was performed.

## Release blocker: generated controls are craftable

`Controls.transformExternalControlWrite` explicitly preserves marked items in
`TransientCraftingContainer` inputs. Vanilla recipe matching accepts those items by their item type.
A marked Fire Charge plus gunpowder and dye produces an unmarked large-ball firework star.
The new product is not a control and therefore is not removed by ride cleanup. Remounting supplies
another temporary control, making the ingredient conversion repeatable.

An isolated executable probe invoked the production input transform and Minecraft 26.2's actual
`FireworkStarRecipe.matches` and `assemble` methods, with the corresponding fire-charge shape,
gunpowder fuel, dye, and firework-star result. It confirmed an unmarked large-ball output:

```text
REPRODUCED: marked Fire Control accepted by vanilla recipe; output=unmarked large-ball firework star
BUILD SUCCESSFUL
```

This verifies the recipe path, not a manual player session. The probe is outside the checkout at
`runtime/happy-artillery-release-audit/`, with JUnit XML evidence in its `results/` directory.
Its first compilation failed because 26.2 exposes red dye through `Items.DYE.red()` rather than
`Items.RED_DYE`; correcting the probe API call produced the result above.

Fix the control ownership invariant at the existing inventory/crafting boundary. Do not add a
periodic scanner or attempt to identify already-crafted products. Ordinary crafting must remain
unchanged. Keeping controls in crafting inputs is an explicit current contract, so either that
contract needs a deliberate change or recipe use must be prevented without destroying valid storage.

## Documentation and storefront findings

- README describes generic overheat effects but should plainly warn that default detonation destroys
  the Happy Ghast. Check the final wording against `overheat.forceRemoveGhast` defaults.
- The live Modrinth body describes ammunition, fixed hotbar slots, water cooling, restart-only config,
  and Java 21. Those describe the old mod, not this candidate. Its gallery also advertises an ammo HUD.
- Modrinth still carries MIT licensing and old GitHub source/issues URLs; candidate licensing is CC0.
- GitHub's repository summary still names Minecraft 1.21.x.
- The changelog labels 1.1.2.2 without its prerelease qualification, whereas GitHub lists it as a
  prerelease. Preserve that historical distinction.

## Control-containment fixes — 2026-09-16

The working candidate removes the crafting exception and rejects marked controls at vanilla's shared
bundle eligibility check. The crafting and bundle regressions failed before their respective fixes.
Fabric-loader JUnit tests now exercise the applied slot and bundle mixins: both controls in every
2x2/3x3 crafting input; every slot of every registry-discovered menu-backed block container; ender
chest slots; and both bundle cursor insertion and slot transfer. Ordinary items retain vanilla behavior.
The block-container matrix includes brewing stands, all furnace variants, storage and copper chests,
barrels, all shulker colors, hoppers, droppers, dispensers, and crafters. Blocks without menus remain
covered by the existing marked block-use rejection. Vanilla recipe-book eligibility excludes the
named generated controls, and that eligibility is regression-tested.

`./gradlew -I runtime/happy-artillery/runtime-build.init.gradle clean build` (init path supplied as an
absolute path) passed: 400 tests, zero failures, errors, or skips. This is executed Minecraft boundary
verification, not an unmodded-client gameplay session. Metadata remains `environment="*"`.

The missing shared anti-slop guard was recovered and the installed hook now resolves its canonical
source under `phred2.0/phred/`. The guard suite passes 81 tests, and the required architecture-groundwork
commit succeeded with the hook active. No checks were bypassed. Prior staged update work is preserved.
The local test-profile resolver now selects the canonical checkout and runtime; exact committed-head
server acceptance and final independent review are still pending at this checkpoint.

## Remaining acceptance and release work

The original `pyretest` inspection resolved this profile to the old checkout and found stale active
metadata without a running supervisor. The resolver is now repaired; the stale checkout was not deployed.

After final independent review, complete exact-candidate runtime acceptance with an unmodded Java
client and the release PR. Maintainer merge/publication and
approval of the exact Modrinth text remain required by the standing release rules. Update the page
and metadata to match the shipped artifact before unarchiving. Do not describe 1.2.0 as shipped while
only old versions are available.
