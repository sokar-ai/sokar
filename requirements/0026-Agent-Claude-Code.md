# 0026 — Agent Claude Code

**Status:** supported

Support Claude Code as a packaged agent.

**Providers.** Anthropic, and the same models through three cloud vendors.

**Authentication.** A subscription token or an API key. The kind decides which variable carries it.

**Can it be brokered?** **Verified.** It honours both a base URL and a unix socket, with either credential kind, including a token minted for the task.

## Acceptance

- It ships as its own binary and its own package, discovered without Sokar changing.
- Nothing outside its own directory names it, enforced by the build.
- A task authenticates without anyone signing in inside the container.
- The real credential is never in the container.

## To be checked

- Before an interactive session it contacts the vendor directly, ignoring the
  endpoint it was given, so that host has to be reachable.
- A fresh container has never been logged in, so its first-run wizard must be
  answered for it or the session stops for input.
- Both were measured while building it; both are why the container-setup mechanism
  exists.
