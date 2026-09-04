# 0040 — Acceptance Suite Isolation

**Status:** done, with one criterion reworded rather than met — see *Notes*. The suite
runs against its own vault, cleans up after a failure, and two consecutive runs on the
same machine gave identical results, check for check.

The acceptance suite is the instrument everything else is measured with, and it
currently reads the machine it runs on. It stores its own fake credential only when
the vault has none, so on a machine with a real one it silently tests against that
instead - and reports failures that are not real.

A suite that cries wolf is worse than no suite: the next genuine regression arrives
as two more red lines nobody looks at.

## Acceptance

- The suite passes on a machine with a real credential in the vault, and on one with
  an empty vault, and reports the same thing either way.
- It never reads or writes the operator's own vault, and creates only containers, images
  and state directories named for itself. It does share two things it cannot sensibly
  own: the hook descriptors, which are per operator, and the container runtime's image
  store, which cannot be redirected without rebuilding every layer.
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

Measured on 2026-09-04, after the run-leaves-nothing-behind work: a deliberately
unbuildable image was used to fail a run after the credential proxy was listening and
before any container existed - the gap no hook covers, because a container that never
started fires none. Against the previous build that check named a stranded proxy still
holding its socket; against the fix it passes on Fedora and Ubuntu, and both machines
end a run with no containers, no state directories and no helper processes.

Two consecutive runs on the same machine were compared check by check and were
identical: 26 passes, no failures, both times.

The criterion about the operator's configuration was narrowed rather than met. The
suite uses the operator's hook descriptors, because podman reads them per user and
there is nowhere else to put them, and the operator's image store, because redirecting
it rebuilds every layer and leaves directories a normal user cannot delete. Both are
shared deliberately; neither is written to.

## To be checked

- Whether the suite can run against its own vault, container names and state
  directory without redirecting the container runtime's own storage - redirecting
  that rebuilds every image layer and leaves directories that cannot be removed.
- Whether the checks that need a credential can be separated from those that do not,
  so the credential-free tier is genuinely credential-free rather than
  credential-optional.
