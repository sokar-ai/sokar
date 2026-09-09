# B14 — Talking Between Tasks

**Status:** open, and the first question is whether it should be built at all. What follows argues
that it can be built without giving anything up, and names the one thing it cannot promise.

**How it would be built** is [B14-Talking-Between-Tasks_design.md](B14-Talking-Between-Tasks_design.md),
written against this file: module by module, with the sockets, the record format and the interface
additions named. Nothing in it is built or measured — it exists so the expensive decisions are
visible before anything is written.

Two tasks are running. One is writing code, the other is reviewing it, or holds the knowledge the
first is missing. Today they cannot say a word to each other: a task is a container with its own
network namespace, a deny-by-default ruleset, a resolver that answers `NXDOMAIN` for every name the
project did not declare, and the only things bind-mounted into it are the vault and ssh sockets.
That isolation is the product. The request is to open a channel through it that a person can see,
join, restrict and cut.

## What a message channel actually is

Three things at once, and only the first is obvious.

**It is an egress path.** Everything below the interface rests on one property: work leaves a task
through the gate, where somebody looks at it. A task that can send arbitrary text to another task
can send its workspace, one message at a time, and no diff is ever shown to anybody. This is the
same shape as [B13](B13-Unreviewed-Work-Leaving-By-The-Side-Door.md) — unreviewed work leaving by a
door that is not the gate — except that B13's door is a person's own checkout, which Sokar does not
own, and this one would be a door Sokar built on purpose.

**It is an ingress path, and that is the worse half.** An agent acts on what it reads. Its inputs
today are the operator's instruction and the repository. A message adds a third: text written by
another model, which has itself been reading whatever its egress set allows. Nothing in a container
can make a model treat a sentence as data rather than as an instruction, so this is the part the
requirement must not overpromise. What can be guaranteed is narrow and worth having: every message
is recorded before it is delivered, attributed to its author, and delivered as content from another
task rather than through whatever channel carries the operator's own instruction. Whether the model
then does what the message says is outside anything Sokar can enforce, and a requirement that
implies otherwise would be selling containment that does not exist.

**It is a bridge between security classes, and this is the one that breaks a promise already
made.** An `offline` project promises that nothing resolves and nothing leaves. Let one of its
tasks talk to a task in an `online` project and the promise is void: the offline task asks, the
online task fetches, and the answer comes back — or, in the other direction, the offline task's
work reaches an upstream through a task that was allowed to push. Neither end did anything it was
forbidden to do. **The reachable class of a conversation is the highest class in it, not the class
of either participant**, and any rule about who may talk to whom that ignores this is decoration.

## Why the transport is not a new transport

Three shapes were considered. The third is the only one that does not contradict something the
product already guarantees.

### Through the frontend only

Rejected as the *authority*; see below for where it survives as a *carrier*.

The client is the wrong place for a control. Policy that lives in it is policy two clients can
disagree about, which is the failure `TaskInventory`, `TaskControl` and `GateSupport` exist to
prevent: one question, one implementation, or the CLI and the daemon come apart. A conversation
whose record is written by whichever client happened to be open is a record with holes in it that
nothing can even detect. And it inverts the access story — there is nothing to log in to, the
filesystem decides who may connect, and a remote client forwards a socket rather than
authenticating. A frontend that decided which agent may say what to which would be the first thing
in the product that has to be trusted rather than merely connected.

### A new direct channel between containers

Rejected. It is a second egress path with none of the first one's machinery: nothing to review, no
gate, no record unless one is invented for it, and a firewall and resolver that would have to learn
about destinations that are not upstreams. It also costs the property that makes the current design
cheap to reason about — a container reaches the host through sockets it was handed, and nothing
else. Adding container-to-container reachability means the answer to "what can this task talk to"
stops being readable from one ruleset.

### The channel that already exists

**A task already has exactly one hole in it that Sokar controls, and there are three instances of
it: the vault proxy, the git gate and the clearance watcher.** Each is a host-side helper — the
`sokar` binary re-invoked — holding a unix socket that is bind-mounted into the container's
directory. The file is world-writable because a rootless container's agent user is a subordinate
uid that cannot open a `0600` file the host user owns; the `0700` directory around it carries the
access control.

A message channel is a fourth instance of that pattern, and nothing else. No port, no nftables
element, no `server=` line in the resolver, no change whatsoever to what the container may reach.
This is the answer to the objection that a channel contradicts minimalism and encapsulation: it is
not a new kind of connection, it is the one kind the product already has, used a fourth time. The
daemon is on the far side of it, so the record, the policy and the interruption all live where
every other guarantee lives.

Two facts constrain how it is started, both already paid for elsewhere: a bind-mounted socket is
bound to the file that existed when the container started, so a helper that replaces its socket
afterwards leaves the container holding a deleted inode; and every helper of a given name writes
the same pid file, so a second one started by a resume orphans the first. A message helper is
subject to both.

## The agent side is where the architectural rule bites

Nothing outside `agents/` may name an agent, and no shipped agent has an inbox. `claude`, `codex`,
`gemini` and the rest take an instruction and produce output; none of them has a concept of
receiving a message from a peer halfway through a run. So the socket is only the transport, and the
*presentation* of a message to an agent has to be expressed in something the agent already
understands. Three candidates, none measured:

- **A file in the workspace** that the agent has been told to read. Works with every agent, needs
  no agent change, and is polling.
- **An MCP server on that socket**, offering send and receive as tools. Gives a generic CLI agent a
  real inbox, and requires the agent to speak MCP over a unix socket.
- **Standard input of an attached session**, which only exists for a task somebody is attached to.

Which of these an agent supports is a property of the agent, so it is a field in the agent
definition and an entry in its `describe` response — not a branch in Sokar. An agent that declares
none of them cannot take part, and must say so; a message silently dropped because the agent had
nowhere to put it is the worst outcome available, because the sender believes it was delivered.

## Who may talk to whom

**Declared in `project.yml`, beside the egress sets, and by both sides.** A pair declared by one
project only would let that project's file grant itself access to another project's task, which is
the same mistake as an ACL that only the caller writes. The file is edited as text and parsed
before it is written, the way `EgressEdit` already treats it — it is the one file here a person
writes by hand and reads in a diff.

**Refused by class, in the way `SetEgress` already refuses one.** An `offline` project takes part
in nothing; that is what the class means. A pair spanning two different classes is the escalation
described above and must be refused or, if it is ever allowed, refused by default and granted with
the escalation stated in the grant — never silently permitted because both ends were individually
legal.

**Authorship is established by the transport, never claimed by the sender.** Anything a container
says about who it is, is an assertion by an agent. The socket is mounted into exactly one
container, so the identity of an author is which socket the bytes arrived on — the same resolution
the vault proxy already relies on.

## Whether an agent should sign what it says

Asked because it is the obvious next question, and the answer is no — not the agent. The signature
would be issued by the one party here that is not trusted, over a path where nothing can alter the
bytes, to prove something the mount already proves better.

**The key would be in the hands of the thing it is meant to bind.** An agent controls its own
container; that is what a container is for. A key it can reach in order to sign is a key it can
read, copy, write into a message, or commit into its workspace. What the signature would then
attest is *something inside this container signed this*, which is exactly what the socket attests —
without a key, and without anything that can be carried off. A mount cannot be exfiltrated.

**There is nothing in between to protect.** Between the agent's write and the daemon's read lies a
unix socket: kernel memory, host-local, no network and no intermediary. Integrity protection is an
answer to a transport that does not exist here.

**And it would be the first credential inside a task container**, which is the rule that is not
negotiable. The phantom token exists so the real one never gets in; the `online` class pushes
through an ssh-agent socket whose key stays outside; the reference implementation answers signing
requests over a per-container socket with the private key on the host. Follow that pattern for
messages and the *host* signs on request — at which point the signature testifies to precisely what
the socket already did.

### What the question is right about

Two real things are underneath it, and neither needs a key in a container.

**The record is what wants protecting, not the message in flight.** Against later modification a
signature held by the sender is no help at all; a **hash chain** is — each entry carrying the hash
of the one before it, so that an entry changed or removed after the fact stops verifying. Nothing
to distribute, nothing to rotate, nothing to revoke. It does not protect against the owner of the
machine, and nothing can: [B13](B13-Unreviewed-Work-Leaving-By-The-Side-Door.md) says as much about
the other door, and saying it here as well is cheaper than discovering later that somebody believed
otherwise.

**Across machines the signing party would be the daemon, never the agent**, and that belongs to the
cross-machine question rather than to this one. It is the only place with an intermediary at all:
ssh gives authenticity and integrity per hop, but the client terminates both connections and sees
plaintext. What a daemon key would buy is narrower than it looks, and it is argued under *To be
checked*.

**What would change the answer**: a record that leaves the machine which produced it — exported to
a SIEM, kept for a retention rule, carried by a broker the operator does not own. Then the thing to
sign is the record, host-side, with a key that never sees a container. That is log signing, and it
is a different requirement from this one.

## Seeing it, joining it, stopping it

The four things asked for map onto machinery that exists.

**Seeing everything** is only true if delivery is impossible except through the recorded path, so
the record is written *before* the message is delivered and a message that cannot be recorded is
not delivered. It goes under the state directory rather than the runtime one, for the reason the
clearance decisions did: `/run/user/<uid>` is cleared when the user's last session ends, and a
record of what agents said to each other that disappears with the task is not a record.

**Watching** is `Prompts` again: one stream over the daemon's socket, covering every conversation
on the machine including ones that start after the call, so an interface does not discover and
connect to anything per task. `Tasks1` only ever grows, so this is new methods and new reply
fields rather than a `Tasks2`.

**Joining** is a message whose author is the person. It must be distinguishable from an agent's
everywhere it appears — in the stream, in the record, and in whatever the receiving agent is
shown. A channel where a human utterance can be confused with a machine one is a channel whose
record cannot be used to answer the only question anybody will ever ask of it.

**Interrupting** is a state on the conversation, checked at delivery, failing closed. Holding a
conversation must tell the sender it was held rather than leaving it waiting, because an agent
that gets silence retries — the clearance path already learned that a decision the watcher forgot
turned into a retry loop within seconds.

**And it needs a turn budget.** Two agents in a conversation is a loop with a bill attached and no
natural end. The budget belongs in the conversation, and exhausting it ends the conversation and
says so; a warning is not a control.

## Across machines

**Not in the first version, and never daemon to daemon.** [B06](B06-Remote-Access.md)'s acceptance
is that the daemon binds no network interface in any configuration, and a daemon that accepts a
peer needs an authentication story this product deliberately does not have — it would be the first
credential in the design that exists so two Sokars can trust each other.

If it is wanted later, the only shape consistent with everything above is the one the product
already uses for remoteness: **the client holds an SSH connection to each machine and carries
frames between two daemons as a transport, not as an authority.** Each daemon applies its own
policy and writes its own record, and refuses anything its own policy would not have allowed — so
a broken or hostile client can delay or drop messages, and cannot inject one that a daemon would
have refused or hide one from either record. That is exactly the property `ssh -L` has today: the
tunnel carries bytes and decides nothing.

Two costs, stated rather than discovered later: a conversation carried by a client stops when the
client closes, and its record is then two records on two machines with no shared clock to order
them.

## The channel that is already there

Worth saying because it changes what this has to be: **Sokar already has a controlled channel
between two pieces of work, and it is the gate.** One task pushes a branch, a person reads it, an
approve forwards it. It is audited, human-in-the-loop, and built. Where a hand-off is what is
actually wanted — this agent produces, that one reviews — the gate is strictly the better answer,
because a message channel would be a second, weaker path to the same place.

What the gate cannot do is dialogue: a question asked and answered inside one run, while both tasks
are still up. That is the only thing that justifies a second channel, and it is the case this
requirement should be judged against. If a proposed use of messaging could have been a branch and a
review, it should have been.

## Prior art

The reference implementation has no agent-to-agent messaging at all. What it has is adjacent and
instructive: `toad`, a multi-agent TUI served over `127.0.0.1`, which is one *person* driving
several agents rather than agents reaching each other; and a sidecar tool mode that runs a second
container against the same repository, which is cooperation through the *work product* rather than
through a channel — the same argument as the gate, above.

It also shows the thing not to do. The reference implementation bind-mounts a set of shared
configuration directories into
**every** task container, writable, and its own documentation names the consequence: containers can
poison them. A writable directory that two tasks share is a message channel nobody designed, with
no record, no policy and no way to interrupt it. Sokar mounts only the vault and ssh sockets into a
task, and that is a property to keep rather than a gap to fill.

## Acceptance

- A message is refused unless **both** projects declare the pair, in `project.yml`, edited in place
  as text like every other project setting and parsed before it is written.
- A pair is refused by class in the way `SetEgress` already refuses one: an `offline` project takes
  part in nothing, and a pair spanning two classes is refused rather than silently allowed because
  each end was individually legal.
- Every message is written to a record that outlives both tasks **before** it is delivered, and a
  message that cannot be recorded is not delivered.
- The record names the author, and the author is established by the socket the bytes arrived on,
  never by what the sender claims to be.
- The record is a chain: every entry carries the hash of the one before it, so that an entry
  changed or removed after the fact stops verifying.
- **No key is placed inside a task container for any of this.** Authorship rests on the socket a
  message arrived on, not on a credential the sender holds and could carry off.
- One subscription over the daemon's socket carries every conversation on the machine, including
  conversations that begin after the call.
- A person can write into a conversation, and their message is distinguishable from an agent's in
  the stream, in the record, and in what the receiving agent is shown.
- A conversation can be held, released and closed while it runs. A held conversation delivers
  nothing, and the sender is told it was held rather than left waiting.
- The moderation modes are the four `clearance` already uses — `prompt`, `allow`, `deny`, `off` —
  with the same meanings, and the mode in force is readable back from the task, for the same reason
  `Task` had to carry `clearance`: a state nothing reports cannot be marked anywhere.
- A conversation has a turn budget. Exhausting it ends the conversation and says so.
- A message reaches the agent as content from another task, never through the channel that carries
  the operator's own instruction.
- Nothing here widens what a container may reach: no port, no firewall element, no resolver entry,
  and no directory shared between two tasks.
- An agent that has no way to receive a message says so, and nothing is delivered into a void.

## Notes

**This is not asked for by the interface.** Unlike changing what running work may reach,
which existed because F17 was short of a method, this comes from the operator's side and has no
frontend requirement waiting on it. That is a reason to be slower about it, not faster: nothing
breaks while it does not exist.

**The honest framing, so it is not lost later.** Sokar can guarantee that every message is
recorded, attributed, bounded, visible as it happens, stoppable, and tamper-evident after the
fact. It cannot guarantee that an
agent treats a message as information rather than as an order — that is a property of the model, not
of the container — and it cannot make the channel safe between two projects of different classes,
which is why the class rule is a refusal and not a warning. A requirement that claimed more would
produce a feature people trust further than it deserves, which is worse than not having it.

## To be checked

- **Whether this should wait for [B13](B13-Unreviewed-Work-Leaving-By-The-Side-Door.md).** That
  requirement is about unreviewed work leaving by a door Sokar does not own. Building a second door
  that Sokar does own, while the first is still open, is a sequencing decision rather than a
  technical one — but it is the decision that matters most here.
- **Which delivery shape an agent can actually take.** All three above are unmeasured. MCP over a
  unix socket is the most promising and the least certain: it has to work from inside a rootless
  container, against a socket file whose access control is the directory around it, for an agent
  that was never told it is in a container.
- **Whether a held message may be edited by the person, or only answered.** Editing it silently
  rewrites what an agent said and destroys the only thing the record is for. Refusing to edit means
  the sole intervention is a new message, which may not be enough for somebody watching two agents
  talk each other into something wrong.
- **What a conversation means across a resume.** A resumed task gets a fresh namespace, and a
  ruleset and resolver rebuilt from the project; the clearance path learned the hard way that a
  change made only in a live container is gone the moment the task comes back. A conversation is
  more than a live change and less than a project setting, and which of the two it behaves like has
  to be decided rather than inherited.
- **What happens when one side stops.** A half-open conversation is the normal case, not an edge
  one, since tasks end at different times. Whether the surviving side is told, and whether it may
  go on writing into a record nobody will read, is undecided.
- **Who is charged for a turn.** A message causes work in the receiving task, against that
  project's provider and credential. A conversation is therefore a way for one project to spend
  another's budget, which nothing in the product currently models.
- **Cross-machine, deferred above.** The shape is argued but nothing is measured, and the two costs
  named there — a conversation that stops with the client, and a record split across two machines
  with no shared clock — may be enough to leave it unbuilt.
- **Whether a daemon should sign what it forwards, if cross-machine is ever built.** That is the
  one place with an intermediary: the client terminates both ssh connections and sees plaintext, so
  a daemon key would stop it injecting a message. The gain is narrow — the receiving daemon applies
  its own policy and writes its own record, so an injected message is one that side authorized and
  recorded anyway, and what is really bought is attribution to a particular remote task rather than
  to whatever the client asserted. Against it: whoever controls the client holds ssh keys to both
  machines and can already start tasks, approve gate pushes and unlock the vault, and the key would
  be the first credential in the design that exists so two Sokars can trust each other — which is
  precisely what [B06](B06-Remote-Access.md) avoids by having nothing to log in to.
