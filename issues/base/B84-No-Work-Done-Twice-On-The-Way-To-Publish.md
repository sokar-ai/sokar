# B84 — No Work Done Twice On The Way To Publish

**Status:** implemented 2026-09-29 and measured on Build #198 (`30b18ec`): the legs started 9 seconds after
the first job, `Publish` took 6m37s instead of 9m46s, and the run 23m34s instead of 31m55s. Open until a
run whose unit tests fail shows its legs cancelled and no server left. Written 2026-09-29 at the operator's
request, after the four-account legs (B83) took a `Build` run from 42 to 32 minutes.

## What a run still spends its time on

Measured from Build #196 (run `98992235329`, commit `e6516a2`):

| Job | Time | On the critical path |
|---|---|---|
| Build and unit tests | 4m26s | yes: both legs wait for it |
| Legs, in parallel | 16m49s-17m31s | yes |
| Publish | 9m46s | yes: it waits for both legs |

Two parts of that are waiting, not proving anything:

- **The legs wait for the unit tests.** A leg compiles and builds the product on its own machine, from
  the same commit. It needs nothing the unit tests make. It starts 4.5 minutes late only in case the unit
  tests fail.
- **`Publish` builds everything twice more.** Its `Build and install` runs `clean install` with the unit
  tests, javadoc and signing (4m10s): tests that already passed in this run, on the same commit. Its
  `Deploy the jars` then runs the lifecycle again (2m37s) and signs and uploads what it builds. The first
  of the two is there so the packaging can resolve the product's jars and so nothing is published before
  every check passes. For that, plain jars in the local repository are enough.

## What must be true

1. **The legs start beside the unit tests**, not after them. They still wait for a snapshot rebuild when
   the push brings one.
2. **A failed unit test stops the run's legs**, so a broken commit rents machines for minutes, not for a
   whole leg. Each leg still deletes what it created.
3. **`Publish` does not run the unit tests again.** Before its checks it builds only what the packaging
   and the checks need. The signed, published jars are built once, in the step that publishes them.
4. **The publishing order does not change.** Nothing reaches Central or Artifactory until every check has
   passed, and `Publish` still waits for the unit tests and both legs.

## Acceptance criteria

- A green run shows the legs starting within a minute of `Build and unit tests`, and its wall clock is
  about 4 minutes shorter than Build #196's, the legs and scenarios being equal.
- A green run's `Publish` runs no unit test, and is about 3 minutes shorter than Build #196's.
- A run whose unit tests fail ends with both legs cancelled and no server of that run left.

## To be checked

1. Whether cancelling a run from its own failed job leaves the legs' `if: always()` clean-up running, and
   whether the run then shows as failed rather than only as cancelled.
