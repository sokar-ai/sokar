# B47 — What The Agent Is Doing, Read From Outside

**Status:** open, written 2026-09-11, and covering a task somebody drives at a terminal as well
as an unattended run - decided on 2026-09-11. It supplies the one producer
[B11](B11-What-A-Task-Says-About-Itself.md) is missing, and it answers the question B11 left
unsolved rather than re-opening it.

## What B11 already settled, and what it could not

B11 asks a task to say whether it is working, idle, waiting for a person, or dead. Three of those
four were measured on 2026-09-11 and need nothing new: the agent's output already reaches the
host - the runtime writes `task.log` into the container's state directory as the agent runs - and
sampled every three seconds against a real turn, the file grew on **every** sample and was never
older than two. So *working* against *not producing output* is answerable for any agent at all,
from a file the host already owns.

**Waiting is the one left**, and B11 says why it is the one that matters: work blocked on a
question nobody noticed is indistinguishable from work that is merely slow, and an unattended run
that spends the night waiting is the failure this product exists to prevent.

B11 also measured why the obvious route is refused. Every agent that can signal *"I am waiting"*
signals it **inside** the container - a hook, an event, a lifecycle callback - and giving one of
them a path to the host would be a fourth way out beside the vault socket, the ssh-agent socket
and the gate. It would be the first channel an agent *writes into*, which inverts the direction
the whole design rests on. A status value does not justify that, and B11 stopped there:

> whether `waiting` can be had at all therefore depends on something the host can ask for rather
> than be told, and that is unsolved.

## What the host is actually short of

Not access. **Meaning.** The output is already on this side of the boundary; the daemon simply
cannot tell an agent's question to a person from any other paragraph the agent printed. That
distinction is a fact about one agent's wording, and agent-specific facts already have a home:
the agent package declares them, the way `resume_flag` is declared rather than known.

So the shape is: *the agent package says what waiting looks like in its own output; the daemon
reads the output it already has; the derived state goes on the contract once.* Nothing crosses out
of the container that does not cross today, and nothing outside `agents/` names an agent.

## Why this is not the screen-parsing that was refused

F28 refuses parsing the screen, and that refusal stands. It is about the **interface**: a client
that scrapes the terminal it renders re-derives the state per client, differently in each, and
breaks on an agent version bump that nobody sees, because nothing declared the thing it was
matching. Every one of those failures comes from *where* the parsing sat, not from the idea that
output carries information.

Doing it once in the daemon is a different thing on every count. The patterns are declared data
that ship and version with the agent that they describe. The result is one value on the contract,
so an interface *reads* an answer instead of inventing one, and two interfaces cannot disagree
about the same task. And it is marked as derived, so nothing presents a guess as an observation.
F28 keeps its refusal and gets what it wanted from it.

## What must be true

1. **Waiting is derived from the agent's own output, read where the host already writes it.**
   Nothing new crosses out of the container, and no agent gains a channel it can write into. This
   holds for a task somebody drives at a terminal as much as for an unattended run - the terminal
   one being the mode a person walks away from, and so the one the state is worth most in.
2. **What waiting looks like is declared by the agent package**, beside `supports_resume` and
   `resume_flag`, in the agent's own YAML. Sokar carries no agent's wording.
3. **A derived state is marked as derived**, and is never mixed with what the runtime observed.
   `state` stays what B11 fixed it as - the runtime's own words, for a person, never parsed - and
   this is a separate value beside it.
4. **An agent that declares nothing reports that it cannot say**, not that the task is fine. B11
   already distinguishes not-knowing from knowing-a-negative, and this is exactly the case it was
   put there for: *"this agent cannot tell us"* is an honest screen; *"not waiting"* from an agent
   that was never able to say is a lie with a timestamp on it.
5. **Only a declared match produces `waiting`.** Quiet is quiet: a task that stopped producing
   output and matched nothing is idle, which is already visibly different from working and already
   gives a person something to walk up to. A timeout must never be allowed to graduate into
   `waiting`; B11's warning is the load-bearing sentence here.
6. **A declaration that has stopped matching is visible.** An agent's wording changes when its CLI
   version changes, and A02 moves that version without being asked. A task whose agent declares
   patterns, has run for a long time, and has never matched any of them is a broken declaration
   rather than an agent that never waits - and the machine should be able to say so rather than
   reading `UNKNOWN` forever in silence.
7. **A declaration cannot cost the daemon its health.** Patterns arrive as data from a package and
   are matched against a stream that grows as fast as an agent talks. What the daemon is allowed
   to spend on matching is bounded by design rather than by the package's good behaviour.
8. **The derived state arrives through `Watch` on B11's terms** - when it changes, not when a
   clock moves - so an overview shows attention where it is needed without anything polling.

## Acceptance criteria

- An agent is driven to the point where it waits on a person. Within a bounded time the task
  reports `waiting`, over the contract, without anything inside the container having sent
  anything. **Asserted at a terminal as well as headless**, because a terminal's redraws are what
  makes the detection hard and a test that only covers the easy mode proves the wrong half.
- The same agent, working, never reports `waiting`; and the same agent, quiet and finished, reports
  idle rather than waiting. Both asserted, because the second is the expensive mistake.
- An agent whose definition declares no patterns reports that it cannot say. A test asserts the
  value is distinguishable from *not waiting*.
- Changing a declared pattern in one agent's YAML, and nothing else, changes what that agent
  detects. `grep` over everything outside `agents/` finds no agent's wording.
- A deliberately pathological declaration - a pattern built to be expensive - does not stall the
  daemon: the bound from point 7 is asserted against it, by a test that fails if the bound is
  removed.

## To be checked

- **What shape are the declared patterns?** A regular expression from a package is code wearing
  data's clothes, and a catastrophic-backtracking pattern in an agent's YAML is a denial of
  service shipped through the update pipeline. The candidates are a linear-time engine, a matcher
  restricted to anchored literals, or a hard time bound per match - and the answer decides point 7
  rather than following from it.
- **~~Does this cover a task somebody is driving by hand?~~ Yes, decided by the operator on
  2026-09-11**, and it is the harder half rather than the optional one. An attached agent renders a
  terminal: the log holds cursor movement and redraws rather than lines, and one sentence may
  arrive as several writes interleaved with escape sequences. So matching raw bytes is not it -
  something has to reduce a terminal's writes to the text currently on the screen before a
  declared pattern is applied to it, and what that costs has to be measured rather than assumed.
  What is no longer in question is whether it is worth doing: an agent waiting at a terminal
  nobody is looking at is precisely the case this requirement exists for, and it is the mode a
  person is most likely to walk away from.
- **Is there a structured route where the output is structured?** Headless mode already emits
  typed events rather than prose, and an event type is a far better thing to declare than a
  sentence. If so, a definition may need to say both - which event in one mode, which wording in
  the other - and that is a bigger declaration than *"a list of patterns"*.
- **How long may a task be `waiting` before something else happens?** This requirement only makes
  the state visible. Whether an unattended run that has waited for hours should be reported
  somewhere a person is actually looking is a separate question, and it probably belongs where
  attention is already collected rather than here.
