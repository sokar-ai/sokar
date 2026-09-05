# 0033 — Provider Anthropic

**Status:** supported

Serve models from Anthropic to a task, without the credential entering the container.

**Upstream.** https://docs.anthropic.com.

**Reached how.** Its own agent natively, and any agent speaking its dialect.

**Authentication.** A subscription token or an API key.

**Can it be brokered?** **Verified** end to end, through a task-scoped token.

## Acceptance

- A task reaches this provider through the broker, and the request arrives.
- The credential stays on the host; the container holds a task-scoped token.
- Which provider a task used is answerable afterwards from the task's own record.

## Notes

The agents that can reach it are listed in
[0021](0021-More-Agents-Providers.md).
