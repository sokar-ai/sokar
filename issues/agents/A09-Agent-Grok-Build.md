# A09 — Agent Grok Build

**Status:** candidate

Support Grok Build as a packaged agent.

**Upstream.** https://github.com/xai-org/grok-build - Rust rather than npm, installed from
`x.ai/cli`, so it pins differently from every agent listed here so far.

**Providers.** One vendor, whose model is also on its public API.

**Authentication.** A subscription account.

**Can it be brokered?** Unverified.

## Acceptance

- It ships as its own binary and its own package, discovered without Sokar changing.
- Nothing outside its own directory names it, enforced by the build.
- A task authenticates without anyone signing in inside the container.
- The real credential is never in the container.

## To be checked

- Whether a subscription account yields anything storable, or only a session.
- Too recent for usage to mean anything; revisit before spending effort on it.
