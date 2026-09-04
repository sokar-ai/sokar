# 0032 — Agent Oh My Pi

**Status:** built and verified against [OpenRouter](0045-Provider-OpenRouter.md); the forge
subscription it was chosen for is still open, see [0025](0025-Oh-My-Pi-Forge-Subscription.md).

Support Oh My Pi as a packaged agent.

**Providers.** Many.

**Authentication.** Per provider: an environment variable, a stored key, or a sign-in that yields a token and a refresh flow.

**Can it be brokered?** **Verified** for the common API dialect - but not with a variable:
its endpoint is set by an extension it auto-discovers, which is why an agent now declares what
shape of endpoint it can address.

## Acceptance

- It ships as its own binary and its own package, discovered without Sokar changing.
- Nothing outside its own directory names it, enforced by the build.
- A task authenticates without anyone signing in inside the container.
- The real credential is never in the container.

## To be checked

- Whether redirection holds for a provider that does not speak that dialect. Verified for
  OpenRouter; a forge subscription may differ.
- Its sign-in flows refresh automatically, which is
  [0024](0024-Refreshable-Task-Tokens.md).
