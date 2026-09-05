# 0036 — Provider GitHub Copilot

**Status:** chosen, see [0025](0025-Pi-Forge-Subscription.md). **Not** answered by the
BYOK mode found in [0029](0029-Agent-Copilot-CLI.md): that mode switches GitHub
authentication off rather than brokering it, so the subscription remains untested.

Serve models from GitHub Copilot to a task, without the credential entering the container.

**Upstream.** https://github.com/features/copilot.

**Reached how.** Its own agent, and as a provider inside provider-agnostic agents.

**Authentication.** A forge account over a device flow, yielding a short-lived token.

**Can it be brokered?** Unverified, and the open question the device flow has to answer:
whether the sign-in yields something storable and replayable at all, what host and header
carry it, and how long it lasts. If it is shorter than a task,
[0024](0024-Refreshable-Task-Tokens.md) is a prerequisite rather than a follow-up.

## Acceptance

- A task reaches this provider through the broker, and the request arrives.
- The credential stays on the host; the container holds a task-scoped token.
- Which provider a task used is answerable afterwards from the task's own record.

## Notes

The agents that can reach it are listed in
[0021](0021-More-Agents-Providers.md).
