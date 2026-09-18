# B66 — A Machine Follows Each Project's Repository

**Status:** built on 2026-09-19. The record of what an account follows; `follow`, `following` and
`unfollow` at the machine and on the socket; the reconcile itself - fetch, then verify through B65,
then move the working tree, never the other way round; the daemon's timer beside the two watches
that already existed; and `sokar doctor` telling *"this account's vault is shut"* apart from
*"the repository cannot be reached"*.

**`unfollow` deletes through the same code `projects delete` uses**, so the refusal when a mirror
holds unreviewed work or a task is still up is the same refusal, with the same `--force`. A second
implementation of "is there work in the way" would have been a second chance to get it wrong.

**Applying points rather than copies.** The project's file is used where its clone holds it, under
`state`, so there is no second copy on the machine for the first to drift from. "The repository
wins" is then structural rather than a rule somebody has to enforce.

**Measured against real git and real signatures**, including the one that matters: when the next
commit is unsigned, the working tree **never moves** and the machine goes on running what it
verified last.

Decided on 2026-09-19. A **project** keeps its configuration in its own git
repository - the same one its planning and its issues live in - and a machine pulls it and
reconciles itself against it, rather than being configured by hand or over a socket. Depends on
**B65**, the check that a change is one the project actually made.

**One account runs several projects, so a machine follows several repositories** - one per project,
each reconciled on its own. A project that cannot be fetched holds up nothing but itself.

Today a machine's configuration is whatever is on its disk. A project file is created where somebody
happened to create it, and two machines meant to be the same are the same only as long as nobody
forgets. There is no answer to *"why is this machine like this"* beyond reading its files.

## The four levels, and what each one is

Written here because they are new and nothing else names them yet. When this is built they belong in
[the glossary](../../doc/glossary.md).

| | What it is | What it is in Sokar |
|---|---|---|
| **Team** | **optional**, one level up: who the people are, which projects they work on, where things live, possibly their public keys. A person may belong to several and picks one in an interface | **nothing yet** - see below |
| **Member** | a person | a Unix account with its own Sokar, signing key and mailbox |
| **Project** | a named unit of work, with **its own repository** holding `project.yml`, the planning and the issues | the source this reconciles against |
| **Repository** | one git repo a task works on - the project's own, or one of the work repositories it names | what a mirror, a gate and a review branch belong to |

**The team layer is deliberately later.** A project repository must exist; a team repository need
not, and nothing in this issue needs one. It is worth building when a person belongs to several
teams and wants to choose between them, or when who-may-sign-what has to be stated in one place
rather than per project - and not before.

## What must be true

1. **The machine pulls, once per project it follows; nothing pushes to it.** The same shape as the
   two watches that already exist, and for the same reason: the daemon binds no network interface.
   One project failing to fetch does not stop the others, exactly as one mailbox's trouble is not
   another's.
2. **What may be reconciled is a short list, and everything else is out of bounds**:

   | reconciled | never reconciled |
   |---|---|
   | what a project declares about itself | the vault, its keyslots and anything in it |
   | egress sets | what a **person** decided: a held peer, a held message, a mode |
   | which agents should be installed | running tasks - a commit starts nothing and stops nothing |

   The right-hand column is the point. A reconciliation that may release a held message or start a
   task is not reconciliation, it is remote control of somebody's machine by whoever can commit.
3. **Members cannot be created by a commit.** The daemon runs unprivileged; making a Unix account,
   its linger and its subuid ranges needs root and stays with the setup script (B62). A commit may
   *describe* a member; only a person with root makes one.
4. **Drift has one rule and it is stated, not discovered.** The repository wins for what it covers;
   a local edit to something reconciled is replaced, and the machine says it replaced it rather than
   doing it quietly.
5. **A machine says what it is following** - for each project, which repository, which commit and
   how long ago - and `sokar doctor` reports any it has not managed to follow for a while, telling
   *"the vault is locked"* apart from *"the repository cannot be reached"*.
6. **Something has to say which projects this machine follows, and it is a person.** A repository
   URL arrives by somebody deciding it does, once per project: nothing discovers projects and no
   commit adds one, because a configuration source that can enrol further configuration sources is
   a source that grows where nobody is looking. This is the step a team repository would later make
   convenient - a list of the team's projects to choose from - and until then it is one command per
   project.

## Acceptance

- Two machines following the same project repository at the same commit end up with the same
  project, without anybody copying a file.
- A change committed and pushed is applied within a stated interval, and one that was not committed
  is not applied.
- **Anything in the right-hand column above survives a reconciliation**, asserted rather than
  assumed: a held peer stays held across a pull that changes the project it belongs to.
- A local edit to a reconciled file is replaced **and reported**; a local file the repository does
  not cover is left alone.
- A machine that cannot fetch keeps working and says it is behind - **for that project only**, with
  the others reconciling as usual.
- **An account whose vault is shut reports that it is not reconciling, and why**, without its
  projects stopping or its tasks being disturbed; opening the vault resumes it without a restart.
- A machine follows exactly the projects a person told it to, and a commit in one of them cannot add
  a second.

## Following a project, and stopping

A person tells this account to follow a project once. The verb does not exist yet; the shape is
`sokar project follow <git-url>`, with `unfollow` and a listing beside it, and a `Tasks1` method so
an interface can do it - **a repository URL is not a secret**, so it may travel over the socket where
a credential may not.

**The clone lives under `state`.** It is this machine's copy of a source of truth, not a working
copy: nothing edits it, and it is rebuilt by fetching. An agent that plans *in* the project's
repository does not use it - it gets its own checkout through the gate, like a task on any other
repository, so that what an agent writes is reviewed before it is anything.

**`unfollow` deletes everything of that project** - the clone, what was reconciled from it, and the
project itself. **With one guard, and it is the same one `BackupRestore` already has:** a mirror may
hold pushes nobody has reviewed, and tasks may hold work. Deleting those silently would destroy
exactly what B13 exists to protect, by a command whose name sounds like unsubscribing from a
newsletter. So it refuses while there is unreviewed work or a task that still exists, says what is in
the way, and proceeds when a person says they mean it.

### The credential comes from the vault

A project's repository is probably private, so the machine needs a read credential for it - and it
cannot come from the repository it opens. **Decided on 2026-09-19: it comes from the vault**, where
credentials belong and where every other one already is.

**What that costs, said plainly because it will be met as a surprise otherwise: after a restart
there is no moment when "the machine is reconciling again".** The daemon reaches the vault only
while it is open, so reconciliation resumes **per person**: a machine with three accounts has three
vaults, each with its own projects, and each starts following again only once **that** person has
opened **theirs**. Until then that account's projects are simply as they were - running, not
broken.

**Sokar's job is to say so; telling anybody is not Sokar's job.** `sokar doctor` and the project
listing report *"not reconciling - this account's vault is locked"* as a state of its own, distinct
from "cannot reach the repository", because the two have different cures and only one of them is a
fault. Whether somebody is then reminded to come and unlock - a monitor watching the machine, a
message, a dashboard - is outside Sokar and deliberately so: a tool that decides a machine is
unattended and starts telling people about it has left the boundary this product keeps.

**What was rejected:**

| | Why not |
|---|---|
| An ssh key of the work account, outside the vault | Reconciliation would survive a restart with nobody present, and it would be the one credential lying unencrypted on the disk - the thing Sokar tells everybody else not to do |
| A readable repository | No credential at all, and simplest - but a project's configuration says which hosts its tasks may reach, and that is not something to publish to whoever finds the URL |

## What this breaks, and it is already built

**`CreateProject` writes a project file onto the machine** (QF22, built 2026-09-18 for the
interface's wizard). Under reconciliation that file is either discarded at the next tick or lives on
as drift nobody can explain. Three ways out, and the third is the recommendation:

| | What it costs |
|---|---|
| The interface commits to the team repository | The interface needs git access to it, which it does not have today |
| The daemon commits and pushes | The daemon holds a git credential for the repository that configures it, and may write to it - a new and large power, pointing the wrong way |
| **`CreateProject` renders and returns the file instead of writing it** | Nobody gains a permission; the interface already shows a preview before anything happens, and committing is a step a person takes where they already commit |

## Notes

**Reconciliation is a word Sokar has not needed until now.** Everything it does today happens because
somebody asked for it in that moment. A loop that changes a machine on its own is a different kind of
thing to own, and the boundary in point 2 is what keeps it one.

**Nothing here is measured**, including the obvious question of what a pull costs on a machine with
many projects, what happens when a reconciliation and a task start collide, and how long a machine
with several accounts takes to be fully reconciled after a restart - which, with the vault variant
above, is not a machine-wide moment at all but one per person.

**A project repository is also where its agents plan.** Requirements are written there as markdown
and move into the work repositories as issues once it is clear which repository does what - which
means an agent with a task on the project repository is editing the same file this reconciles
against. That is safe only because of B65: an agent may **propose** configuration, as a commit
through the gate for a person to review, and can never put it in force, because the signing key
that makes a change apply is not on the machine the agent runs on.
