# P06 — Provider Google

**Status:** later; a candidate, nothing built.

**What must be true.** A task is served models from Google, without the credential entering the container.

## Why

**Upstream.** https://ai.google.dev.

**Reached how.** Its own agent, and provider-agnostic agents.

**Authentication.** An account sign-in or an API key.

The agents that can reach it are listed in
[the comparison](https://github.com/sokar-ai/sokar-project/blob/main/doc/agents-and-providers.md).

## Acceptance

- A task reaches this provider through the broker, and the request arrives. Seen to fail: a task's request to it
  without the broker's token is refused by the broker.
- The credential stays on the host; the container holds a task-scoped token. Seen to fail: a scan of the
  container's environment and files for the real credential finds it.
- Which provider a task used is answerable afterwards from the task's own record. Seen to fail: the task's record
  after a request to it does not name Google.

## To be checked

- Can it be brokered? Unverified.
