# B98 — The Image A Signature Names

**Status:** soon

**What must be true.** A signed project's image is the image it names: an operator is told when a
project names its image by tag only, the digest is recorded when the image is built, and a start
says when it changed.

## Why

From Codex's review (PJ19), "Project images are not digest- or signature-pinned", rated important.

A project file's `base_image` is any image reference, and it reaches the container file's `FROM` as written. A tag
such as `ubuntu:24.04` names whatever the registry serves under it on the day a machine first builds the image:
the signed project file pins a name, not content, so two machines following the same signed project can build
from different roots, and a registry that changed the tag changes what runs without any signature changing.

Weaker than it sounds, and said so: podman pulls only what is missing, so the image drifts on a new machine or
after a prune; and the same build installs packages from the distribution's mirrors, so a pinned base alone is not
a reproducible root either.

### What holds today

A digest is accepted: `base_image: "ubuntu:24.04@sha256:…"` builds from exactly that image. A project that wants
its signature to mean its image can pin one now.

## Acceptance

- **`sokar project` and `sokar doctor` say when a project's image is a tag only**, and what its digest is today.
- **The resolved digest is recorded with the task image**, and a start says when it changed since the last build.
- **A followed project's image carries a digest**, or its start says that the signature does not cover the image -
  the setting a person decides with, not a silent default.
- **The default project writes a digest** when it is created.
- **Seen to fail:** a test with a tag-only `base_image` goes red when `sokar project` or `sokar doctor` does not
  say so; a test that rebuilds after the tag moved to another digest goes red when the start does not say it
  changed; a test of a followed project with a tag-only image goes red when the start is silent about it; a test
  of a freshly created default project goes red when its `base_image` has no digest.

## To be checked

- Whether to require podman's signature policy (`policy.json`) for a project that names a signed image, or the
  digest alone.

**Guideline points, 2026-10-09:** a review of security guidelines counts this as its item F9,
answering NCSC/CISA secure development, OWASP LLM03, Sysdig 6, AISVS C06.

