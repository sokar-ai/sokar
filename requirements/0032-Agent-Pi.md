# 0032 — Agent Pi

**Status:** built and verified against [OpenRouter](0045-Provider-OpenRouter.md); the forge
subscription it was chosen for is still open, see [0025](0025-Pi-Forge-Subscription.md).

Support Pi as a packaged agent.

**Upstream.** https://github.com/earendil-works/pi - the Pi Agent Harness, installed from npm as
`@earendil-works/pi-coding-agent` and run as `pi`. Named here because there is a second project
called Oh My Pi ([0046](0046-Agent-Oh-My-Pi.md)) that this is not, and the two were confused in
these files until 2026-09-05.

**Providers.** Several, and it is provider-agnostic rather than tied to one - which is why it
was chosen. How many is unverified; the figure once written here belonged to no project in
particular.

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
- **Whether it reaches GitHub Copilot at all.** [0025](0025-Pi-Forge-Subscription.md) assumes it
  does, and that was never checked. Oh My Pi advertises Copilot explicitly; this one does not.
- Its sign-in flows refresh automatically, which is
  [0024](0024-Refreshable-Task-Tokens.md).
