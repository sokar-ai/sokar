# Requirements

What Sokar must do, in four sets. Each set has its own index, its own numbering and its own
order — importance is decided inside a category, not across them, because the people working on
one rarely need to rank it against another.

| Set | What it covers | Files |
|---|---|---|
| [**Base**](base/README.md) | The product below the interface: the CLI, the daemon, and the guarantees they make. | `B01`… |
| [**Frontend**](frontend/README.md) | The interface people actually use, described as what must be true for a person using it. | `F01`… |
| [**Agents**](agents/README.md) | Which agents exist, how each authenticates, whether it can be brokered, and how updates follow upstream. | `A01`… |
| [**Providers**](providers/README.md) | Which providers exist, and how a task reaches one without ever holding its credential. | `P01`… |

## How to read one

Every file carries its own **acceptance criteria**, so it can be judged done or not done rather
than discussed. A file that ends with a **To be checked** section has something unresolved whose
answer could change what the requirement says — or whether it survives at all; its index marks
that as an open question.

The number is identity, not order: `B06` is the sixth base requirement written, not the sixth to
build. Each index orders its own set by what to do next.

**A finished requirement is deleted, not marked done.** What it measured — the things that were
expensive to learn, and the traps that would otherwise be learned twice — moves into
[AGENT.md](../doc/AGENT.md), and any question it leaves behind moves to whichever requirement now
owns it. The set is therefore what is left to do, not a history of what was done; the history is
in git.

## The one thing that spans two sets

[**Agents And Providers Compared**](Agents-And-Providers-Compared.md) sets every agent and every
provider side by side, because the comparison is what a single file cannot hold: which of them
can be brokered at all — the column that decides whether an agent can be supported — what each
costs, and which to build next. It belongs to two sets, so it lives here rather than inside one.

## Why these four

An **agent** is code: it has behaviour that cannot be expressed as data — a stream formatter,
first-run setup, one CLI's quirks. A **provider** is data: an upstream, a header, a prefix and a
path, found by scanning a directory. Adding a provider is a file; adding an agent is a release.
That difference is why they are separate sets rather than one list of integrations.

The **frontend** is separate because it is judged differently — what must be true for a person
using it, rather than what must be true of the machine — and because it should be workable on its
own. Each set is a candidate for a repository of its own when that becomes worth it, the way every
shipped agent already has one.
