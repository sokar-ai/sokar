# P07 — Provider xAI

**Status:** later; a candidate, nothing built.

**What must be true.** A task can be served models from xAI, without the credential entering the
container.

## Why

**Upstream.** https://docs.x.ai.

**Reached how.** Its own agent, and its public API from any agent.

**Authentication.** A subscription account, or an API key for the public API.

**Can it be brokered?** Unverified.

The agents that can reach it are listed in
[the comparison](https://github.com/sokar-ai/sokar-project/blob/main/doc/agents-and-providers.md).

## Acceptance

- A task reaches this provider through the broker, and the request arrives.
- The credential stays on the host; the container holds a task-scoped token.
- Which provider a task used is answerable afterwards from the task's own record.
- **Seen to fail:** a test that sends a task's request through the broker goes red when it does not
  arrive at xAI; a search of the container for the real credential goes red when it is found; a
  test that reads the task's record afterwards goes red when it does not name the provider.

## To be checked

- **Can it be brokered?** Unverified - neither for the subscription account its own agent uses nor
  for an API key on the public API.
