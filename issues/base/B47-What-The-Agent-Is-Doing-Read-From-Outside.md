# B47 — What The Agent Is Doing, Read From Outside (the daemon's half)

**Status:** open, written 2026-09-11, split in two on 2026-09-12, and **stage 1 rewritten the
same day** after all three shipped agents were measured: none of them waits in an unattended run,
so there was nothing there to detect. What stage 1 is about now is a run that *ended* with a
question and said so to nobody.
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

## The two sources are not equally hard, and the measurement changed which one this is about

`task.log` exists for an **unattended** run and only for that one: `TaskLaunch.runAgent` writes it
(`app/.../TaskLaunch.java:450`), and it starts the agent with `machineReadable = true`, so the
agent's own machine-readable output flags are on the command line
(`HeadlessCommandBuilder`, `agents/api/.../HeadlessCommandBuilder.java:58`). An unattended run's
log is therefore already a stream of JSON records rather than prose.

An **attached** run writes nothing here at all. `task attach` runs tmux, the bytes go to the
person's terminal, `task.log` does not exist, and `TaskInventory.activityOf` correctly reports
`UNKNOWN`.

## What the measurement found, and why stage 1 is now a different requirement

**Measured against the pinned artifacts on 2026-09-12, all three shipped agents, driven with a
prompt that asks them to put a question to the person and wait. Not one of them waits.**

- **claude** `-p --output-format stream-json --verbose`: `system/init`, two `assistant`, `result`.
  It asked its question and **ended** - `subtype=success`, `stop_reason=end_turn`,
  `terminal_reason=completed`, `num_turns=1`.
- **pi** `--print --mode json`: `agent_settled` is the **last line, at exit**. It marks the end of
  a run, not a wait, whatever its documentation says it is for.
- **omp** `--print --mode json`: no `agent_settled` and no `ui_prompt_*` at all.

So **there is no waiting state to detect in an unattended run**, and a feature built to find one
would have been correct and fired never. What the measurement exposes instead is the failure this
product should actually be afraid of:

> **A run ended early because it had a question, and nothing anywhere says so.**

It looks exactly like a run that finished its work. `Attention` in the interface sorts it under
*stopped* - last, below quiet and unseen, in the same words as a successful run: *"Not running"* -
and the count the window opens on does not include it. **The worst case is today the least visible
one.** That is what stage 1 is for, and it belongs beside *finished*, not beside *waiting*.

## Stage 1 — the last thing a finished run said

**Nothing in the stream distinguishes *ended by asking* from *ended by finishing*.** That is the
finding, and the design has to respect it rather than paper over it: the daemon must not claim to
know which happened. What it can do is carry the material, and separate what it observed from what
it inferred.

1. **The last message of a finished run, on the contract.** This is an observation, not a guess:
   the record is in `task.log`, the run is over, and that message is what the agent said last.
   Today an interface can only send somebody to a log file - which is the thing a listing exists
   to save them.
2. **Whether it was a question, as three values**: *asked*, *did not ask*, and **cannot say**.
   Never two. pi attached says nothing at all when it waits - its question is plain text and the
   chrome around it is the idle screen exactly - so a boolean would report pi as *did not ask*
   while it sits there waiting, which is a statement, and a wrong one. This is the same rule as
   point 4 below, applied one level up, and the interface has both of the other renderings
   already.
3. **Where the answer came from**, carried with it: a rule the agent package declared, or nothing.
   An interface renders a derived value as a guess and an observed one as a statement, and it
   cannot make that choice from the shape of a value.
4. **The rule, where there is one, is declared by the agent package** ([A11](../agents/A11-What-An-Agent-Declares-About-Waiting.md)) -
   the same mechanism stage 2 uses for a screen, applied to a message instead. A11's measurement
   says what each agent can offer here, and for the headless mode today the answer is *nothing*,
   which is a legitimate declaration rather than a gap to be filled with a heuristic.
5. **It clears itself.** The field reflects the last message of the last run, so starting the task
   again with the answer - which is what an interface would offer, `Start` with `now` - replaces
   it. A question with no deadline must not quietly lapse, and it must not need a channel that
   does not exist to be cleared.

**Not a heuristic.** Deciding from the text whether something is a question - a trailing question
mark, a phrase - is exactly the guess this requirement refuses everywhere else, and it would be
wrong most confidently on the agents that need it most. Absent a declared rule the honest answer
is *cannot say*, with the message itself shown so a person can decide in one glance.

**What this needs from the launcher:** nothing new. The run already ends, the log is already
written, and the last message is already in it.

## Pulled, never pushed - the line an agent-side signal must not cross

An agent can be made to write its own state down: pi has prompt-start and prompt-end events, and an
extension subscribing to them can record *asking* / *not asking* as it happens. That is a far
better signal than reading a screen, and it is admissible **only in one shape**.

**The file stays inside the container, and the host reaches in and reads it.** `podman exec` to
read a path in the container's own filesystem is the host pulling, exactly as `capture-pane` is;
the container gains no path outward and cannot make anything happen on this side. Nothing about
the boundary changes.

**What would cross the line, and will look like an obvious simplification later:**

- **A mounted directory**, so the agent writes straight into the host's state directory and the
  read costs nothing. That is a channel the container writes into - the thing this whole design
  refuses - and the saving is one `podman exec`.
- **A socket, a callback, a port.** Same objection, more plumbing.
- **Anything the host acts on without reading it first.** A pull that is really a push because the
  host reacts to a write it did not ask for is the same inversion wearing a filename.

The rule, so it survives somebody refactoring for speed: **the host decides when to look; the
container never decides when the host learns something.** A11 carries the same rule for a hook that
prints into the agent's own output - the moment such a hook needs to reach anything other than
stdout, it has become the refused design.

## The smallest thing upstream could change, and why we build as if it never will

The whole of stage 1's awkwardness is one missing distinction: **a headless run that ends because
it has a question is byte-for-byte a run that ends because it finished**. Everything above works
around that. The agents are where it could simply not be true - two of the three are open source,
and the operator asked for that avenue to be explored on 2026-09-12.

**What to ask for is one field on a record they already emit**, not a new event, not a callback,
not a channel. pi and omp both end a run with a final record (`agent_end`, and pi's `agent_settled`
after it); claude's `result` already carries `subtype`, `stop_reason` and `terminal_reason`. The
ask is a **value on that existing field that means "I stopped because I put something to the
person"**, distinct from finishing. It costs an upstream one enum value and a line where the run
ends, and it needs no agreement about what a question looks like - the agent is the only thing that
knows, and it knows it exactly.

Phrased that way it is also useful to *them*: anything that drives these CLIs unattended - a CI
job, a queue, a wrapper - has the same problem and today has the same non-answer.

**It changes nothing about what gets built here.** Three reasons, and they are the reason this
section is short:

- **A version pins it.** Agents are installed at a pinned version and moved by A02, so a signal
  that lands upstream reaches a task only when somebody moves that pin - and never for the versions
  already in use.
- **It cannot cover claude**, where the most that can be done is to ask. A design that needs every
  agent to have it is a design with a hole in the middle of its roster.
- **The declaration already has room for it.** A11 says what an agent can offer; an agent that
  grows a real terminal reason declares that instead of *nothing*, the daemon reads a record rather
  than guessing, and the same field on the contract goes from *cannot say* to *asked* - which is
  precisely the three-valued shape existing for this.

So: worth proposing, worth pinning when it arrives, and not worth waiting for.

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

**Two fields, for two different questions, and neither folded into `activity`.** `activity` stays
what B11 fixed it as, and `state` stays the runtime's own words for a person.

**A running task: is it waiting?** Whether the agent is **waiting**, what it is **waiting for**
where the declaration says so, and **where the answer came from** - a declared screen match, or
nothing because the agent declares nothing. This is stage 2's value. `Watch` reports it when it
changes, on B48's terms, not on a clock.

**A finished task: what did it say last, and was that a question?** The **last message**, which is
observed; whether it **asked**, as three values, which is derived; and **where that came from**.
This is stage 1's value, and it is carried in the list rather than pushed as an event - a window
opened an hour later has to see it, and `Refresh` is how an interface catches up on everything
that is not pushed.

Four values, and the fourth is the point: *waiting*, *not waiting*, *cannot say because this agent
declares nothing*, and *cannot say because nothing here can see the output*. **The tempting
simplification is to collapse the third into idle** - the agent is known, its rules matched
nothing, call it idle and record why. **Do the opposite**, because B11 already distinguishes
not-knowing from knowing-a-negative: *"this agent cannot tell us"* is an honest screen; *"not
waiting"* from an agent that was never able to say is a lie with a timestamp on it.

## What must be true

1. **Everything here is derived from the agent's own output, read where the host already writes
   it.** Nothing new crosses out of the container, and no agent gains a channel it can write into.
   That holds for a running task's screen and for a finished run's last message alike.
2. **What waiting looks like is declared by the agent package** (A11). Sokar carries no agent's
   wording, and `grep` over everything outside `agents/` proves it.
3. **A derived state is marked as derived** and is never mixed with what the runtime observed.
4. **An agent that declares nothing reports that it cannot say**, not that the task is fine.
   Both fields obey this, and pi is why it is not academic: attached, it says nothing at all when
   it waits, so a boolean would report it as *not asking* while it sits there waiting.
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
9. **A run that ended with a question is as visible as one that is waiting.** It sorts with what
   needs a person rather than under *stopped*, where it currently sits below quiet and unseen, in
   the same words as a run that finished its work.
10. **A screen that is not the agent's live interface freezes the state rather than replacing it.**
   A transcript viewer or a pager open over the agent's own screen is still the agent's pane, and
   reading it reports on the pager. A declaration needs a way to say *this is not the agent's
   screen* and publish nothing, or opening a pager reports the agent idle.

## Acceptance criteria

- **Stage 1:** an unattended agent is driven to ask a question and ends. The task carries **what
  it said last** over the contract, and a listing can show it without anybody opening a log file.
- **Stage 1:** the same run reports whether it asked as one of three values, and **an agent with
  no declared rule reports *cannot say*** - asserted to be distinguishable from *did not ask*,
  because that is the whole point and a boolean is the mistake being avoided.
- **Stage 1:** starting the task again replaces the field. A question that cannot be cleared is
  one somebody learns to ignore.
- **Stage 2:** an attached agent driven to a question reports `waiting` **at a terminal**, because
  a terminal's redraws are what makes the detection hard and a test that only covers the easy mode
  proves the wrong half.
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

- **~~Can `tmux capture-pane -p` be issued against a session somebody is attached to, cheaply and
  without disturbing them?~~ Yes, measured 2026-09-12.** The capture itself costs 2.3-2.8 ms and
  the whole call ~131 ms, which is the `podman exec` around it rather than the capture. With a
  real client attached, 400 captures wrote **0 bytes** to that client: it is server-side and
  invisible to the person. So stage 2 is a scheduled call, not a subsystem, and no terminal
  emulator is needed.

  Two things came with that answer. **The pane's geometry follows whoever is attached** - it went
  120x40 to 80x23 the moment a client attached - so a declared *"last N lines"* region is measured
  against a window the person resizes, and the declaration rules have to say what that means.
  And the numbers were taken against a session created by `task attach`; the launch path now runs
  in the same session (`4a0cdae`), so **re-measure against a task started the new way** before
  anything depends on the figure.
- **Which engine** - the ruling above is a recommendation, not a decision, and it belongs to
  whoever builds the matcher.
- **How long may a task be `waiting` before something else happens?** This requirement only makes
  the state visible. Whether an unattended run that has waited for hours should be reported
  somewhere a person is actually looking belongs where attention is already collected - B48.
- **~~Does the unattended JSON stream actually carry a waiting record for any shipped agent?~~ No,
  for none of the three, measured 2026-09-12** - and that is what rewrote stage 1. See *What the
  measurement found* above.
- **Does a run that ended with a question need a way to be answered, or only to be seen?** Stage 1
  makes it visible and clears itself when the task is started again with the answer, which needs
  no new channel. Whether somebody should be able to reply to an ended run *in place* is a bigger
  question - it is a channel into a container that is no longer running anything - and it is not
  this requirement's to answer.
- **A trap for whoever builds stage 2:** a new tmux session inherits the **server's** environment,
  not the client's. Measured on 2026-09-12 by starting a second session against a server already
  running in a container: the second pane came up with the first one's variables and reported no
  credential. Sokar creates exactly one named session per task, so this does not bite today;
  anything that adds a second one will meet it.
