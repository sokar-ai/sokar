# Requirements

One file per requirement, ordered here by importance rather than by number. A
requirement describes what must be true, not how to build it; each file carries its
own acceptance criteria so it can be judged done or not done.

The first is a prerequisite for most of the rest. **Open question** means the file
ends with a *To be checked* section: something unresolved whose answer could change
what the requirement says, or whether it survives at all.

| # | Requirement | What must be true | Open question |
|---|---|---|---|
| 1 | [Local Daemon API](0001-Local-Daemon-API.md) | Everything the interface can do is exposed by a local daemon over a private socket, so no feature depends on shelling out to the CLI. | yes |
| 2 | [Fleet Overview](0002-Fleet-Overview.md) | One screen lists every task on the machine with its project, agent, state and age, so a person with several running tasks can see all of them at once. |  |
| 3 | [Task State Detection](0003-Task-State-Detection.md) | Each task reports whether it is working, idle, or blocked waiting for a person, so an unattended run that has quietly stopped is visible. | yes |
| 4 | [Clearance Prompts](0004-Clearance-Prompts.md) | Allow and deny decisions appear in the interface with enough context to answer them, and the answer reaches the waiting task. | yes |
| 5 | [Review And Approve Work](0005-Review-And-Approve-Work.md) | Work pushed by a task is listed, diffed and approved or rejected from the interface, without dropping to a terminal. |  |
| 6 | [Task Log Viewer](0006-Task-Log-Viewer.md) | Every log a task produces is readable in the interface, live, with the ability to follow or search it. | yes |
| 7 | [Attach To A Task](0007-Attach-To-A-Task.md) | A person can get an interactive shell inside a running task from the interface, in a real terminal emulator. | yes |
| 8 | [Start A Task](0008-Start-A-Task.md) | A task can be started from the interface, choosing project, agent, mode and credential type, without typing a command. |  |
| 9 | [Task Lifecycle Control](0009-Task-Lifecycle-Control.md) | Tasks can be listed, stopped and resumed as first-class operations rather than by reaching for the container runtime. |  |
| 10 | [Notifications](0010-Notifications.md) | The interface notifies outside itself when a task needs a person or has finished, so nobody has to watch it. | yes |
| 11 | [Agent Inventory](0011-Agent-Inventory.md) | Installed agents, their versions, what they may reach and what is pinned are all visible in the interface. |  |
| 12 | [Credential Management](0012-Credential-Management.md) | Credentials are stored and listed from the interface without ever displaying, logging or copying a value. |  |
| 13 | [Egress Sets Editor](0013-Egress-Sets-Editor.md) | The destinations a project may reach can be read and edited in the interface, with the effect of a change visible before it is applied. | yes |
| 14 | [Project Setup Wizard](0014-Project-Setup-Wizard.md) | A new project can be described, checked and made runnable from the interface, including the parts that are easy to get wrong. |  |
| 15 | [Health And Diagnostics](0015-Health-And-Diagnostics.md) | The interface reports whether the machine can actually run a task, naming anything missing or misconfigured. |  |
| 16 | [Repository Context](0016-Repository-Context.md) | Each task shows what it has done to the repository: branch, commits, files changed, and whether anything is waiting for review. |  |
| 17 | [Remote Access](0017-Remote-Access.md) | The interface can drive tasks on another machine over an encrypted tunnel, without the daemon ever binding a network port. | yes |
| 18 | [Mobile Client](0018-Mobile-Client.md) | A phone can monitor tasks, answer decisions and stop a run, sharing the codebase with the desktop interface. | yes |
| 19 | [Task Templates](0019-Task-Templates.md) | Common jobs are startable as named templates carrying their own prompt and settings, rather than retyped each time. |  |
| 20 | [Narrow The Git Endpoint](0020-Narrow-The-Git-Endpoint.md) | The host endpoint a task clones from should not be reachable from the local network. | yes |
| 21 | [Second Agent](0021-Second-Agent.md) | A second agent is packaged and installed alongside the first, proving that adding one needs no change to anything else. |  |
| 22 | [Recovery And Panic](0022-Recovery-And-Panic.md) | A task that has gone wrong can be isolated for inspection, and everything can be stopped at once. |  |
| 23 | [SokarMc Apple Containers](0023-SokarMc-Apple-Containers.md) | A sibling project offering the same behaviour on Apple Containers, with one client that connects to either host. | yes |

## To be checked

Ten of these carry a **To be checked** section at the end: an open question whose
answer changes what the requirement should say, or whether it survives at all. Three
are worth knowing about before any of this is planned into an order:

- Whether a task can report that it is **waiting for a person** at all, for every
  agent ([0003](0003-Task-State-Detection.md)). Several screens assume it can.
- Whether the remote transport can carry the daemon's socket directly
  ([0017](0017-Remote-Access.md)). It decides the transport posture, whether
  [0010](0010-Notifications.md) is achievable away from the machine, and how much of
  [0018](0018-Mobile-Client.md) is real.
- Whether the untried approach in [0020](0020-Narrow-The-Git-Endpoint.md) works. The
  last attempt reported success while silently disabling outbound filtering, so this
  one is verified by the acceptance suite or not at all.
- Whether the guarantees can be re-derived at all on the second platform in
  [0023](0023-SokarMc-Apple-Containers.md). It decides whether that project offers
  the same product or a weaker one wearing the same name, and it also constrains how
  [0001](0001-Local-Daemon-API.md) may be written.
