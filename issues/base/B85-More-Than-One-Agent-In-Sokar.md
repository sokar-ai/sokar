# B85 — More Than One Agent In Sokar

**Status:** open, written 2026-09-29 at the operator's word, relayed by Agent Coordinator (QC). Priority
high, right after the items other repositories wait on.

## Why

`sokar` has become the project's bottleneck, and not only through build time: one agent owns all of it,
and the work other repositories wait on queues behind whatever that agent is doing. What decides whether
a second agent can help is not the size of the code. It is whether two agents can change it at the same
time without meeting in the same files.

## What the tree looks like, measured 2026-09-29

Agent Coordinator's reading at `4510091`, by file name:

| Part | Size | Changes since 09-15 |
|---|---|---|
| `app`: CLI and daemon logic, one flat package of 286 files | 31,400 lines | 530 file changes |
| Build and test tooling: `sokar-machines`, release, package-check, ffm-check, cpu-check, the acceptance kit | ~13,000 lines | 235 |
| Libraries: core, wire, vault, runtime, shield, gate, clearance, supervisor, agents, hooks | ~22,000 lines | under 120 |

Inside `app`, by class name: task ~6,800 lines; vault and credentials ~4,900; messaging ~4,600; project and
follow ~3,200; egress and clearance ~2,500; gate and review ~1,600; agents ~1,600.

**The files every feature touches, whatever its area**, measured by Agent Core the same day:

| File | Size | Commits since 09-15 |
|---|---|---|
| `issues/base/README.md` | index | 42 |
| `SokarDaemon.java`, every daemon method in one class | 2,120 lines | 25 |
| `org.fuin.sokar.Tasks1.varlink`, the whole daemon contract | 2,590 lines | 22 |
| `SokarPaths.java`, every file location | 592 lines | 17 |

These are where two agents would collide first, even in separate modules. A split that leaves them whole
moves the bottleneck from `app` into four files.

## What must be true

1. **Two agents can work in `sokar` at once**, each in its own worktree, on different areas, and meet only
   where their work really touches.
2. **`sokar` stays one build, one package, one push.** It is one process, one socket contract and one
   installation. A feature that touches task, vault and egress at once must not become a push chain across
   repositories.
3. **An area's boundary is enforced, not agreed.** A dependency one area may not take on another fails the
   build.
4. **The shared files are split along the same lines:** the daemon's methods registered per area, the
   contract's text per area, file locations per area, and an index a second agent's new requirement does
   not have to edit in the same place as the first's.
5. **Who owns what is written down**, in `AGENTS.md`, so an agent knows which area is theirs and whom to
   ask about the rest.

## The two proposed steps, and my judgement of each

**1. The build and test tooling into its own repository, with its own agent.** For `sokar-machines`, the
release tool, package-check, ffm-check and cpu-check this holds:
- They are already a separate world: published artifacts all repositories consume from Central, with a
  command line as the contract. Much of the channel's traffic to `sokar` is about them.
- **What it costs:** `sokar` takes them from Central like everyone else. A change that needs both, like
  the four-account legs of B83 (the leg driver and the kit together), becomes two pushes in order: the
  tooling first, then `sokar`.

**The acceptance kit is the exception to decide deliberately.** Its steps change with the product
(`I enter`, the task and vault steps): 17 changes in the last 14 days, most of them together with a
product change. Moved out, most product features would need a kit release first.

**2. `app` split into modules by area inside `sokar`**, with the dependencies enforced: task; vault and
credentials; messaging; project and follow; egress and clearance; gate and review; agents. **This is the
step that lets a second agent work here**, provided point 4 comes with it. Without point 4 it does not
help.

**Not proposed, and rightly: one repository per command group.** The bottleneck would move into ordering
pushes, not go away.

## What the move has to carry, found by the consumers

- **A plugin dependency does not follow the bom.** The agent repositories run `sokar-release` as the
  exec plugin's dependency at package time, which a bom import does not reach, so they name its version
  themselves as `${sokar.version}`. Once the tools have their own version line, that would keep resolving
  the last snapshot on `sokar`'s line, silently. Each such repository names the tools' version in a
  property of its own, with a test that fails when it disagrees with what `sokar-bom` names - found by
  Agent Smith on 2026-09-29. A property in the bom itself would not help: an import brings the managed
  versions, not the bom's properties.

## Acceptance

- `app` is modules by area, and a dependency between areas that is not allowed fails the build.
- The daemon registers each area's methods from that area; `SokarDaemon` holds none of them itself.
- The contract is split per area, and a client still sees one interface, `org.fuin.sokar.Tasks1`,
  unchanged on the wire.
- Two agents have each shipped a change in a different area in the same day, without a merge conflict
  outside a file both changes really needed.
- If the tooling moves: `sokar` builds, runs its legs and publishes with the tooling taken from Central,
  and nothing in `sokar` reads the tooling's source.

## To be checked

1. **Whether the acceptance kit moves with the tooling or stays in `sokar`.** Its steps change with the
   product; the leg driver and the release tool do not.
2. **The areas' exact lines**, drawn from the real dependencies rather than from class names. Task,
   messaging and vault share the most today.
3. **How the contract splits**: several `.varlink` files assembled into the one interface at build time,
   or one interface per area. The second changes the wire and every client.
4. **The order**: the modules first, or the tooling first. The modules decide whether a second agent in
   `sokar` helps at all; the tooling removes a third of the channel's traffic to `sokar`.
