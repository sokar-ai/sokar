# Base Requirements

What the product must do below the interface: the CLI, the daemon and the guarantees they make.
One file per requirement, each carrying its own acceptance criteria so it can be judged done or
not done.

**Open question** counts the unresolved items in the file's own *To be checked* or *Still open*
section - something whose answer could change what the requirement says, or whether it survives at
all. A question that has been answered is not listed, because it is no longer in the file.

The count is what is left, not what was ever asked. Several built requirements still carry
questions; that is the ordinary state of a requirement here rather than a sign it is unfinished.

## Work, grouped by when

Grouped by when, ordered within each group; the number is only the file's identity. Why a
requirement exists is in its own file - this table says only what it is, where it stands and when
it is due. Where the position is not obvious, the last section says why.

**Status** is one of four, and each is taken from the requirement's own status line rather than
guessed at here:

| | |
|---|---|
| `open` | Nothing of it is built. |
| `decided` | The design question is answered and no code is written - which is a different thing from open, because it is ready to start. |
| `in progress` | Part of it is built. The file says which part. |
| `built` | Every acceptance criterion is met, and a question is still open that could change what the requirement says. Those are in the last table. |

There is deliberately no value for *met*. **A requirement that is finished is deleted**, along with
its row, in the same change that finishes it - and whatever it measured that outlives it is written
into [AGENTS.md](../../AGENTS.md) or `doc/` **first**. An index holds what is still to do.

**Blocks** names the issues in other repositories that cannot be worked on until that row lands. The agent repositories and the interface mark the same relation from their side as *blocked by*, so a dependency is visible from both ends and neither side has to remember the other's. A row that blocks something elsewhere costs more to leave waiting than its position here suggests: the waiting happens in another repository's queue.

### Now

| # | Requirement | Status | Blocks | What must be true | Open question | Why here |
|---|---|---|---|---|---|---|
| B75 | [A Login Nobody Measures Is A Login That Does Not Work](B75-A-Login-Nobody-Measures-Is-A-Login-That-Does-Not-Work.md) | open | sokar-claude-code (a stub that fakes a login) | The build runs an agent login end to end and fails when it stops storing, using an agent that fakes one - no account, no network. | two, and the first is whether the stub can carry it | [note](#b75) |
| B76 | [The Code Checked Against The Skills It Was Written Without](B76-The-Code-Checked-Against-The-Skills-It-Was-Written-Without.md) | open | none - every agent opens its own | Every Java module has been read against the skills that apply to it, and each finding is fixed with a test watched to fail or declined with its reason. | one - the order | [note](#b76) |
| B54 | [Stopping The Daemon Stops The Tasks It Started](B54-Stopping-The-Daemon-Stops-The-Tasks-It-Started.md) | decided | sokar-frontend F33 | Stopping, restarting or losing the daemon does not stop a task; a task ends when something asks that task to end. | one - whether removing a task reaps helpers in its scope | [note](#b54) |
| B52 | [Knowing An Agent Reached Work](B52-Knowing-An-Agent-Reached-Work.md) | open | sokar-claude-code CC09, sokar-pi PI07, sokar-omp OM07 | An acceptance scenario proves an agent reached work without being asked anything, and fails when a release adds a question - without the kit naming any agent. | none - decided 2026-09-27: a manifest marker, within a bound the agent declares | [note](#b52) |
| B44 | [One Way To Start Work](B44-One-Way-To-Start-Work.md) | in progress | - | Starting work does one of two understandable things, says which, and never silently creates a second task beside the one that was named. | four; the shape is decided and mostly built | [note](#b44) |
| B45 | [One Documentation Somebody Can Find Their Way Through](B45-One-Documentation-Somebody-Can-Find-Their-Way-Through.md) | open | - | A reader finds one place per subject, in an order, on a published site that cannot go stale unnoticed. | four | [note](#b45) |
| B49 | [What The Build Trusts To Run Beside Its Secrets](B49-What-The-Build-Trusts-To-Run-Beside-Its-Secrets.md) | open | - | Nothing runs beside this product's credentials that was fetched by a name its owner may repoint. | four, and the first decides whether it lasts | [note](#b49) |
| B50 | [Whose Business The Files Sokar Leaves Behind Are](B50-Whose-Business-The-Files-Sokar-Leaves-Behind-Are.md) | open | - | Every file Sokar writes is readable by whoever it concerns, and by nobody else - including on machines that already ran tasks. | four, and the first is whether the umask is fought directly | [note](#b50) |
| B51 | [The Tests That Run Nowhere](B51-The-Tests-That-Run-Nowhere.md) | open | - | A test that cannot run anywhere in the pipeline says so, rather than reporting itself as skipped on this run. | two, and the first is whether a leg runs the unit tests on the machine | [note](#b51) |
| A01 | [Pi Forge Subscription](A01-Pi-Forge-Subscription.md) | in progress | - | A credential that belongs to a provider rather than an agent is obtained on the host, stored, and used by a task that never sees it. | see the file | [note](#a01) |
| B78 | [A Test Install That Stays In One Account](B78-A-Test-Install-That-Stays-In-One-Account.md) | open | - | `deploy` can install a build into one account only, so what one agent tests changes nothing another account runs; the machine-wide install stays for a test of the package. | none | |
| B29 | [Keys Presented As They Are Stored](B29-Keys-Presented-As-They-Are-Stored.md) | open | - | A stored key reaches its destination as it is stored, in the header or the URL that destination asks for. | see the file | [note](#b29) |
| B10 | [What An Egress Set Can Express](B10-What-An-Egress-Set-Can-Express.md) | decided | - | A destination that cannot be written as a host name is supported or refused, never silently unreachable. | one | |

### Soon

| # | Requirement | Status | Blocks | What must be true | Open question | Why here |
|---|---|---|---|---|---|---|
| B61 | [A Declared Refusal That Nothing Enforces](B61-A-Declared-Refusal-That-Nothing-Enforces.md) | open | - | A domain an agent declares as refused does not resolve in a task, instead of being reported as refused and reachable. | two | |
| B62 | [Preparing A Machine Before There Is A Daemon](B62-Preparing-A-Machine-Before-There-Is-A-Daemon.md) | built | - | A machine being prepared has no daemon, so the one thing Sokar cannot do through its socket is make a Sokar machine - and the interface is about to reimplement it. | three | |
| B63 | [A Share That Is Not Stored At All](B63-A-Share-That-Is-Not-Stored-At-All.md) | open | - | On a desktop a keyslot's share sits in a keystore any process running as that user can read, so a device is worth no more than the account it runs under until the share is derived from a token rather than stored. | three | |
| B64 | [The Two Steps That Still Need A Terminal](B64-The-Two-Steps-That-Still-Need-A-Terminal.md) | open | - | A freshly prepared machine needs its daemon started and its vault created, and neither can be done from an interface - so a wizard that exists to avoid a shell has to open one twice. | two | |
| B65 | [A Gate For Configuration Coming In](B65-A-Gate-For-Configuration-Coming-In.md) | built | - | A machine applies configuration only when it is signed by a key it was given out of band, because reconciliation lets a git repository decide what a task may reach. | two | |
| B69 | [Why A Machine Is Not Following](B69-Why-A-Machine-Is-Not-Following.md) | built | sokar-frontend QF26 | A machine that is not applying a project's configuration says why as a named reason and about which commit, so a refused signature is a state on the project rather than a line somebody has to read. | none | |
| B70 | [The Clone Is What A Task Gets](B70-The-Clone-Is-What-A-Task-Gets.md) | built | - | A task of a followed project runs against the project file the machine verified, rather than one left in a directory that nothing checked. | none | [note](#b70) |
| B71 | [A Project Is Named, Not Pointed At](B71-A-Project-Is-Named-Not-Pointed-At.md) | built | sokar-frontend | A command names a project instead of pointing at a file, so which project a task belongs to no longer depends on which directory somebody stood in. | none | |
| B72 | [A Project Exists By Being Followed](B72-A-Project-Exists-By-Being-Followed.md) | built | sokar-frontend QF27 | The only way a project comes to be on a machine is that the machine follows its repository; Sokar writes no project file anywhere, and the checks move to where the file arrives. | none | |
| B73 | [The Anchor Arrives With The Follow](B73-The-Anchor-Arrives-With-The-Follow.md) | built | - | The key a project's configuration is signed with is given in the same command that follows it, and a machine may follow without an anchor as long as it says so everywhere the project is shown. | none | |
| B55 | [The Changelog Entry A Change Has To Bring](B55-The-Changelog-Entry-A-Change-Has-To-Bring.md) | open | - | A change to what ships or builds brings a changelog entry, or says on purpose that it does not, and the build checks it. | three, and the first is what logchange's maintainers want | [note](#b55) |
| B57 | [Names Asked Past The Resolver](B57-Names-Asked-Past-The-Resolver.md) | open | - | Port 53 out of a task is open to Sokar's resolver and nothing else in the task, so an undeclared name gets no answer from anywhere. | three, and the first is measuring it | [note](#b57) |
| B58 | [The Upstream The Checkout Already Names](B58-The-Upstream-The-Checkout-Already-Names.md) | open | - | Starting in a checkout that already names its upstream never makes a person type that URL again, and never copies a credential out of it. | three, and the first is where the seed comes from | [note](#b58) |
| B28 | [More Than One Credential In A Task](B28-More-Than-One-Credential-In-A-Task.md) | open | - | A task can be given the credentials its work needs, each confined to its own destination, without any of them entering the container. | see the file | |
| B30 | [Credentials The Broker Has To Fetch](B30-Credentials-The-Broker-Has-To-Fetch.md) | open | - | A credential the broker obtains rather than holds, including the machinery B01 parked. | see the file | |
| B31 | [An Authorization A Person Grants Once](B31-An-Authorization-A-Person-Grants-Once.md) | open | sokar-frontend F31 | A person grants an authorization once, out of band, while the work waits. | see the file | |
| B01 | [Refreshable Task Tokens](B01-Refreshable-Task-Tokens.md) | in progress | - | An agent that renews an expiring credential must not be broken by holding a task-scoped one. | two | |
| B25 | [Names The Operator Should Not Have To Find](B25-Names-The-Operator-Should-Not-Have-To-Find.md) | in progress | - | A command that needs a name Sokar already knows never makes the operator go and find it. | three, and two of three parts are built | |
| B33 | [A Task's Own Fetches](B33-A-Tasks-Own-Fetches.md) | open | - | A task can fetch from the forges its work depends on, and a failure to do so is never reported as a credential problem. | three, and the first is whether it reproduces | |
| B26 | [What This Machine Has Been Doing](B26-What-This-Machine-Has-Been-Doing.md) | open | - | A machine can say what it has done, for longer than the tasks themselves existed. | three, and four are decided | [note](#b26) |
| B39 | [Handing A File To A Running Task](B39-Handing-A-File-To-A-Running-Task.md) | open | - | A file on this machine can be put in front of a running task, once, without going through a repository, without landing in the work, and without the task gaining any way to send one back. | six, and four are decided | [note](#b39) |
| B43 | [Tasks After The Machine Restarts](B43-Tasks-After-The-Machine-Restarts.md) | open | - | A restart is visibly why tasks are down, and getting them back needs no list of names. | four, and B44 took its cause | [note](#b43) |
| B46 | [The Conversation A Restart Loses](B46-The-Conversation-A-Restart-Loses.md) | open | sokar-claude-code CC10, sokar-pi PI08, sokar-omp OM08 | A task that comes back continues the conversation it was having, where its agent can name one, and says plainly when it cannot. | four, and the scope is decided | [note](#b46) |
| B47 | [What The Agent Is Doing, Read From Outside](B47-What-The-Agent-Is-Doing-Read-From-Outside.md) | open | sokar-claude-code CC05, sokar-pi PI05, sokar-omp OM05 | A task waiting on a person says so, derived from output the host already has, declared by the agent that wrote it, and marked as derived. The daemon's half; what each agent declares is tracked in that agent's own repository. | five, and the engine one is answered by a recommendation | [note](#b47) |
| B48 | [The Overview That Stays Open](B48-The-Overview-That-Stays-Open.md) | open | - | A person at a terminal watches their tasks change, from the same answer the interface reads, without a loop they wrote themselves. | four, and the first two decide its shape | [note](#b48) |
| B34 | [What The Resolver Can Actually Do](B34-What-The-Resolver-Can-Actually-Do.md) | open | - | A machine says what its resolver can do and what follows for a task, and a refusal names what was missing. | two, and the first may end it | |
| B06 | [Remote Access](B06-Remote-Access.md) | open | - | Tasks on another machine are usable over an encrypted tunnel, without the daemon ever binding a network port. | four | |
| B40 | [The Domain That May Bind The Socket](B40-The-Domain-That-May-Bind-The-Socket.md) | open | - | Only Sokar may bind a socket carrying Sokar's label, so a task that connects to one reaches Sokar. | four, and the first decides the shape | [note](#b40) |
| B41 | [What a Packaged Agent Installs](B41-What-A-Packaged-Agent-Installs.md) | open | - | `--supply-chain` says what a packaged agent's package ships, read from the bill that package installed. | two, and the first is whose job the reading is | |

### Later

| # | Requirement | Status | Blocks | What must be true | Open question | Why here |
|---|---|---|---|---|---|---|
| B18 | [Storing A Credential From Elsewhere](B18-Storing-A-Credential-From-Elsewhere.md) | open | - | A credential can be stored from an interface, the reply never carries the value back, and no path logs, echoes or records it. | two, plus `Login` held open as nice to have | |
| B23 | [Secrets In This Process's Memory](B23-Secrets-In-This-Process-Memory.md) | open | - | A credential's plaintext exists in as few places and for as short a time as a managed runtime allows, and what cannot be achieved is written down rather than implied. | three, and one is a one-line fix | |
| B14 | [Talking Between Tasks](B14-Talking-Between-Tasks.md) | in progress | the message sluice's local transport | A task has a mailbox: it writes a strictly narrowed A2A message into a directory and reads what arrives there, while the host decides what may leave - judged by a tool of its own that carries no model - signs it on the way out and hands it to a transport package, with trust a property of the peer, refusals arriving as bounces, and nothing widening what a container may reach. | none | [note](#b14) |
| B15 | [Handing Artifacts Between Tasks](B15-Handing-Artifacts-Between-Tasks.md) | open | - | What a task builds can reach another task through a per-project content-addressed store, with the pointer committed and reviewed at the gate, written through a socket rather than a shared directory, and never mounted into a task. | six, and it turns on B14 | |
| B37 | [The Build That Runs Somewhere Else](B37-The-Build-That-Runs-Somewhere-Else.md) | open | - | A task learns the verdict and the reason for the build its own work triggered, without reaching the forge and without holding a forge credential. | seven, and the first may end it | [note](#b37) |
| B38 | [How Far Something That Got Through Can Get](B38-How-Far-Something-That-Got-Through-Can-Get.md) | open | sokar-frontend F32 | How far a convinced agent can get is bounded where it can be, named where it cannot, and the reviewer sees what matters before what is merely large. | six, and one may have no answer | [note](#b38) |
| B42 | [Where The Agent Protocols Touch This](B42-Where-The-Agent-Protocols-Touch-This.md) | open | - | Each of A2A, MCP, ACP and AG-UI is adopted, answered otherwise, or refused - with the reason. | five, and the first decides whether ACP is interesting at all | [note](#b42) |
| B08 | [McSokar Apple Containers](B08-McSokar-Apple-Containers.md) | open | - | A sibling project offering the same behavior on Apple Containers, with one client that connects to either host. | six, and the first is to measure it on Linux | [note](#b08) |
| B56 | [Programs Sokar Runs That Could Be Calls](B56-Programs-Sokar-Runs-That-Could-Be-Calls.md) | open | - | Every external program Sokar starts either has a reason to stay a program or is replaced by a call proven on a JVM and in the native image. Nice to have, re-checked with Java and GraalVM releases rather than scheduled. | four, and all are re-checks | |
| B59 | [A Kernel Of Its Own For A Task](B59-A-Kernel-Of-Its-Own-For-A-Task.md) | open | - | Whether a task can run under a runtime that gives it its own kernel is measured rather than assumed - the ruleset, the resolver, the hooks, the sockets and the terminal each have an answer - and the default does not move until it fails closed. Nice to have, an experiment first. | six to measure, five to check, and the first two decide the rest | |


### Two things that dissolved rather than becoming requirements

Both are recorded where somebody will look rather than dropped.

**Provisioning a machine** is refused and could not have worked anyway - a machine that is not
ready has no daemon to ask - and it is argued in [B05](B05-Health-And-Diagnostics.md). **Routing
credentials to projects** describes a relation that does not exist, and it is argued in
[B18](B18-Storing-A-Credential-From-Elsewhere.md); restated correctly it is the agent roster, which
was already refused.

**And instructions for an agent are not Sokar's business at all.** They live in the repository,
checked in or not, and Sokar does not know what they are called - `CLAUDE.md`, `AGENTS.md`,
something an agent invents next year. It cannot merge them, because how an agent combines several
is that agent's rule and not ours. A team running more than one agent has to agree upfront how
instructions are stored in their repository; that agreement is theirs to make.

A hardcoded list of filenames would have been worse than nothing: for a feature whose only job is
to show what an agent was told, a name that goes out of date produces a confident *"no
instructions"* for a task that had them, and a blank that looks like an answer is the failure this
project keeps writing down. That is the fourth of the same shape, after the agent roster, hardware
access and key routing - each time the honest answer was *"that is not a thing this system has"*,
and saying so cost a paragraph and bought a screen that is not lying.


## Built, and still carrying questions

These are not work waiting to be done: every acceptance criterion is met and each is in
use. What each still carries is a question whose answer could change what the requirement
says - which is why they are not retired, and why they are not at the top of the list
above.

| # | Requirement | Status | Blocks | What must be true | Open question |
|---|---|---|---|---|---|
| B05 | [Health And Diagnostics](B05-Health-And-Diagnostics.md) | built | - | The machine reports whether it can actually run a task, naming anything missing or misconfigured. | one, and `doctor` is on the wire |
| B11 | [What A Task Says About Itself](B11-What-A-Task-Says-About-Itself.md) | built | - | A task says which agent, which mode, which branch, since when, and whether it is working, idle, waiting or dead. | one, and it is built |
| B13 | [Unreviewed Work Leaving By The Side Door](B13-Unreviewed-Work-Leaving-By-The-Side-Door.md) | in progress | - | Work that reaches the upstream without passing the gate is prevented or reported, not silently possible. | two, and the guard is built |
| B19 | [Preparing An Environment On Purpose](B19-Preparing-An-Environment-On-Purpose.md) | built | - | An environment can be prepared without starting a task, at a depth chosen explicitly, and 'prepared' tells absent from stale. | one - which agent an image was built for |
| B20 | [Creating A Project](B20-Creating-A-Project.md) | built | - | A project is validated against the machine before it is created, and nothing half-created survives somebody walking away. | one - whether creating also prepares |
| B36 | [A Snapshot Small Enough To Have A Choice](B36-A-Snapshot-Small-Enough-To-Have-A-Choice.md) | built | - | The images a leg boots fit on a cheap machine, so a sold-out server type costs a fallback rather than the run. | two - how fast cx43 builds, and what stops a later rebuild raising the floor again |
| B32 | [A Cached Passphrase That Says What It Is](B32-A-Cached-Passphrase-That-Says-What-It-Is.md) | built | - | Sokar never reports a cached passphrase gone unless it is gone. | two - a chain of caches, and a keyring that is not the operator's |

## To be checked

Two open questions are worth knowing before any of this is planned in detail, because each
changes what gets built rather than only how:

- Whether a person away from the machine can be reached at all ([B06](B06-Remote-Access.md)).
  The transport itself is settled - the socket survives an ssh forward, measured - so what is left
  is the half that decides how much of F20 and F23 is real, and how long a clearance prompt should
  wait for somebody who is not there. Both are in the
  [interface's own set](https://github.com/sokar-ai/sokar-frontend/blob/main/issues/README.md) and
  are named by number rather than linked by file: a requirement that is met is deleted there, so a
  link to one breaks exactly when it is finished.
- Whether the guarantees can be re-derived at all on a second platform
  ([B08](B08-McSokar-Apple-Containers.md)). It decides whether that project offers the same
  product or a weaker one wearing the same name, and it constrains what may be added to the
  daemon's contract.

## Why here

Only the placements that are not obvious from the files themselves. Ranking is a property of the
set, so it lives here and nowhere else.

<a id="b75"></a>**B75 leads, by the operator's decision of 2026-09-19, made the hour it was
found.** `sokar vault login` had never stored anything, for any agent, and the pipeline had no way
to notice: no unit test can see the shape of a `podman cp`, no scenario logs in, and the rented
legs have no account. It survived behind its own error message - *"it may have been cancelled"* -
which blamed the person and was believed three times in one afternoon. Everything else in **Now**
is work that is known to be missing; this is the one row about work that was believed to be
present.

<a id="b76"></a>**B76 was set to follow B53 by the operator on 2026-09-27; B53 is done since 2026-09-28.** The house skills were
never used, and the first module read against them had two defects its tests had not found: a
token sent wherever a property pointed, and a crash that answered the question. Every agent opens
the same issue in its own repository.

<a id="b54"></a>**B54 is second, above B52, because it ends work that is running.** Measured on
2026-09-13: stopping the daemon stopped the task it had started, container included - and a daemon
that crashes goes through the same stop before systemd restarts it. B52 holds up work in other
repositories; this one ends a person's. Ranked here by Agent Sokar the day it was found; the
operator re-ranks.

<a id="b52"></a>**B52 sits under B54, because three agent repositories are waiting on
it and nobody else can build it.** First-run consent moved to the agent repositories on 2026-09-13, and each of them now
has a task that fails its acceptance when a release adds a dialog. That needs one step all their
scenarios share, and it lives in the kit because the kit is the only glue those scenarios have:
written three times it would be three dialects of one check.

<a id="b29"></a>**B29 sits above B28, the foundation it depends on.** A set that begins with its
own foundation tends to sit unstarted. Building the smallest kind first makes the foundation's
first user exist while correcting its shape is still cheap.

<a id="b26"></a>**B26 is below B25** because it is a new subsystem rather than a small change, and
above the rest of Soon because the gap is total rather than partial.

<a id="b39"></a>**B39 is in Soon although nothing waits on it.** It is unblocked, it is the
cheapest of the three new ones, and the gap is total - there is no way to hand a file to a running
task today except pasting into a terminal. B37 is its first consumer rather than its reason.

<a id="b14"></a>**B14 carries B15 with it.** They are the same question twice, and B15 reuses
B14's policy, so settling B14 settles most of both. It is decided and ready to start: a task writes
into a mailbox and never speaks a transport, everything is checked on the machine that writes it, and
carrying a message is a package - the first one moving a file between mailboxes on one machine.

<a id="b37"></a>**B37 is in Later because its first question may end it.** In `guarded` the forge
does not build a task's push until a person approves it, so what looks like watching a build may
be waiting on a human - which is a different requirement.

<a id="b38"></a>**B38 is deliberately behind the practical work.** It is a frame rather than a
feature: most of it is bounding what already exists, its one buildable half is the review, and its
largest item may have no answer. It is kept because B37, B14 and B15 would otherwise each re-argue
it from scratch.

<a id="b08"></a>**B08 is last** because it is a project rather than a feature.

<a id="b40"></a>**B40 is in Soon rather than Now** although it is small and needs nobody's
decision. What it closes is a second lock on a door whose first lock is the uid: the process it
guards against is already running as the operator and can already read the vault file. It is worth
doing and it is not urgent.

<a id="b44"></a>**B44 is in Now although nothing is broken by it.** Everything it touches works; what fails is the operator. Asking to run an existing task accepted the name, built something else beside it, and removed that again on exit - three wrong turns, none of them reported, on the command a person reaches for first. The acceptance suite is how it would have been caught.

<a id="b45"></a>**B45 is in Now although no code depends on it.** The documentation is what a person meets before any of it, and it currently answers a simple question - what can a project file hold - only by reading the reader's source. A third of it is one text maintained twice by hand. It makes the product usable by a person, which is what somebody hits first.

<a id="b43"></a>**B43 is in Soon rather than Now, and shrank once B44 was decided.** What made a restart unrecoverable - the records a resume needs living in tmpfs - is B44's to fix, and B44 fixes it. What is left here is whether a machine coming back should do anything by itself, and how a person finds out that a restart is why their tasks are down. Its first question may reduce it to a line in the listing.

<a id="b46"></a>**B46 is in Soon, beside B43, because it is the other half of the same sentence.** B43 asks that a machine coming back gets its tasks back; this asks that what comes back remembers what it was doing. Neither is worth much alone - a container with an empty-headed agent in it is a workspace, not a resumed task - and both wait on the durable task state B44 deferred, which is where a session id has to live. It is not in Now because the thing it needs is already scheduled and not yet built.

<a id="b47"></a>**B47 is in Soon although B11, which needs it, is built.** B11 is built everywhere except here: the field is on the contract and three of its four values are produced, and `waiting` has no producer because the measurement said the obvious one would cost a channel the agent writes into. This is the route that costs nothing new, so it is a design question answered rather than a subsystem, and it is below B46 only because B46 has a working part to attach to. **Split in two on 2026-09-12**: this file is the daemon's half, what each agent declares is an issue in each agent repository, and stage 1 was rewritten on 2026-09-12 once all three shipped agents were measured: none of them waits in an unattended run - they answer and exit, and nothing in the stream tells *ended by asking* from *ended by finishing*. So stage 1 is now about a run that ENDED with a question and said so to nobody, which today sorts under *stopped*, below quiet and unseen, in the same words as a run that finished its work.

<a id="b48"></a>**B48 is in Soon, under B47, because it is what makes B47 worth having on a machine somebody is logged into.** It is the smallest requirement in this list - the daemon already streams changes and the wire client already consumes a stream; what is missing is a caller. It sits below B47 rather than beside it because the state most worth watching for is the one B47 produces, and a view built before that exists would be built around the wrong column.

<a id="b49"></a>**B49 is in Now although nothing is broken by it.** Everything works; what is wrong is what it would take for that to stop being true. Six third-party actions are fetched by mutable tag into jobs holding the Hetzner credentials, the publishing tokens and the GPG signing key, and one of those actions is handed the key on purpose. It is above the rest of Now because the fix is mechanical and the exposure is continuous, and because it is the same finding in all four repositories - which makes it the one piece of work here that nobody else can do instead.

<a id="b50"></a>**B50 is in Now, under B49, because it is the same omission in a different place and it was found the same way.** Neither is a mistake in something somebody wrote; both are what happens where nobody wrote anything - an action fetched by a name nobody pinned, a directory left at a mode nobody chose. It is below B49 because what it exposes is covered today by this machine's home directory being 0750, which is luck rather than design, and above the rest of Now because the mirror holds work that has not been reviewed.

<a id="a01"></a>**A01 is here, with an A in its number, and both halves of that are deliberate.** It was written as an agent requirement and the agent half is built - Pi installs as its own package and Sokar needed no change to discover it. What is left is not an agent's work at all: the vault is keyed by agent name and this is the first case where that is wrong, the browser sign-in has to happen on the host because a box has no browser, and the short-lived token is where [B01](B01-Refreshable-Task-Tokens.md) stops being theoretical. Moved here on 2026-09-12 when the agent requirements went to the agent that owns those repositories. **It kept its number** because a number in this product is a file's identity rather than its address: renumbering it would break every reference, including the ones in copies another repository has already taken.

<a id="b42"></a>**B42 is in Later although it blocks nothing and is cheap.** It is reading rather
than building, and three of its four edges already have an answer here - the value is in comparing
those answers with what the field settled on, which is worth doing before somebody defends them in
public and not before that.

<a id="b51"></a>**B51 is in Now although it breaks nothing today.** What it costs is not a bug, it
is the ability to tell "did not apply" from "could not apply" - and this repository has already
paid once for not being able to: eleven acceptance scenarios sat green and unexecuted until the day
they were run and turned up three faults. It is below B50 because nothing is exposed by it, and
above the rest of Now because every further green build makes the two skipped tests look more
settled than they are.

<a id="b55"></a>**B55 is at the top of Soon rather than in Now because its gap is deliberate.** On
2026-09-13 logchange was adopted and the old changelog check removed from the three agent
repositories, so for now nothing forces an entry. The first step is a proposal to logchange, not code
here, and nothing is blocked by it.

<a id="b57"></a>**B57 is in Soon rather than Now because connections stay refused.** An address
the agent learns past the resolver is still dropped and still prompts. What is open is data leaving
through query names, which the documentation claimed was closed and now says is not. It is below B55
because nothing reads from it, and above the rest of Soon because the likely fix is small and it
touches a stated guarantee. Placed here by Agent Discuss on 2026-09-14; the operator re-ranks.

<a id="b70"></a>**B70 is first of the four, because the other three assume it.** Following a
project repository today verifies a commit, resets a clone and then uses none of it: a task reads
the project file out of the working directory. So every promise that rests on reconciliation is
currently true of a directory nobody reads, and naming a project or removing the local file would
both be changes to a path that leads nowhere. Found on 2026-09-18 while answering whether
`CreateProject` should exist.

<a id="b58"></a>**B58 is in Soon, under B57, because it is met on the first day and breaks nothing.**
Everyone who presses Enter through the wizard in a cloned repository ends up with a project whose
approve refuses at the moment the work is ready. That is a first impression, not a fault, and a
flag works around it. It is below B57 because B57 concerns a guarantee and this concerns
convenience. Placed here by Agent Discuss on 2026-09-14; the operator re-ranks.
