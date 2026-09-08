# B24 — First-Run Consent Inside The Box

**Status:** open, and at the top. Reported on 2026-09-08 by somebody following the getting-started
guide on a clean machine, after the permission-prompt work had already landed: an agent that no
longer asks permission per command still stops twice before it starts.

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

## Why this is the same defect as the one just fixed, and worse

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

- A first run of an agent in a fresh task image reaches work without any interactive prompt -
  measured on a machine where that agent has never run, not on one carrying an answered dialog.
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

## To be checked

- **What exactly has to be written, per dialog.** Claude Code appears to record these in
  `~/.claude.json` and `~/.claude/settings.json` - candidates are a bypass-mode acceptance flag and
  a list of approved custom API keys - but this was **not verified**: neither key was present in
  the config of a machine that had never used bypass mode or a custom key. It must be read off a
  real config that has answered both, not inferred from the names.
- **Whether pre-answering is the right mechanism at all**, or whether the agent offers a
  non-interactive mode that makes the question moot. Writing a consent flag on somebody's behalf is
  a thing to do deliberately: it is defensible inside a container Sokar built for one task, and
  would not be on a person's own machine.
- **Whether the first dialog should instead be avoided.** It fires on the *presence* of
  `ANTHROPIC_API_KEY`. A task pointed at the broker may not need that variable at all, in which
  case the honest fix is not to set it rather than to approve it.
- **What the other agents do.** Oh My Pi and Pi have not been checked for first-run dialogs, and
  Pi has not even declared its permission flag yet.
