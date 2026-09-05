# 0028 — Agent Gemini CLI

**Status:** candidate

Support Gemini CLI as a packaged agent.

**Upstream.** https://github.com/google-gemini/gemini-cli, npm `@google/gemini-cli`.

**Providers.** One vendor.

**Authentication.** An account sign-in or an API key.

**Can it be brokered?** Unverified.

## Acceptance

- It ships as its own binary and its own package, discovered without Sokar changing.
- Nothing outside its own directory names it, enforced by the build.
- A task authenticates without anyone signing in inside the container.
- The real credential is never in the container.

## To be checked

- Whether the endpoint can be redirected at all.
- Whether the free tier changes what a credential is, and whether it can be held
  outside the box.
