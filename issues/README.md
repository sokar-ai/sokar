# Requirements

What Sokar must do, in sets. Each set has its own index, its own numbering and its own
order — importance is decided inside a category, not across them, because the people working on
one rarely need to rank it against another.

| Set | What it covers | Files |
|---|---|---|
| [**Base**](base/README.md) | The product below the interface: the CLI, the daemon, and the guarantees they make. | `B01`… |
| [**Providers**](providers/README.md) | Which providers exist, and how a task reaches one without ever holding its credential. | `P05`… |

An `A` number cited here - an agent that has no repository yet - is `sokar-project`'s, and an `F` number is
`sokar-frontend`'s, in
[its own index](https://github.com/sokar-ai/sokar-frontend/blob/main/issues/README.md); what connects the two
repositories is the daemon's contract, `daemon/src/main/resources/varlink/org.fuin.sokar.Tasks1/`.

## How to read one

Every file carries its own **acceptance criteria**, so it can be judged done or not done rather
than discussed. A file that ends with a **To be checked** section has something unresolved whose
answer could change what the requirement says — or whether it survives at all; its index marks
that as an open question.

The number is identity, not order: `B06` is the sixth base requirement written, not the sixth to
build. Each index orders its own set by what to do next.

**A finished requirement is deleted, not marked done.** What it measured — the things that were
expensive to learn, and the traps that would otherwise be learned twice — moves into
[AGENTS.md](../AGENTS.md), and any question it leaves behind moves to whichever requirement now
owns it. The set is therefore what is left to do, not a history of what was done; the history is
in git.

## Agents and providers side by side

Which agents can be brokered at all, what each costs and which to build next is
[the comparison of agents and providers](https://github.com/sokar-ai/sokar-project/blob/main/doc/agents-and-providers.md) in `sokar-project`, since it spans agents that
have no repository here.

## Why agents and providers are separate sets

An **agent** is code: it has behavior that cannot be expressed as data — a stream formatter,
first-run setup, one CLI's quirks. A **provider** is data: an upstream, a header, a prefix and a
path, found by scanning a directory. Adding a provider is a file; adding an agent is a release.
That difference is why they are separate sets rather than one list of integrations.
