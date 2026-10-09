# B141 — New Dependencies Checked Before Review

**Status:** open.

**What must be true.** A dependency the agent's work adds is marked in the review and checked: that it exists in its registry, how old it is, and whether its name is close to a well-known one (slopsquatting).

## Why

Today the manifests are only shown first in a review; a hallucinated package name that someone registered is not told apart from a real one.

**Guideline points it answers:** OpenSSF 6; Checkmarx (slopsquatting). From the operator's review of security guidelines, 2026-10-09.

## The shape

Per ecosystem the manifests are read (Maven, npm, PyPI, Cargo, Go); the check runs on the host, through the declared registries; the review shows each new name with its finding.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Which registries first; how a check that cannot reach a registry is said.
