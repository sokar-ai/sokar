# How far a task can get

What a task can **reach**, **hold**, **spend** and **produce**, and what bounds each. It assumes the
worst case: an agent convinced, by something it read, to work for somebody else.

**Sokar does not detect that.** Any useful task reads text outsiders wrote: the repository, issues,
release notes, build logs. Nothing reliably separates instructions from content. So this page asks
**what a convinced agent is worth**.

Each guard is one of two kinds:
- a **control** holds even against an agent that tries to get round it;
- an **accident-catcher** stops the ordinary mistake and nothing more.

The numbers here are checked against the code by a test.

## Reach: what a task can connect to

| | Bound | What it is |
|---|---|---|
| A name the project did not declare | Does not resolve through the task's resolver | control |
| A declared host | Reachable on ports 80 and 443 only | control |
| The upstream | Never reached by a task, in any class: an online task's gate fetches from it and passes the task's own branch on, from the host; no port to it is open in a container | control |
| An empty `egress` section | Deny, never a default that widens with a release | control |
| A reach for something undeclared | Blocked, and raises a clearance prompt: the closest thing on the machine to a hijack alarm | control |
| The git gate | One address and one port on this machine, never "the machine" | control |
| Another task | No path: separate containers, no shared workspace. Messages between tasks go through the filter and the peer table, both on the host | control |
| The host's files | Nothing outside what Sokar itself mounts or writes | control |

**Not bounded:**

- **The model provider.** Every task has a working route to its provider, with a request body of
  up to **32 MB**. Anything the agent knows can go into a prompt. **This is the largest open path, and it
  is open on purpose**: closing it closes the task. Sokar records what a task sent but raises no alert.
- **Every declared host is a second such channel.** Data fits in a URL to a forge the work clones from.
- **A DNS query sent past the resolver** still gets an answer, though the connection does not.

See [the firewall](security.md) and [DNS](security.md) for how this is enforced.

## Hold: what a task has in its hands

| | Bound | What it is |
|---|---|---|
| The provider credential | Never in the container. The vault holds it; the task gets a phantom token, worthless anywhere else, that dies with the task | control |
| Credential headers from the task | `authorization`, `x-api-key`, `private-token` and `proxy-authorization` are dropped before anything is forwarded | control |
| A credential in the provider's answer | The first 8 KB of every answer are examined for `access_token`, `refresh_token` and `id_token` as JSON fields, before any of it is handed over; the rest is examined as it streams, and an answer that carries one later is cut off there | control |
| A request for a token of its own | A `refresh_token` or `client_credentials` grant is refused, in the query and in the whole body decoded - gzip, deflate or zstd - and a body coded otherwise, coded twice, beyond the limit or not decoding is refused rather than forwarded unread | control |
| A key for the upstream | Never in the container, nor a socket that answers with it, in any class: the host lends it to the gate | control |
| The workspace's `.git/hooks` and `.git/config` | Stay inside the container, where no host-side `git` runs them | control |

## Spend: what a task can use up

**Not bounded: a credential that cannot be read can still be spent.** The phantom token can do
whatever the real credential can at the provider: use tokens, spend money. The only bound is the
account's own limit at the provider, which Sokar does not set.

| | Bound | What it is |
|---|---|---|
| Its gate on the host | Four requests at once, `503` beyond; a push spooled to disk, never held; the gate and its `git` in a scope of 1 GiB memory, 256 tasks and half the default CPU weight | control |

## Produce: what a task can leave behind

| | Bound | What it is |
|---|---|---|
| Work, in `guarded` | Leaves only when a person approves it at the gate; nothing the agent can reach calls approve | control |
| Work, in `offline` | Does not leave at all | control |
| What the agent installed | Counted with `podman diff` before removal, and dies with the container | control |
| What it reached for | Journaled per task, and kept after the task | control |
| `gate protect`'s hook | Fires on a first, an ordinary and a force push. Four things go round it: `--no-verify`, a `core.hooksPath` pointing elsewhere, a fresh clone, and a client built on a git library | **accident-catcher** |
| `gate check`'s author test | The author can be spoofed | **accident-catcher** |

**Not bounded: the work itself.** A plausible but harmful change on the branch the task may write is
stopped by nothing above, only by a person reading the patch. Commit signing does not help: it proves
origin, not intent.

**So the review is ranked.** `sokar gate review` shows first what the task was asked, then every file
that is dangerous by kind, however small the change: CI definitions, build scripts, dependency
manifests and lockfiles, what runs on checkout, files made executable, symbolic links. Then everything
else; reformatting and generated files last. This is not detection; it only sets what a person reads
first.

**The easy way round the gate is a person** fetching the agent's branch and pushing it. Use
`sokar gate checkout` instead: it is the safe way and just as easy.

## When a clearance prompt appears

The agent tried to reach something the project did not declare. Nothing can tell you which of two
things that is:
- an ordinary missing declaration;
- an agent acting on instructions from something it read.

Allow it only if you expected that host. A prompt is asked once per destination and task, and the
answer, or the silence, is journaled. Silence refuses: a prompt nobody answers within 60 seconds is a Deny, and the
destination stays blocked for the run unless a person allows it later from the interface while the task runs.
