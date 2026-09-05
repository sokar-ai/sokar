# 0037 — Provider xAI

**Status:** candidate

Serve models from xAI to a task, without the credential entering the container.

**Upstream.** https://docs.x.ai.

**Reached how.** Its own agent, and its public API from any agent.

**Authentication.** A subscription account, or an API key for the public API.

**Can it be brokered?** Unverified.

## Acceptance

- A task reaches this provider through the broker, and the request arrives.
- The credential stays on the host; the container holds a task-scoped token.
- Which provider a task used is answerable afterwards from the task's own record.

## Notes

The agents that can reach it are listed in
[0021](0021-More-Agents-Providers.md).
