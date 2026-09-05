# 0044 — Automated Agent Updates

**Status:** open

An agent pins the version of the tool it installs, and today every part of that pin is
moved by hand: the exact version in the npm manifest, the regenerated lockfile, the
property the definition is filtered from, and the runtime version and digest in the
build script. Four edits, in four files, with nothing checking that they agree.

That is already fragile, and it gets worse rather than better as agents are added: each
one pins a different tool from a different source, and the work of following an upstream
release is real but almost never interesting.

The goal is that a new upstream release becomes a published agent package with no human
intervention **unless something needs a decision**.

## Acceptance

- A new upstream release is noticed without anyone looking for it.
- The version is applied by one command per agent, leaving a reviewable change rather
  than an unexplained one.
- Nothing publishes that has not passed the acceptance suites against the new version -
  including the tier that authenticates for real, because that is what catches a
  breaking change in flags, configuration or an extension API.
- A release that changes what third-party code ships stops and asks instead of
  publishing.
- Which version an installed agent will actually install is answerable from the
  installed system, for a packaged agent as much as for a downloaded one.

## The pipeline

1. **Detect.** A scheduled job compares the upstream's published latest version against
   the one the module pins. The source differs per agent - a registry for one, a
   per-version manifest for another - so this is per agent behind a common contract.
2. **Apply.** One script per agent, `update <version>`, doing exactly what a person
   would: rewrite the pinned version, regenerate whatever is derived from it, and bump
   the module's own version.
3. **Verify.** Full suite, native build, packages, then the acceptance suites on both a
   machine with SELinux enforcing and one without.
4. **Publish.** Signed packages attached to a release, the development version moved on.

## When it must stop instead of publishing

Automation is only safe if it knows what it is not allowed to decide:

- the acceptance run failed;
- **the set of transitive dependencies changed** - new third-party code entering a
  containment tool is exactly the thing pinning exists to make visible;
- **a licence in the shipped tree is new or changed** - the package redistributes that
  code, so this is an obligation rather than tidiness;
- the upstream major version moved.

Anything else - a patch or minor release, an unchanged dependency set, green suites -
publishes without anyone being asked.

## To be checked

- **Whether the credential the verifying tier needs can live in CI.** Without it the
  automation checks less than a person does by hand today, which would make it a
  worse gate wearing the appearance of a better one.
- What version the agent package itself takes when only the tool it installs moved.
  The two were deliberately separated, so a bot needs a stated rule rather than a
  guess.
- Where published packages go. They are build artifacts today; a release is a place to
  attach them, a repository is a larger piece of work.
- Whether "latest" is the right thing to follow at all, or whether a release has to be
  a certain age before it is picked up.
- **How the CI snapshots are refreshed when GraalVM or the base image moves.** The same
  question one layer down: the test machines pin GraalVM by digest and pre-pull the base
  images, so following an upstream release means rebuilding an image, not editing a version.
  The builder is not in the repository yet, which has to be fixed before a bot could run it.

## Prerequisites

Two gaps make automation unsafe as things stand, and both are small:

- **Nothing enforces that the pinned versions agree.** A bot editing one place and not
  another would ship a package whose definition advertises a version its own payload
  does not contain, and no test would notice.
- **`sokar agents --supply-chain` reports what is pinned for a downloaded tool, but a
  packaged one carries its payload instead.** Until that reports the shipped tree's
  version and digest, "which version ran" is unanswerable for exactly the agents this
  requirement automates.
