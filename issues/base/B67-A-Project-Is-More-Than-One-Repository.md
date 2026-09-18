# B67 — A Project Is More Than One Repository

**Status:** built on 2026-09-18. A **project** is a named unit of work over **one or more**
git repositories, not a second name for one repository. Independent of B65 and B66.

Today `project.yml` declares one `upstream`, `TaskWorkspace` clones one mirror, and the gate holds
one review branch. So a project *is* a repository, and the word has been carrying two meanings: how
a task is contained - image, security class, egress, limits - and what it works on.

The cases that do not fit are the ordinary ones. A backend, a frontend and a shared library are one
piece of work and three repositories; a monorepo is one repository whose parts are worked on
separately. Neither can be said today without lying in one direction or the other.

## What must be true

1. **A project has its own repository and names the others.** Its own is where `project.yml`, the
   planning and the issues live, and it must exist; the work repositories are what its agents
   change, and there may be none, one or several. A project whose only repository is its own is a
   project that is still being planned, and that is a legitimate state rather than a broken one.
2. **Each repository keeps its own mirror, gate and review branch**, exactly as today - and a task
   works on exactly one of them, so nothing about review changes.
3. **The containment does not multiply.** One image, one security class, one egress set, one set of
   limits, however many repositories - those describe the box, not the contents.

   **Corrected on 2026-09-18 by [B68](B68-What-One-Repositorys-Work-Needs.md): right about two
   of these and wrong about the other two.** Egress and limits are both applied per task already -
   one ruleset and one resolver configuration per container, and the memory, CPU and process
   limits on the container podman creates - so nothing held either of them to one per project. The
   sentence read as an argument and was a description of where the code happened to read the
   values from. A repository may declare egress of its own, added to the project's, and limits of
   its own, replacing the project's key by key. The image and the security class stay one, which
   is what this point was actually about.
4. **Peers follow from the project.** The agents of one project may address each other without
   anybody writing a peer list, which is what the coordination case needs; a peer outside the
   project stays an explicit exception.

## Decided on 2026-09-19: a task sees exactly one repository

**If several are needed, several agents are started.** The project is the bracket, and coordination
between repositories happens between tasks, by message.

**The reason is the repositories themselves, not the effort.** They are separate because the work is
separable - if it were not, it would be a monorepo. A task that changes three of them in one go
contradicts the decision that split them, and would have to be paid for at the gate, where the cost
is highest: once a task pushes to three repositories, *"this task's work is waiting for review"* has
three answers and a person can approve a third of it.

So **nothing in the gate changes**. `TaskWorkspace` clones one mirror, one review branch belongs to
one task, `WorkHeld`, `Pending`, `Review`, `Approve` and `BackupRestore` keep their meaning, and
B13's promise is the same promise. What a project adds is a name for the set and the peers that
follow from it.

### What was considered and rejected

**One checkout holding all of a project's repositories**, the way a team's own script would lay them
out. It is what somebody would ask for to rename a symbol across a backend and a frontend in one go -
a real thing to want - and it was rejected because it buys that one case by making "done" ambiguous
for every case. Two tasks agreeing by message is the answer Sokar already has, with a record of what
was said.

### How the work reaches the work repositories

Requirements are planned in the project's own repository as markdown and **move into the work
repositories as issues** once it is clear which one does what. In Sokar's terms that is an agent
with a task on the project repository handing over to an agent with a task on a work repository -
which is B14's `handover` message, carrying `repository`, `ref` and `commit`, because work is named
by commit and travels through the gate rather than inside a message.

Nothing new is needed for it. It is worth writing down because it is the case that rule was written
for, and because it explains why a project's own repository is a repository like any other: an agent
works on it the same way, and its changes go through the same gate.

### Which repository a task is for: always chosen, never inferred

**Decided on 2026-09-19: starting a task always names a repository**, and the project's own
repository is one of the choices - that is how an agent gets a task for planning.

**No default, not even when a project has exactly one.** The cost of a default is paid later and
somewhere else: a project that grows a second repository would silently change what an existing
command does, and a command whose meaning depends on how many repositories exist today is one
nobody can read. Naming it is one word, and it makes *what a task is working on* a thing the
command says rather than a thing the reader works out.

## Built afterwards, 2026-09-18: what a reader of the state needs

B67's acceptance asked that a project's repositories be *offered*, and stopped there. The interface
asked the questions that exposed what else follows from a project having several, and none of it was
a new decision - only the reporting side of one already made:

- **A task says which repository it works on**, as a container label beside the project and the
  class, so a listing still answers after a reboot and bringing a stopped task back carries it over.
- **`Projects()` reports repositories as objects**, each with its own upstream, mirror, pending
  count and distance - not parallel lists, which drift silently, and not one set of numbers per
  project, which would be one repository's answer shown against all of them.
- **The upstream distance is measured per repository**, keyed `<project>.<repository>`; the
  project's own keeps the project's key, so nothing already measured is discarded.
- **Backups and `SyncUpstream` name a repository.** This one was a defect rather than a gap: after
  the gate commands learned `--repository`, `gate backup --repository backend` backed up the
  backend's mirror and **recorded it under the project's name**. A restore would then have written
  one repository's history over another's, destroying pushes that exist nowhere else. Records are
  now keyed by repository.

## Acceptance

- A project declaring one repository behaves exactly as a project does today, and its file still
  reads naturally. **Met.** `repositories:` is absent from every project file that exists, and the
  project's own repository keeps the mirror path it has always had.
- A project declaring three repositories is accepted, and a task started for it works on exactly one
  of them - with **unreviewed work still unable to reach that repository's upstream**, measured and
  not argued from the single-repository case. **Met**, by `SeveralRepositoriesTest`, which drives
  real git: a push into one repository waits there, reaches no upstream, is invisible in the other,
  and the other's gate refuses to approve it. With the two mirrors made one, two of its three tests
  fail.
- **Starting a task without naming a repository is refused**, and the refusal lists what there is to
  choose from. A project with one repository refuses in the same way as a project with three: there
  is no case where Sokar picks. **Met**, and the refusal prints the line to type.
- A task can be started for the project's own repository, and what it writes there goes through that
  repository's gate like anything else. **Met.** Its own repository is named after the project and
  is the first choice offered.
- Two tasks of one project, on different repositories, can coordinate by message without anybody
  writing a peer list. **Met**, through the `local` transport and derived peers.
- The peers of a task are the other tasks of its project without anybody writing them down, and an
  exception is still possible and still explicit. **Met**, by `ProjectTasksArePeersTest`: a peer
  written in `mail.peers` wins on the same name.
- `sokar doctor` and the project listing say how many repositories a project has, rather than
  implying one. **Met.** A project whose file cannot be read says so rather than being counted as
  one, which is the whole point of the line.

## Built, 2026-09-18

**The mirror is per repository, and the project's own keeps the path it had.** A mirror is not a
cache - it holds pushes nobody has reviewed yet - so moving one is not a rename but a thing that can
lose somebody's work. The named repositories are new, so they start where they belong, one directory
down: `mirrors/<project>/<repository>.git`.

**Deleting a project was looking at one mirror.** Found while building this: the guard that refuses
to delete a project holding unreviewed work read only the project's own mirror, so with a second
repository it would have answered "nothing is waiting" and destroyed work nobody had seen. It now
reads every mirror the project has **from disk rather than from the project file**, because deleting
is exactly the moment the file may already be gone. Each waiting push is named by its repository.

**Peers between tasks needed no new mechanism.** The `local` transport already carries a message
from one mailbox to another on the same machine, so what was missing was only the peer entries - and
those are derived from the tasks of the project rather than written down. A peer list somebody
maintained for tasks the machine starts and stops by itself would be wrong most of the time.

**One thing got worse, on purpose.** Every `sokar task start` now needs `--repository`, including
the first one a person ever types, which follows the wizard writing the project file moments
earlier. The refusal prints the exact line to run, and the guides were rewritten, but the first run
is two commands where it was one. That is the price of the decision above, paid where it is
visible.

## Notes

**It no longer touches the gate**, which is what the decision above bought: a task works on one
repository, so the mirror, the review branch and everything that hangs off them keep their meaning.
That makes this the smallest of the three rather than the largest, and the order among them is now
free.

**The word was never wrong, only too narrow.** "Project" for a unit of work over several
repositories is what people already mean by it; what was missing were the level above - the team and
its repository - and the level below, the repository itself. Both have obvious names that nobody has
to invent.
