# 0029 — Agent Copilot CLI

**Status:** candidate

Support GitHub Copilot CLI as a packaged agent.

**Providers.** One subscription, which itself fronts several models from different vendors.

**Authentication.** A forge account over a device flow.

**Can it be brokered?** Unlikely, unverified.

## Acceptance

- It ships as its own binary and its own package, discovered without Sokar changing.
- Nothing outside its own directory names it, enforced by the build.
- A task authenticates without anyone signing in inside the container.
- The real credential is never in the container.

## To be checked

- Whether anything about this agent can be redirected. If not, it cannot be
  brokered and would have to hold a real credential or go unsupported.
- A device flow needs a browser, which a box does not have, so the credential has to
  be obtained on the host either way.
