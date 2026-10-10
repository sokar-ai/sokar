# B170 — A Devcontainer File As Image Source

**Status:** open.

**What must be true.** Where a repository has `.devcontainer/devcontainer.json`, a task's base image and tools can be
taken from it; what Sokar does not take - ports, mounts, privileges - is ignored and said.

## Why

Many repositories already say in that file which image and tools they build with; keeping the same twice in
`project.yml` is work and drifts. Comparable tools read the file.

## The shape

- `project.yml` says `image: devcontainer` (or the path); the base image and the features that install tools are taken,
  everything else is listed as ignored at the start and by `--dry-run`.
- The image is built outside the task's firewall, as every task image is, and the start says so, naming what the build
  fetched from.
- Size: medium.

## Acceptance

- Seen to fail first, then green: a repository with a devcontainer file naming an image and a port gets a task on that
  image; the port is named as ignored.
- `doc/project-file.md` says it.

## To be checked

- Which of the file's features are taken in the first step.
