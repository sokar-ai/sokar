# B68 — What One Repository's Work Needs

**Status:** built on 2026-09-18. A repository of a project may declare the egress
and the limits its own work needs. **Egress is added** to the project's; **a limit replaces** the
project's, key by key. The two rules differ because the values differ, and the difference is the
substance of this requirement rather than an inconsistency in it.

## This corrects B67

[B67](B67-A-Project-Is-More-Than-One-Repository.md) says, as its third point:

> **The containment does not multiply.** One image, one security class, one egress set, one set of
> limits, however many repositories - those describe the box, not the contents.

**That is right about two of the four and wrong about the other two**, and the error was mine. An
image and a security class do describe the box: one image is built per project, and the class is
what the containment *is*. Egress and limits do not. Both are applied **per task** already - one
nftables set and one resolver configuration per container, and `--memory`, `--cpus` and
`--pids-limit` on the container podman creates - so nothing structural held either of them to one
per project. The sentence read as an argument and was a description of where the code happened to
read the values from.

The mistake was found the first time a real project was written down. Sokar's own project covers
seven repositories; five of them build with Maven, and `sokar-frontend` is `packaging: pom` driving
Flutter through the exec plugin. One egress for all of them is the union of what any of them needs,
which means a task on the core repository can reach a package registry it has no business with, and
nothing in the file says why it may.

## What must be true

1. **A repository may declare `egress`**, with the same two keys as the project's: `sets` and
   `domains`. Both are optional and both are **additive** - what a task reaches is the project's
   sets plus the repository's, and the project's domains plus the repository's.
2. **A repository may declare `limits`**, with the same three keys: `memory`, `cpus` and `pids`.
   Each is optional and each **replaces** the project's, key by key. A repository that names only
   `memory` keeps the project's `cpus` and `pids`.
3. **Absent means the project's, never the default.** This is the trap in the limits half.
   `ProjectReader` falls back per key to `Limits.defaults()` today - 8g of memory, 2048 processes -
   and a repository block reusing that fallback would mean a repository naming only `pids` silently
   reset memory to 8g for itself, undoing a project that had deliberately raised it. The chain is
   the repository's key, then the project's, then the default, in that order and no other.
4. **`none` still means no limit, and is not absence.** `memory: "none"` is how somebody opts out on
   purpose. A repository must be able to say it, and it must not read as "nothing was said".
5. **Egress is additive and limits are not, and that is not an inconsistency.** Egress is a list of
   grants, where adding is the only operation that makes sense and taking away is the failure mode
   to avoid. A limit is one number: there is nothing to add it to, and two memory limits for one
   container is not a thing podman can be asked for. The rule follows the value.
6. **Additive, for egress, is the decision rather than a detail.** `sokar shield egress`
   and allowing a blocked connection **write back into `project.yml`**. With replacement, allowing
   one domain for a frontend task would create that repository's block and, in the same moment,
   silently take `maven` away from it. A grant that removes a grant is the wrong direction for the
   most dangerous key in this file.
7. **The write-back goes to the repository's block**, not the project's. A connection a frontend
   task made is a frontend fact; remembering it at project level would widen five other
   repositories for a reason none of them can see. This is the part that costs work -
   `EgressEdit` finds the top-level `egress:` by looking for it at column zero, and now has to be
   told which block it is editing.
8. **An offline project still refuses egress**, wherever it is declared. The existing refusal is
   about the security class, which does describe the box, and a repository must not be a way around
   it.
9. **Nothing is required.** A project that declares egress once, for all its repositories, keeps
   working and keeps reading naturally. This adds a place to be more precise, not a place that has
   to be filled in.

## Decided: additive

**What it costs**: knowing what a frontend task reaches means reading two blocks instead of one.
Both are in the same file and in the same diff, so this is not the invisible grant `Egress` warns
about - that warning is about a default nobody wrote, not about a value written in two places by
the same person.

**What it buys**: the write-back can only ever add, and a repository that needs both Maven and
Flutter names only what is particular to it. `sokar-frontend` needing `maven` as well is not a
special case to remember - it is what the project already says.

**How to make a repository reach less**, which replacement would have given for free: move the set
out of the project's block into the repositories that do need it. Then that, too, is written down.

## A `flutter` set, measured rather than guessed

`sokar-frontend` needs a set that does not exist. **It is not to be written from memory.** The
`maven` set exists because 193 artifacts were resolved into an empty local repository through a
logging proxy, which recorded every host the resolver opened a tunnel to - which is why it knows
that Maven Central is fronted by a CDN under its own name and that no second host appears.

Sokar can measure this about itself: a frontend task started with `--clearance prompt` reports every
connection it was refused. That is the method, and the set is written from what it reports.

This is a second piece of work and can land separately; the model above does not depend on it.

## Built, 2026-09-18

**The model, the reader, the docs and what a task actually runs under.** A repository carries an
`Egress` and a `Limits.Declared`; `Project.egressFor(repository)` adds and
`Project.limitsFor(repository)` replaces key by key, and both are what the launch now uses -
`podman create` is asked for the repository's limits and the resolver is given the repository's
hosts.

**`Limits.Declared` exists because `Limits` cannot say "unsaid".** Its `pids` is an `int` and its
`null` memory already means *no limit*, so a repository naming only `pids` would have been
indistinguishable from one asking for no memory cap at all. The test that guards it uses a project
whose memory and pids are deliberately away from the defaults, so a fallback to `Limits.defaults()`
fails it rather than passing by coincidence.

**Each granted host is labelled with who granted it**, so a report can say a host is reachable
because of the repository rather than because of the project.

## Built: the write-back

**A grant is remembered in the repository the task works on.** `EgressEdit` now finds, and where
necessary creates, `repositories.<name>.egress`, taking its indent from what is already under that
repository rather than assuming two spaces. A repository the file does not name is **refused** -
falling back to the project's block would put the grant in the one place this exists to keep it out
of, and it would look as though it had worked.

**A repository with nothing under it gets a block made for it.** A bare `scratch:` is a repository
with no upstream, which is legitimate, and it has no mapping to insert into.

**`SetEgress` and `sokar shield egress` take a repository**, and `RunningEgress` passes the one the
task is labelled with, so allowing a connection and taking one back both land where the task was.

**One thing the tests found, and it was not about repositories at all.** Emptying a repository's
declaration left a bare `egress:` behind with the *next* repository's comment inside it: the block
end skipped comments, so a comment introducing the repository below was swallowed by the block
above. A comment ends a block exactly as a key does - which is what the top-level form had always
done, and the two must not differ.

## Acceptance

- A repository declaring `egress.sets` gets them **in addition to** the project's, measured in the
  ruleset a task actually runs under rather than argued from the file. **Met** at the model and the
  launch; the ruleset assertion rides on the existing egress tests.
- A repository declaring `limits.memory` runs under that value and not the project's, measured in
  what podman is actually asked for rather than read back out of the file. **Met.**
- A repository declaring **only** `limits.memory` keeps the project's `cpus` and `pids` - not the
  defaults. Measured with a project that names something other than the default, so that a fallback
  to `Limits.defaults()` fails the test rather than passing it by coincidence. **Met**, exactly
  that way.
- A repository declaring `limits.memory: "none"` runs with no memory limit, which is distinguishable
  from declaring nothing. **Met.**
- A repository declaring `egress.domains` gets them in addition to the project's, the same way.
- A project declaring egress and no repository declaring any behaves exactly as it does today.
- Allowing a blocked connection for a task remembers it **in that task's repository's block**, and
  a second repository of the same project does not gain it. **Met**, and checked by looking at the
  file rather than the model: the top-level block being rewritten at all is the failure.
- Remembering a domain for a repository that has no `egress` block yet creates one and **takes
  nothing away** - the repository still reaches everything the project's block grants. **Met.**
  This is the case the additive rule was chosen for, and the test says so.
- An offline project declaring egress under a repository is refused with the same message as one
  declaring it at project level.
- `sokar shield egress` and `sokar doctor` say what a task reaches without a reader having to add
  two lists in their head.

## To be checked

- **Whether a repository should be able to say "and nothing else"**, refusing the project's grant
  explicitly. It would make the file complete in one place for the repository that wants that, and
  it is a second mechanism. Not now.
