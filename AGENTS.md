# PyreHaven Mod Agent Rules

These rules apply to every agent working in Happy Artillery.

## Routing and authority

- Standing rules live in `~/flow.MD`; this lane is routed through `#happy-artillery` and the `happy-artillery` project state.
- Load `pyrehaven-routing`, `pyrehaven-mod-release`, `pyrehaven-git-discipline`, and `pyrehaven-anti-slop-development` before changing code. Use `pyretest-workflow` for runtime tests.
- Never commit, push, merge, or rewrite `main`. Elijah merges release PRs.

## Structure contract

- `ARCHITECTURE.md` is the proposed ownership tree. Every tracked production and test source file must appear in its fenced tree and behavior belongs in the named owner.
- Source file additions, removals, and moves require an earlier architecture-groundwork commit on the branch. Changing the tree and source layout in the same commit is blocked.
- Do not add parallel handlers, managers, wrappers, fallbacks, state maps, schedulers, or config owners over a broken path. Fix the first broken invariant and delete the superseded path.

## Commit gate

- Run the canonical Gradle build and focused tests before staging completion.
- Review the full staged diff, run the mechanical anti-slop guard, and obtain a fresh diff-hashed independent-review receipt for source changes.
- The reviewer answers: name the invariant that broke, and say whether the change fixes it or hides it.
- Never bypass hooks. A blocked commit stays blocked until the bytes, architecture groundwork, or review receipt is corrected.

## Runtime boundary

- Source stays in this checkout. Builds, jars, worlds, and test instances stay under `~/runtime`.
- A completed gameplay feature is tested from its exact committed head through `pyretest`; groundwork and documentation changes receive machine verification but no human gameplay test.
