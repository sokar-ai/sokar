# B33 — A Task's Own Fetches

**Status:** open, and unconfirmed here. Taken from a fix in the reference implementation on
2026-09-09; the failure it describes has not been reproduced on this project's images, and the
first job is to reproduce it rather than to act on it.

## What they found

> GitHub answers most anonymous git-over-HTTPS requests from noble's git/libcurl-gnutls client with
> 401, while the same request over HTTP/1.1 is served.

Their fix is one line in the image, and only on noble:

```
git config --system http.version HTTP/1.1
```

## Why it would apply here

The default base image for a task is `ubuntu:24.04`, which is noble, and nothing in this project
sets `http.version`. Sokar's own gate is not affected — an agent pushes to it over plain HTTP on a
link-local address — but **anything the work itself fetches is**: a dependency from a forge, a
`pip install` from a git URL, an online-class project cloning its real upstream.

**The symptom is the dangerous part.** It arrives as `401`, which reads as a credential problem. In
a product whose whole subject is credentials that deliberately never enter the container, an
unexplained 401 inside a task is the most expensive possible wrong signal: every instinct points at
the broker, the phantom token, or the vault, and none of them is involved.

## What must be true

**A task can fetch from the forges its work depends on, and a failure to do so is never reported as
a credential problem.**

## Acceptance

- An anonymous clone from a public forge succeeds inside a task on every supported base image.
- Where a base image needs a setting to achieve that, the image carries it and the reason is
  written where somebody changing the image will read it.
- The setting is applied to the images that need it and not to the ones that do not - a workaround
  applied everywhere is one nobody can later remove safely.
- A fetch that fails for a protocol reason does not present as an authentication failure.

## Notes

**Reproduce before fixing.** This is a report about somebody else's images, and the difference
between their base and this project's is unmeasured. A one-line change that is not needed is a line
nobody can delete later, because nobody knows what it was for.

**This project has been bitten by an HTTP version once already.** `HttpClient` negotiating HTTP/2
and exposing its pseudo-headers is in AGENTS.md: it surfaced as `Unable to connect to API` after 21
responses that were all HTTP 200. Different mechanism, same shape - a protocol difference wearing
an authentication failure's clothes.

## To be checked

- **Does it reproduce?** An anonymous clone from GitHub inside a `ubuntu:24.04` task, with and
  without the setting. Until that is done this requirement is somebody else's bug report.
- **Which images are affected**, if it does. Fedora bases use a different curl and may not be.
- **Whether the acceptance suite should cover it.** It is a scenario a person would do - start a
  task, clone something public - and B27 now has the machinery to run exactly that.
