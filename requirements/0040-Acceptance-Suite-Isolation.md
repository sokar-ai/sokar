# 0040 — Acceptance Suite Isolation

**Status:** open

The acceptance suite is the instrument everything else is measured with, and it
currently reads the machine it runs on. It stores its own fake credential only when
the vault has none, so on a machine with a real one it silently tests against that
instead - and reports failures that are not real.

A suite that cries wolf is worse than no suite: the next genuine regression arrives
as two more red lines nobody looks at.

## Acceptance

- The suite passes on a machine with a real credential in the vault, and on one with
  an empty vault, and reports the same thing either way.
- It never reads or writes the operator's own vault, containers, or configuration.
- A run leaves nothing behind, including when it fails part way.
- Every check states what it measured, so a failure names the thing that broke
  rather than the step that noticed.
- Running it twice in a row gives the same answer.

## Notes

Measured on 2026-09-04: with a real credential stored, two checks failed - one
because no fake token line appeared, one because the provider's answer was not the
rejection the suite expected. Both were the suite testing something other than what
it thought it was testing. Nothing was wrong with the product.

The same isolation argument applies to the tier that does use a real credential: it
already backs up and restores the vault, which is closer but still touches it.

## To be checked

- Whether the suite can run against its own vault, container names and state
  directory without redirecting the container runtime's own storage - redirecting
  that rebuilds every image layer and leaves directories that cannot be removed.
- Whether the checks that need a credential can be separated from those that do not,
  so the credential-free tier is genuinely credential-free rather than
  credential-optional.
