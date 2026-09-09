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
| **Out** | nowhere |
| **Credential in the container** | none |
| **Review** | by hand, out of the mirror |

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
