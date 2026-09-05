# 0031 — Agent OpenCode

**Status:** candidate

Support OpenCode as a packaged agent.

**Upstream.** https://github.com/sst/opencode.

**Providers.** Many, chosen per session.

**Authentication.** Per provider: a pasted key in one store, a browser sign-in, or an environment variable. A stored value may be a reference to a variable rather than a literal.

**Can it be brokered?** **Documented.** A base URL is configurable per provider.

## Acceptance

- It ships as its own binary and its own package, discovered without Sokar changing.
- Nothing outside its own directory names it, enforced by the build.
- A task authenticates without anyone signing in inside the container.
- The real credential is never in the container.

## To be checked

- The reference-to-a-variable form would need no file placed in the container at
  all, which would be simpler than what the supported agent needs. Worth confirming
  before building anything.
- One credential per provider, which the vault cannot express today.
