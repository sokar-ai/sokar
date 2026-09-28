# B71 — A Project Is Named, Not Pointed At

**Status:** built on 2026-09-19. A command names a project; it does not point at a
file.

## What is wrong today

`sokar task start` takes `-p/--project <file>`, a path, defaulting to `./project.yml`. So which
project a task belongs to depends on which directory somebody was standing in - and on a machine
that takes its configuration from a repository it follows, the directory is the one place the
answer should not come from.

It is worse than a spelling: **a project's file can be in three places.** The working directory,
`config/projects/<name>/project.yml` where `CreateProject` puts it, and the followed clone. Three
answers to one question, and the only one a machine verified is the one nothing reads - see
[B70](B70-The-Clone-Is-What-A-Task-Gets.md).

## What must be true

1. **`--project` takes a name.** `sokar task start review --project acme --repository backend`. The
   name is what `sokar project list` prints and what `follow` was given.
2. **A name that is not followed here is refused**, and the refusal lists what is. Not "no such
   file": a person who mistypes a project name should be told which projects this machine has,
   which is a question only the machine can answer.
3. **The path form goes.** Not deprecated beside the name - removed. Two ways to say which project
   is how the three places above happened.
4. **Every command that takes a project takes the name**: the gate commands, the shield commands,
   the talk commands, and their varlink counterparts. One vocabulary, or a person learns two.
5. **What a client sends is a name**, so an interface that cannot see this machine's filesystem
   stops having to guess at paths - which is why it was given `file` in the first place.

## Built, 2026-09-19

**`ProjectSource` answers the name**, in one place and in one order: the verified clone of a
followed project, then where a task last read one, then a `project.yml` in the working directory
whose name matches. The third is **transitional and marked as such** - until a project can only
come to be by being followed, removing it would leave no way to start a task for a project that has
never run, because it is in neither the follow record nor the registry until it has.

**Every command and every call takes the name**: `task start`, `task prepare`, the four gate
methods, `gate serve`, `gate backup`, `gate restore`, `gate checkout`, `shield egress`,
`shield dns`, `talk peers`, `talk pass`, and `Start`, `CanStart`, `SetEgress` and the rest on the
socket. The gate helper is spawned with the name too, so the gate and the task cannot end up
reading two different files.

**`shield egress --task` keeps working without a project**, because a running task names its own -
asking for it again would be asking somebody to repeat what the machine already knows. `talk pass
<task>` follows the same rule since 2026-09-28: `--project` is taken from the task when not given,
and refused only for a task that records none - before, it looked up a project called `null`. That was
`required = true` for an hour and wrong.

**An unknown name is an answer on `CanStart`, not an error.** Asking whether work can start is a
question a client is entitled to ask about a project that turns out not to exist, and throwing
would hand it an exception for asking. It answers `NO_PROJECT_FILE` and names what the machine has.

## Acceptance

- Starting a task names a project and works from any directory, including one holding an unrelated
  `project.yml`. **Met** - and measured by the test that starts from a directory whose file names a
  different base image.
- A project name this machine does not have is refused with the names it does have. **Met.**
- No command accepts a project file path any more, and none has two ways to say which project.
  **Met.**
- An interface can drive a task start knowing only what `Projects()` told it. **Met.**
- The daemon and the CLI refuse the same input with the same words. **Met**, through the same
  resolver.

## Notes

**This is a breaking change to every command that takes `-p`**, to the end-to-end run, to the
acceptance suite and to every documented example. That is the cost of having one answer instead of
three, and it is paid once.

**It only makes sense after [B70](B70-The-Clone-Is-What-A-Task-Gets.md).** A name that resolves to
a file nothing verified is the same problem with better spelling.
