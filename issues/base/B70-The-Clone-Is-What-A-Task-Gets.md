# B70 — The Clone Is What A Task Gets

**Status:** built on 2026-09-19. A task of a followed project runs against the
`project.yml` the machine **verified**, not against a file somebody left in a directory.

## The finding

Following a project repository fetches it, checks the signature, resets a clone under
`state/follow/<name>.git` and confirms that `project.yml` reads as a project. **Then nothing uses
it.** Two places touch that clone: the reconciliation that writes it, and `unfollow`, which deletes
it. A task started with `sokar task start` reads `./project.yml` out of the working directory and
never learns the followed one exists.

So reconciliation changes nothing about what a task does. Every promise that rests on it - two
machines ending up with the same project, a machine that cannot fetch keeping what it verified, an
agent being unable to put configuration in force - is true of a directory nobody reads.

**A test of mine passed while measuring nothing.** It compared the `project.yml` in two machines'
clones and found them equal. They were. Whether a task ever sees either was not asked, and that is
the half that matters.

## What must be true

1. **A followed project's file comes from its clone.** Starting a task for a followed project reads
   `state/follow/<name>.git/project.yml`, whatever directory the command was run in.
2. **A file somewhere else does not win.** For a followed project, a `project.yml` in the working
   directory is not consulted at all - not preferred, not merged, not warned about and then used.
   Two sources is how a machine comes to run something nobody chose.
3. **What a task got is recorded with the task**, by commit. *"This task ran against `a1b2c3`"* is
   the question a person asks after something goes wrong, and it cannot be answered from a file
   that has moved on since.
4. **A reconciliation does not change a running task.** A task holds what it started with; the next
   one gets what is in force then. Anything else is a container whose rules change under it.
5. **The project's own repository is seeded from the clone.** Its mirror needs history, and the
   followed clone is that history - nothing should fetch it twice from the forge.

## Built, 2026-09-19

**One place answers where a project's file comes from.** `ProjectSource` resolves a name: a followed
project's verified clone first, then what a task last recorded, then nothing. The three answers that
existed are now one, ordered by what was checked.

**A followed project with nothing in force starts no task.** Refused, unreachable, nothing pinned -
the local file is *not* the fallback. Falling back would run exactly what the machine declined to
apply, and would make a refusal look as though it had no effect. Found while writing the resolver:
the obvious shape returns a file whenever it can find one.

**The commit is a fourth container label.** The project moves on, and *"what was this task running
under"* is asked afterwards - so it is kept with the task rather than read back out of a file that
has changed since. `Task.commit` carries it, "" when nothing verified it.

**`doctor` names the commit each project is in force at.** "Up to date" without one says nothing a
person can check against the repository.

## Acceptance

- A task started for a followed project uses the clone's file, measured by starting one from a
  directory holding a **different** `project.yml` and showing that the directory's file had no
  effect. **Met**, exactly that way: the directory says one base image and the clone says another.
- A project that is not followed keeps working exactly as it does today. **Met.**
- The commit a task ran against is on the task, and survives the project moving on. **Met**, as a
  container label.
- A reconciliation while a task runs changes nothing about that task. **Met**, by the assertion
  that a reconciliation leaves a held message, a moderation file and a task's own state untouched.
- `sokar doctor` and the project listing say which commit a project is in force at, from the same
  value the task used. **Met.**

## Notes

**This is the requirement the other three rest on.** Naming a project rather than a path, and a
project existing only by following, both assume the clone is what work is done against. Until it
is, following is an expensive way to keep a directory up to date.
