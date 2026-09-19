# B72 — A Project Exists By Being Followed

**Status:** built on 2026-09-19. The only way a project comes to be on a machine is
that the machine was told to follow its repository. Sokar writes no `project.yml`, anywhere.

## The whole flow, after this

```
sokar project follow acme git@github.com:acme/acme-project.git
sokar task start review --project acme --repository backend
```

Two commands. The first brings the project; the second starts work in one of the repositories that
project declares, and nothing else is a valid answer to `--repository`.

Before either, somebody wrote `project.yml` into the project repository and committed it - in an
editor, not in Sokar.

## What must be true

1. **`CreateProject` goes, and so does the wizard.** Not reduced to rendering: gone. A project is
   created by a commit, and a second way to have one is a way to have a project the machine never
   verified.
2. **The checking goes with it, because the machine already does it.** That was the argument for
   keeping a rendering-and-checking call, and it is wrong: the machine checks what it is handed,
   when it is handed it - at `follow` and at every reconciliation. A check beforehand is a second
   place the same question can be answered differently.
3. **The check at follow has to be the whole check.** Today reconciliation only asks whether the
   file reads as a project. It must also refuse an egress set this machine does not have, a name
   that cannot become an image tag, and everything else `CreateProject` refuses today - otherwise
   removing that call loses a refusal rather than moving it. The named reasons for reporting it
   already exist.
4. **`project delete` becomes `unfollow`.** There is nothing else left to delete.
5. **A project that is not followed does not exist.** No task can be started for one, because there
   is no way to name it.

## Built, 2026-09-19

**`CreateProject`, `ProjectCreation` and the wizard are gone**, with their tests and their entry in
the contract. The contract says so where the method was, rather than leaving a hole somebody has to
work out.

**The checks moved to where the file arrives.** Reading it covers the name's shape, the security
class, the base image and an online project without an upstream; what was left was the egress sets,
and a follow now refuses a project naming a set this machine does not have. Refused there rather
than at the first task start, which is minutes later and would read as a broken build.

**The directory is no longer a source.** `ProjectSource` answers from the verified clone, then from
what a task last recorded - which survives only for the tasks a machine already has. Nothing new
arrives that way.

**`unfollow --force` is the teardown that cannot be refused**, and it already was: it overrides both
held work and running tasks, and removes the mirrors, the image, the build directory, the registry
entry and the followed clone. What it did not do was run blind - it refused a project it does not
follow, which is exactly the state a cleanup trap finds after a failure. With `--force` that is now
a success that sweeps whatever is there. **I told the agent repositories this did not exist; it did,
and I was wrong.**

**The fixtures follow a local repository.** The end-to-end run and the acceptance kit make a git
repository on the machine, commit the project file into it and follow it `--unverified`. No forge,
no network, no signing key in a script - and nothing writes a project file into a directory and
stands in it any more.

## Acceptance

- There is no call and no command that writes a `project.yml`. **Met.**
- A project file naming an egress set this machine does not have is refused **at follow**, rather
  than at the first task start. **Met**, and the end-to-end run measures it there now.
- A project file whose name could not become an image tag is refused at follow. **Met**, by the
  reader, which is where that rule already lived.
- Starting a task for a project this machine does not follow is refused, and says how to follow
  one. **Met.**
- The documented first run is the two commands above. **Met in the documentation**; that it works
  from a clean machine is what the next rented-machine run has to show.
- Nothing in the end-to-end run or the acceptance suite writes a project file *as the way to have a
  project*: both now commit one into a repository and follow it. **Met.**

## What the fixtures need, from the agent repositories

Read off their own scripts rather than designed here, and accepted as acceptance criteria because
they are testable where my own wording was not:

1. **Follow takes a local path** - a directory, or `file://`. A rented CI machine has no forge to
   push a fixture to, and a public one is a dependency on somebody else's uptime.
2. **Follow is synchronous.** Returning 0 means the project is startable now, not at the next
   reconciliation. A script that polls for it is a script with a sleep in it.
3. **An unverified follow can start tasks.** Refusing at start would make every fixture sign
   commits; reporting `--unverified` as a state is the whole of it.
4. **A teardown that cannot be refused.** One command that removes everything a follow created,
   with a scriptable *"I mean it"* - because a cleanup trap runs after a failure, which is exactly
   when work is held and a task still exists. Today `unfollow` refuses and `--force` destroys, and
   **there is no single command that can be run blind in a trap**. That is new work rather than a
   rename.
   <p>
   It also ends something that should never have started: the agent suites delete
   `sokar/build/<project>` and `sokar/mirrors/<project>.git` **by path** in their cleanup, because
   nothing offered to do it for them.
5. **The acceptance kit carries the replacement step**, the way it carries *"a project called …"*
   today, so three agent repositories do not each invent a local-repository dance and drift apart.

## Notes

**What this costs, said plainly.** Today `sokar task start` in a directory without a project file
offers to write one and Enter accepts every default. That is the first five minutes in the README,
both getting-started guides, the cheat sheet and the FAQ, and it goes. In its place: write a file,
commit it, follow it. For somebody trying Sokar on a laptop that is a real step up, and the answer
is not to keep a second way - it is that the two commands must work without a forge, against a
repository on the same machine.

**It also reverses something I built today.** `CreateProject` was checked, tested and reported as
working; this deletes it. The reasoning that survives - that a client cannot know whether an answer
is acceptable to *this* machine - is exactly why point 3 exists.

**Depends on [B70](B70-The-Clone-Is-What-A-Task-Gets.md) and
[B71](B71-A-Project-Is-Named-Not-Pointed-At.md).** Removing the local file before the clone is read
and before a project can be named would leave no way to start a task at all.
