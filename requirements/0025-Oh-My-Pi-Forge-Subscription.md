# 0025 — Oh My Pi Forge Subscription

**Status:** open

The second agent to build, chosen deliberately rather than by convenience. It is a
provider-agnostic agent authenticating against a forge's own subscription over a
browser sign-in, which exercises three things the first agent never touched:

- **a credential belonging to a provider, not to the agent** - the vault is keyed by
  agent name today, and this is the first case where that is wrong;
- **a browser sign-in**, which cannot happen inside a box that has no browser and no
  path to the operator's desktop, so the credential must be obtained on the host and
  imported;
- **a short-lived token**, which is where [0024](0024-Refreshable-Task-Tokens.md)
  stops being theoretical.

Picking the awkward combination on purpose: an agent that is easy to add proves
only that an easy agent can be added.

## Acceptance

- The agent installs as its own package and Sokar needs no change to discover it.
- The credential is obtained on the host, stored, and never entered in the box.
- A task authenticates without the agent asking anyone to sign in.
- The real credential is not in the container, checked the same way as for the first
  agent.
- Both agents are installed at once and neither disturbs the other.

## To be checked

- **Whether this agent's endpoint can be redirected for this provider.** It is
  documented for the common API dialect; a forge subscription may not use it.
- Whether the sign-in yields something storable at all, or only a session belonging
  to a browser profile.
- How long the token lasts. If it is shorter than a task, [0024](0024-Refreshable-Task-Tokens.md)
  is a prerequisite rather than a follow-up.

## Notes

The survey this was chosen from, and the matrix of what else exists, is
[0021](0021-More-Agents-Providers.md).
