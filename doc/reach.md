# How far a task can get

What a task can **reach**, **hold**, **spend** and **produce**, what bounds each, and what nothing
bounds. It assumes the worst case, not the usual one: an agent that has been convinced, by something it
read, to work for somebody else.

**Sokar does not detect that.** A task reads text an outsider wrote whenever it does anything useful:
the repository, an issue, a dependency's release notes, a build log. No filter separates instructions
from content in text meant to be read by something that follows instructions. So this page is not about
whether an agent gets convinced. It is about **what a convinced agent is worth**.

Every guard below says what it is:
- a **control** holds even against an agent that tries to get round it;
- an **accident-catcher** stops the ordinary mistake and nothing more.

A number on this page is held to the code by a test (`ReachDocumentTest`): change a bound in the code,
and the build fails until this page says the same.

## Reach: what a task can connect to

| | Bound | What it is |
|---|---|---|
| A name the project did not declare | Does not resolve through the task's resolver | control |
| A declared host | Reachable on ports 80 and 443 only, unless the project names others | control |
| An empty `egress` section | Deny, never a default that widens with a release | control |
| A reach for something undeclared | Blocked, and raises a clearance prompt: the closest thing on the machine to a hijack alarm | control |
| The git gate | One address and one port on this machine, never "the machine" | control |
| Another task | No path: separate containers, no shared workspace. Messaging between tasks goes through the filter and the peer table, both on the host | control |
| The host's files | Nothing outside what Sokar itself mounts or writes | control |

**Not bounded:**

- **The model provider.** Every task has, by construction, a working route to its provider, carrying a
  request body of up to **32 MB**. Anything the agent knows can be written into a prompt. No egress set
  closes this, because closing it is closing the task. **This is the largest open path, and it is open
  on purpose.** Sokar keeps a record of what a task sent, and raises no alert: a false alarm on a
  working task is worse than none. What would change this: a provider that could be told what a task may
  send.
- **Every declared host is a second such channel.** A project that declares a forge so the work can
  clone from it has an outbound path to that forge on 443, and data fits in a URL. Declaring what the
  work needs is correct, and it is also the shape of the hole.
- **A DNS query sent past the resolver** still gets an answer, though the connection does not.

## Hold: what a task has in its hands

| | Bound | What it is |
|---|---|---|
| The provider credential | Never in the container. The vault holds it; the task gets a phantom token, worthless anywhere else, that dies with the task | control |
| Credential headers from the task | `authorization`, `x-api-key`, `private-token` and `proxy-authorization` are dropped before anything is forwarded | control |
| A credential in the provider's answer | The first 8 KB of every answer are examined for `access_token`, `refresh_token` and `id_token` as JSON fields, before any of it is handed over | control |
| A request for a token of its own | A `refresh_token` or `client_credentials` grant is refused outright | control |
| The ssh signing key | Not in the container; the task gets an agent socket | control |
| The workspace's `.git/hooks` and `.git/config` | Stay inside the container, where no host-side `git` runs them | control |

## Spend: what a task can use up

**Not bounded: a credential that cannot be read can still be spent.** Inside the task, the phantom token
commands whatever the real credential commands: tokens, money, and whatever else that account can do at
the provider. Non-exfiltration is not non-use. The bound is the account's own limit at the provider,
which Sokar does not set.

## Produce: what a task can leave behind

| | Bound | What it is |
|---|---|---|
| Work, in `guarded` | Leaves only when a person approves it at the gate; nothing the agent can reach calls approve | control |
| Work, in `offline` | Does not leave at all | control |
| What the agent installed | Counted with `podman diff` before removal, and dies with the container | control |
| What it reached for | Journaled per task, and kept after the task | control |
| `gate protect`'s hook | Fires on a first, an ordinary and a force push. Four things go round it: `--no-verify`, a `core.hooksPath` pointing elsewhere, a fresh clone, and a client built on a git library | **accident-catcher** |
| `gate check`'s author test | The author can be spoofed | **accident-catcher** |

**Not bounded: the work itself.** It is the intended output, and it is what a convinced agent writes
into. A plausible change on the branch the task may write is blocked by nothing above. It is caught, if
at all, by a person reading the patch. Commit signing does not help: the task signs whatever the agent
wrote, so the signature proves origin, not intent.

**That is why the review is ranked.** `sokar gate review` shows first what the task was asked. Then it
lists every file that is dangerous by kind, however small the change: a CI definition, a build script, a
dependency manifest or lockfile, what runs on checkout, a file made executable, a symbolic link. Then
everything else. Reformatting and generated files come last. The patch is printed in the same order.
None of this is detection: it only changes what a person reads first.

**And the convenient path around the gate is a person.** Fetching the agent's branch into your own
checkout and pushing it is two ordinary git commands. `sokar gate checkout` exists so that the safe way
is also the easy one.

## When a clearance prompt appears

It means the agent tried to reach something the project did not declare. That is one of two things, and
nothing on the machine can tell which:
- an ordinary missing declaration;
- an agent acting on instructions from something it read.

Allow it only if you expected that host. A prompt is asked once per destination and task, and the answer,
or the silence, is journaled.
