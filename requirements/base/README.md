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
| B04 | [Egress Sets Editor](B04-Egress-Sets-Editor.md) | What a project may reach is readable and editable, with the effect of a change visible before it is applied. | yes |
| B05 | [Health And Diagnostics](B05-Health-And-Diagnostics.md) | The machine reports whether it can actually run a task, naming anything missing or misconfigured. | yes |
| B06 | [Remote Access](B06-Remote-Access.md) | Tasks on another machine are usable over an encrypted tunnel, without the daemon ever binding a network port. | yes |
| B08 | [McSokar Apple Containers](B08-McSokar-Apple-Containers.md) | A sibling project offering the same behaviour on Apple Containers, with one client that connects to either host. | yes |

## What was here and is finished

Nine requirements have been met and retired. Their files are gone; what each measured is in
[AGENT.md](../../AGENT.md), where it will be read again:

- **The local daemon API.** `sokard` serves the domain over an owner-only varlink socket, and the
  CLI and the daemon reach it through the same objects, so neither can grow a behaviour the other
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
