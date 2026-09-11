# B44 — One Way To Start Work

**Status:** open, and it proposes removing something rather than adding one. Written on
2026-09-11 after an operator used the obvious command on an existing task and Sokar silently did
something else.

## What happened

The operator had a stopped task and typed what anyone would type:

    sokar task run sokar-utils4j-shell-5684

They got a shell, worked, typed `exit`, and looked at the list. Their task was still down. What
actually happened, in three steps, none of them reported:

1. `run` takes a **task name**, not a container name. `sokar-utils4j-shell-5684` was accepted as
   one, with no complaint that a container by exactly that name already existed.
2. The container it created was therefore
   `sokar-utils4j-sokar-utils4j-shell-5684-<runId>` - the name is
   `sokar-<project>-<task>-<runId>`, so the old one was never a candidate to be reused.
3. `run` is ephemeral. On a clean exit its armed teardown **removes** the container it made. The
   entry still in the list afterwards was the original, untouched, from two days earlier.

Three commands would have been right, depending on a state the operator cannot see from the
command they typed: `resume` for a stopped task, `attach` for a running one, `run` for a new one.

## The verbs today

| Verb | Where | Creates | On exit |
|---|---|---|---|
| `task run <task>` | CLI | a new container, always | **removes it**, unless `--keep` or a signal |
| `Start` | varlink only | a new container | leaves it running; nobody is expected to watch |
| `task resume <container>` | CLI | nothing | restarts a stopped container, with its workspace |
| `task attach <container>` | CLI | nothing | enters a running container |

Two of them are in the CLI, a third is only in the contract the interface uses, and the split
between them is by *what Sokar does internally*, not by anything the operator knows when they
decide what to type.

## The shape proposed

One verb that covers the states, and one modifier:

- **`run`, no container yet** — creates it and puts the operator inside.
- **`run`, container stopped** — restarts that container, with its workspace, and puts the
  operator inside. Today's `resume`, reached by the command a person already reaches for.
- **`run --detach`** — the same in both cases, except the operator keeps their prompt and the
  task runs behind them. Today's `Start`.
- **`attach`** — goes into a task that is running. Unchanged.

`resume` and `Start` stop being things anyone has to choose between; they become what `run` does
when it looks at the state.

## What must be true

1. A name that already identifies a task is never quietly reinterpreted as something else. Asking
   to run a thing that exists either **does** that thing or **fails saying it exists** - it never
   creates a second thing with a similar name.
2. Which of the two paths `run` took is said plainly. Restarting an existing workspace and
   creating an empty one are not interchangeable outcomes, and the operator finds out from the
   report, not from the prompt.
3. Starting work never destroys work. See the first question below: today's default does.
4. A task that is already running is not started twice. `run` on it says so and names `attach`.
5. The interface and the CLI agree on the model. A person who learns one is not surprised by the
   other.

## Acceptance criteria

- `run` against a stopped task restarts *that* task: same container, same workspace, same branch,
  and a test asserts the container id is unchanged.
- `run` against a running task fails, names the task, and names `attach`. Exit code distinguishes
  it from a real error.
- `run` against a name that is a container but not a task fails with what exists, rather than
  creating anything.
- `run --detach` leaves a task the operator can later `attach` to, and returns promptly.
- Every one of these is covered from a terminal by [B27](B27-Testing-What-A-Person-Actually-Does.md),
  because the whole defect was invisible to anything that did not type the command a person types.

## To be checked

1. **Does the ephemeral default survive?** It cannot, unchanged: if `run` also means "restart the
   task I already have", then removing on exit destroys the thing the operator asked to keep.
   Either the default flips to keeping, with `--rm` for the throwaway case, or `run` refuses to
   adopt an existing container. The first is closer to what the operator expected and further
   from what `run` does today. **This is the decision the rest hangs on.**
2. **What is a task's identity?** Today the container name carries a `<runId>`, so "the same task"
   never maps to one container - which is why step 2 above could happen at all. A `run` that can
   resume needs one container per project and task, or a rule saying which of several is meant.
   Dropping the suffix changes names people and scripts already use.
3. ~~**Does the contract change?**~~ **Decided on 2026-09-11: yes, in place.** `Start` and
   `Resume` are in `org.fuin.sokar.Tasks1.varlink`, and its compatibility rules would normally
   forbid removing either - a shape that turns out wrong costs a `Tasks2` served beside `Tasks1`
   for a release. Those rules are now marked *not in force yet* in the file itself: there is no
   release, no tag, and the only clients are this daemon and the frontend beside it, changed
   together. So the contract is edited rather than forked, and the frontend moves with it. What
   the exemption does not license is changing a method while the frontend still reads the old
   meaning: both sides agree first, and the change is written down. The exemption ends at the
   first release.
4. **What does `--detach` do about the first build?** `Start` streams the image build because it
   takes minutes and silence is indistinguishable from a hang. A detaching `run` has to say
   something useful about a build it is not waiting for.
5. Whether `prepare` belongs in this story too: it does everything up to starting the container
   and is a fifth thing to know about.
