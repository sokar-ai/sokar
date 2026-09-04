# 0032 — Agent Oh My Pi

**Status:** chosen, see [0025](0025-Oh-My-Pi-Forge-Subscription.md)

Support Oh My Pi as a packaged agent.

**Providers.** Many.

**Authentication.** Per provider: an environment variable, a stored key, or a sign-in that yields a token and a refresh flow.

**Can it be brokered?** Yes for the common API dialect, by design.

## Acceptance

- It ships as its own binary and its own package, discovered without Sokar changing.
- Nothing outside its own directory names it, enforced by the build.
- A task authenticates without anyone signing in inside the container.
- The real credential is never in the container.

## To be checked

- Whether redirection holds for a provider that does not speak that dialect.
- Its sign-in flows refresh automatically, which is
  [0024](0024-Refreshable-Task-Tokens.md).
