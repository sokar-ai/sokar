# The three security classes

A project declares one of three classes, and the class decides two things: what a task may reach,
and where somebody looks at its work before it goes anywhere. The same actors appear in all three
pictures, in the same places — what changes is the path the work takes.

## offline

Nothing resolves and nothing leaves. The agent works against a mirror on this machine, and there is
no route to the upstream — not for the agent, and not for a person inside the container either.

![offline: the agent clones from the local mirror and pushes back to it; the upstream is out of reach](images/security-classes-offline.svg)

| | |
|---|---|
| **In** | seeded from the mirror |
| **Out** | into the mirror; off the machine only by hand |
| **Credential in the container** | none |
| **Review** | by hand, out of the mirror |

### Where the history comes from, and where the work goes

*"Nothing leaves"* is about the container, and the mirror is not in it. The mirror is a bare
repository on the host, at

```
$XDG_DATA_HOME/sokar/mirrors/<project>.git     # ~/.local/share/sokar/mirrors/<project>.git by default
```

where `<project>` is the `name` from `project.yml`. There is one per project and per OS user, and it
is both ends of the journey: the agent clones from it and pushes back into it. So the two questions this raises -
how the history got in, and how changed work gets out - are both answered on the host, by a person,
deliberately.

**Getting a history in, when there is no route to an upstream from the host either.** The mirror is
seeded from the first of these that exists:

1. `--upstream` on the command,
2. the project's `upstream:`,
3. **the checkout you are standing in** - committed history only, since a bare clone has no working
   tree.

The third is the offline case and needs no network at all: `cd` into your own working copy and
start the task. It is printed rather than done silently, because a seed decides what the agent will
believe the project is. A mirror that already exists is never re-seeded. The other way in is
`sokar gate restore`, from a bundle carried here by whatever means a disconnected machine has.

**Getting changed work out.** The agent's push lands in that mirror, so the work is on the host the
moment the task ends - it has left the container, which is the boundary this class is about. From
there:

- `sokar gate checkout <name>` opens waiting work as a copy you can read;
- `sokar gate backup <file>` writes the whole mirror, pending pushes included, as **one bundle** -
  a single file to carry to a machine that does have a route;
- or fetch from the mirror into your own checkout, since it is an ordinary git repository on your
  own disk: `git fetch ~/.local/share/sokar/mirrors/<project>.git`.

**What the class is therefore for.** A project whose upstream must not be reachable from a task -
because the code is sensitive, or the agent is not trusted with a route, or the machine genuinely
has none - and a project that has no upstream at all, where the mirror *is* the origin. What it
costs is that transport is manual: nothing moves off this machine unless a person moves it. That is
the guarantee, not a gap in it.

## guarded

The default, and the one the gate was built for. The agent pushes to a gate on this machine; the
work waits under `refs/sokar/incoming/<task>` until somebody reads it and approves it, and the
approve is what forwards it.

![guarded: the agent pushes to the local gate, the operator reviews and approves, and only the approve reaches the upstream — while a dashed path shows work leaving past the approval](images/security-classes-guarded.svg)

| | |
|---|---|
| **In** | cloned from the mirror |
| **Out** | `sokar gate approve` |
| **Credential in the container** | none |
| **Review** | at the gate, before the upstream |

The dashed path is not a hole in the container: it is a person with their own key, fetching the
agent's branch into their own checkout and pushing it. Nothing stops that, and today it is the
*convenient* way to look at the work — which is what makes it happen. Two consequences follow, and
both are open questions rather than settled behavior:

- The gate then says something untrue. `approve` pushes **and** deletes the incoming ref, and it is
  the delete that empties the queue; a push made by hand leaves the ref, so `gate pending` shows the
  work as waiting for review for ever, and a later `reject` records it as discarded while it is
  live upstream.
- The safe way to inspect the work is the inconvenient one. The working copy is inside the
  container on purpose — a bind-mounted one would let an agent write `.git/hooks/`, which then runs
  with the privileges of whoever next runs git in that directory.

## online

The gate is out of the path. The agent clones from the upstream and pushes to it directly, using an
ssh-agent socket whose key never enters the container. Nothing is reviewed before it lands, which is
what the class is for and why a project has to choose it deliberately rather than a task asking for
it.

![online: the gate is not in the path, the agent clones from and pushes to the upstream itself, and any review happens afterwards on the forge](images/security-classes-online.svg)

| | |
|---|---|
| **In** | cloned from the upstream |
| **Out** | the agent pushes |
| **Credential in the container** | ssh-agent socket |
| **Review** | none, unless somebody does it on the forge afterwards |

## What the pictures do not show

Every arrow above is the same firewall underneath: declared names resolve, everything else is
NXDOMAIN, and only ports 80 and 443 are open to the addresses those names answer with. The gate's
own endpoint is the exception, and it binds loopback.

## What is accepted rather than solved

These are open on purpose, with the reason and with what would close them. A risk that is only
written down in a review answer is one the next reader never meets.

**A unix socket can be taken from a live process, in a window.** Before binding, Sokar connects to
the path and refuses to continue if something answers - so a socket another process is *already*
serving is not removed, which is the case that happens by accident. The check and the unlink are
still two operations, and another process of the same user can bind in between. **The sequence is
therefore not race-free**, and nothing in the code or these documents should say it is. Closing it
takes an ownership protocol where binding and replacing are one step - a per-run directory and an
atomic rename - which changes how a task's runtime directory is laid out and was deliberately not
built. The residual risk is availability: a process left unreachable and running. It is not
disclosure; no credential travels this path.

**The credential scan is known-field lexical filtering with an accepted residual risk**, and that
is the whole of what it claims. It reads every byte of a provider's answer,
matches a watched member name whether it is spelled plainly or with `\uXXXX` escapes, joins a name
split across two reads, and - because JSON allows unlimited whitespace between a name and its colon
- keeps waiting for that colon across however many reads the whitespace fills, rather than across a
fixed window. Each of those closed a way past it that had been measured, the last one after the
first three were already in place.

**What it therefore does not promise.** The decision is a list of member names -
`access_token`, `refresh_token`, `id_token` - so a provider that calls its credential something
else is not covered. That is a real confidentiality gap and it is accepted rather than closed: the
alternative is an incremental parser per supported response format, which has to accept every
framing each provider actually emits, and a parser that mis-frames an ordinary answer breaks every
task rather than withholding one credential. There is a test asserting the gap, so that anyone who
believes they have closed it finds out.

**What the scan refuses to guess about, it refuses outright.** An answer it cannot read is not
streamed on the reasoning that nothing was found in it - not finding something in bytes you cannot
read is not evidence. Two cases fail closed with `502`:

- a body in any content coding but `identity`, where a member name is not absent but *unreadable*;
- a media type outside `application/json` and `text/event-stream`, which may frame a credential in
  a shape the scan does not look for at all.

**So the proxy asks for `identity` itself**, replacing whatever `Accept-Encoding` the agent sent.
Claude Code asks for `gzip, deflate, br`, and the provider compresses exactly when asked - measured
on 2026-09-14, `br` with that header and uncompressed with `identity` or with none. Until the proxy
asked, every answer to a task running Claude Code was withheld and the agent reported an API error.

An answer with no body is not refused, decided on the bytes that arrived rather than on a declared
length. **This has an availability cost and it is deliberate:** a provider that starts answering in
a form not listed here breaks tasks visibly, where the alternative fails silently and in the
direction that loses a credential. Where it is wrong it is wrong loudly, which is the only kind of
wrong that gets fixed.

All of it is checked end to end through the proxy, not only as a unit, so what is asserted is what
the container did or did not receive.

**This risk is accepted on a condition, not in general.** It holds only while the three watched
names and the two understood media types are maintained against what the supported providers
actually emit. **A provider changing its response schema reopens the decision** - it is not
inherited by whoever reads this next, and the test that asserts the gap is where that shows up.
