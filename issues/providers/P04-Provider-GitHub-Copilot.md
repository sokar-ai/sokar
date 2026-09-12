# P04 — Provider GitHub Copilot

**Status:** chosen, see [A01](../base/A01-Pi-Forge-Subscription.md). **Not** answered by the
BYOK mode found in [A08](../agents/A08-Agent-Copilot-CLI.md): that mode switches GitHub
authentication off rather than brokering it, so the subscription remains untested.

Serve models from GitHub Copilot to a task, without the credential entering the container.

**Upstream.** https://github.com/features/copilot.

**Reached how.** Its own agent, and as a provider inside provider-agnostic agents.

**Authentication.** A forge account, by device code or browser redirect, yielding a long-lived
`gho_` token in `~/.copilot/config.json` - plaintext, because a container has no credential
store. It also accepts a **fine-grained PAT with the "Copilot Requests" permission**, which is
the better credential to hold: the OAuth token carries `repo`, `gist` and `codespace` as well.
A token can be supplied on standard input or in `COPILOT_GITHUB_TOKEN`. See
[A01](../base/A01-Pi-Forge-Subscription.md) for the measurement.

**Can it be brokered?** **Half answered.** The credential is storable, portable and does not
expire, so the vault side is solved. What is not settled is where the proxy stands: the model
API is `api.individual.githubcopilot.com`, but the client also calls `api.github.com` four
times per session, which looks like the long-lived token being exchanged for a short-lived one
the client then uses directly. If so, brokering the model API alone leaves a real credential
inside the container.

## The stored credential cannot be reached by either extractor

`sokar vault import` lifts what a vendor's own sign-in already wrote, using one of the two shapes
in the agent API: a dotted path into a JSON file, or a variable in an env file. Neither reaches this
one.

```
~/.copilot/config.json
  authTokens."https://github.com:michael-schnell".token
```

The account name is **inside the key**, and the key contains both dots and colons, so
`JsonFieldExtractor` splits it into pieces that do not exist. Supporting this provider needs
either a wildcard segment or a "the only key under this object" rule - the first case where the
two shipped shapes are not enough.

It is also the first credential whose location is clearly the **provider's** business rather than
the agent's, which is the split [P01](P01-Providers-As-Packages.md) makes: any agent signing in
to GitHub Copilot finds it in the same place, in the same shape.

## Acceptance

- A task reaches this provider through the broker, and the request arrives.
- The credential stays on the host; the container holds a task-scoped token.
- Which provider a task used is answerable afterwards from the task's own record.

## Notes

The agents that can reach it are listed in
[the comparison](../Agents-And-Providers-Compared.md).
