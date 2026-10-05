# P05 — Provider OpenAI

**Status:** later.

**What must be true.** A task is served models from OpenAI, without the credential entering the
container.

## The shape

**Upstream.** https://platform.openai.com.

**Reached how.** Its own agent, and any agent speaking its dialect - which is the most widely imitated one.

**Authentication.** An account sign-in or an API key.

**Can it be brokered?** Unverified, but its dialect is what the provider-agnostic agents redirect by default.

The agents that can reach it are listed in
[the comparison](https://github.com/sokar-ai/sokar-project/blob/main/doc/agents-and-providers.md).

## Acceptance

- A task reaches this provider through the broker, and the request arrives. Seen to fail: a request
  from a task to the broker's OpenAI route does not reach the upstream.
- The credential stays on the host; the container holds a task-scoped token. Seen to fail: a search
  of the container's environment, files and process arguments finds the real key or sign-in token.
- Which provider a task used is answerable afterwards from the task's own record. Seen to fail: the
  record of a task that used it does not name OpenAI.

## To be checked

- Whether it can be brokered at all: unverified, for the account sign-in as much as for the API key.
