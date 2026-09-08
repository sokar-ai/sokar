# B13 — Unreviewed Work Leaving By The Side Door

**Status:** open, and asked for after a question nobody had asked: what happens when a person opens
the work in their own editor.

The gate is built for the agent. Inside the container there is no credential for any forge, the
firewall drops port 22 to a declared host, and the only remote the working copy has is Sokar's
mirror. An agent cannot push past the gate, and that was verified.

**The person can.** A developer opens the project in an IDE over SSH, fetches the agent's branch
out of the mirror to look at it, likes it, and pushes - with their own key, from their own checkout,
straight to the upstream. Nothing in Sokar is in that path. The review the gate exists to force did
not happen, and nothing recorded that it did not.

This is not a hole in the container. It is the seam between the machine's two halves: the agent's
work is behind a gate, and the operator's own repository is not, and the mirror is how work crosses
from one to the other.

## What is actually exposed

Measured rather than assumed, because the first guess was wrong:

- **The working copy is not on the host.** `/workspace` lives inside the container - `git init` in
  the image, remote `sokar` pointing at the gate. Only the vault and ssh sockets are bind-mounted.
  So an IDE attached to the *container* is as constrained as the agent: no credential, no route.
- **The mirror is on the host**, at `~/.local/share/sokar/mirrors/<project>.git`, holding every
  pushed branch under `refs/sokar/incoming/`. It is an ordinary bare repository owned by the
  operator, and anything they can run can read it.
- **So the path out is: fetch from the mirror into the operator's own checkout, then push that
  upstream.** Two ordinary git commands, no privilege needed, no warning anywhere. `sokar gate
  approve` is a third command that does the same thing *and* records it - and it is the one that
  can be forgotten.
- **Agent commits are identifiable.** The working copy is configured with `user.name` `agent` and
  `user.email` `agent@localhost` unless the task overrides them, so a commit made by an agent can
  be told from one made by a person - which is what any guard here would have to rest on.

## Why the work is in the container at all

Asked because it is the premise of everything below, and the answer is not "because nobody thought
about it".

**A bind-mounted workspace hands the agent the host.** Not metaphorically: a working copy contains
`.git/hooks/`, and a hook is a script git runs *with the privileges of whoever runs git next*. An
agent that can write into a directory the operator later opens has arranged to execute code as the
operator. The container exists to make that impossible, and a bind mount is a hole straight through
it. Two lesser reasons point the same way: rootless podman writes those files as a subordinate uid,
so the operator cannot even edit them without `podman unshare`, and relabelling somebody's real
project directory for SELinux is not something a tool should do to them.

**The reference implementation does the same and goes further.** Terok also clones into the
container (`REPO_ROOT=/workspace/...`, seeded from `file:///git-gate/gate.git`) and then builds an
explicit way in for a person: an sshd baked into the image, the host's public key bind-mounted to
`/etc/ssh/authorized_keys.d/terok`, reached over podman's pasta, offered in its interface as
`terok login <project> <task>`. It exists there partly for a reason Sokar does not have - under the
krun runtime `podman exec` cannot enter the guest at all - and it is a *shell*, not an editor.

**But for reviewing, inside is the wrong place regardless.** The reviewer does not want the agent's
environment; they want the diff, in their own tools, on the machine they are already connected to.
And the work is *already on the host*: every pushed branch is in the gate's mirror. What is missing
is not a way in, it is a way out - nothing turns `refs/sokar/incoming/<task>` into a working copy
somebody can open.

## Whether the safe way is even open

Asked while writing this, because the answer decides whether the whole thing is a documentation
problem or a product one: **can an editor attach to the container at all?**

Mechanically yes - both VS Code and JetBrains attach by running a command in the container, and
`podman exec` is what `task run` already does to hand over a shell. What neither can do is *arrive*:
both install a server component into the container on first attach, and both download it. The
container's egress is deny-by-default, no shipped egress set covers either vendor's hosts, and
nothing resolves that a project did not declare. So the first attach fails, and the way to make it
work is to declare an editor vendor's hosts in the project - widening what the *agent* may reach in
order to let a person look at its work, which is the wrong trade.

There is a way in that needs no network at all: copy the server in with `podman cp`, or bake it
into the image. Both vendors support a server installed by hand.

**So today the safe way to inspect the work is the inconvenient one, and the unsafe way - fetch the
branch out of the mirror into your own checkout - is the convenient one.** That is the shape of the
accident this requirement is about: it is not carelessness, it is the only path that works.

## And it leaves the gate saying something untrue

Found by asking what a hand push does to the state, which is a different question from what it does
to the upstream. `approve` is two operations:

```
git push <upstream> refs/sokar/incoming/<name>:refs/heads/<branch>
git update-ref -d refs/sokar/incoming/<name>
```

The delete is what empties the queue. A push made by hand does the first and not the second, so the
ref stays, and:

- **`gate pending` lists the work as waiting for review, permanently.** A queue that always has
  something in it is a queue nobody reads, and then the one entry that really is waiting does not
  stand out.
- **`reject` afterwards writes down the opposite of the truth.** Somebody tidying up marks it
  discarded while the code is on the upstream. Of the three consequences this is the dangerous one:
  the record now says the work was thrown away.
- **A later `approve` fails as a non-fast-forward** if anything was rebased before the hand push,
  with a git error that does not say why.

There is a fix for this half that does not depend on preventing anything: the mirror knows the
upstream URL, so `pending` can ask whether an incoming ref is already reachable from the upstream
branch and say **already upstream** instead of **waiting**. That turns a wrong state into a true
one even when nobody could be stopped.

### Three ways to notice, and they are not equal

**Watching the operator's checkout** is possible and partial. A successful push updates the
remote-tracking ref, and git writes `update by push` into its reflog, which inotify can see; the
project registry already knows one `project.yml` path per project, so there is somewhere to point
a watcher. What it cannot see is every other clone: the same person pushing from a laptop, a
colleague, CI, or the forge's own web editor. A guard that is right about one directory and blind
to the rest teaches people to trust it, which is worse than not having it.

**Asking the upstream** sees all of those, because it asks about the thing everyone shares. One
fetch in the mirror and one reachability test per pending ref, at the moment somebody looks -
no watcher, no daemon, nothing running between times. It costs the network, and it is the truth
rather than an inference from a local side effect.

**The pre-push hook knows first.** Where the guard above is installed, it is standing exactly at
the moment the push happens - so besides refusing, it can record. Somebody who confirms and pushes
anyway can leave the gate's state correct on the way past, which is the only version of this where
nothing is ever briefly wrong.

The three compose in that order of reliability, and only the last two are worth building.

## Acceptance

- Work that reached the upstream without passing the gate is **prevented or reported**, not
  silently possible.
- The gate never describes work as waiting for review when it is already upstream, and never
  records it as discarded when it is not.
- The guard recognizes agent work by something the agent actually leaves behind, not by where a
  branch happens to be.
- A person who means it can still do it. **This is protection against an accident, not against the
  owner of the machine** - anything else would be a lie, since it is their key, their checkout and
  their computer.
- Whatever is installed into an operator's own repository is asked for, reversible, and says what
  it did when it fires.
- Nothing here weakens the case where it already works: an IDE attached to the container stays as
  constrained as the agent.

## Notes

The honest framing matters. Sokar cannot stop a determined person from pushing their own commits
with their own credentials, and pretending otherwise would produce a feature that is both
irritating and untrue. What it can do is make the accidental case loud: the moment somebody pushes
commits an agent wrote and nobody approved, they should hear about it.

## Measured, 2026-09-08: what a pre-push hook actually sees

Against real repositories, because the guard's whole value is whether it fires.

**It fires, and it can tell what is being pushed.** Each line on standard input is
`<local ref> <local sha> <remote ref> <remote sha>`, so the commits are
`git rev-list <remote sha>..<local sha>`. Verified for the three cases that matter:

| | fires | remote sha |
|---|---|---|
| first push of a new branch | yes | forty zeros - "everything not already there" |
| an ordinary push | yes | the previous tip |
| a **force** push | yes | the previous tip, so a rewrite is visible too |

**Three ways it does not fire, and they decide what this feature may claim:**

- **`git push --no-verify` skips it.** That is not a flaw - it is the acceptance criterion *"a
  person who means it can still do it"*, arriving for free and by a name people already know.
- **`core.hooksPath` pointing elsewhere silently disables it.** Measured: with that set, the
  repository's own hook is never found and the push goes through with no output at all. This is
  the dangerous one, because it is a **team-wide setting** somebody may have configured for shared
  hooks long ago, and the guard would then never fire for anybody while appearing installed.
  `gate protect` has to check it and say so, rather than write a file and report success.
- **The hook is not versioned.** `git ls-files` shows nothing under `hooks/`, so it is per clone: a
  colleague's fresh clone has no guard, and neither does the same person's second checkout.

**Not measured here: whether a client that uses a git library runs hooks at all.** JGit and libgit2
are reported not to, and some editors and desktop clients are built on them. Nothing on this
machine could test it, so it is stated as unmeasured rather than assumed either way - and it is
the fourth way the guard may not fire.

**What this means for the claim.** The guard catches an accident by somebody using git, which is
the case the requirement describes. It is not a control, and four documented paths go around it.
Anything that presented it as prevention rather than as a loud accident-catcher would be the lie
this requirement's own notes warn against.

## To be checked

- **Whether the guard is worth building, given the four ways round it.** Measured above. Still a
  decision rather than a fact: a guard that catches the ordinary accident and is silently absent
  under `core.hooksPath` may be worth having, or may be worse than nothing because people will
  believe they are protected.
- **What the guard tests.** Author identity is the cheap signal and it is spoofable and easy to
  lose in a rebase. A trailer that the gate adds when it forwards - so that *approved* commits are
  the marked ones and everything else is suspect - inverts it into the safer direction, at the cost
  of rewriting commits at approval time.
- **Whether the mirror should be harder to fetch from.** Making it unreadable would break `gate
  review`, which is how anybody looks at the work at all. Probably nothing to do here, but it is
  the other end of the same path.
- ~~A working copy on the host~~ **Built: `sokar gate checkout <name>`.** It materialises the
  incoming ref into a new directory, with the mirror as its only remote, `core.hooksPath` pointed
  at an empty directory of its own, and a detached HEAD so nothing looks like work to carry on. Not
  a warning somebody can click past: there is no address that reaches the upstream, so a push from
  there cannot arrive by mistake.

  The test for that last property was written against a gate with **no** upstream and therefore
  could not fail; a mutation adding the upstream as a remote sailed past it. Rewritten against a
  gate that has one, it fails as it should. That is the third fixture today that could not
  reproduce the thing it was asserting.
- **Or review on a forge, which is a different trade.** Pushing the incoming ref to a review branch
  on the upstream would give a person the diff view they already know - and it gives up the
  property that unreviewed work never leaves the machine. Worth naming as an option rather than
  dismissing, because for some teams the review tooling is worth more than that property.
- **Whether attaching an editor to the container should be made to work anyway.** It is possible
  and it is currently blocked by the egress rules, and the workaround - declaring an editor
  vendor's hosts - widens the agent's reach for a person's benefit, which is backwards. `podman cp`
  places the server with no egress at all. This matters less if the checkout above exists.
