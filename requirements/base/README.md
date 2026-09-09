# Base Requirements

What the product must do below the interface: the CLI, the daemon and the guarantees they make.
One file per requirement, each carrying its own acceptance criteria so it can be judged done or
not done.

**Open question** counts the unresolved items in the file's own *To be checked* or *Still open*
section - something whose answer could change what the requirement says, or whether it survives at
all. Answered ones are struck through in place rather than deleted, so a question that turned out
to have an answer stays readable beside it.

The count is what is left, not what was ever asked. Nine of these are built or partly built and
still carry questions; that is the ordinary state of a requirement here rather than a sign it is
unfinished.

## Work, in the order to do it

**Three of these came from reading somebody else's commits.** B32, B33 and B34 were found on
2026-09-09 by checking new work in [Terok](https://github.com/terok-ai/terok) - the reference
implementation this project is inspired by - against this code. One was a defect here too and
worse than theirs, one is an unreproduced report about images, and one is a design idea. That is a
useful ratio and the reason to keep doing it.

Ordered by consequence, not by number; the number is only the file's identity. Anything already
built is not here - it is in the second table below, or gone entirely.

**B24 leads because it stops a task before it starts.** An agent that no longer asks permission
per command still opens with two consent dialogs, and unattended there is nobody to answer them -
so a run begins and then waits at a menu. One of the two asks a person whether to trust the
phantom token Sokar minted for that task, recommending they refuse it. It is above B01 because it
is not a failure in the middle of long work, it is the first thing a new operator meets.

**B27 is second because it decides how everything below it gets verified.** There is an acceptance
suite - 1177 lines, on two rented machines per merge - and it cannot allocate a terminal, so every
behaviour gated on `isTerminal()` has been checked by a person by hand. That is not a gap in
coverage, it is a gap in what the suite can reach, and it will not close by adding cases to it.

**B32 is second because it is a false statement, not a missing feature.** `vault lock` reports
"nothing was cached" whenever the keyring search fails for any reason, including reasons that leave
the passphrase exactly where it was. Everything else on this list is work that has not been done;
this is the tool telling an operator something untrue about a secret. It is also small.

**B29 is next, and above the foundation it is built on.** A task holds exactly one brokered
credential - the agent's - and everything else the work authenticates to has nowhere to go. B28 is
the foundation of that and the largest of the four; B29 is the smallest and the one that proves the
plumbing. A set that begins with its own foundation tends to sit unstarted, and doing the small one
first makes the foundation's first user exist while correcting its shape is still cheap.

**B01 is next because it is the one that breaks work already running.** An agent that renews an
expiring credential and finds a task-scoped one instead fails in the middle of a long task, and
Sokar's own expiry is only half of it: provider-side renewal is untouched, and answering it needs a
credential kind that expires, which there is none to test with.

**B22 is next because what is left of it has teeth.** Listing and deleting are done; a restore that
would discard unreviewed work is not, and unreviewed pushes exist only in the mirror. The refusal
shape exists twice already - `DeleteProject` and `Stop` both answer `HOLDS_WORK` - so this is a
third use of it rather than a fourth invention.

**B12 was retired on 2026-09-08 and restored the same day**, which is why it sits here rather
than in the built table. Every criterion was checked except the fourth, and the fourth is the one
nothing answers: enforcement can be chosen when a task starts and not changed afterwards. It is one
missing method and it is the last thing an interface requirement is waiting on, so it outranks the
items below that unblock nothing.

**B25 is third because it is met every time, not because it is severe.** Nothing in it goes
silently wrong the way B10 does; it is above the rest because it is cheap and because every
command that takes a container, a project or an agent name currently makes somebody go and look it
up. The first of its three parts — saying which names would have been accepted when one is missing
or wrong — needs no shell integration and no packaging at all.

**B26 is fourth because nothing else answers the question it asks.** Every host-side log Sokar
keeps belongs to one task, lives on tmpfs, and is deleted by a reboot — so "what has this machine
been doing" has nowhere to look, with or without an interface. It sits below B25 because it is a
new subsystem rather than a small change, and above the rest because the gap is total rather than
partial.

**B10 is decided and only unbuilt**, which makes it the cheapest thing on this list: the four
questions it was written to ask were settled, and what remains is the work plus one smaller
question that appeared once the others were answered. A destination that cannot be written as a
host name is silently unreachable today.

**B06 decides how much of the remote story is real**, so it is above the things that would be built
on top of it. The transport itself is settled - the socket survives an ssh forward, measured - and
what is left is how long a clearance prompt should wait for somebody who is not there.

**B18 and B23 are waiting on decisions rather than on effort.** B18 states what must be true *if* a
credential becomes storable from an interface; whether it does is held together with the vault
passphrase in [secrets from elsewhere](Secrets-From-Elsewhere_design.md), because the two were
argued separately and reached opposite answers within a day on reasoning that moved under both -
two of the three original arguments did not survive examination. **Until it is decided, both are
entered at the node over ssh**, and an interface asks for neither. B23 is deliberately later, and
honest that erasure is not achievable in a managed runtime at all.

**B14 and B15 are last before the second platform because the first question in B14 is whether to
build it.** They are the same question twice - a conversation, then the same shape again for bytes
rather than text - and B15 reuses B14's policy and record wholesale, so settling B14 settles most
of it. Neither is waiting on an interface requirement, which both files argue is a reason to be
slower rather than faster, and both begin by asking whether the gate already answers the need,
which for source it does.

**B08 is a second platform, which is a project rather than a feature.**

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

| # | Requirement | What must be true | Open question |
|---|---|---|---|
| B24 | [First-Run Consent Inside The Box](B24-First-Run-Consent-Inside-The-Box.md) | A task starts its agent and the agent works; nothing between asks a person a question the box already answered. | four, and one dialog is refused rather than solved |
| B27 | [Testing What A Person Actually Does](B27-Testing-What-A-Person-Actually-Does.md) | What a person does at a terminal is tested by the build, on a real machine, and reported case by case. | five, three decided |
| B32 | [A Cached Passphrase That Says What It Is](B32-A-Cached-Passphrase-That-Says-What-It-Is.md) | Sokar never reports a cached passphrase gone unless it is gone. | three, and the fix is small |
| B29 | [Keys Presented As They Are Stored](B29-Keys-Presented-As-They-Are-Stored.md) | A stored key reaches its destination as it is stored, in the header or the URL that destination asks for. | see the file |
| B28 | [More Than One Credential In A Task](B28-More-Than-One-Credential-In-A-Task.md) | A task can be given the credentials its work needs, each confined to its own destination, without any of them entering the container. | see the file |
| B30 | [Credentials The Broker Has To Fetch](B30-Credentials-The-Broker-Has-To-Fetch.md) | A credential the broker obtains rather than holds, including the machinery B01 parked. | see the file |
| B31 | [An Authorization A Person Grants Once](B31-An-Authorization-A-Person-Grants-Once.md) | A person grants an authorization once, out of band, while the work waits. | see the file |
| B01 | [Refreshable Task Tokens](B01-Refreshable-Task-Tokens.md) | An agent that renews an expiring credential must not be broken by holding a task-scoped one. | one |
| B25 | [Names The Operator Should Not Have To Find](B25-Names-The-Operator-Should-Not-Have-To-Find.md) | A command that needs a name Sokar already knows never makes the operator go and find it. | three |
| B33 | [A Task's Own Fetches](B33-A-Tasks-Own-Fetches.md) | A task can fetch from the forges its work depends on, and a failure to do so is never reported as a credential problem. | three, and the first is whether it reproduces |
| B26 | [What This Machine Has Been Doing](B26-What-This-Machine-Has-Been-Doing.md) | A machine can say what it has done, for longer than the tasks themselves existed. | two, and four are decided |
| B10 | [What An Egress Set Can Express](B10-What-An-Egress-Set-Can-Express.md) | A destination that cannot be written as a host name is supported or refused, never silently unreachable. | one |
| B06 | [Remote Access](B06-Remote-Access.md) | Tasks on another machine are usable over an encrypted tunnel, without the daemon ever binding a network port. | three |
| B18 | [Storing A Credential From Elsewhere](B18-Storing-A-Credential-From-Elsewhere.md) | A credential can be stored from an interface, the reply never carries the value back, and no path logs, echoes or records it. | two, plus `Login` held open as nice to have |
| B23 | [Secrets In This Process's Memory](B23-Secrets-In-This-Process-Memory.md) | A credential's plaintext exists in as few places and for as short a time as a managed runtime allows, and what cannot be achieved is written down rather than implied. | three, and one is a one-line fix |
| B14 | [Talking Between Tasks](B14-Talking-Between-Tasks.md) | Two tasks can hold a conversation that is recorded before it is delivered, attributed by the socket it arrived on, declared by both projects, refused across security classes, and stoppable while it runs - widening nothing a container may reach. | eight, including whether to build it |
| B15 | [Handing Artifacts Between Tasks](B15-Handing-Artifacts-Between-Tasks.md) | What a task builds can reach another task through a per-project content-addressed store, with the pointer committed and reviewed at the gate, written through a socket rather than a shared directory, and never mounted into a task. | six, and it turns on B14 |
| B34 | [What The Resolver Can Actually Do](B34-What-The-Resolver-Can-Actually-Do.md) | A machine says what its resolver can do and what follows for a task, and a refusal names what was missing. | two, and the first may end it |
| B08 | [McSokar Apple Containers](B08-McSokar-Apple-Containers.md) | A sibling project offering the same behavior on Apple Containers, with one client that connects to either host. | two |

## Built, and still carrying questions

These are not work waiting to be done: every acceptance criterion is met and each is in
use. What each still carries is a question whose answer could change what the requirement
says - which is why they are not retired, and why they are not at the top of the list
above.

| # | Requirement | What must be true | Open question |
|---|---|---|---|
| B05 | [Health And Diagnostics](B05-Health-And-Diagnostics.md) | The machine reports whether it can actually run a task, naming anything missing or misconfigured. | one, and `doctor` is on the wire |
| B11 | [What A Task Says About Itself](B11-What-A-Task-Says-About-Itself.md) | A task says which agent, which mode, which branch, since when, and whether it is working, idle, waiting or dead. | three, and it is built |
| B13 | [Unreviewed Work Leaving By The Side Door](B13-Unreviewed-Work-Leaving-By-The-Side-Door.md) | Work that reaches the upstream without passing the gate is prevented or reported, not silently possible. | three, and the guard is built |
| B19 | [Preparing An Environment On Purpose](B19-Preparing-An-Environment-On-Purpose.md) | An environment can be prepared without starting a task, at a depth chosen explicitly, and 'prepared' tells absent from stale. | one - which agent an image was built for |
| B20 | [Creating A Project](B20-Creating-A-Project.md) | A project is validated against the machine before it is created, and nothing half-created survives somebody walking away. | one - whether creating also prepares |

## What was here and is finished

Thirteen requirements have been met and retired. Their files are gone; what each measured is in
[AGENT.md](../../AGENT.md), where it will be read again:

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
  [F20](https://github.com/fuinorg/sokar-frontend/blob/main/requirements/F20-Access-From-Elsewhere.md)
  and [F23](https://github.com/fuinorg/sokar-frontend/blob/main/requirements/F23-Notifications.md)
  is real, and how long a clearance prompt should wait for somebody who is not there.
- Whether the guarantees can be re-derived at all on a second platform
  ([B08](B08-McSokar-Apple-Containers.md)). It decides whether that project offers the same
  product or a weaker one wearing the same name, and it constrains what may be added to the
  daemon's contract.
