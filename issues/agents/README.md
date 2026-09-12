# Agent Requirements

**This set is where an agent lives before it has a repository.** An agent that has one keeps its
requirements there, in its own set, because that is where the work is and an issue belongs with
whoever does it. What is left here is therefore candidates - and the set emptying is a measure of
progress rather than untidiness.

Decided 2026-09-12, when the shipped agents' requirements were handed to the agent that owns those
repositories. [A01](../base/A01-Pi-Forge-Subscription.md) left in the other direction, to
`issues/base/`: it reads like an agent requirement and what remains of it is Sokar's - the vault
being keyed by agent name, a sign-in that has to happen on the host, a short-lived token. **It kept
its number**, because a number here is a file's identity rather than its address, and changing one
on a move breaks every reference to it - including the copies another repository has taken.

Each file carries its own acceptance criteria so it can be judged done or not done.

**Status** is what exists today. **Shipped** means packaged, installable and covered by the
acceptance suite — an open question beside a shipped entry is something still to learn about it,
not work left to do.

Every shipped agent lives in its own repository and is discovered at runtime; nothing in Sokar
names one. These files say what must be true of an agent, not how Sokar finds it — see
[agents/README.md](../../agents/README.md) for the contract itself.

How each of these compares with the others - and which can be brokered at all - is
[one level up](../Agents-And-Providers-Compared.md), because that comparison covers providers too.

## The candidates

Ordered by what to do next, not by number: the number is only the file's identity.

| # | Agent | Status | Open question |
|---|---|---|---|
| A06 | [Codex CLI](A06-Agent-Codex-CLI.md) | candidate | yes |
| A07 | [Gemini CLI](A07-Agent-Gemini-CLI.md) | candidate | yes |
| A08 | [Copilot CLI](A08-Agent-Copilot-CLI.md) | candidate, and the brokering question is answered | yes |
| A09 | [Grok Build](A09-Agent-Grok-Build.md) | candidate | yes |
| A10 | [OpenCode](A10-Agent-OpenCode.md) | candidate | yes |

## Where the shipped agents' requirements went

Claude Code, Pi and Oh My Pi each have a repository, so their requirements live there, in that
repository's own set: `sokar-claude-code`, `sokar-pi`, `sokar-omp`. Handed over on 2026-09-12.

Two of them were not one requirement each. **Automated agent updates** and **what an agent declares
about waiting** existed here as one file apiece and arrived there as three - the pipeline and the
declaration exist separately in every agent repository, and an issue that spans three repositories
is three issues with a named dependency rather than one with a footnote. That the files were single
was a fact about this directory, not about the work.

**No row is kept for them here.** An index holds what is still to do, and none of that is.

## Notes

An agent and a provider are different things and the distinction is load-bearing: an agent is
code, because it has behavior that cannot be expressed as data; a provider is an upstream, a
header, a prefix and a path, and is therefore [data](../providers/README.md).
