# B47 — What The Agent Is Doing, Read From Outside (the daemon's half)

**Status:** open, written 2026-09-11, split in two on 2026-09-12.
**This file is Sokar's half**: the manifest field, the two sources of text, the matcher, the
contract and the budget. What each agent must declare and prove is
[A11](../agents/A11-What-An-Agent-Declares-About-Waiting.md), in the agents set, because it is a
fact about one agent's wording and nothing outside `agents/` may name one.

It supplies the one producer [B11](B11-What-A-Task-Says-About-Itself.md) is missing.

## What B11 already settled, and what it could not

B11 asks a task to say whether it is working, idle, waiting for a person, or dead. Three of those
four were measured on 2026-09-11 and need nothing new: the runtime writes `task.log` into the
container's state directory as the agent runs, and sampled every three seconds against a real
turn, the file grew on **every** sample and was never older than two. So *working* against *not
producing output* is answerable for any agent at all, from a file the host already owns.

**Waiting is the one left**, and B11 says why it matters: work blocked on a question nobody
noticed is indistinguishable from work that is merely slow, and an unattended run that spends the
night waiting is the failure this product exists to prevent.

B11 also measured why the obvious route is refused. Every agent that can signal *"I am waiting"*
signals it **inside** the container - a hook, an event, a lifecycle callback - and giving one of
them a path to the host would be a fourth way out beside the vault socket, the ssh-agent socket
and the gate. It would be the first channel an agent *writes into*, which inverts the direction
the whole design rests on.

**The temptation is strong and worth naming.** An agent's own configuration can be made to run a
hook, and a hook that calls back over a socket answers this question exactly, for every agent that
has one, with no reading of anything. That is why it keeps being the first design anybody reaches
for. It is also the one route this design cannot take, and the reason is not squeamishness: the
container would gain a channel it writes into, and every other way out of the box is a channel it
is answered on. Everything below is what can be had without that.

## What the host is actually short of

Not access. **Meaning.** The output is already on this side of the boundary; the daemon simply
cannot tell an agent's question to a person from any other paragraph the agent printed. That
distinction is a fact about one agent's wording, and agent-specific facts already have a home: the
agent package declares them, the way `resume_flag` is declared rather than known.

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
that ship and version with the agent they describe. The result is one value on the contract, so an
interface *reads* an answer instead of inventing one, and two interfaces cannot disagree about the
same task. And it is marked as derived, so nothing presents a guess as an observation. F28 keeps
its refusal and gets what it wanted from it.

## The two sources are not equally hard, and that decides the order

`task.log` exists for an **unattended** run and only for that one: `TaskLaunch.runAgent` writes it
(`app/.../TaskLaunch.java:450`), and it starts the agent with `machineReadable = true`, so the
agent's own machine-readable output flags are on the command line
(`HeadlessCommandBuilder`, `agents/api/.../HeadlessCommandBuilder.java:58`). **An unattended run's
log is therefore already a stream of JSON records rather than prose.** A declared *event* is a
far better thing to match than a declared sentence, and matching one needs no terminal emulation,
no regular expression and no budget argument.

An **attached** run writes nothing here at all. `task attach` runs tmux
(`app/.../TaskAttachCommand.java:81`), the bytes go to the person's terminal, `task.log` does not
exist, and `TaskInventory.activityOf` correctly reports `UNKNOWN`
(`app/.../TaskInventory.java:267`). That half needs a source of text that does not exist yet, and
it is the half B47 calls the harder and more valuable one.

So this is built in two stages, and the first is worth having on its own.

## Stage 1 — the unattended run, from records

1. **A `waiting:` block in the agent manifest**, read into `AgentDefinition`
   (`agents/api/.../AgentDefinition.java`), through `AgentDefinitionReader` and
   `AgentDefinitionJson`, and shown by `sokar agents --verbose` the way `refused_domains` is. The
   reader maps known keys by hand and ignores what it does not know, so an agent package may
   declare this **before** the daemon reads it: this is not a cut and nothing goes red in either
   order. A11 owns the block's contents; this file owns its existence and its limits.
2. **A matcher over the records the log already holds.** Each line is a JSON object; a declaration
   names a field path and a value, or a small set of them. No regular expression is involved, and
   an agent that emits records is answered exactly.
3. **The derived value on the contract**, beside `activity` rather than inside it - see *What goes
   on the wire* below.
4. **An agent that declares nothing says so**, and that is distinct from *not waiting*.

Measured signals exist for two of the three shipped agents today; A11 carries them.

## Stage 2 — the attached run, from the screen

This is the part with new machinery, and most of its questions have answers.

1. **The text must be the rendered screen, not the byte stream.** Matching raw bytes was never
   going to work: one sentence arrives as several writes interleaved with escape sequences, and a
   redraw rewrites a line that was already matched. Something has to reduce the writes to the text
   that is actually on the screen first - the last few non-empty lines of the physical buffer,
   rather than whatever the person has scrolled to.
2. **Sokar may not need an emulator.** The attached session already runs under tmux, and tmux has
   rendered that screen for its own reasons. `tmux capture-pane -p` returns the text as it stands.
   The alternative is `pipe-pane` plus writing that reduction here, which means owning a terminal
   emulator. **Measure both before choosing**: what capture-pane costs per call, and
   whether it can be issued for a session the person is attached to without disturbing them. If
   capture-pane holds, stage 2 costs a scheduled call and no new subsystem.
3. **Regions, not whole screens.** A declaration should name a region before it names text - the
   prompt box body, the last N non-empty lines, the window title. That is what keeps a pattern
   from firing on an agent quoting its own prompt back, and it is most of what keeps a rule short
   enough to read. The smallest useful set is: the whole recent text, the last N non-empty lines,
   and the prompt box body.
4. **A polling interval and a skip.** A few hundred milliseconds per pane is a multiplexer's
   budget and not a daemon's. Sokar reads on the schedule its own overview already uses, and skips
   the read entirely when nothing has been written since the last one.
5. **Window title escape sequences are a signal, not noise.** An agent that sets its terminal
   title - OSC 0, 2 or 9 - is telling the outside what it is doing without anybody adding a hook,
   and the sequence rides the stream that already crosses the boundary. For at least one shipped
   agent the title carries a working indicator that is more reliable than anything in the body of
   the screen. Cap what is read from it; a title is attacker-adjacent text like any other.

## The engine question, which is no longer open in the same way

B47 asked what shape a declared pattern may be, and named three candidates: a linear-time engine,
anchored literals, or a hard time bound. **The first two together are the answer, and they make
the third unnecessary.** With an engine that cannot backtrack, a pattern's cost is linear in the
input, and a static bound on the *declaration* - a limit on the number of rules, the nesting
depth, the number of matchers and the length of each, all rejected when the manifest is read -
leaves nothing a pattern can do to earn a timeout. A bound checked at load is also a refusal
somebody can act on; a timeout is a slow task nobody notices.

**Java does not have that engine in its standard library.** `java.util.regex` backtracks, and
every regular expression in this repo today is one an author here wrote
(`UnhandedWork.java:197`, `VaultProxy.java:81`). A pattern that arrives from a package through the
update pipeline is a different thing, and B47 already names it: a denial of service shipped as
data. Three ways out, and this has to be ruled before stage 2 reads its first pattern:

- **Anchored literals only.** Case-insensitive substring and line-prefix matching, no expressions
  at all. Enough for every rule A11 needs today, and it cannot be made expensive.
- **A linear-time engine as a dependency.** `com.google.re2j` is the usual answer: RE2 semantics,
  no backtracking. One dependency, and the declaration may then be expressive.
- **A time bound per match**, which needs an interruptible matcher and is the weakest of the three
  because it turns a pathological pattern into a slow task rather than a refused declaration.

**Recommendation: literals for stage 1 and stage 2's first version, and re2j only when a declared
rule genuinely needs more.** The rules the shipped agents actually need are short - for one of
them a single literal - and a dependency taken before anything needs it is a dependency taken for
a rule nobody has written.

## What goes on the wire

A new field beside `activity`, never folded into it. `activity` stays what B11 fixed it as, and
`state` stays the runtime's own words for a person. The new value carries three things a client
needs to render honestly: whether the agent is **waiting**, what it is **waiting for** where the
declaration says so, and **where the answer came from** - a declared record, a declared screen
match, or nothing because the agent declares nothing. `Watch` reports it when it changes, on
B48's terms, not on a clock.

Four values, and the fourth is the point: *waiting*, *not waiting*, *cannot say because this agent
declares nothing*, and *cannot say because nothing here can see the output*. **The tempting
simplification is to collapse the third into idle** - the agent is known, its rules matched
nothing, call it idle and record why. **Do the opposite**, because B11 already distinguishes
not-knowing from knowing-a-negative: *"this agent cannot tell us"* is an honest screen; *"not
waiting"* from an agent that was never able to say is a lie with a timestamp on it.

## What must be true

1. **Waiting is derived from the agent's own output, read where the host already writes it.**
   Nothing new crosses out of the container, and no agent gains a channel it can write into.
2. **What waiting looks like is declared by the agent package** (A11). Sokar carries no agent's
   wording, and `grep` over everything outside `agents/` proves it.
3. **A derived state is marked as derived** and is never mixed with what the runtime observed.
4. **An agent that declares nothing reports that it cannot say**, not that the task is fine.
5. **Only a declared match produces `waiting`.** Quiet is quiet: a task that stopped producing
   output and matched nothing is idle. A timeout must never be allowed to graduate into `waiting`.
6. **A declaration that has stopped matching is visible.** An agent's wording changes when its CLI
   version changes, and A02 moves that version without being asked. A task whose agent declares
   patterns, has run for a long time, and has never matched any of them is a broken declaration
   rather than an agent that never waits - and the machine says so rather than reading `UNKNOWN`
   forever in silence. A11 carries the other half of this: the agent's own repository proves its
   declaration still matches, at the version it pins.
7. **A declaration cannot cost the daemon its health.** The bound is on the declaration, checked
   when it is read, and not on the good behaviour of whoever wrote it.
8. **The derived state arrives through `Watch` when it changes**, not when a clock moves.
9. **A screen that is not the agent's live interface freezes the state rather than replacing it.**
   A transcript viewer or a pager open over the agent's own screen is still the agent's pane, and
   reading it reports on the pager. A declaration needs a way to say *this is not the agent's
   screen* and publish nothing, or opening a pager reports the agent idle.

## Acceptance criteria

- **Stage 1:** an unattended agent is driven to the point where it waits on a person. Within a
  bounded time the task reports `waiting` over the contract, without anything inside the container
  having sent anything.
- **Stage 2:** the same, asserted **at a terminal**, because a terminal's redraws are what makes
  the detection hard and a test that only covers the easy mode proves the wrong half.
- The same agent, working, never reports `waiting`; and the same agent, quiet and finished,
  reports idle rather than waiting. Both asserted, because the second is the expensive mistake.
- An agent whose definition declares nothing reports that it cannot say, and a test asserts the
  value is distinguishable from *not waiting*.
- Changing a declared pattern in one agent's YAML, and nothing else, changes what that agent
  detects. `grep` over everything outside `agents/` finds no agent's wording.
- A deliberately pathological declaration does not stall the daemon: the bound from point 7 is
  asserted against it, by a test that fails if the bound is removed.
- A task whose agent declares patterns and has never matched one is reported as a broken
  declaration rather than as a task that never waits.

## To be checked

- **Can `tmux capture-pane -p` be issued against a session somebody is attached to, cheaply and
  without disturbing them?** If yes, stage 2 needs no terminal emulation at all. If no, the
  fallback is `pipe-pane` plus a reduction here, which is a subsystem rather than a call.
- **Which engine** - the ruling above is a recommendation, not a decision, and it belongs to
  whoever builds the matcher.
- **How long may a task be `waiting` before something else happens?** This requirement only makes
  the state visible. Whether an unattended run that has waited for hours should be reported
  somewhere a person is actually looking belongs where attention is already collected - B48.
- **Does the unattended JSON stream actually carry a waiting record for any shipped agent?** A11
  measured the events each agent can emit; whether one of them reaches `task.log` in
  `--print`/`-p` mode is measured there, and stage 1 is worth building only for the agents where
  it does.
