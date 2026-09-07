# B09 — Secrets Off The Command Line

**Status:** open. Found by sweeping the credential-management requirement, which is now
retired, rather than by anything failing.

A credential is never given to Sokar as an argument - `vault put` reads standard input, and its
own documentation says why: a command line is visible in the host's process list to every user on
the machine for as long as the command runs. The same rule is broken on the way out. Every value a
container is given reaches it as `podman --env NAME=VALUE`, so the task's git token and its phantom
token are both in podman's argument list while that command runs.

## Acceptance

- No secret Sokar holds appears in the argument list of any process it starts.
- A container still receives exactly the variables it does today, with the same values.
- What replaces the argument is no more readable than the process's own environment.
- The rule is enforced by something that fails, not by a habit.

## Measured

2026-09-07, on the development machine, because the exposure depends on how `/proc` is mounted and
that is a per-machine fact:

```
-r--r--r--  /proc/<pid>/cmdline      world-readable
-r--------  /proc/<pid>/environ      owner only
proc on /proc type proc (rw,nosuid,nodev,noexec,relatime)     no hidepid
```

So an argument is readable by every user on the machine and an environment variable is not. Fedora
and Ubuntu both mount `/proc` this way by default; a machine with `hidepid` set is better off and
must not be assumed.

What is exposed today, and for how long:

- **The git gate token**, as `GIT_CONFIG_VALUE_0=Authorization: Basic <base64>`, for the duration
  of `podman create`. It authenticates pushes into the task's mirror.
- **The phantom token**, in whichever variable the provider names, for the same window and again
  for every `podman exec` that runs the agent.

Both are worth nothing off this machine and nothing after the task - which is exactly what makes
this bounded rather than urgent - but a local user is precisely the reader `/proc` exposes them to.

## To be checked

- **Whether `--env-file` or an inherited environment is the right shape.** A file in the task's
  state directory is `0600` and needs no plumbing; passing `--env NAME` without a value makes
  podman copy it from its own environment, which is owner-only but means giving the runner an
  environment, which `CommandRunner` does not currently carry. The second is cleaner and costs
  more.
- **Whether podman's env-file parser preserves the header value.** `GIT_CONFIG_VALUE_0` holds
  spaces, a colon and trailing `=` padding. It has to arrive byte-identical or a push fails in a
  way that looks like a wrong token.
- **Whether `podman exec` has the same option.** The agent run is a second exposure and may need a
  different answer from the one that fixes container creation.
- **How the rule is kept.** A test that greps every argument list Sokar builds for a value it
  holds would fail on a regression; whether that is expressible without naming secrets is the
  question.
