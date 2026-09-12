# B25 — Names The Operator Should Not Have To Find

**Status:** open, and two of its three parts are built - candidates in the refusal (2026-09-09)
and TAB completion for bash and zsh (2026-09-12). What is left is the picker, which may not be
worth having at all. Asked for on 2026-09-09 after `sokar task resume` was typed without a container
name and answered with picocli's *"Missing required parameter: TASK"*, on a machine where Sokar
knew exactly which three containers could have been resumed.

## The shape of it

Sokar's commands are full of arguments that name something Sokar already knows about: a container,
a project, an agent, an egress set, a credential. Every one of them is a fact the machine holds and
the operator has to go and fetch — run `task list`, read a 38-character container name, type it
back correctly.

**The name is not the hard part of the task. Finding it is.**

## Three different things, and they are not the same feature

**Candidates in the error. Built 2026-09-09.** A command that refuses because a name was missing
or wrong says what it would have accepted. `Suggests` is the interface a command implements;
`task resume` offers stopped tasks, `task attach` running ones, `task stop` both.

Two things fell out of building it. **The parameter exception handler was installed only in
`main`**, so all eleven CLI tests constructed their own `CommandLine` and exercised picocli's
default messages - none of `CliErrors` was covered through the CLI at all. There is now one
factory, `SokarCli.commandLine(context)`, and the tests use it. And **two tests asserted that a
refusal touched podman not at all**; offering candidates reads `podman ps`, so both now assert the
property that actually matters - only ever asked to list, never to act.

**Completion on TAB. Built 2026-09-12**, for bash and zsh, after the operator found that
`sokar task attach` and `sokar project` completed nothing. It is a callback rather than a generated
list: picocli bakes **static** candidates into a generated script, and container names are live, so
the two scripts ask `sokar __complete` the way kubectl and docker both do. Each command offers the
names it can actually use, read through the same `Suggests.candidates()` the refusal prints — one
source, so the two cannot disagree. `task label` and `task clearance` and `project delete` gained
that interface here, which improved their refusals as well.

**This is where the native image pays for itself.** A JVM round trip per TAB press would be
unusable; `sokar` starts in milliseconds. What is left is the `podman ps` behind it, roughly a
tenth of a second, which is tolerable — and caching it would trade that for staleness, where
offering a container that no longer exists is worse than answering slowly.

Three things it cost, none of which a unit test would have found:

- **The native image carried no scripts.** They are resources, every test passed, and the binary
  answered *"this build carries no bash completion script"*. A resource nobody declares is simply
  absent from the image. Now declared, and a test asserts that the declaration and the files agree
  in both directions — it is the one thing about this that is invisible from a JVM.
- **zsh drops an empty word.** The partial word is empty whenever the cursor sits after a space,
  which is most of the times anybody presses TAB, and unquoted it vanishes from the argument list
  entirely — so `sokar task <TAB>` reached the program as `sokar task` and was answered with
  `task`. Measured on zsh 5.9; the bash half was right by accident and is now pinned too.
- **The two ecosystems disagree about where a zsh completion goes**, and neither directory works on
  the other side: Debian's zsh reads `vendor-completions` and has no `site-functions` in its fpath,
  Fedora's the other way round. Measured on both. The package parity check normalizes the
  directory rather than excluding the file, so a package that forgets it still fails.

**`task start` is deliberately not completed.** Its argument is a task name within a project, not a
container name, so the list every other verb offers would be the wrong one there. Completing it
properly means resolving the project from the working directory first, and that is a separate
piece of work rather than a line.

**A picker when the argument is omitted.** `sokar task resume` on a terminal could show a numbered
list. `VaultPutCommand` already gates on `tty.isTerminal()`, so the precedent exists — and the gate
is mandatory, or every script that forgets an argument hangs on a prompt instead of failing.

## Not the same question as `sokar setup`

A completion script is a **system file** — `/usr/share/bash-completion/completions/sokar` — and the
package can install it. That is genuinely different from the OCI hooks, which podman reads per user
and which an install script running as root has no business writing. The two look alike and are
not.

## What must be true

**A command that needs a name Sokar already knows never makes the operator go and find it.**

## Acceptance

- A command refused for a missing or unknown name says which names it would have accepted.
- The candidates are **the ones that command can actually use**: `resume` offers stopped tasks,
  `attach` offers running ones, `stop` offers both. One list for all three is a list that is wrong
  twice.
- Nothing is offered that is not a task. A completion built on the `sokar-` prefix would offer the
  throwaway containers `vault login` creates — the same defect that put them in `task list`, in a
  new place. `ContainerName.isTask` is the only correct filter.
- **Nothing that needs the vault open is offered while it is locked**, and when a list is empty
  because the store is locked, that is said rather than shown as "there are none".
- Completion never changes anything: it reads, and a machine mid-TAB is not a machine being
  operated on.
- A script that omits a required argument still fails rather than waiting for somebody to answer.

## Notes

**Ranked high for frequency, not severity.** Nothing here is a correctness defect and nothing is
blocked on it — B01 and B10 both describe things that go silently wrong, which is worse than
anything on this page. It is above them because it is cheap, and because it is met every single
time somebody uses the tool rather than once in a rare state.

**The first of the three was worth doing alone.** Candidates in the error message need no script,
no packaging and no new command, and they answered the case that prompted this. The second was
asked for on 2026-09-12 and built; only the picker is left, and its own question below is whether
it should exist at all.

## To be checked

- **Whether the picker should exist at all.** Sokar is a CLI that a daemon also drives; making a
  command interactive when it has a terminal is a change in character, not a convenience, and it
  is the one of the three that could make a script hang.
- ~~**Whether `task attach` on a stopped task should offer to start it.**~~ **Built 2026-09-09.**
  It asks `[Y/n]` on a terminal and resumes through the same rendering `task resume` uses, so the
  two cannot disagree about what a resumed task did. Without a terminal the old refusal stands
  unchanged - `task attach` is also how a script gets in, and one that finds a task stopped must
  fail rather than wait for an answer nobody will type. End of input is not consent. Originally
  asked as:
  somebody who typed `attach` has said what they want, and being told to run `resume` and then
  `attach` is being told to say it twice. It is the same character question as the picker, with
  one extra edge - resuming is not free: it starts the gate, the credential broker and the
  clearance watcher, and re-applies an egress ruleset. The codebase already argues for the
  opposite of a refusal one level down: `tmux new-session -A` is attach-or-create precisely so
  that "coming back" is not a second code path exercised less often.
- **What a TAB costs on a loaded machine.** A tenth of a second is a guess from `podman ps` on an
  idle test VM, not a measurement on a machine running several tasks.
- **Whether the daemon should answer instead of podman.** Completion runs where the operator types,
  which for a remote setup is not where the containers are. The socket already carries `Tasks`;
  whether completion should use it is the same question B06 asks about everything else.
