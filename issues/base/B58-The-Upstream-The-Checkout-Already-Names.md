# B58 — The Upstream The Checkout Already Names

**Status:** open. Proposed on 2026-09-14 by Agent Discuss, after being asked whether `task start`
in a git checkout takes anything from that checkout into `project.yml`. It takes nothing. Everything
below comes from reading the code; nothing was measured.

## Where this project is

**When there is no `project.yml`, the wizard asks three questions** (`ProjectWizard.create`):

- **project name**, defaulting to the directory's name made legal (`nameFrom`),
- **security class**, defaulting to `guarded`,
- **base image**, defaulting to `ubuntu:24.04`.

It then passes `null` as the upstream to `ProjectCreation.create`. The class comment gives the
reason: an upstream is required only by `online`, so asking would be padding. **Nothing in the
codebase reads a checkout's remotes.** `LocalRepository` knows only `rev-parse --show-toplevel`.

**The checkout is used for one thing: seeding the mirror.** `WorkspaceSetup.seed` takes the
checkout's top level when there is no `--upstream`, no `upstream:` in the project, and no mirror
yet, and prints `seed <path> (committed history only)`.

**What a person gets by pressing Enter at every prompt, in a clone of a hosted repository:**

- a `guarded` project with no `upstream:`,
- a mirror seeded from the local committed history,
- and, when the work has been reviewed and is ready, `sokar gate approve` refusing with *"No upstream
  is configured for this project"*. From then on they need `--upstream` on every approve, or
  `project.yml` edited by hand.

Choosing `online` in the wizard fails instead: *"an online project pushes to its upstream itself, so
it needs one"*. The URL it asks for is in `.git/config`, in the directory the person is standing
in.

## What it would change

The wizard offers the checkout's remote URL as the upstream, as a default Enter accepts. The reason
the wizard skipped the question holds for a value a person would have to invent. It does not hold
for one the machine already has.

## What it is not free of

- **The seed changes with it.** An upstream takes precedence over the checkout, so the mirror
  would be cloned from the remote, not from local history. Local commits that were never pushed
  would no longer reach the agent. For `guarded` that matches where approved work goes. For someone
  who wanted the agent working on top of unpushed commits it is a regression, so it has to be said
  before the file is written. The two are separable: `GitGate` already takes the seed and the
  forwarding URL as separate arguments, and `GateSupport` is what sets the seed to the upstream.
- **`offline` must not be offered one.** That class is for a host with no route to the upstream
  either, and its gate refuses to forward. Its only effect there would be seeding from the network.
- **A remote URL can carry a credential.** `https://user:token@host/owner/repo.git` is an ordinary
  result of cloning with a token. Written into `project.yml`, which normally sits inside the
  checkout, it gets committed. Printed as a default, it lands in terminal scrollback.
- **"The" remote is a guess.** `origin` is a convention, not a rule. A checkout can have several
  remotes, and the current branch can track one that is not `origin`.
- **A remote can be a path on this machine.** That is a valid upstream for git, and the gate would
  forward into it. Worth showing as what it is, not refusing.

## What must be true

**Starting in a checkout that already names its upstream never makes a person type that URL
again, and never copies a credential out of that URL into a file or onto a screen.**

## Acceptance

- In a checkout with a usable remote, the wizard offers that remote's URL as the upstream default,
  names the remote it came from, and Enter accepts it. One answer declines it.
- The remote is the current branch's tracking remote, else `origin`, else none. With none, the
  wizard behaves as it does today.
- User information in a URL is never written to `project.yml` or printed. A URL that carries some is
  offered without it, and the wizard says it removed something.
- An `offline` project is not offered an upstream.
- Before writing, the wizard says where the mirror will be seeded from, whenever accepting the
  upstream changes that.
- Detection happens only when a project is created. `task start` with an existing `project.yml`
  never infers an upstream.
- A suggestion stays on the wizard's side of the handover, like `suggestedSets`, so a project
  created over the contract gets no value nobody reviewed.

## To be checked

- **Whether an accepted upstream should still leave the seed with the checkout.** That keeps
  unpushed commits in front of the agent. The cost is that the mirror's history and the upstream's
  can differ from the first push, and whether `approve` then works has not been tried.
- **Whether an `https` remote works for `online`.** In that class the container's git credential is
  an ssh-agent socket, so a remote that needs a token may be offered, accepted, and fail at the
  first push.
- **Whether the interface should get the same suggestion.** A project created over the contract is
  for a directory the daemon is not standing in. That is B20's territory ([index](README.md)), and
  nothing is blocked on it.
