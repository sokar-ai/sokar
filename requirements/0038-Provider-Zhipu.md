# 0038 — Provider Zhipu

**Status:** candidate

Serve models from Zhipu to a task, without the credential entering the container.

**Reached how.** **An endpoint compatible with another vendor's dialect**, so an agent built for that vendor drives it.

**Authentication.** An API key, carried in a variable of its own that no agent definition names today.

**Can it be brokered?** Reachable in principle, but it makes the broker's upstream a per-credential value instead of a constant in the agent.

## Acceptance

- A task reaches this provider through the broker, and the request arrives.
- The credential stays on the host; the container holds a task-scoped token.
- Which provider a task used is answerable afterwards from the task's own record.

## Notes

The agents that can reach it are listed in
[0021](0021-More-Agents-Providers.md).
