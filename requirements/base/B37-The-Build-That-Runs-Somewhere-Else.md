# B37 — The Build That Runs Somewhere Else

**Status:** open, and the first question is whether the build it waits for exists at all. Proposed
on 2026-09-10, out of [B28](B28-More-Than-One-Credential-In-A-Task.md): once a task can hold more
than one credential, the obvious second one is a forge token, and the obvious use of a forge token
is asking what the build did. That obvious use is the one this file argues against granting
directly. Related to [B29](B29-Keys-Presented-As-They-Are-Stored.md), which is how a forge token
would be presented, [B31](B31-An-Authorization-A-Person-Grants-Once.md), which is the other way to
obtain one, and [B26](B26-What-This-Machine-Has-Been-Doing.md), which owns the record. **How the
result reaches the task is [B39](B39-Handing-A-File-To-A-Running-Task.md)**, which was split out of
this file and can be built without it.

## What is being asked for

Work pushed out of a task triggers a build somewhere else - GitHub Actions, GitLab CI, a Jenkins.
An agent that has just finished a change wants two things from it: **the verdict** (running,
success, failure) and **the log**, which arrives compressed and is where the reason lives. Today it
gets neither, and a person reads the build and pastes the failure back in.

The proposal is a host-side helper that authenticates out of the keyring, polls the forge, and
presents only those two things inside the container - rather than putting a forge token in the
container and letting the agent use `gh` or `glab` for it.

## The word is taken

`sidecar` already names something here: `Sidecar` in `sokar-wire`, the file under
`$XDG_RUNTIME_DIR` that the launcher writes and the three OCI hooks read, gated on the annotation
`org.fuin.sokar.sidecar`. It is the launcher-to-hooks contract and nothing else, and it is at
version 3 with a refusal for unknown versions. A second meaning of the word in the same repository
would make every sentence containing it ambiguous.

The family this belongs to already has a name and three members: **helpers** - `gate serve`,
`vault serve`, `vault relay` - host-side processes a task's launch starts, which hold what the
container must not. There is also already a **watcher**, the clearance one. This is a fourth helper
and a second watcher, and it should be called what those are called.

## Three classes, three different builds

This is first because it decides how much of the requirement is real, and it is not visible from
inside the proposal.

- **offline** - nothing leaves the machine, so there is no build to watch. The requirement is empty
  here unless something on this machine builds the mirror, which nothing does.
- **guarded**, the default and the class the gate exists for - the agent pushes to the gate, the
  work waits under `refs/sokar/incoming/<task>`, and `GitGate.approve` is *the only method that
  forwards anything and is never called by anything the agent can reach*. **So the forge does not
  build a task's push until a person approves it.** An agent waiting for its build is waiting for a
  human first, and a container, a firewall, a gate and a credential proxy stay up for the whole of
  that wait.
- **online** - no gate is created at all, the agent pushes to the upstream itself, and a build does
  start within seconds. This is the only class where the thing being asked for happens the way the
  proposal imagines it.

Two consequences. The wait in `guarded` is [B31](B31-An-Authorization-A-Person-Grants-Once.md)'s
shape - work blocked on a person who may not be looking - and it belongs to
[B06](B06-Remote-Access.md)'s open question about how long anything here waits for somebody who is
not at the machine, rather than being invented again. And a helper that reports *nothing yet* for
an hour because the work is sitting in a review queue is describing a queue, not a build; if it
cannot say which of the two it is looking at, it is the blank that looks like an answer this
project keeps writing down.

## What a task cannot do today, exactly

Not a reachability problem and not only a credential problem:

- **In `online` the container's git credential is an ssh-agent socket.** That authenticates git and
  nothing else. There is no REST call it can make, with or without egress, so even the class that
  reaches the forge cannot ask the forge a question.
- **An undeclared name does not resolve.** Declared names resolve and everything else is NXDOMAIN,
  so a project that names `github.com` for cloning has not thereby opened `api.github.com`. Adding
  it opens the whole API to whatever holds a token.
- **In `guarded` the container reaches no forge at all**, by design.

So this is not a gap that closes by handing over a token. Handing over a token in `guarded` would
be the first thing that gave a guarded task a route to the upstream, which is the one property the
class is bought for.

## Route, or reduced surface

The central choice, and both are defensible.

**A route.** [B28](B28-More-Than-One-Credential-In-A-Task.md) is building a broker with a route
table: the container gets a phantom token and a base URL, the broker attaches the real credential
and forwards. A forge is one more destination - which B28 already decided is a `destination` rather
than a widened `ProviderDefinition`. The agent then uses `gh`, which it already knows, and Sokar
writes no forge-specific code. It is data.

**A reduced surface.** The helper exposes two things - a verdict and a log - and nothing else
reaches the forge. The argument for it is the one the proposal makes, and it is stronger than it
looks: **a forge token cannot be scoped to the question being asked.** A GitHub token that can read
Actions for one repository can read that repository's code, its issues and, depending on the token
kind, every other repository the grantee can see. The route model is honest about what it grants
and grants far more than the work needs; the reduced surface grants exactly the work's need.

It also removes a whole class of accident. With a route, an agent told to check the build can
instead open an issue, close a pull request, or push a comment - not maliciously, but because an
agent that has a tool uses it, and none of that passes the gate. With a reduced surface there is no
verb to reach for.

**The cost is that it is behavior, not data, and this project has a rule about that.** A provider
is a file; an agent is a release. A verdict-and-log shim for GitHub Actions, then GitLab, then
Jenkins, then whatever a customer runs, is per-forge code in Sokar - the cost the agent/provider
split exists to avoid paying twice. The mitigation is to push as much of each forge into data as it
will go: a URL template, an auth header shape (which is [B29](B29-Keys-Presented-As-They-Are-Stored.md)
exactly), and the field paths for status, conclusion and log location. What will not go into data is
what each forge does differently in kind - GitHub answers a run's logs with a redirect to a zip,
GitLab with a per-job trace - and that residue is the honest price of this option.

## What comes back is not trusted input

A build log is output from a machine that ran arbitrary steps, and it is being delivered to an
agent that will read it and act on it. Two things follow, and neither is a detail.

**A log may carry a secret, and that is the build server's job rather than this one's.** Masking
is where the secret is known: GitHub scrubs its registered secrets from Actions logs, Jenkins does
the same for its credentials binding, and neither is complete - an `env` dump in a failing step, a
signed artifact URL, a registry credential in a curl trace all get past a scrubber that only knows
the values it was given. **But Sokar knows none of those values at all.** A CI secret lives in the
forge, was never in the vault, and Sokar's guarantee - *a stored value never enters a container* -
is a statement about credentials this machine holds. It does not extend to one the build server
leaked into its own output, and stretching it to cover that would mean inventing a heuristic
masker, which is the incomplete rule presented as a promise that this project refuses everywhere
else.

So the responsibility is named rather than assumed, and what is left is only what Sokar could make
*worse*: a delivered log must not land in `/workspace`, where it becomes part of the work and
leaves through the gate as though somebody had written it, and it must not be copied into a record
that outlives the task without that being a decision somebody made
([B26](B26-What-This-Machine-Has-Been-Doing.md) owns the second). Those two are this file's; the
scrubbing is not.

**A log is text an outsider can influence, and the agent obeys text.** Anyone who can open a pull
request can put a sentence in a build log.

**But this feature does not create that surface, and it must not be written as though it did.** A
task already reads text nobody here wrote, every time: the repository it was pointed at, a diff it
was asked to review, an issue quoted into its prompt, a dependency's release notes. Refusing to
deliver a log on injection grounds would refuse one drop of an ocean the task is already swimming
in, and would buy nothing. **So the requirement is the reverse: whatever generally lowers what an
injection is worth has to exist anyway, and this is one more consumer of it rather than the reason
to build it.**

What that general answer is, is largely built here already, and is nowhere written down as a
property with named limits. It does not prevent the agent from being convinced - nothing can - it
makes being convinced not worth much: an undeclared name does not resolve and a declared one opens
only 80 and 443; the real credential is in the vault and the container holds a phantom token that
dies with the task; work does not leave without a person at the gate; and a reach for something
undeclared raises a clearance prompt, **which is the closest thing on this machine to a hijack
alarm** - an agent asking for a host nobody declared is the observable shape of an agent following
somebody else's instructions. `doc/sokar-for-dummies.md` already argues the credential half of this
in exactly those terms.

Its limits have to be named beside it, or it becomes the reassuring sentence that stops people
looking: containment bounds where a convinced agent can *go*, not what it can *do inside the work it
was given*. A hijacked agent can still write a plausible change into the branch it is allowed to
write, and the person at the gate is reading a diff that is large and boring. That is the residue,
and the gate is the only thing standing on it.

**Where this feature can pay in, rather than only cost:** content Sokar delivers is content Sokar
can mark. A repository arrives by git clone and Sokar never sees a byte of it, so its provenance is
unmarkable - but a build log arrives *through a helper of ours*, and so do a message from another
task ([B14](B14-Talking-Between-Tasks.md)) and an artifact ([B15](B15-Handing-Artifacts-Between-Tasks.md)).
Everything on that list can be handed over labeled as data of known origin rather than as
instructions. How it is labeled is not Sokar's to decide: fencing conventions differ per agent, and
**an agent's own facts belong in its definition YAML, not in a branch here** - the same rule that
keeps every other agent-specific fact out of this code. Whether that is worth building is the
general question, not this file's.

## Getting it into the container

**This is no longer this file's question.** Handing a file to a running task is a need of its own -
a specification, a data extract, a crash dump, the answer to something the agent asked - and it is
[B39](B39-Handing-A-File-To-A-Running-Task.md), which is smaller than this one and blocked by
nothing. A build log is its first consumer rather than its reason. What remains below is what B39
inherits, kept here because it was measured while arguing this feature.

Three candidates, and the measured facts already rule on most of it:

- **A loopback endpoint in the task's network namespace**, the `vault relay` shape. A host-side
  listener is unreachable from a rootless container - measured, refused both via `169.254.1.2` and
  on the container's own loopback - and binding the helper inside the namespace directly gives it
  the host's mount namespace and a resolver that resolves nothing. The relay exists because of
  that, and a second consumer of the same shape costs a port, not a design. If it is a URL, it is a
  **literal address and never `localhost`**: Node resolves that to `::1` first and an IPv4 listener
  answers `ECONNREFUSED` while everything looks right.
- **A unix socket.** Then the container's agent user is a subordinate uid that cannot open a `0600`
  socket the host user owns, so it is world-writable inside a `0700` directory, and on SELinux it
  has to be labeled at `socket()` and not at `bind()`. All of that is written down and all of it is
  a call site that can be got wrong.
- **A file copied in.** Simplest, and the one the proposal names. It is one-shot, which fits a
  finished build and not a running one - and **it must not land in `/workspace`**: a log written
  there becomes part of the work, gets committed by an agent tidying up, and leaves through the
  gate as though somebody had written it.

The B28 warning applies here more than anywhere: a listener, a socket, a mount and a port *per
credential* multiplies the part that is awkward already. If the broker is being built with a route
table anyway, the cheapest reduced surface is a route whose upstream is this helper rather than the
forge - one relay, one port, one phantom token, and the narrow surface behind it.

## Waiting is the expensive half

The verdict is the small part. Waiting for it is what costs.

- **Who polls.** Host-side, the poll is one process on a timer that no task pays for. Container-side
  against a route, every poll is a turn of the agent's own loop - tokens spent on `sleep` and
  `curl` - and an agent left to poll will either hammer it or give up early.
- **A rate limit is per token, and tasks share it.** Ten tasks watching builds on one operator's
  token is one budget, and a limit hit reads as a broken credential unless the helper says
  otherwise - [B33](B33-A-Tasks-Own-Fetches.md) is the same failure wearing the same clothes.
- **A wait has to end.** A build that never finishes, a queue that never drains, a run cancelled by
  somebody else: each needs an answer that is not silence, because a task blocked forever holds
  everything its container holds.
- **Which build.** A task cannot know a run id - the run does not exist when it pushes - so the only
  key it can name is its own commit. That survives the gate: `approve` forwards the same commit to
  the upstream branch, so the sha the task knows is the sha the forge builds. It does not survive a
  rebase or a squash merge, and there the honest answer is that the build belongs to a different
  commit.
- **A tail's rate is already known**: 64 KB per reply with a 200 ms sleep, about 320 KB/s, whatever
  the transport can do. A 40 MB CI log is two minutes of that before anything reads it, which is an
  argument for delivering the tail of a failing job rather than the log.

## What must be true

**A task learns the verdict and the reason for the build its own work triggered, without reaching
the forge, without holding a forge credential, and without gaining any other access to the forge in
the process.**

## Acceptance

- A task names a commit and gets back a verdict - queued, running, success, failure, or cancelled -
  for the build of that commit, and a task whose work has not yet left the gate is told that,
  distinctly from a build that has not started.
- The reason for a failure is available inside the task as text, for the job that failed, and
  arrives decompressed.
- No forge credential is in the container: not in a variable, not in a file, not on a command line,
  and not behind a token that could be presented anywhere but here.
- The container gains no other forge access. A request for anything but a verdict or a log is
  refused, and the refusal names what was asked for.
- No host the helper talks to appears in the task's egress report as something the task can reach -
  the second class of host [B28](B28-More-Than-One-Credential-In-A-Task.md) names.
- A rate limit, an expired token and a missing build are three different answers, and none of them
  is reported as an authentication failure.
- A wait ends: at a deadline, on a terminal verdict, or when the task stops. Nothing waits forever.
- The record says which builds a task asked about and what it was told, and it outlives the task.
- An offline project refuses this before the container exists rather than waiting on a build that
  cannot exist.
- Nothing is asked of an agent that does not use it.

## To be checked

- **Whether the build being waited for is the task's own push at all.** In `guarded` it cannot be
  until a person approves, which makes this a feature about waiting on a human. If the real need is
  "watch the build of what I just pushed", it is an `online`-class feature; if it is "watch the
  build of what was approved", it is a gate feature and possibly belongs to the gate rather than to
  a task. Everything below depends on this answer.
- **Route or reduced surface**, argued above. The tie-break is likely whether any forge's status and
  log can be expressed as data with no per-forge code, on two forges rather than one - if the second
  one needs code, so will the fifth.
- **Whether a leaked secret in a log ends up in Sokar's own record.** The scrubbing belongs to
  the build server, which is where the values are known; what is this file's is that a log passing
  through a helper of ours must not be written somewhere more durable than the task that asked for
  it, and must not be delivered where the gate would carry it out again.
- **Whether lowering what an injection is worth is a requirement of its own.** It is not this
  file's - a task reads text nobody here wrote whatever this feature does - and the answer that
  exists is spread across the firewall, the phantom token, the gate and the clearance prompt
  without ever being stated as one property with named limits. If it becomes one, this file
  delivers its log through it rather than arguing about it, and an agent's fencing convention
  belongs in that agent's definition.
- **Whether an agent asked to watch a build should block or return.** Blocking is simpler for the
  agent and holds a turn open for as long as a build takes; returning makes the agent poll, which is
  the cost this file just argued against.
- **Whether this is one credential or two.** Reading Actions and reading a private repository's log
  artifacts may need different scopes on some forges, and a credential that turns out to be two is
  B28's problem arriving inside its own first consumer.
- **Whether it is worth building before an interface exists.** A verdict and a failing log are
  something a person wants on a screen at least as much as an agent wants them in a container, and
  [B14](B14-Talking-Between-Tasks.md) argues that being early to a client's need is a reason to be
  slower rather than faster.
