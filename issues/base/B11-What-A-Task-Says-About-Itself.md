# B11 — What A Task Says About Itself

**Status:** built. Every field is on the contract - agent, mode, prompt, branch, since,
activity and what it is waiting for - and arrives through `Watch` on the same terms as the
rest. One question is left, and it is about what a resumed task reports rather than about
whether a task says anything.

A `Task` says what it is called, whose project it belongs to, what class it runs under, whether
the runtime says it is up, and how many helpers are alive. That is enough to list tasks and not
enough to answer anything anybody asks about one.

The gap is not a missing method. Every field below is a reply field on an existing type, which
under the compatibility promise is the cheap kind of change: `Tasks1` may grow, and nothing that
reads it today breaks. No `Tasks2`.

What is missing shows up as questions an interface cannot answer without guessing:

- **Which agent is this running?** Not recorded. Two tasks in the same project under the same
  class are indistinguishable, and `Agents` lists what is installed rather than what is in use.
- **How is somebody meant to be involved in it?** Driving it by hand, working through a richer
  session, or leaving it to run unattended are different things to walk up to, and nothing says
  which this is.
- **What branch is it working on?** The gate knows; the task does not say. So "what has this done
  to the repository" starts by asking the operator what it was pointed at.
- **How long has it been like this?** `state` carries the runtime's own words — *"Up 4 minutes"*,
  *"Exited (143) 2 seconds ago"* — and the contract says plainly not to parse them. Correct, and
  it leaves *"idle for forty minutes"* unanswerable by anything but parsing them.
- **Is it working, idle, waiting for a person, or dead?** `running` is the container's state, not
  the work's. A task waiting on a question nobody saw and a task grinding through a build are both
  `running: true`, and that difference is the one that costs an afternoon.

The last is the one that matters most. Work blocked on a question nobody noticed is
indistinguishable from work that is merely slow, and an unattended run that spends the night
waiting is the failure this product exists to prevent.

## Acceptance

- A task says **which agent** is running in it, by the name `Agents` reports, so that what is
  installed and what is in use can be told apart.
- A task says **how a person is meant to be involved in it** — driven by hand, worked with through
  a session, or left to run unattended — in the same terms the thing that started it used.
- An unattended task says **what it was asked to do**, and keeps saying it after it has finished.
- A task says **what branch it works on**, so its effect on the repository can be found without
  asking the operator what they pointed it at.
- A task says **when its current state began**, as a timestamp rather than as words, so how long
  it has been that way is answerable by arithmetic and not by parsing prose.
- A task says whether it is **working, idle, waiting for a person, or dead**, as a value beside
  `state` rather than instead of it. `state` stays exactly as it is: the runtime's own words, for
  a person, never parsed.
- **Waiting is detected from the work's own signals**, not inferred from a timeout. A quiet task
  is not a waiting one, and an interface that guessed would be wrong in the direction that costs
  the most.
- **Idle is distinguished from finished**, and a task whose container died reads as dead rather
  than as idle.
- Everything above arrives through `Watch` on the same terms as the rest: when it changes, and not
  when a clock moves.

## Notes

Asked for by the interface, where four requirements are short of it and none is short of a method:

- **F10 Task Inspection And Work Handover** asks for a task's agent, its mode, and how long it has
  been in its state. Its handover half is built and its inspection half cannot be.
- **F22 Task State Visibility** is entirely this. Its own note says it: *"F22 is not blocked on a
  missing method. It is blocked on missing fields."*
- **F08 Task Creation And Modes** asks that a finished unattended run can be continued with a new
  prompt, which needs the first prompt to have been kept.
- **F02 Project Overview** wants to say whether a project needs attention, which is a question
  about the work under it.

**Modes are not only a field.** `Start` has no `mode` and no `prompt` parameter either, so this
half and that half are the same decision: what the product calls the ways of being involved, taken
once and used by both. Settling `Task.mode` without settling `Start(mode:)` would name the same
thing twice.

**Adding a value is not a breaking change and adding a field is not either** — that is stated at
the top of the IDL, and it is why this is worth asking for now rather than after several clients
exist. An unrecognized activity value renders rather than throwing, by the same rule that already
covers `Outcome`.

## Measured, 2026-09-11: how much of *waiting* is visible from outside

The question was whether "waiting for a person" can be detected per agent at all, and the answer is
that the two honest options are both needed rather than one.

*What the outside route covers, and it needs nothing new.* The agent's own output already reaches
the host: `TaskLaunch` hands `runAgent` a `task.log` in the container's state directory, and
`podman` writes it there as the agent runs. Sampled every three seconds against a real omp turn on
the ubuntu VM, the file grew on **every** sample - 0, 670, 35314, 63905 bytes in twelve seconds,
never older than two. So **quiet is not "working silently"**: for this agent, in this mode, output
arrives continuously while it works. That gives *working* against *not producing output* for any
agent at all, with no per-agent knowledge and no new way out of the container - the host reads a
file it already owns.

*What the outside route does not cover.* Quiet is quiet. It cannot tell **waiting for a person**
from finished, stuck, or rate-limited, and this file's own warning stands: a quiet task is not a
waiting one, and guessing is wrong in the direction that costs.

*So the per-agent flag is needed after all, but only for one value.* The agent-repositories agent
measured the shipped artifacts on 2026-09-11: `claude` and `pi` each carry an event for it - Claude
Code's `Notification` and Pi's `ui_prompt_start`/`_end`, the latter documented for exactly this -
and `omp` 18.1.13 has none. So the honest shape is **`working` and `idle` observed from outside for
everybody, and `waiting` declared per agent** - absent where the agent cannot say it, shown as
*"this agent cannot tell us"* rather than as a guess.

*One thing that has to cross, and does not.* Every per-agent signal fires **inside** the container.
Giving one a path to the host would be a fourth way out beside the vault socket, the ssh-agent
socket and the gate, and it would invert the direction this design rests on - a channel the agent
writes into. A status value does not justify that. Whether `waiting` can be had at all therefore
depends on something the host can ask for rather than be told, which is what
[B47](B47-What-The-Agent-Is-Doing-Read-From-Outside.md) took up on 2026-09-11: the output the host
already writes, read against patterns the agent's own package declares.

**Nothing agent-independent delivers this, including the tool it was learnt from.** AI Beacon
reports *awaiting permission* from a plugin that knows Claude Code, not from the wrapper that
observes the process.

**An agent stopping to ask is a defect in that agent's first-run handling**, not a state an
interface should learn to show. Inside a task the agent's own prompts are turned off - the container
is the answer - and the measured outcome was a CLI reaching its prompt with no dialog at all. What
remains is the clearance decision, which this machine *knows* rather than infers: the resolver
raised it, `Prompts` streams it, and it carries a deadline. The residue worth watching is an agent
that ships a new dialog, and each agent repository's acceptance covers that with the kit's step
*"the … agent reaches work without being asked anything"*, against the ready marker the agent
declares.

## To be checked

- **Does the mode survive a restart?** `Resume` brings a container back; whether it comes back as
  the same kind of thing decides whether the field is recorded once or re-derived, and an
  interface that showed a resumed task as a different mode from the one it was started as would
  be reporting a change nobody made.
