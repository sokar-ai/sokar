# A11 — What An Agent Declares About Waiting

**Status:** open, written 2026-09-12.
**This file is the agent packages' half** of
[B47](../base/B47-What-The-Agent-Is-Doing-Read-From-Outside.md): what each agent must
declare about its own wording, and how its own repository keeps that declaration true. B47 owns
the daemon side - the manifest field, the matcher, the contract and the budget - and carries the
reasoning for why this is declared rather than known.

The rule this exists to keep: **Sokar carries no agent's wording.** An agent's question to a
person is a fact about that agent, so it is declared where `resume_flag` and `sandboxed` are
declared, in the agent's own package, and it ships and versions with the CLI it describes.

## What each agent declares

Two sections, because an agent says the same thing two different ways depending on how it was
started, and B47 reads them from two different places.

- **`records:`** - for the unattended run. `TaskLaunch` starts the agent with its machine-readable
  output flags on, so `task.log` is a stream of JSON records rather than prose. A declaration here
  names the record that means *waiting for a person*: the field path and the value. Exact,
  cheap, and unaffected by redraws.
- **`screen:`** - for the attached run, where the only evidence is what is on the terminal. A
  declaration here names a region (the prompt box, the last few non-empty lines, the window
  title) and a literal to find in it, with the wording the CLI actually prints.

An agent may declare one, both, or neither. **Neither is a legitimate answer** and is not the same
as *not waiting*: B47 point 4 exists for exactly this case, and an agent whose CLI has no
distinguishable question must be allowed to say so rather than be guessed at.

## What is known today, per shipped agent

**Measured 2026-09-12 against the pinned artifacts** - claude 2.1.267 and omp 18.1.13 fetched by
the pinned URL and checked against the pinned SHA-256, pi 0.85.0 out of the published
`sokar-agent-pi` package - in both modes: headless with the flags Sokar puts on the command line,
and attached in a tmux session driven to a real question. Re-measured on every version bump,
because the pin moves weekly under [A02](A02-Automated-Agent-Updates.md).

### The headless mode has nothing to declare, for any of them

Driven with a prompt that asks the agent to put a question to the person and wait, **all three
answered and exited.** None of them can block on a person in a headless run - there is nobody to
type, and each treats that as "finish".

- **claude** emits `system/init`, `assistant`, `result` and ends with `stop_reason=end_turn`,
  `terminal_reason=completed`, `num_turns=1`. **Nothing distinguishes a run that ended by asking
  from one that ended by finishing.**
- **pi** emits `session agent_start turn_start message_* turn_end agent_end agent_settled`, and
  `agent_settled` is the **last line before exit** - it marks the end of a run, not a wait,
  despite what its documentation suggests the event is for.
- **omp** emits the same minus `agent_settled`, plus `advisor_cost_changed`.

So a declared *record* has nothing to match, and the unattended failure worth reporting is a
different one: **a run that ended early with a question in its last message.** That belongs beside
"finished", not beside "waiting", and it is not what this requirement was written for.

### A03 Claude Code - declarable, from the screen

- **Working:** the status line carries `esc to interrupt`, and a line above the box counts up
  (`Calculating… (6s · ↓ 588 tokens)`).
- **Waiting on the person:** a numbered list under the question, with the footer
  `Enter to select · ↑/↓ to navigate · Esc to cancel`. The first-run consents - theme, detected
  API key, security notes, folder trust, bypass-permissions - use `Enter to confirm · Esc to
  cancel`. Both disappear the moment the question is answered.
- **Idle:** neither marker. `Esc to cancel` is therefore the one literal that separates waiting
  from both other states.
- **The window title is useless here.** It is `✳ Claude Code` at startup and then `✳ <what the
  task is about>`, and it does **not** change between working, waiting and idle.
- **Already shipped here:** `ClaudeSettings` writes this agent's `settings.json` inside the
  container, and `ClaudeFirstRun` is what gets past the consent screens above.

### A04 Pi - working only, and honest about the rest

- **Working:** a separator line `── ⠏ Working ───…` with a braille spinner.
- **Waiting:** **nothing.** Its question is plain text in the transcript and the chrome around it
  is byte-for-byte the idle screen - no footer, no selector, no marker. Measured over repeated
  captures while it sat waiting.
- **The window title never changes**: `π - <directory>` in every state.
- So pi declares a working rule and **declares nothing for waiting**, which the contract must
  report as *cannot say* rather than as *not waiting*.
- **Note:** pi asks no tool-approval questions at all; `--no-approve` removes its only in-box
  question (`sokar-pi` `4bf5a11`). An agent deliberately stripped of its questions has little to
  declare, and that is a good outcome rather than a gap.

### A05 Oh My Pi - the best signal of the three, against expectation

The earlier inference that omp has nothing was **wrong**, and measuring it is what showed that.
omp puts its state in the **window title**, where it costs nothing to read and cannot be confused
with the agent quoting itself:

- `π ⠦ <task>` while working (a braille spinner that advances),
- `π > <task>` when idle,
- **`π ! <task>` while it waits for the person** - stable across repeated samples, and back to
  `π > ` the moment the question is answered.

Its question also draws a box with the footer `Enter select · n note · ↑/↓ move · Esc cancel`, and
its five-step first-run wizard uses `↑/↓ select · enter confirm · esc skip · ctrl+c exit setup`.

## What each agent's repository must do

1. **Measure, then declare.** Drive the agent to a real waiting point, twice - once unattended and
   once at a terminal - and write down only what was seen. Never copy wording out of upstream
   documentation.
2. **Prove the declaration, in that repository's own tests.** A declaration that is never exercised
   is a comment. The proof is a test that drives the agent to the waiting point and asserts the
   declared record or literal matches what the agent produced. The terminal half belongs in
   `terminal.feature` alongside the existing acceptance.
3. **Keep it true across A02.** The weekly job moves the pinned CLI version without being asked,
   and an agent's wording moves with its version. The proof from point 2 runs on that bump, so a
   declaration that has stopped matching **fails the agent's own build** rather than going quiet in
   the field. This is the agent-side half of B47 point 6; the daemon-side half reports a task whose
   declaration never matched.
4. **Declare nothing rather than guess.** An agent with no distinguishable question declares
   nothing, and the contract says it cannot tell.

## The one route that needs a ruling before it is built

An agent package can install configuration into its own container - this is not new, it is what
`ClaudeSettings` and `PiRoutingExtension` already do. So an agent package could install a hook or
extension that, when the agent stops to ask, **prints a marked line or a window-title sequence into
the agent's own output**. The daemon would then match a literal that this repository chose, instead
of wording upstream chose, and a CLI version bump could not silently break it.

**The distinction that makes this admissible is narrow and has to be kept.** A hook that calls
back over a socket is the obvious version of this and is refused: it is a channel the container
writes into. A hook that writes into the output the agent already produces is not that - the
stream already crosses the boundary, nothing new is opened, and the direction of the boundary is
unchanged. If a proposal ever needs the hook to reach anything other than the agent's own stdout,
it has become the refused design and should be recognised as one.

It is written down rather than started, because it needs three answers first:

- Does a marked line in an **attached** session put visible noise on the person's screen? A window
  title sequence would not; a printed line would.
- Does installing a hook for Sokar's benefit change what the agent does for its own? Claude Code's
  hook configuration is shared with whatever the person configured.
- Is the daemon allowed to treat a literal the agent package itself prints as more trustworthy than
  a literal upstream prints? It is the same mechanism either way, but the failure modes differ.

## Acceptance criteria

- For each shipped agent, either a declaration exists and a test in that agent's own repository
  drives it to a waiting point and asserts the declaration matches, **or** the agent declares
  nothing and a test asserts that the contract reports *cannot say* rather than *not waiting*.
- The same test drives the agent while it is working and asserts nothing matches.
- Both are asserted unattended and at a terminal, for any agent that declares a `screen:` section.
- Bumping the pinned CLI version without updating a declaration that has stopped matching fails
  that agent's build. Asserted by changing a declaration to something the agent never prints.
- `grep` for any agent's wording outside `agents/` and the agent repositories finds nothing.

## To be checked

- **What a declaration may contain**, once the daemon's matcher exists: the three agents need a
  literal in a named region, a footer line, and a window title - the title being the one omp puts
  its whole state in. Whether the title is declared like any other region or is its own thing is
  B47's to settle.
- **Whether the wording survives a version bump**, which is the standing question A02 creates and
  the reason point 3 above exists. Claude Code announced 2.1.269 and pi 0.85.1 while these
  measurements ran.
- **What an agent driven by a person, rather than by a prompt, looks like in the middle of a
  turn** - measured here only for a task the agent was given, not for one somebody typed into an
  attached session over several turns.
