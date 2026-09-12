# P03 — Provider OpenRouter

**Status:** supported

Serve models from OpenRouter to a task, without the credential entering the container.

**Upstream.** https://openrouter.ai - the broker's upstream in `pi.yaml`.

**Reached how.** Any agent speaking the OpenAI dialect, which OpenRouter serves under
`/api/v1`. Its Anthropic-shaped skin sits under `/api`, so the path an agent needs
depends on which dialect it speaks - a base URL ending in `/v1` answers "model not
found" to a client that appends its own version segment.

**Authentication.** An API key, sent as `Authorization: Bearer`.

**Can it be brokered?** **Verified** end to end, through a task-scoped token: the first
provider proved with an agent that is not its own.

## Acceptance

- A task reaches this provider through the broker, and the request arrives.
- The credential stays on the host; the container holds a task-scoped token.
- Which provider a task used is answerable afterwards from the task's own record.

## Notes

Verified on 2026-09-04 with Pi: a real prompt answered, the
broker logging `POST /api/v1/chat/completions -> 200 from the provider`, and no trace of
the key in the container's environment or files.

The credential belongs to the provider rather than to the agent, which the vault does not
yet express - it is keyed by agent name. Deliberately deferred; see
[P01](P01-Providers-As-Packages.md).
