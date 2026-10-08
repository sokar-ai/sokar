# B123 — Message Transports Behind A Published API

**Status:** later.

**What must be true.** A message transport is written against a published, versioned Java API, as an agent is - a
library it builds on and a contract Sokar checks at runtime - and every transport lives in a repository of its own,
so a new one needs nothing from `sokar` but that API.

## Why

A transport is already a package of its own: `sokar-message-matrix` is installed on the host, Sokar finds it and
runs it, and no Matrix code is in `sokar` (the decision "Sokar knows transports; the transport knows Matrix"). What
it builds against is not. The contract is a command line - `describe`, `send`, `poll`, the lifecycle verbs, exit
codes from 0 to 78, files in a directory Sokar made - written down only in
[writing a transport](../../doc/transports.md). So every transport parses its own arguments, maps its own exit codes
and writes its own JSON, and a mismatch between what Sokar expects and what a transport does is found when a message
does not arrive.

Agents solved the same problem differently, and better for whoever writes the next one: `sokar-agent-api` on Central
holds both the contract and the code that serves it - an agent implements an interface, the library speaks the
versioned varlink protocol `org.fuin.sokar.Agent1`, and Sokar refuses an agent whose protocol version it does not
know rather than misreading it. The build readers take that shape too ([writing a build reader](../../doc/build-readers.md)). Messaging should not be the one
extension point left on a hand-written command line.

## What changes

- **A published `sokar-transport-api`**, beside `sokar-agent-api`: the transport's interface (describe, send, poll,
  read, receipt, and the lifecycle `setup`, `enroll`, `retire`, `join`, `settings`), the server side an implementation
  runs, and the client Sokar uses. Every refusal a named value, as the exit codes are today.
- **A versioned varlink contract**, `org.fuin.sokar.Transport1`, with a protocol version Sokar checks before it uses a
  transport; one that speaks another version is refused against its own name, and the others stay usable.
- **Secrets stay where they are today**: kept in the vault and handed to the transport on the host, never into a
  task; over the owner-only socket rather than the environment of a command.
- **A stub transport in `sokar`**, as `agents/stub` is for agents, so the acceptance suite proves the contract
  without a homeserver; nothing outside it names a transport.
- **`sokar-message-matrix` moves to the API** in its own repository; a second transport is written against the API
  alone.

## Acceptance

- A transport built only against `sokar-transport-api` and `sokar-wire` carries a message between two tasks, and
  `sokar` holds no line of it. Seen to fail: the stub transport needs anything from `sokar` beyond those two.
- A transport speaking an unknown protocol version is refused, named, and the other transports keep working. Seen
  to fail: one broken transport stops messages through another.
- Everything `doc/transports.md` promises today holds over the API: the guarantees of delivery, the signature
  beside the message, the refusals and what each means. Seen to fail: a scenario of the messaging features that
  passes with the command line fails with the API.
- No transport secret reaches a task or a command line. Seen to fail: a search of the process list and of a task's
  environment finds one.

## To be checked

- **Whether a transport runs as a long-lived process**, as an agent does, or is started per call as today: a
  process per poll costs a JVM start where the transport is not native.
- **How the move happens without a gap**: both contracts side by side for one release, or `sokar-message-matrix`
  and `sokar` moved in one step.
- **Whether `sokar-agent-api`, the build readers' API and this one share the scaffolding** - discovery, handshake,
  protocol version, a broken one recorded against its own name - in one library, or each carries its own.
