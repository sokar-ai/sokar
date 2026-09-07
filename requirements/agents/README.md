# Agent Requirements

One file per agent, plus the two that are about agents as a class rather than about any
particular one. Each carries its own acceptance criteria so it can be judged done or not done.

**Status** is what exists today. **Shipped** means packaged, installable and covered by the
acceptance suite — an open question beside a shipped entry is something still to learn about it,
not work left to do.

Every shipped agent lives in its own repository and is discovered at runtime; nothing in Sokar
names one. These files say what must be true of an agent, not how Sokar finds it — see
[agents/README.md](../../agents/README.md) for the contract itself.

How each of these compares with the others - and which can be brokered at all - is
[one level up](../Agents-And-Providers-Compared.md), because that comparison covers providers too.

## Agents as a class

Ordered by what to do next, not by number: the number is only the file's identity.

| # | Requirement | Status | What it covers | Open question |
|---|---|---|---|---|
| A01 | [Pi Forge Subscription](A01-Pi-Forge-Subscription.md) | half built | A provider-agnostic agent against a forge subscription, chosen because it is the awkward case. | yes |
| A02 | [Automated Agent Updates](A02-Automated-Agent-Updates.md) | open | Following an upstream release must be automatic up to the point where something needs deciding. | yes |

## One file per agent

| # | Agent | Status | Open question |
|---|---|---|---|
| A03 | [Claude Code](A03-Agent-Claude-Code.md) | **shipped** | yes |
| A04 | [Pi](A04-Agent-Pi.md) | **shipped** | yes |
| A05 | [Oh My Pi](A05-Agent-Oh-My-Pi.md) | **shipped** | yes |
| A06 | [Codex CLI](A06-Agent-Codex-CLI.md) | candidate | yes |
| A07 | [Gemini CLI](A07-Agent-Gemini-CLI.md) | candidate | yes |
| A08 | [Copilot CLI](A08-Agent-Copilot-CLI.md) | candidate | yes |
| A09 | [Grok Build](A09-Agent-Grok-Build.md) | candidate | yes |
| A10 | [OpenCode](A10-Agent-OpenCode.md) | candidate | yes |

## Notes

An agent and a provider are different things and the distinction is load-bearing: an agent is
code, because it has behaviour that cannot be expressed as data; a provider is an upstream, a
header, a prefix and a path, and is therefore [data](../providers/README.md).
