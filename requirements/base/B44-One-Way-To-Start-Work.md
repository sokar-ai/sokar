# B44 — One Way To Start Work

**Status:** shape decided on 2026-09-11, not built. It removes commands rather than adding them.
Written after an operator used the obvious command on an existing task and Sokar silently did
something else.

## What happened

The operator had a stopped task and typed what anyone would type:

    sokar task run sokar-utils4j-shell-5684

They got a shell, worked, typed `exit`, and looked at the list. Their task was still down. Three
things happened, none of them reported:

1. `run` takes a **task name**, not a container name. The container name was accepted as one, with
   no complaint that a container by exactly that name already existed.
2. The container it created was therefore `sokar-utils4j-sokar-utils4j-shell-5684-<runId>`, from
   `sokar-<project>-<task>-<runId>`. The old one was never a candidate to be reused.
3. `run` is ephemeral. On a clean exit its armed teardown **removes** what it made. The entry still
   in the list afterwards was the original, untouched, from two days earlier.

Both attempts are still visible in the runtime directory on the test VM:

    sokar-utils4j-sokar-utils4j-shell-5684-2065495   10:29
    sokar-utils4j-sokar-utils4j-shell-5684-2338937   10:42

Three commands would have been right, depending on a state the operator cannot see from the
command they typed: `resume` for a stopped task, `attach` for a running one, `run` for a new one.

## Why it cannot be fixed where it shows

`TaskLaunch` names a container with **the PID of the process that launched it**:

```java
runner.containerName(project, request.task(), String.valueOf(ProcessHandle.current().pid()));
```

Two invocations never share a PID, so two invocations never share a container. There is no mapping
from "a task" to "its container" - which is why `run` always creates: the question *which one did
you mean* is not answerable from what it holds. Every other complaint here follows from that.

## The shape decided

`docker compose`, not `docker`. A `project.yml` declaring tasks is a compose file, and `compose up`
already means create-or-start; taking `docker start`'s narrower meaning would be the surprising
choice, not the safe one.

| Intent | Today | Decided | compose |
|---|---|---|---|
| create, do not start | `task prepare` | `task create` | `create` |
| create **or** start, at a terminal | `task run` | `task start` | `up` |
| the same, in the background | varlink `Start` only | `task start --detach` | `up -d` |
| throw it away on exit | **the default** | `task start --rm` | `run --rm` |
| go inside | `task attach` | `task attach` | `exec` |
| halt, keep | `task stop` | `task stop` | `stop` |
| delete | `task stop --purge` | `task remove` | `rm` |
| list | `task list` | `task list` | `ps` |
| resume | `task resume` | **gone** | — |

`--keep` disappears because it becomes the default. The dangerous half of this requirement is that
inversion: today the command a person reaches for destroys the container it made.

### 1. One container per task

`sokar-<project>-<task>`, no run id. `start` then has an answerable question: absent, create;
stopped, start; running, say so and name `attach`. A task cannot run twice at once, which nothing
wants - two runs of one task share the project mirror and the gate.

The project name may be taken from the directory the command runs in, which is normally the git
repository's name and normally right. Today `Project.name` is a required field of `project.yml`
and nothing is derived; that `utils4j` matches its directory is diligence, not mechanism. Belongs
with [B25](B25-Names-The-Operator-Should-Not-Have-To-Find.md).

### 2. What survives a restart

Resuming needs the helpers' command lines **and their environment**, because a container's
environment is fixed when it is created: the agent already holds the gate token, and a freshly
minted one would be rejected as a bad credential. `resume.json` therefore carries
`SOKAR_GATE_TOKEN` in clear.

It lives in the runtime directory, which is tmpfs, and the comment says why:

> Under the runtime directory, so the kernel removes it when the session ends and no stale sidecar
> can be picked up by a later run.

Good reason, unwritten consequence: **a reboot makes a task unresumable**. The work in the
container survives; the means of bringing it back does not. Measured - the operator's
`sokar-utils4j-shell-5684` has no runtime directory left, and the frontend reported `Resume` on it
doing nothing.

Decided: `resume.json` becomes persistent, **without the secret**; the gate token goes into the
vault Sokar already has, referenced by name. The cost is accepted deliberately: the first `start`
after a reboot needs the vault unlocked, even for a task that has nothing to do with credentials.

### 3. `remove` refuses a running task

`remove` inherits everything `--purge` protects: `HOLDS_WORK` refuses rather than destroys,
`--rescue` pushes into the mirror first, `NOTHING_KNOWS` refuses when nothing could tell. What is
new is that removal can now meet a *running* task, which it never could while it hung off `stop`.
It refuses and names `stop`; `--force` does both in one step.

### 4. One rule for where a file lives

The per-task directory holds fourteen files with no rule saying which are meant to be volatile.
Two protections were quietly resting on the wrong side of that line - `resume.json` (above) and
the unhanded-work note, which `TaskControl` writes on the way down precisely because "whoever
removes this task later cannot ask the container itself", and which `remove` reads to decide
whether to refuse.

After a reboot that note is gone, `remove` answers `NOTHING_KNOWS`, and the operator has to pass
`--force` - the flag that means *destroy work*. A guard that must be routinely overridden is not a
guard. Anyone who reboots regularly learns to type `--force` always.

The rule: **describes something live, it is volatile; is it knowledge about the task, it is
durable.** Sockets, pid files, the nftables ruleset and the resolver's files stay in the runtime
directory. `resume.json`, the work note and the task's own description move to state.

### 5. One coordinated cut

`Start`, `Resume` and `Stop` are in `org.fuin.sokar.Tasks1.varlink`, whose compatibility rules
would forbid removing `Resume` or changing what `Start` means. Those rules are marked *not in force
yet* in the file: there is no release, no tag, and the only clients are this daemon and the
frontend beside it.

Decided: **one agreed cut**, contract and daemon here, tiles there, rather than a compatibility
shim neither side will ever need again. Between the two pushes an old frontend gets
`MethodNotFound` and a tile action does nothing. Nobody outside notices; the operator will, and
accepted that.

## What must be true

1. A name that already identifies a task is never quietly reinterpreted. Asking to start a thing
   that exists either does it or fails saying it exists - it never creates a second thing beside it.
2. Which of the two paths `start` took is said plainly. Restarting a workspace and creating an empty
   one are not interchangeable outcomes.
3. Starting work never destroys work.
4. A task that is already running is not started twice; the answer names `attach`.
5. The list says **beforehand** what the verb would do to each task - start it fresh, bring it back,
   or refuse and why. The interface names the action by its effect and shows a refusal as
   unavailable with its reason, rather than finding out by pressing.
6. The reply says what it did, as a named outcome.
7. The CLI and the interface agree on the model.

Points 5 and 6 are the frontend's, asked for on the channel on 2026-09-11.

## Acceptance criteria

- `start` against a stopped task starts *that* task: same container, same workspace, same branch,
  asserted by container id.
- `start` against a running task fails, names it, names `attach`, and its exit code distinguishes
  that from a real error.
- `start` against a name that is a container but not a task fails with what exists, creating
  nothing.
- `start --detach` leaves a task that `attach` reaches, and returns promptly. It says something
  useful about a build it is not waiting for.
- `start` after a reboot brings the task back whole - container **and** helpers - with the gate
  token it already had, or reports that it did not.
- `remove` on a running task refuses; `remove --force` stops and removes; `HOLDS_WORK` and
  `NOTHING_KNOWS` still refuse, **after a reboot as well**, which is the case that fails today.
- Every one of these is exercised from a terminal by
  [B27](B27-Testing-What-A-Person-Actually-Does.md). The whole defect was invisible to anything
  that did not type what a person types.

## To be checked

1. **Existing containers become unreachable.** Thirteen on the test VM carry a run id and will not
   match the new scheme, so `start` will not find them. `list` shows them and `remove` deletes
   them, but they cannot be resumed. Acceptable for throwaway shells; whether anything else needs
   a migration is not checked.
2. **What `--detach` reports about a build it is not watching.** `Start` streams because a build
   takes minutes and silence is indistinguishable from a hang.
3. **Whether `create` earns its place** or is just `start --detach` stopping one step earlier.
4. Whether the vault prompt after a reboot can be avoided for a task that holds no credential of
   its own - it still needs its gate token, so probably not, but it has not been looked at.
