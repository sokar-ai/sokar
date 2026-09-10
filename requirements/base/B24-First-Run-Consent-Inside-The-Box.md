# B24 — First-Run Consent Inside The Box

**Status:** the second dialog is answered and built. The first is **refused as not solvable**, and
that half is what this file is now for.

Reported on 2026-09-08 by somebody following the getting-started guide on a clean machine, after
the permission-prompt work had already landed: an agent that no longer asks permission per command
still stopped twice before it started.

## What happens

A task starts. Before the agent does anything, it asks:

```
  Detected a custom API key in your environment

  ANTHROPIC_API_KEY: sk-ant-...
  Do you want to use this API key?

  ❯ 1. Yes
    2. No (recommended)
```

and then, having been told to skip permission checks:

```
  WARNING: Claude Code running in Bypass Permissions mode
  ...
  By proceeding, you accept all responsibility for actions taken while running in
  Bypass Permissions mode.

  ❯ 1. No, exit
    2. Yes, I accept
```

Both are **first-run consent dialogs**, not permission prompts, which is why
`--dangerously-skip-permissions` does not answer them - the second one exists *because* of that
flag.

## The first one will not be fixed, and here is why

**Decided 2026-09-09.** The dialog fires because the account already has a live session on a
different payment model, and the key in the environment belongs to the other one. That is a
collision the vendor detects on purpose, between two ways of paying for the same account - not a
first-run question Sokar can pre-answer, and not a state a container is in.

There is a mechanism that would suppress it - the CLI records approved keys, and Sokar mints the
token so it could write the approval per task - but suppressing this particular dialog would be
answering "yes, bill it that way" on somebody's behalf. That is not the container's decision the
way bypass mode is. **So: won't do**, and the interface should say what the dialog is rather than
hide it.

**What stays true regardless:** its recommended answer is *No*, and taking it refuses the only
credential the task has. That is worth a line in the documentation so nobody follows the vendor's
recommendation into a task that cannot work.

## Why the second is the same defect as the one just fixed, and worse

The flag work established the rule: **inside a task the answer is always yes, and Sokar decides
that rather than asking.** These two dialogs are the same question wearing different clothes, and
they land in the same two places:

- **Attended:** the person answers two questions whose answers were settled when they chose to run
  the agent in a box at all. The second one asks them to accept responsibility for something the
  container already contains.
- **Unattended:** there is nobody to press 1. The run stops at a menu and waits, and what an
  operator sees is a task that started and then did nothing - the exact failure the permission
  work was meant to remove, reached by another route.

The first dialog is worse than an annoyance. Its recommended answer is **No**, and the key it is
asking about is the phantom token Sokar minted for this task. A person who takes the recommendation
has refused the only credential the task has, in a dialog that describes it as suspicious.

## What was built for the second

Answered where B24 said it should be: the agent declares the file, Sokar writes the bytes.
`ClaudeSettings` produces `/home/agent/.claude/settings.json`

```json
{"permissions":{"defaultMode":"bypassPermissions"},"skipDangerousModePermissionPrompt":true}
```

placed by `ClaudeContainerSetup` beside the two files it already wrote. No Sokar code knows what
is in it, and an agent that declares nothing still gets nothing.

## Where this belongs

**Not in Sokar's code, and not conditional.** The same split the manifest already makes for
`sandboxed: arguments:` applies: what these dialogs are, and what has to be written to pre-answer
them, is the agent's own business and Sokar must not guess it; *whether* to pre-answer them is not
a question - inside a task there is no other correct answer.

The seam already exists. `TaskLaunch.placeAgentFiles(...)` puts an agent's files into the container
before it runs, and an agent module already knows its own config directory (`config_dir`) and its
own file formats - `ClaudeAgent` reads `.credentials.json` there today.

## What must be true

**A task starts its agent and the agent works. Nothing between those two points asks a person a
question that was answered by the decision to run in a box.**

## Acceptance

- A first run of an agent in a fresh task image reaches work without any interactive prompt
  **that a container can answer** - measured on a machine where that agent has never run, not on
  one carrying an answered dialog. The API-key dialog above is excluded by decision, not by
  oversight, and the documentation says so.
- The same is true unattended: a headless run with no terminal completes rather than waiting.
- **The phantom token is never presented to a person as a suspicious key**, and no path asks them
  to approve the credential Sokar issued for that task.
- What is written to pre-answer a dialog is declared by the agent, not branched on the agent's name
  anywhere in Sokar.
- An agent that declares nothing gets nothing written, and still starts.
- The acceptance suite fails if a new agent release adds a dialog: the check is "reached work
  without a prompt", not "the two known dialogs are absent".

## Notes

**This is the third time the same shape has appeared**, and that is the reason it is written down
rather than patched: an agent stopping to ask something the box already decided. First per-command
permission prompts, then bypass-mode acceptance, now credential confirmation. Whatever answers this
should be general enough that the fourth costs a manifest entry and no code.

**The stub agent in the acceptance suite has no dialogs**, which is why neither of these was caught
by a green suite. A test that only ever runs an agent that cannot ask questions cannot notice an
agent that does.

## Measured 2026-09-10, on a local VM

Claude Code 2.1.267, in a task container, driven at a real pty. Three earlier attempts answered
nothing and are worth recording so nobody repeats them: `--version` never reaches the first-run
flow at all; starting in `/home/agent` rather than `/workspace` raises the *trust* dialog instead
of the one being looked for; and neither `python3` nor `node` exists in the image, so a probe that
edits the agent's own JSON has to write the file rather than patch it.

**The settings file works.** With `settings.json` in place the bypass-mode dialog does not appear -
not with `--dangerously-skip-permissions` and not without it. All three declared files are placed,
and the launch says `prepared 3 file(s)`.

**The flag is redundant, and that answers the second question.** With the token approved, the CLI
reaches its prompt either way and the footer reads `⏵⏵ bypass permissions on` in both runs.
`defaultMode: bypassPermissions` alone puts the session in bypass mode. Since the flag is what
raises the warning, dropping it removes the dialog's cause rather than its symptom.

**What this did not separate:** `settings.json` sets `defaultMode` *and*
`skipDangerousModePermissionPrompt`, and the runs above cannot say which one silences the dialog.
A third run with only `defaultMode` would settle it, and is worth having before the flag is
dropped rather than after.

**The API-key dialog is suppressible after all, and the reason it was refused may not hold.** It
disappears completely when the last 20 characters of the token are listed under
`customApiKeyResponses.approved` in `.claude.json` - measured, not inferred. The refusal above
rests on this being a billing decision taken on somebody's behalf. The key in question is the
**phantom token Sokar minted for that task**, not a payment credential: the billing decision was
made when the operator put the real credential in the vault and chose a provider. Whether that
distinction reopens the question is for whoever revisits this file; what is certain is that the
third acceptance criterion is **not met today** - the dialog shows the phantom token and
recommends refusing it.

## Fixed 2026-09-10: a task with no credential got no files at all

`TaskLaunch.placeAgentFiles` returned as soon as there was no token, before writing anything. Two
of Claude Code's three files - onboarding complete, this folder is trusted - have nothing to do
with a credential, so somebody who started a task without one and logged in inside it met every
dialog this requirement exists to remove, by a route nobody had looked at.

It now passes an **empty token** instead of returning, and what to write without one is the
agent's own decision. That keeps the split this file argues for: Sokar writes bytes, the agent
knows which. Each agent has to answer it for itself - the file that carries a token is useless
without one, while a file recording that onboarding is done is not.

## To be checked

- ~~**Whether the settings file actually silences it**~~ - measured above on 2026-09-10: it does.
  What is left is which of its two keys does the silencing.
- ~~**Whether the flag is now redundant.**~~ - measured above on 2026-09-10: it is. Dropping it
  waits only on separating the two settings keys, so the removal is made on a measurement rather
  than on a guess.
- ~~**A task with no credential still meets the dialog.**~~ - fixed above on 2026-09-10 in
  `TaskLaunch`. Each agent still has to say what it writes without a token; until every agent
  does, this is only half closed.
- **What the other agents do.** Oh My Pi and Pi have not been checked for first-run dialogs, and
  Pi has not even declared its permission flag yet.
