# Base Requirements

What the product must do below the interface: the CLI, the daemon and the guarantees they make.
One file per requirement, each carrying its own acceptance criteria so it can be judged done or
not done.

**Open question** means the file ends with a *To be checked* section: something unresolved whose
answer could change what the requirement says, or whether it survives at all.

## Work, in the order to do it

Ordered by consequence, not by number; the number is only the file's identity. The first is live
on somebody's machine today. The last is a second platform, which is a project rather than a
feature.

| # | Requirement | What must be true | Open question |
|---|---|---|---|
| B01 | [Refreshable Task Tokens](B01-Refreshable-Task-Tokens.md) | An agent that renews an expiring credential must not be broken by holding a task-scoped one. | yes |
| B02 | [Clearance Prompts](B02-Clearance-Prompts.md) | A blocked destination becomes a question with enough context to answer it, and the answer reaches the waiting task. | yes |
| B03 | [Credential Management](B03-Credential-Management.md) | Credentials are stored, listed and used without a value ever being displayed, logged or copied. |  |
| B04 | [Egress Sets Editor](B04-Egress-Sets-Editor.md) | What a project may reach is readable and editable, with the effect of a change visible before it is applied. | yes |
| B05 | [Health And Diagnostics](B05-Health-And-Diagnostics.md) | The machine reports whether it can actually run a task, naming anything missing or misconfigured. | yes |
| B06 | [Remote Access](B06-Remote-Access.md) | Tasks on another machine are usable over an encrypted tunnel, without the daemon ever binding a network port. | yes |
| B07 | [Recovery And Panic](B07-Recovery-And-Panic.md) | A task that has gone wrong can be isolated for inspection, and everything can be stopped at once. | yes |
| B08 | [McSokar Apple Containers](B08-McSokar-Apple-Containers.md) | A sibling project offering the same behaviour on Apple Containers, with one client that connects to either host. | yes |

## What was here and is finished

Five requirements have been met and retired. Their files are gone; what each measured is in
[AGENT.md](../../doc/AGENT.md), where it will be read again:

- **The local daemon API.** `sokard` serves the domain over an owner-only varlink socket, and the
  CLI and the daemon reach it through the same objects, so neither can grow a behaviour the other
  lacks. The two questions it left are part of [B06](B06-Remote-Access.md).
- **Task lifecycle control.** List, stop and resume, in the CLI and over the socket.
- **The git endpoint.** It bound every interface and sat on the operator's network; it now binds
  loopback, measured unreachable from a second machine.
- **The workspace outliving its container.** Cleanup used to destroy work while reporting
  success. The question it left - what the agent installed *inside* a container, which is lost
  with it and warns nobody - is part of [B07](B07-Recovery-And-Panic.md).
- **What a project may reach.** Curated egress sets, declared per project.

## To be checked

Two open questions are worth knowing before any of this is planned in detail, because each
changes what gets built rather than only how:

- Whether the remote transport can carry the daemon's socket directly
  ([B06](B06-Remote-Access.md)). It decides the transport posture, whether notifications are
  achievable away from the machine, and how much of
  [F20](../frontend/F20-Access-From-Elsewhere.md) is real.
- Whether the guarantees can be re-derived at all on a second platform
  ([B08](B08-McSokar-Apple-Containers.md)). It decides whether that project offers the same
  product or a weaker one wearing the same name, and it constrains what may be added to the
  daemon's contract.
