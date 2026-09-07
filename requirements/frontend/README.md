# Frontend Requirements

The interface that replaces the current one, described as what must be true for a
person using it — not how it is built. One file per requirement, each carrying its own
acceptance criteria so it can be judged done or not done.

**Open question** means the file ends with a *To be checked* section: something
unresolved whose answer changes what the requirement can promise.

Files here are numbered with an `F` prefix; the number is identity, not order. The
order to build them in is the table below.

**This is the whole of the interface.** The central index carries one row for all of it, so that
this set can be worked on separately - and moved to a repository of its own when that is worth
it, the way each agent already has been. A few of these files narrow a requirement that is still
central because it constrains the daemon or the domain rather than the interface; where they do,
they link to it rather than restating it.

## Work, in the order to do it

The first group is the frame. Nothing else can be judged until a person can find their
way around, so it comes first even though it delivers no capability on its own. The
second group is the daily loop — start work, watch it, get its output out — which is
what the interface is for. The third is the standing configuration people touch
weekly rather than hourly. The last is the safety surface, which is last only because
it is judged against the rest, not because it matters least.

| # | Requirement | What must be true | Open question |
|---|---|---|---|
| F01 | [Application Shell](F01-Application-Shell.md) | Projects and their work are two selections apart, every action is reachable by keyboard, and one command finder names everything the product can do. | |
| F02 | [Project Overview](F02-Project-Overview.md) | Every project on the machine is listed with enough state to decide whether it needs attention, without opening it. | |
| F13 | [Operation Feedback And History](F13-Operation-Feedback-And-History.md) | Long operations never block or disturb the interface, and every one started in a session can be reopened with its output. | |
| F08 | [Task Creation And Modes](F08-Task-Creation-And-Modes.md) | Work is started with a project, an agent and a mode, and finished unattended work can be continued with a new prompt. | |
| F09 | [Task Control](F09-Task-Control.md) | Running work can be stopped, restarted, recreated, renamed and deleted, each named by its consequence. | |
| F11 | [Live Log Viewing](F11-Live-Log-Viewing.md) | Everything work produces is readable inside the interface, live, with structure visible. | |
| F10 | [Task Inspection And Work Handover](F10-Task-Inspection-And-Work-Handover.md) | What a piece of work is and what it did to the repository is visible, and its changes leave the interface in one action. | |
| F12 | [Interactive Session Attach](F12-Interactive-Session-Attach.md) | An interactive session is one action away, and the way back is reliable. | |
| F03 | [Project Environment Preparation](F03-Project-Environment-Preparation.md) | A project is made runnable from the interface, with rebuild depths distinguished by what each replaces and what it costs. | |
| F04 | [Guided Project Creation](F04-Guided-Project-Creation.md) | A new project is described, checked, reviewed and created without leaving the interface. | |
| F05 | [Project Configuration](F05-Project-Configuration.md) | Agents, hardware and reachable destinations are set per project, with open-ended and explicit selections never confused. | |
| F07 | [Instruction Management](F07-Instruction-Management.md) | Standing instructions are editable at both levels, and the combined result is viewable before anything runs. | |
| F06 | [Upstream Synchronisation And Backups](F06-Upstream-Synchronisation-And-Backups.md) | Falling behind the upstream is visible, syncing is one action, and snapshots can be listed, restored and deleted. | |
| F14 | [Authentication Flows](F14-Authentication-Flows.md) | Agents and providers are authenticated from the interface without a secret ever being displayed or logged. | |
| F15 | [Secret Store Control](F15-Secret-Store-Control.md) | The protected store's state is visible and changeable, and its recovery secret is revealed once and acknowledged. | |
| F16 | [Access Key Routing](F16-Access-Key-Routing.md) | Which keys reach which projects is answerable in both directions from one view, and editable there. | |
| F17 | [Network Exposure Control](F17-Network-Exposure-Control.md) | What running work may reach is changeable while it runs, and refusals are watchable and answerable live. | |
| F19 | [Host Readiness And Remediation](F19-Host-Readiness-And-Remediation.md) | The interface establishes whether the machine can run anything and offers the fix in place. | |
| F18 | [Emergency Stop](F18-Emergency-Stop.md) | One always-visible action cuts every form of access at once and says what state it left behind. | |
| F21 | [Continuity And Updates](F21-Continuity-And-Updates.md) | Closing, reopening or updating the interface never disturbs running work. | |
| F20 | [Access From Elsewhere](F20-Access-From-Elsewhere.md) | The interface is reachable from another device, authenticated, and off unless deliberately started. | yes |
| F22 | [Task State Visibility](F22-Task-State-Visibility.md) | Working, idle and waiting are told apart, with a timestamp, for every piece of work at once. | yes |
| F23 | [Notifications](F23-Notifications.md) | A decision waiting inside a closed window still reaches the person. | yes |
| F24 | [Agent Inventory](F24-Agent-Inventory.md) | What is installed, what it may reach and which build it pins are all visible, and nothing names a specific agent. | |
| F25 | [Task Templates](F25-Task-Templates.md) | A recurring job is startable by name, and a template can never widen what work may reach. | |

## To be checked

One open question, and it is about scope rather than detail: whether the actions that
hand off to something local — attaching to a session, opening an editor, putting
changes on the clipboard — can be honoured from another device at all
([F20](F20-Access-From-Elsewhere.md)). If they cannot, the remote view is a monitoring
and decision surface rather than a full one, and that is worth deciding before it is
built rather than discovering afterwards.
