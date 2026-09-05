# 0036 — Provider GitHub Copilot

**Status:** chosen, see [0025](0025-Pi-Forge-Subscription.md). **Not** answered by the
BYOK mode found in [0029](0029-Agent-Copilot-CLI.md): that mode switches GitHub
authentication off rather than brokering it, so the subscription remains untested.

Serve models from GitHub Copilot to a task, without the credential entering the container.

**Upstream.** https://github.com/features/copilot.

**Reached how.** Its own agent, and as a provider inside provider-agnostic agents.

**Authentication.** A forge account, by device code or browser redirect, yielding a long-lived
`gho_` token in `~/.copilot/config.json` - plaintext, because a container has no credential
store. It also accepts a **fine-grained PAT with the "Copilot Requests" permission**, which is
the better credential to hold: the OAuth token carries `repo`, `gist` and `codespace` as well.
A token can be supplied on standard input or in `COPILOT_GITHUB_TOKEN`. See
[0025](0025-Pi-Forge-Subscription.md) for the measurement.

**Can it be brokered?** **Half answered.** The credential is storable, portable and does not
expire, so the vault side is solved. What is not settled is where the proxy stands: the model
API is `api.individual.githubcopilot.com`, but the client also calls `api.github.com` four
times per session, which looks like the long-lived token being exchanged for a short-lived one
the client then uses directly. If so, brokering the model API alone leaves a real credential
inside the container.

## Acceptance

- A task reaches this provider through the broker, and the request arrives.
- The credential stays on the host; the container holds a task-scoped token.
- Which provider a task used is answerable afterwards from the task's own record.

## Notes

The agents that can reach it are listed in
[0021](0021-More-Agents-Providers.md).
