# Writing a build reader

A build reader tells Sokar what a forge built: where a branch points, and what the build of a commit did. It is an
executable of its own, installed on the host, which Sokar starts and asks over a socket; it never runs inside a
task. What a task sees of it is in [being told what the build did](running.md#being-told-what-the-build-of-a-push-did).
This page is for whoever writes one for a forge Sokar does not read yet.

## What it is built on

`sokar-build-api` on Central, and `sokar-wire` beside it: nothing else of Sokar's. A reader implements one
interface, `BuildReader`, and its main method is one line:

```java
public static void main(String[] arguments) {
    BuildMain.run(new MyForgeReader(), arguments);
}
```

`BuildMain` answers `describe` with the forge's name and the protocol version, and `serve <socket>` with the
varlink interface `org.fuin.sokar.Build1`. Sokar refuses a reader whose protocol version it does not know, and one
that reads another forge than the project names, before it asks it anything.

## What it answers

- **`forge()`**: the name a project file gives under `builds.forge`, and the name the executable is installed
  under: `/usr/libexec/sokar/builds/<forge>`, or `~/.local/share/sokar/builds/<forge>` for one account.
- **`head(target, branch)`**: the commit a branch points at, "" when the forge has no such branch.
- **`look(target, commit, logs)`**: what the build of one commit did, across everything the forge ran for it.

`target` carries the repository as the project file names it (the reader takes it apart: only it knows how its
forge spells a repository), the API's address ("" for the forge's own) and the token. **A reader keeps no secret**:
Sokar reads the token from the vault and hands it over the owner-only socket in each call. A reader never writes
it anywhere, and never sends it to any host but the forge's API - a log behind a redirect to storage is fetched
without it.

## The verdict, the jobs and their logs

- **The verdict** is one of `queued`, `running`, `success`, `failure`, `cancelled`, `unknown`, across every run of
  the commit: `failure` as soon as one failed, `running` or `queued` while one is not finished, otherwise
  `cancelled` or `success`. `unknown` says why in its detail: no build of the commit yet, for one.
- **A job** is the smallest unit the forge reports its own result and its own log for: on GitHub and GitLab a job,
  on Bitbucket a step, on Jenkins a stage with a log of its own or else the whole build. Its name is
  `<workflow or pipeline> / <job>`, spelled as the forge spells it; its result is what became of it, in lower case,
  or its state while it is not finished.
- **Which jobs:** every job the forge reports, once the verdict is `failure` or final; none while it is queued or
  running, so a running build costs one question per round and no more.
- **Which logs:** `logs` is `failure`, each failed job's, or `all`, every job's once the verdict is final. A log is
  the job's **plain text**, its last 64 KiB: a reader whose forge sends an archive, or splits a job into files per
  step, unpacks it and joins the steps in their order, one header line each, before it takes the end. At most 50
  jobs.

## When the forge will not answer

Not a verdict but a refusal, each its own error, so Sokar tells them apart and so does the task:

| Refusal | When | What Sokar does |
|---|---|---|
| `RateLimited` | the forge's limit is used up; `retryAfter` says for how long | asks again after it |
| `CredentialRefused` | the forge does not take the token, or it lacks the scope | asks again in 10 minutes |
| `NoSuchRepository` | the forge shows the token no such repository | asks again in 10 minutes |
| `Unreachable` | the forge did not answer, or answered something that is not an answer | asks again next round |

A reader that cannot be started, or fails, is a fault of the installation, and Sokar says so in the task's
`buildProblem`.
