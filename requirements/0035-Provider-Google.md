# 0035 — Provider Google

**Status:** candidate

Serve models from Google to a task, without the credential entering the container.

**Upstream.** https://ai.google.dev.

**Reached how.** Its own agent, and provider-agnostic agents.

**Authentication.** An account sign-in or an API key.

**Can it be brokered?** Unverified.

## Acceptance

- A task reaches this provider through the broker, and the request arrives.
- The credential stays on the host; the container holds a task-scoped token.
- Which provider a task used is answerable afterwards from the task's own record.

## Notes

The agents that can reach it are listed in
[0021](0021-More-Agents-Providers.md).
