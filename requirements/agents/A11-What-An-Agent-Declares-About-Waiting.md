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

Measured live on 2026-09-11 against the pinned artifacts, for B11's open question. This is the
starting material, not the declaration itself: every line of it is re-measured against the pinned
version before it is written down, because the pin moves weekly under
[A02](A02-Automated-Agent-Updates.md).

### A03 Claude Code

- **Records:** the `-p` run emits `stream-json`, which
  `ClaudeStreamJsonFormatter` in `sokar-claude-code` already parses for display, so this repository
  has read that stream before. Its hooks fire `SessionStart`, `UserPromptSubmit`, `Stop` and
  `SessionEnd` in `-p`. The three `Notification` kinds that would say it best - `idle_prompt`,
  `elicitation_dialog`, `permission_prompt` - exist in the binary but **cannot fire in `-p`**.
  So whether a waiting record reaches `task.log` at all is the first thing to measure.
- **Screen:** the richest of the three. Claude Code sets its **window title with a spinner while
  it works** - the most reliable working signal it offers, because it is a sequence rather than a
  sentence - and draws a prompt box whose body is where its questions appear. The permission prompt has stable wording around a numbered
  choice list.
- **Already shipped here:** `ClaudeSettings` writes this agent's `settings.json` inside the
  container, so if the hook route below is ever ruled in, the machinery to install it exists.

### A04 Pi

- **Records:** `--print --mode json`. Its extension API fires `session_start`, `agent_start`,
  `turn_start`, `turn_end`, `agent_end`, `agent_settled`, `session_shutdown`, and the shipped
  `docs/extensions.md` names `agent_settled` and `ui_prompt_start/_end` as the ones meant for
  status integrations - "waiting for user" in its own words. Whether they reach the JSON stream in
  `--print` mode, as opposed to only an extension, is the measurement.
- **Screen:** one literal, `"Working..."`, appears to be all pi's screen offers; everything else
  worth knowing comes from its extension API rather than from what it draws. A one-rule
  declaration is the expected outcome here, not a sign the survey was lazy.
- **Already shipped here:** `PiRoutingExtension` installs an extension into the container already.
- **Note:** pi asks no tool-approval questions at all. Its one in-box question was project trust,
  and `--no-approve` removes it (`sokar-pi` `4bf5a11`). An agent that has been deliberately stripped
  of its questions has correspondingly little to declare, and that is a good outcome rather than a
  gap.

### A05 Oh My Pi

- A fork of a different Pi, and the awkward one: **no `agent_settled`, no `ui_prompt_*`, no
  `project_trust`** at 18.1.13. Its interactive `ask` tool would appear only as a tool execution.
  This is an inference from the shipped tree, not a live measurement.
- If that holds, **omp declares nothing and reports that it cannot say.** That is this
  requirement's honest answer for it, and the acceptance criteria below assert it rather than
  treating it as unfinished work.

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

- **Does a waiting record reach `task.log` for any of the three?** The events are known to exist;
  whether they survive `-p` / `--print --mode json` is not. Measured per agent, and it decides
  whether B47's stage 1 is worth building for that agent at all.
- **What does the attached screen actually look like** at the pinned versions, region by region?
  Nobody here has captured one. It has to be read from a real attached session under tmux, because
  a description written against a different terminal stack would not survive contact with ours.
- **Does omp really have nothing?** The inference is from a shipped tree, not from a run.
