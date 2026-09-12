# B42 — Where The Agent Protocols Touch This

**Status:** open, and it is a reading task rather than a feature. Asked for by the operator on
2026-09-11: four protocols are settling around agents, and what this product should do about each
is not obvious from either direction. Some of it is already decided here without the protocol being
named; some of it is a decision this project has already refused; at least one may be a format
worth adopting for nothing.

## The four, and the axis each one sits on

| Protocol | What it is for | Between |
|---|---|---|
| **A2A** | collaboration, task delegation, peer-to-peer exchange | agent ↔ agent |
| **MCP** | data sources, organisational knowledge, local tools | agent ↔ tool / database |
| **ACP** | putting an assistant into the developer's working environment | agent ↔ IDE / editor |
| **AG-UI / A2UI** | streaming intermediate state and UI elements to a person | agent ↔ interface |

**They are not alternatives to each other and the question is not which one to pick.** Each names a
different edge of the same box, and this product already has an answer at three of those edges -
reached without the protocol, and worth comparing against it rather than replaced by it.

## What is already decided here, so this does not get argued twice

- **A2A is already chosen as a format, and not as a server.**
  [B14](B14-Talking-Between-Tasks.md)'s design says `Talk1`'s payload should be an A2A message
  *"rather than a shape invented here, so that an agent already speaking A2A needs no adapter
  later. It is a format, it costs nothing at runtime, and it commits to no server."* That is
  settled and this file does not reopen it.
- **A2A as a network protocol is refused by a property rather than by preference.** The daemon
  binds no network interface in any configuration, so a daemon that accepts a peer is a different
  product. Same argument as cross-machine talking in B14.
- **MCP is already in the credential design.**
  [B31](B31-An-Authorization-A-Person-Grants-Once.md) exists *because* of remote MCP servers - the
  case where an agent inherits a person's permissions rather than a service account's - and takes
  its shape from what that ecosystem settled on: RFC 9728, PKCE, dynamic client registration,
  revocation, and the explicit refusal of client credentials as a passthrough violation.
- **The interface has an answer at the fourth edge and it is not a protocol.**
  [F28](https://github.com/sokar-ai/sokar-frontend) opens the window on what needs a person, fed by
  `Prompts` and `Watch` over the daemon's own socket.

## What must be true

**For each of the four, this project can say whether it adopts it, is already doing it another way,
or refuses it - with the reason, and with what would have to change if the answer were different.**

## Acceptance

- Each protocol gets an answer of one of three kinds: **adopt**, **already answered otherwise**, or
  **refuse** - and a refusal names the property it conflicts with rather than a preference.
- Where this product already does the same job differently, the two are compared on what an
  operator gets, not on which is more standard.
- Anything adopted is adopted at the narrowest useful level. A format costs nothing at runtime; a
  server is a network surface, and this daemon has a property about those.
- The answer says what it would take to change it later, so a decision made now is not mistaken
  for a door closed.
- Nothing here becomes a dependency on a specification that is still moving without that being
  said out loud, with the version read.

## To be checked

- **ACP is the one edge this product has no answer at, and may not want one.** An IDE integration
  puts an assistant in the developer's environment; Sokar's whole claim is that the agent is in a
  box and the developer is outside it. Whether ACP describes *the agent inside the task* speaking
  to an editor inside the same task - which would be a container-internal matter Sokar does not
  touch - or an editor outside reaching in, which is a hole in the box, decides whether this is
  interesting or refused. **Read the specification before answering.**
- **Whether MCP arrives as something a task uses or as something Sokar speaks.** B31 treats an MCP
  server as a destination a credential is needed for. An agent inside a task calling one is a
  `destination` in B28's sense and needs no new mechanism. Sokar itself becoming an MCP server -
  exposing tasks, the gate or the vault as tools - is a different thing entirely and would put a
  tool-call surface on the daemon.
- **Whether AG-UI overlaps F28 or sits under it.** Streaming intermediate state to a person is what
  `Prompts` and `Watch` already do over a unix socket. If AG-UI is a schema for that, it is a
  format question like A2A. If it presumes a browser and a server, it meets the same refusal the
  interface already recorded.
- **Which of these are stable enough to depend on.** A2A carried a version and a date when B14 read
  it (Linux Foundation, v1.0.1, May 2026). The others have not been read here at all, and a
  specification that moves under a format is a different risk from one that moves under a wire.
- **Whether any of this is urgent.** Nothing in the product is waiting on an answer. The reason to
  do it at all is that each of these edges already has a decision here, and a decision that was
  never compared with what the field settled on is one nobody can defend later.
