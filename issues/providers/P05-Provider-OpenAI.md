# P05 — Provider OpenAI

**Status:** candidate

Serve models from OpenAI to a task, without the credential entering the container.

**Upstream.** https://platform.openai.com.

**Reached how.** Its own agent, and any agent speaking its dialect - which is the most widely imitated one.

**Authentication.** An account sign-in or an API key.

**Can it be brokered?** Unverified, but its dialect is what the provider-agnostic agents redirect by default.

## Acceptance

- A task reaches this provider through the broker, and the request arrives.
- The credential stays on the host; the container holds a task-scoped token.
- Which provider a task used is answerable afterwards from the task's own record.

## Notes

The agents that can reach it are listed in
[the comparison](../Agents-And-Providers-Compared.md).
