# 0036 — Provider GitHub Copilot

**Status:** chosen, see [0025](0025-Oh-My-Pi-Forge-Subscription.md)

Serve models from GitHub Copilot to a task, without the credential entering the container.

**Reached how.** Its own agent, and as a provider inside provider-agnostic agents.

**Authentication.** A forge account over a device flow, yielding a short-lived token.

**Can it be brokered?** Unverified. The token's lifetime may make [0024](0024-Refreshable-Task-Tokens.md) a prerequisite.

## Acceptance

- A task reaches this provider through the broker, and the request arrives.
- The credential stays on the host; the container holds a task-scoped token.
- Which provider a task used is answerable afterwards from the task's own record.

## Notes

The agents that can reach it are listed in
[0021](0021-More-Agents-Providers.md).
