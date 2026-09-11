# Base Requirements

What the product must do below the interface: the CLI, the daemon and the guarantees they make.
One file per requirement, each carrying its own acceptance criteria so it can be judged done or
not done.

**Open question** counts the unresolved items in the file's own *To be checked* or *Still open*
section - something whose answer could change what the requirement says, or whether it survives at
all. Answered ones are struck through in place rather than deleted, so a question that turned out
to have an answer stays readable beside it.

The count is what is left, not what was ever asked. Several built requirements still carry
questions; that is the ordinary state of a requirement here rather than a sign it is unfinished.

## Work, grouped by when

Grouped by when, ordered within each group; the number is only the file's identity. Why a
requirement exists is in its own file - this table says only what it is and when it is due.
Where the position is not obvious, the last section says why.

### Now

| # | Requirement | What must be true | Open question | Why here |
|---|---|---|---|---|
| B24 | [First-Run Consent Inside The Box](B24-First-Run-Consent-Inside-The-Box.md) | A task starts its agent and the agent works; nothing between asks a person a question the box already answered. | one, and one dialog is refused rather than solved | [note](#b24) |
| B44 | [One Way To Start Work](B44-One-Way-To-Start-Work.md) | Starting work does one of two understandable things, says which, and never silently creates a second task beside the one that was named. | four; the shape is decided and mostly built | [note](#b44) |
| B27 | [Testing What A Person Actually Does](B27-Testing-What-A-Person-Actually-Does.md) | What a person does at a terminal is tested by the build, on a real machine, and reported case by case. | five, three decided | |
| B29 | [Keys Presented As They Are Stored](B29-Keys-Presented-As-They-Are-Stored.md) | A stored key reaches its destination as it is stored, in the header or the URL that destination asks for. | see the file | [note](#b29) |
| B10 | [What An Egress Set Can Express](B10-What-An-Egress-Set-Can-Express.md) | A destination that cannot be written as a host name is supported or refused, never silently unreachable. | one | |

### Soon

| # | Requirement | What must be true | Open question | Why here |
|---|---|---|---|---|
| B28 | [More Than One Credential In A Task](B28-More-Than-One-Credential-In-A-Task.md) | A task can be given the credentials its work needs, each confined to its own destination, without any of them entering the container. | see the file | |
| B30 | [Credentials The Broker Has To Fetch](B30-Credentials-The-Broker-Has-To-Fetch.md) | A credential the broker obtains rather than holds, including the machinery B01 parked. | see the file | |
| B31 | [An Authorization A Person Grants Once](B31-An-Authorization-A-Person-Grants-Once.md) | A person grants an authorization once, out of band, while the work waits. | see the file | |
| B01 | [Refreshable Task Tokens](B01-Refreshable-Task-Tokens.md) | An agent that renews an expiring credential must not be broken by holding a task-scoped one. | two | |
| B25 | [Names The Operator Should Not Have To Find](B25-Names-The-Operator-Should-Not-Have-To-Find.md) | A command that needs a name Sokar already knows never makes the operator go and find it. | three | |
| B33 | [A Task's Own Fetches](B33-A-Tasks-Own-Fetches.md) | A task can fetch from the forges its work depends on, and a failure to do so is never reported as a credential problem. | three, and the first is whether it reproduces | |
| B26 | [What This Machine Has Been Doing](B26-What-This-Machine-Has-Been-Doing.md) | A machine can say what it has done, for longer than the tasks themselves existed. | three, and four are decided | [note](#b26) |
| B39 | [Handing A File To A Running Task](B39-Handing-A-File-To-A-Running-Task.md) | A file on this machine can be put in front of a running task, once, without going through a repository, without landing in the work, and without the task gaining any way to send one back. | six, and four are decided | [note](#b39) |
| B43 | [Tasks After The Machine Restarts](B43-Tasks-After-The-Machine-Restarts.md) | A restart is visibly why tasks are down, and getting them back needs no list of names. | four, and B44 took its cause | [note](#b43) |
| B34 | [What The Resolver Can Actually Do](B34-What-The-Resolver-Can-Actually-Do.md) | A machine says what its resolver can do and what follows for a task, and a refusal names what was missing. | two, and the first may end it | |
| B06 | [Remote Access](B06-Remote-Access.md) | Tasks on another machine are usable over an encrypted tunnel, without the daemon ever binding a network port. | four | |
| B40 | [The Domain That May Bind The Socket](B40-The-Domain-That-May-Bind-The-Socket.md) | Only Sokar may bind a socket carrying Sokar's label, so a task that connects to one reaches Sokar. | four, and the first decides the shape | [note](#b40) |
| B41 | [What a Packaged Agent Installs](B41-What-A-Packaged-Agent-Installs.md) | `--supply-chain` says what a packaged agent's package ships, read from the bill that package installed. | two, and the first is whose job the reading is | |

### Later

| # | Requirement | What must be true | Open question | Why here |
|---|---|---|---|---|
| B18 | [Storing A Credential From Elsewhere](B18-Storing-A-Credential-From-Elsewhere.md) | A credential can be stored from an interface, the reply never carries the value back, and no path logs, echoes or records it. | two, plus `Login` held open as nice to have | |
| B23 | [Secrets In This Process's Memory](B23-Secrets-In-This-Process-Memory.md) | A credential's plaintext exists in as few places and for as short a time as a managed runtime allows, and what cannot be achieved is written down rather than implied. | three, and one is a one-line fix | |
| B14 | [Talking Between Tasks](B14-Talking-Between-Tasks.md) | Two tasks can hold a conversation that is recorded before it is delivered, attributed by the socket it arrived on, declared by both projects, refused across security classes, and stoppable while it runs - widening nothing a container may reach. | eight, including whether to build it | [note](#b14) |
| B15 | [Handing Artifacts Between Tasks](B15-Handing-Artifacts-Between-Tasks.md) | What a task builds can reach another task through a per-project content-addressed store, with the pointer committed and reviewed at the gate, written through a socket rather than a shared directory, and never mounted into a task. | six, and it turns on B14 | |
| B37 | [The Build That Runs Somewhere Else](B37-The-Build-That-Runs-Somewhere-Else.md) | A task learns the verdict and the reason for the build its own work triggered, without reaching the forge and without holding a forge credential. | seven, and the first may end it | [note](#b37) |
| B38 | [How Far Something That Got Through Can Get](B38-How-Far-Something-That-Got-Through-Can-Get.md) | How far a convinced agent can get is bounded where it can be, named where it cannot, and the reviewer sees what matters before what is merely large. | six, and one may have no answer | [note](#b38) |
| B42 | [Where The Agent Protocols Touch This](B42-Where-The-Agent-Protocols-Touch-This.md) | Each of A2A, MCP, ACP and AG-UI is adopted, answered otherwise, or refused - with the reason. | five, and the first decides whether ACP is interesting at all | [note](#b42) |
| B08 | [McSokar Apple Containers](B08-McSokar-Apple-Containers.md) | A sibling project offering the same behavior on Apple Containers, with one client that connects to either host. | two | [note](#b08) |


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

| # | Requirement | What must be true | Open question |
|---|---|---|---|
| B05 | [Health And Diagnostics](B05-Health-And-Diagnostics.md) | The machine reports whether it can actually run a task, naming anything missing or misconfigured. | one, and `doctor` is on the wire |
| B11 | [What A Task Says About Itself](B11-What-A-Task-Says-About-Itself.md) | A task says which agent, which mode, which branch, since when, and whether it is working, idle, waiting or dead. | two, and it is built |
| B13 | [Unreviewed Work Leaving By The Side Door](B13-Unreviewed-Work-Leaving-By-The-Side-Door.md) | Work that reaches the upstream without passing the gate is prevented or reported, not silently possible. | three, and the guard is built |
| B19 | [Preparing An Environment On Purpose](B19-Preparing-An-Environment-On-Purpose.md) | An environment can be prepared without starting a task, at a depth chosen explicitly, and 'prepared' tells absent from stale. | one - which agent an image was built for |
| B20 | [Creating A Project](B20-Creating-A-Project.md) | A project is validated against the machine before it is created, and nothing half-created survives somebody walking away. | one - whether creating also prepares |
| B36 | [A Snapshot Small Enough To Have A Choice](B36-A-Snapshot-Small-Enough-To-Have-A-Choice.md) | The images a leg boots fit on a cheap machine, so a sold-out server type costs a fallback rather than the run. | two - how fast cx43 builds, and what stops a later rebuild raising the floor again |
| B32 | [A Cached Passphrase That Says What It Is](B32-A-Cached-Passphrase-That-Says-What-It-Is.md) | Sokar never reports a cached passphrase gone unless it is gone. | two - a chain of caches, and a keyring that is not the operator's |

## What was here and is finished

Thirteen requirements have been met and retired. Their files are gone; what each measured is in
[AGENT.md](../../AGENT.md), where it will be read again:

- **A passphrase nobody could type.** Closed on 2026-09-10 because it does not reproduce - the
  same binary, at a pty, over `ssh -tt`, and through the acceptance kit itself, all working a day
  after it was recorded as failing three ways.
  [Its file is kept](B35-A-Passphrase-Nobody-Can-Type.md) rather than deleted, because what it
  teaches is not about passphrases: a defect recorded from one environment is a measurement, and
  this one was written with a stack trace and three reproductions and still did not survive contact
  with the same binary. What the original lacks is what would have made it checkable.
- **The local daemon API.** `sokard` serves the domain over an owner-only varlink socket, and the
  CLI and the daemon reach it through the same objects, so neither can grow a behavior the other
  lacks. The two questions it left are part of [B06](B06-Remote-Access.md).
- **Task lifecycle control.** List, stop and resume, in the CLI and over the socket.
- **The git endpoint.** It bound every interface and sat on the operator's network; it now binds
  loopback, measured unreachable from a second machine.
- **The workspace outliving its container.** Cleanup used to destroy work while reporting
  success. The question it left - what the agent installed *inside* a container, which is lost
  with it and warned nobody - is answered below, under recovery and panic.
- **What a project may reach.** Curated egress sets, declared per project.
- **Credential management.** The value goes in through standard input, comes back only as a name,
  a kind and a length, and is questioned when it looks like a placeholder rather than a secret. A
  passphrase is verified before it is cached, and `sokar vault lock` drops it again without
  restarting anything - saying, when a task is running, what it cannot reach. What the sweep found
  and this did not own became the requirement below it, and is also done.
- **The egress sets editor.** `sokar shield egress` shows what a project may reach with the origin
  of every host - through the same composition a task run uses, so the CLI and an interface cannot
  come to disagree about what is open - and changes it, reporting the effect in hosts rather than
  in set names. The project file is edited in place, comments and all, and the result is parsed by
  the project reader before anything is written, which is where an offline project is refused. The
  daemon serves both halves through `Egress` and `SetEgress`, taking the project path `Projects`
  hands out. What it left is not about editing at all: it is
  [B10](B10-What-An-Egress-Set-Can-Express.md).
- **Secrets off the command line.** Every variable a container is given is now named on podman's
  command line without its value, which podman copies from Sokar's own environment: an argument
  list is world-readable and an environment is not. It covered the git gate's token and the
  phantom token, at container creation and at every agent run. The pair that must not drift - the
  names in the arguments, the values in the environment - is guarded by tests at both places that
  build them, because podman drops a name it cannot resolve rather than failing.
- **Recovery and panic.** A task that fails is held rather than removed - nobody can ask for that
  in advance, since the run worth looking at is the one that went wrong - and `sokar panic` stops
  every running task and every helper without being told a single container name, removing nothing.
  Both go through the same stop the CLI uses, so both write down what a task held that never
  reached the gate. The question it left, what the agent installed *inside* a container, is
  answered rather than moved: `podman diff` counts it, and a removal now says how much it
  destroyed.
- **Changing what running work may reach.** Widening, narrowing, and turning enforcement off or
  back on while a task runs - the scope a required value rather than a default, and turning it off
  deliberately not `WidenTask` with a special value, because folding "stop asking about anything"
  into a method that grants names would make one method mean two unrelated things. What it measured
  is in [AGENT.md](../../AGENT.md): a set holds addresses and not names, so a grant and a withdrawal
  are not mirror images; the resolver cannot be told without being restarted; and nothing applied to
  a running task survives `Resume` unless it was written down - which is why turning enforcement off
  also takes the watcher out of what a resume would restart.

- **Backups that can be told apart.** Listing, deleting, restoring and synchronising. Nothing had
  ever recorded that a backup was taken - `gate backup <file>` writes a bundle wherever an operator
  names it and forgets it - so the listing was never the missing part; the record was. A restore
  refuses with `HOLDS_WORK` and names the refs, because unreviewed pushes exist only in the mirror
  and overwriting one destroys the only copy. A triggered fetch is its own method rather than a flag
  on a read: a listing that reached the network would make the queue cost what a listing must not.

- **Working inside a running container.** `sokar task attach` opens a session that outlives leaving
  it, and says on returning exactly how much it can show rather than apologising for not knowing.
  The question it opened and closed is worth keeping: varlink cannot carry a session at all - it is
  one call in and many replies out, with no way for a client to keep sending into an open call - so
  it is carried by ssh with a pty, running Sokar's own verb rather than the runtime's. That adds no
  privilege: whoever can forward the daemon socket can already run commands there. Containment is
  untouched, and the session is a terminal in the container rather than on the node, because
  `sokar` is not installed in a task image.

- **Clearance prompts.** A blocked destination is a question naming the project, the task and what
  was reached; the answer takes effect on the waiting connection, is never asked twice for a task -
  across a resume, which is where it used to leak - and is written to a record that outlives the
  task. A question nobody answered is replaced on screen by one saying so, and reaches a client as
  a verdict rather than as silence. The question it left, how long a prompt should wait for
  somebody who is not at the machine, is part of [B06](B06-Remote-Access.md).

## To be checked

Two open questions are worth knowing before any of this is planned in detail, because each
changes what gets built rather than only how:

- Whether a person away from the machine can be reached at all ([B06](B06-Remote-Access.md)).
  The transport itself is settled - the socket survives an ssh forward, measured - so what is left
  is the half that decides how much of
  [F20](https://github.com/sokar-ai/sokar-frontend/blob/main/requirements/F20-Access-From-Elsewhere.md)
  and [F23](https://github.com/sokar-ai/sokar-frontend/blob/main/requirements/F23-Notifications.md)
  is real, and how long a clearance prompt should wait for somebody who is not there.
- Whether the guarantees can be re-derived at all on a second platform
  ([B08](B08-McSokar-Apple-Containers.md)). It decides whether that project offers the same
  product or a weaker one wearing the same name, and it constrains what may be added to the
  daemon's contract.

## Why here

Only the placements that are not obvious from the files themselves. Ranking is a property of the
set, so it lives here and nowhere else.

<a id="b24"></a>**B24 leads** because it is not a failure in the middle of long work; it is the
first thing a new operator meets, and it stops a task before it starts.

<a id="b29"></a>**B29 sits above B28, the foundation it depends on.** A set that begins with its
own foundation tends to sit unstarted. Building the smallest kind first makes the foundation's
first user exist while correcting its shape is still cheap.

<a id="b26"></a>**B26 is below B25** because it is a new subsystem rather than a small change, and
above the rest of Soon because the gap is total rather than partial.

<a id="b39"></a>**B39 is in Soon although nothing waits on it.** It is unblocked, it is the
cheapest of the three new ones, and the gap is total - there is no way to hand a file to a running
task today except pasting into a terminal. B37 is its first consumer rather than its reason.

<a id="b14"></a>**B14 carries B15 with it.** They are the same question twice, and B15 reuses
B14's policy and record wholesale, so settling B14 settles most of both. B14's first open question
is whether to build it at all.

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

<a id="b44"></a>**B44 is in Now although nothing is broken by it.** Everything it touches works; what fails is the operator. Asking to run an existing task accepted the name, built something else beside it, and removed that again on exit - three wrong turns, none of them reported, on the command a person reaches for first. It is above B27 because B27 is how it would have been caught, and below B24 because B24 stops work rather than confusing it.

<a id="b43"></a>**B43 is in Soon rather than Now, and shrank once B44 was decided.** What made a restart unrecoverable - the records a resume needs living in tmpfs - is B44's to fix, and B44 fixes it. What is left here is whether a machine coming back should do anything by itself, and how a person finds out that a restart is why their tasks are down. Its first question may reduce it to a line in the listing.

<a id="b42"></a>**B42 is in Later although it blocks nothing and is cheap.** It is reading rather
than building, and three of its four edges already have an answer here - the value is in comparing
those answers with what the field settled on, which is worth doing before somebody defends them in
public and not before that.
