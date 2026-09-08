# Base Requirements

What the product must do below the interface: the CLI, the daemon and the guarantees they make.
One file per requirement, each carrying its own acceptance criteria so it can be judged done or
not done.

**Open question** means the file ends with a *To be checked* section: something unresolved whose
answer could change what the requirement says, or whether it survives at all. Where a file has
answered most of what it asked, the column says how much is left rather than only that something
is.

## Work, in the order to do it

Ordered by consequence, not by number; the number is only the file's identity. The first is live
on somebody's machine today. The last is a second platform, which is a project rather than a
feature. B11 and B12 came from the interface, which is short of fields rather than of methods:
B11 is built, and B12 is what F17 is still waiting on.

B14 sits directly below B13 because the two are the same question from opposite sides - work
leaving by a door that is not the gate - and B14's own first open question is whether it should
wait for B13. B15 follows B14 because it is the same shape again for bytes rather than text, and
reuses B14's policy and record wholesale: settling B14 settles most of it. Neither is waiting on an
interface requirement, which both files argue is a reason to be slower about them rather than
faster - and both begin by asking whether the gate already answers the need, which for source it
does. B16 is the opposite case: it comes from the interface, F12 is not started because nothing
behind it exists, and its first open question - whether varlink can carry a session at all - is
already answered. It cannot: varlink is one call in and many replies out, with no way for a client
to keep sending into an open call. So it is carried by ssh - a second channel with a pty, running
Sokar's own `task attach` rather than the runtime's command, which is what a person already does by
hand and is a correct terminal without anything being rebuilt. A session is a multiplexer inside the
container, which Sokar installs in the layer it already writes - so leaving does not end it, and
what re-entering may claim is a scrollback figure rather than an apology.

B18 to B22 arrived together, from the interface, as one list of everything a person could see and
not do. They are filed separately because the work is separate, but the distinction that decides
their cost is shared: **B18 and B22 are verbs that already exist and are only invisible**, while
B19 and B20 existed nowhere and are built. B21 was withdrawn - see below. `doctor` was the third of the already-built ones and it is not
here - it went into [B05](B05-Health-And-Diagnostics.md), which is the same work seen from the
other side: B05 asks whether the machine reports what it can do, and the missing half is that
nothing can ask it.

**B18 states what must be true if a credential becomes storable from an interface; whether it does
is not decided there.** That question is held together with the vault passphrase in
[secrets from elsewhere](Secrets-From-Elsewhere_design.md), because the two were argued separately
and reached opposite answers within a day, on reasoning that moved under both - two of the three
original arguments did not survive examination. The design records four options and what each is
worth without choosing between them. **Until it is decided, both are entered at the node over ssh**,
and an interface asks for neither.

**Instructions for an agent are not Sokar's business, and B21 was withdrawn rather than built.**
Standing instructions live in the repository, checked in or not, and Sokar does not know what they
are called - `CLAUDE.md`, `AGENTS.md`, something else an agent invents next year. It cannot merge
them, because how an agent combines several is that agent's rule and not ours. A team running more
than one agent has to agree upfront how instructions are stored in their repository; that agreement
is theirs to make and nothing here can help with it.

**A hardcoded list of filenames would have been worse than nothing.** For a feature whose only job
is to show what an agent was told, a name that goes out of date produces a confident "no
instructions" for a task that had them - and a blank that looks like an answer is the failure this
project keeps writing down.

What Sokar knows about a repository it already offers: the gate shows the work under review, and
the person has the repository. Three of these dissolved for the same reason as the agent roster,
hardware access and key routing before them - the honest answer was *"that is not a thing this
system has"*, and saying so cost a paragraph and bought a screen that is not lying.

Two of that list dissolved rather than becoming requirements, and both are recorded where somebody
will look rather than dropped. Provisioning a machine is refused and could not have worked anyway -
a machine that is not ready has no daemon to ask - and it is argued in B05. Routing credentials to
projects describes a relation that does not exist, and it is argued in
[B18](B18-Storing-A-Credential-From-Elsewhere.md); restated correctly it is the agent roster, which
was already refused.

| # | Requirement | What must be true | Open question |
|---|---|---|---|
| B11 | [What A Task Says About Itself](B11-What-A-Task-Says-About-Itself.md) | A task says which agent, which mode, which branch, since when, and whether it is working, idle, waiting or dead. | yes |
| B12 | [Changing What Running Work May Reach](B12-Changing-What-Running-Work-May-Reach.md) | What a running task may reach can be widened or narrowed without restarting it, and a task says whether enforcement is on. | yes |
| B13 | [Unreviewed Work Leaving By The Side Door](B13-Unreviewed-Work-Leaving-By-The-Side-Door.md) | Work that reaches the upstream without passing the gate is prevented or reported, not silently possible. | two, and the guard is built |
| B14 | [Talking Between Tasks](B14-Talking-Between-Tasks.md) | Two tasks can hold a conversation that is recorded before it is delivered, attributed by the socket it arrived on, declared by both projects, refused across security classes, and stoppable while it runs - widening nothing a container may reach. | yes, including whether to build it |
| B15 | [Handing Artifacts Between Tasks](B15-Handing-Artifacts-Between-Tasks.md) | What a task builds can reach another task through a per-project content-addressed store, with the pointer committed and reviewed at the gate, written through a socket rather than a shared directory, and never mounted into a task. | yes, and it turns on B14 |
| B16 | [Working Inside A Running Container](B16-Working-Inside-A-Running-Container.md) | A shell in a running task is reachable from the interface, leaving does not end it, coming back says what it can and cannot show, and being inside weakens nothing the container is held to. | none - built |
| B18 | [Storing A Credential From Elsewhere](B18-Storing-A-Credential-From-Elsewhere.md) | A credential can be stored from an interface, the reply never carries the value back, and no path logs, echoes or records it. | two |
| B22 | [Backups That Can Be Told Apart](B22-Backups-That-Can-Be-Told-Apart.md) | Backups are listable, deletable and restorable by something other than a person at a terminal, and anything that would discard unreviewed work refuses by name first. | one |
| B19 | [Preparing An Environment On Purpose](B19-Preparing-An-Environment-On-Purpose.md) | An environment can be prepared without starting a task, at a depth chosen explicitly, and 'prepared' tells absent from stale. | one |
| B20 | [Creating A Project](B20-Creating-A-Project.md) | A project is validated against the machine before it is created, and nothing half-created survives somebody walking away. | one |
| B01 | [Refreshable Task Tokens](B01-Refreshable-Task-Tokens.md) | An agent that renews an expiring credential must not be broken by holding a task-scoped one. | yes |
| B10 | [What An Egress Set Can Express](B10-What-An-Egress-Set-Can-Express.md) | A destination that cannot be written as a host name is supported or refused, never silently unreachable. | one |
| B05 | [Health And Diagnostics](B05-Health-And-Diagnostics.md) | The machine reports whether it can actually run a task, naming anything missing or misconfigured. | yes |
| B06 | [Remote Access](B06-Remote-Access.md) | Tasks on another machine are usable over an encrypted tunnel, without the daemon ever binding a network port. | yes |
| B08 | [McSokar Apple Containers](B08-McSokar-Apple-Containers.md) | A sibling project offering the same behavior on Apple Containers, with one client that connects to either host. | yes |

## What was here and is finished

Ten requirements have been met and retired. Their files are gone; what each measured is in
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
