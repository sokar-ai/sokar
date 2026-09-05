# 0046 — Agent Oh My Pi

**Status:** candidate

Support Oh My Pi as a packaged agent.

**Upstream.** https://github.com/can1357/oh-my-pi - installed from npm as
`@oh-my-pi/pi-coding-agent` and run as `omp`. Its README describes it as a fork of Pi by
Mario Zechner - a *different* Pi from the one [0032](0032-Agent-Pi.md) builds, which is
`earendil-works/pi`. Three projects, two names, and the reason this file exists at all: the
work done as "Oh My Pi" was actually done against Pi, and the docs said otherwise until
2026-09-05.

**Providers.** 60+ claimed by its README, including frontier APIs, self-hosted runtimes, and
- unusually - coding-plan subscriptions such as GitHub Copilot and Cursor. None verified here.

**Authentication.** Per provider: a key, a variable, or an OAuth sign-in. It carries a
`/login` command for attaching a session to a subscription-routed service.

**Can it be brokered?** Unverified. It inherits Pi's shape, so the extension mechanism that
worked for [0032](0032-Agent-Pi.md) is the first thing to try - but a fork is not a promise,
and the packaging differs (`omp`, a different npm scope, and installers Pi does not have).

## Acceptance

- It ships as its own binary and its own package, discovered without Sokar changing.
- Nothing outside its own directory names it, enforced by the build.
- A task authenticates without anyone signing in inside the container.
- The real credential is never in the container.
- Installed alongside Pi, neither disturbs the other - the two are near-identical trees
  under different names, which is exactly where a collision would hide.

## Why it might be worth building

Not as a third agent for its own sake. It advertises the credential kind
[0025](0025-Pi-Forge-Subscription.md) was chosen to exercise and Pi may not have: a
subscription reached over a browser sign-in, GitHub Copilot among them. If Pi turns out not
to reach Copilot, this is the cheaper vehicle for that question than a new agent from
scratch, because the packaging work is already done in a shape it shares.

## To be checked

- Whether its provider list really includes a forge subscription that can be brokered, or
  only ones that route through the vendor's own service.
- Whether `registerProvider` survives the fork, and under the same name.
- Whether being a fork makes its release cadence follow Pi's or diverge from it, which
  decides how much of [0044](0044-Automated-Agent-Updates.md) it can share.

## Notes

The survey it sits in is [0021](0021-More-Agents-Providers.md).
