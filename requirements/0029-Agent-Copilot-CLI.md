# 0029 — Agent Copilot CLI

**Status:** candidate, and the brokering question is **answered**: yes, by environment variable.

Support GitHub Copilot CLI as a packaged agent.

**Upstream.** https://github.com/github/copilot-cli, npm `@github/copilot`.

**Providers.** One subscription, which itself fronts several models from different vendors.

**Authentication.** A forge account over a device flow.

**Can it be brokered?** **Yes**, and far more easily than assumed - the earlier
"unlikely" was wrong. Measured 2026-09-05 against version 1.0.83 by installing it and
reading `copilot help providers`:

```
Custom Model Providers (BYOK):
  Set the COPILOT_PROVIDER_BASE_URL environment variable to activate BYOK mode.
  GitHub authentication is not required when using a custom provider.

  COPILOT_PROVIDER_BASE_URL   API endpoint URL (required to activate BYOK)
  COPILOT_PROVIDER_TYPE       "openai" (default), "azure", or "anthropic"
  COPILOT_PROVIDER_API_KEY    API key
  COPILOT_PROVIDER_BEARER_TOKEN, COPILOT_PROVIDER_HEADERS, COPILOT_MODEL, ...
```

So it is the **easy** shape, like the first agent: a base URL and a credential, both in
variables, no file to place and no extension to write. It also models the provider's
dialect as data (`COPILOT_PROVIDER_TYPE`), which is the same split
[0039](0039-Providers-As-Packages.md) proposes.

**But BYOK is not the Copilot subscription.** Activating it turns GitHub authentication
off; the agent then talks to whatever endpoint it is given. Building this agent therefore
exercises nothing new and does **not** reach [0036](0036-Provider-GitHub-Copilot.md).
Those are two separate pieces of work that share a name.

## Acceptance

- It ships as its own binary and its own package, discovered without Sokar changing.
- Nothing outside its own directory names it, enforced by the build.
- A task authenticates without anyone signing in inside the container.
- The real credential is never in the container.

## To be checked

- ~~Whether anything about this agent can be redirected.~~ **Answered:** yes, by
  variable, and without any GitHub credential at all.
- A device flow needs a browser, which a box does not have, so a *subscription*
  credential still has to be obtained on the host. That question belongs to
  [0036](0036-Provider-GitHub-Copilot.md) and is not answered by BYOK.
- Which model names the subscription serves, since BYOK requires an explicit model and
  the built-in catalogue is what a subscription run would use.
