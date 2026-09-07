# 0046 — Agent Oh My Pi

**Status:** built, in [sokar-omp](https://github.com/fuinorg/sokar-omp), and short of one
acceptance criterion. Everything up to the provider rejecting a deliberately fake key is
measured; a valid key returning 200 is not, because nobody here has an OpenRouter account.

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
  only ones that route through the vendor's own service. Still open, and now the only reason
  this agent was worth building: the 60+ providers are a bundled catalogue, and the two that
  matter - Copilot and Cursor - authenticate into omp's own store rather than through anything
  Sokar can stand in front of.
- **Answered: `registerProvider` survives the fork and does not do the job.** See above. The
  file route works and is what shipped.
- **Answered: the cadence diverges hard.** 617 npm versions to Pi's 0.85.0, three releases on
  three consecutive days. Nothing in [0044](0044-Automated-Agent-Updates.md) can be shared with
  Pi on the grounds of them being the same project.
- Whether a valid credential completes the path. It needs an account nobody here has, and it is
  the one criterion between this and done.

## Built, 2026-09-07

The adapter is its own repository and its own package, discovered by Sokar without Sokar
changing: measured on Fedora 44 and Ubuntu 26.04, where `sokar agents` lists `omp` beside
`claude`, `pi` and the stub, each from its own package, and no file is claimed by two of them.
The package is 6.2 MB because it fetches upstream's 200 MB self-contained binary and checks it
against the SHA-256 upstream publishes beside it, rather than carrying it.

**The assumption in this file was wrong, and that is the finding.** It reasoned that Oh My Pi
inherits Pi's shape, so Pi's extension mechanism would be the first thing to try. `registerProvider`
does survive the fork, under the same name and a compatible signature - and it does not redirect
a built-in provider in 18.1.13. Measured: the extension loads and runs, writes its marker, and
requests still go to `openrouter.ai`. What works is `providers.<name>.baseUrl` in
`~/.omp/agent/models.yml`, so the container is pointed at the broker by a file rather than by a
variable. A fork is not a promise, which this file said, and the packaging it shares turned out
to matter more than the mechanism it does not.

What is measured: the build and both packages; the digest matching upstream's published sum;
`omp --version` on a stock `ubuntu:24.04` with nothing added; the broker path end to end with a
deliberately fake key, ending in the provider's own 401 rather than the proxy's; the real key
absent from the container's environment and files; and coexistence with Pi. What is not: a valid
key returning 200, and whether any of its subscription providers can be brokered at all - Copilot
and Cursor sign in through `/login` into omp's own SQLite store, which is not a shape Sokar reads.

## Notes

The survey it sits in is [0021](0021-More-Agents-Providers.md).
