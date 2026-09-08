# B15 — Handing Artifacts Between Tasks, design

How [B15](B15-Handing-Artifacts-Between-Tasks.md) would be built. **Nothing here exists yet**: every
class, path, method and file below is a proposal, and none of it has been measured. Facts about the
running system are quoted from the code and marked where it matters.

It shares its policy and its record with [B14](B14-Talking-Between-Tasks.md)'s design, deliberately.
Where that is so, this document points there rather than restating it — two versions of one decision
is the failure `TaskInventory` and `TaskControl` exist to prevent.

## Scope of the first version

One machine. A task produces artifacts; a person sees them; another task in a declared peer project
receives the ones its own project asked for. Out of scope: anything crossing machines, publishing to
a repository manager, expiry, and any inspection of what an artifact contains.

## The shape

```
  task A container                     host                              client
  ┌──────────────────────┐   ┌──────────────────────────────┐        ┌──────────────┐
  │ agent writes a file  │   │ sokar store serve (A)        │        │ frontend/CLI │
  │  /run/sokar/out/  ───┼──▶│  1 rename out of reach       │        └──────┬───────┘
  │                      │   │  2 copy → digest → quota     │               │
  │  /run/sokar/in/   ◀──┼───│  3 journal (chained)         │──── store     │ varlink
  │    (read-only)       │   │  4 publish                   │     (per      │
  └──────────────────────┘   │                              │      project) │
                             │              sokard ◀────────┼───────────────┘
  task B container           │                │             │
  ┌──────────────────────┐   │        StorePolicy decides   │
  │  /run/sokar/in/   ◀──┼───│ sokar store serve (B)        │
  └──────────────────────┘   └──────────────────────────────┘
```

No socket is shared, no directory is shared, and the store is mounted nowhere. Each task has its own
drop directory and its own inbox, both bind-mounted from **its own** state directory — the same kind
of mount `/run/sokar/vault.sock` already is.

## What gets built, module by module

| Module | What is added |
|---|---|
| `store/` (new) | `Artifact`, `ArtifactStore`, `ArtifactJournal`, `StorePolicy`, `StoreService`, `Digest`, `PutOutcome`, `StoreException`. |
| `core/project` | `Store` record on `Project`; `ProjectReader` learns one key. |
| `app/` | `StoreCommand` and subcommands, `StoreServeCommand`, `StoreWiring`, `StoreEdit`, one entry in `TaskHelpers`, two methods on `SokarPaths`. |
| `runtime/` | Nothing. `ContainerSpec.volume` already mounts a directory, with `:Z` for SELinux. |
| `daemon/` | New types and methods on `org.fuin.sokar.Tasks1`. |
| `agents/api` | Nothing. This needs no agent capability at all — see below. |

**A naming note before it causes confusion:** `InstallArtifact` already exists in `agents/api` and
means something else entirely — a file an agent's installation downloads. The type here is
`org.fuin.sokar.store.Artifact`, in a different module, and the two should never appear in one
sentence without qualification.

## Why a drop directory rather than a protocol

B14 needs the agent to speak something, because receiving a message mid-run is a concept no shipped
agent has. **Producing a file is not.** Every agent can write a file, so the mechanism can be a
convention instead of a capability:

- **`/run/sokar/out/`** — writable, bind-mounted from `SokarPaths.containerState(container)
  .resolve("out")`. The agent writes finished files here. Nothing else reads it but this task's own
  helper.
- **`/run/sokar/in/`** — mounted read-only, populated by the helper according to the project's
  declaration, with a `manifest.json` naming each file, its digest, its size, and which project and
  task it came from.

That is the whole container-side interface: two directories in a place the agent already has, no
new binary in the image, no MCP, no field in the agent definition, and nothing in Sokar that names
an agent.

**The mount is not a shared directory.** Each is under one task's own state directory. Two tasks
never see the same inode, which is the property the requirement turns on.

**Reading that directory from the host is safe; running anything in it is not.** This looks like the
rule that keeps the host out of `/workspace`, and the difference is worth writing down: that rule
exists because `.git/hooks`, `core.pager` and `core.fsmonitor` make *executing git* in an
agent-controlled directory a way to run agent-controlled code. Hashing bytes executes nothing. So
the helper may read, and nothing anywhere may run a program out of either directory.

**It also survives a container that died**, which `/workspace` does not — the same reason the
clearance journal is not under the runtime directory. An artifact produced by a run that then failed
is still on the host.

## Ingest, step by step, and why the order is that order

1. **Rename out of reach.** `Files.move` from `out/` into a staging directory in the task's state
   directory that is **not** mounted into the container. After this the agent has no path to the
   file. Doing anything else first is a race with a process that is still running.
2. **Refuse anything that is not a regular file.** Opened with `LinkOption.NOFOLLOW_LINKS`, checked
   before it is read. **A symbolic link is refused, never followed** — an agent that drops a link to
   the operator's vault file, `~/.ssh/id_ed25519` or `/etc/passwd` would otherwise have the helper
   read it, hash it, store it and hand it to another project. This is the single most important line
   in this design.
3. **Copy into the store, hashing during the copy, counting bytes during the copy.** Not `stat`
   then read: an open file descriptor the agent still holds can change the length between the two,
   and the quota must be enforced against what is actually written. What is stored is the copy, so
   whatever happens to the agent's descriptor afterwards changes nothing.
4. **Quota and size limits are checked as the bytes arrive**, and exceeding one aborts the copy and
   removes the partial file. The refusal names the number.
5. **Journal, then publish.** The entry is appended before the artifact is visible anywhere. A
   journal that cannot be written means the artifact is not published — the same ordering B14 uses,
   and for the same reason.
6. **Receipt.** A line is written into `out/.receipts/<name>.json` giving the digest, so the agent
   can commit a pointer. Whether it does is a convention; the store's record does not depend on it.

Failure at any step leaves the file in staging and says so, rather than deleting the only copy of
something a run spent an hour producing.

## The store

**`~/.local/share/sokar/artifacts/<project>/<aa>/<rest-of-digest>`**, reached through a new
`SokarPaths.artifactStore(String project)`. Under the data directory, beside `mirrors/`, because it
is content an operator would back up — not under the runtime directory the kernel clears at logout.

**Mode `0644`, owner-only directories, and the executable bit is never set**, whatever the file was
written with. Delivery is a read-only mount on top of that. Two independent reasons an artifact
should not be executable are one reason each too few.

**Per project, and no cross-project deduplication.** A machine-wide content-addressed store answers
"does this digest exist" for anybody who can ask, which is a covert channel with a one-bit payload
and unlimited retries. Two projects that both produce identical bytes store them twice.

## The record

**`~/.local/state/sokar/artifacts/<project>.jsonl`**, `0600`, one JSON object per line, chained
exactly as B14's journal is: `prev` is the previous line's `hash`, the first line's `prev` is 64
zeros, `hash` is SHA-256 over the line's canonical `Json.write` serialization with `hash` removed.
`sokar store verify <project>` walks it and names the first line that does not verify.

```json
{"seq":12,"at":"2026-09-08T11:02:44Z","event":"put","project":"sokar",
 "task":"sokar-a1b2","artifact":"sha256:9c1e…","name":"build.tar.zst","bytes":48210114,
 "media":"application/zstd","commit":"6e4187a…","prev":"…","hash":"…"}
```

`event` is `put`, `hand`, `receive`, `withdraw` or `refused`. **A refusal is recorded too**: a
policy that only writes down what it allowed cannot answer the question anybody actually asks after
an incident.

`commit` is the provenance that cannot be reconstructed later — the revision the workspace was at
when the file was dropped. The helper gets it from the gate's view of the task's branch rather than
by running git in the workspace, which nothing does.

## Policy

```yaml
project:
  name: "sokar"
  security_class: "guarded"
store:
  quota: "5g"
  max_artifact: "512m"
  peers:
    - "sokar-frontend"
  accepts:
    - from: "sokar-frontend"
      names: ["coverage-*.json"]
```

`ProjectReader` learns `store`; `Project` gains a `Store` record beside `Egress` and `Limits`;
editing goes through a `StoreEdit` built like `EgressEdit` — replace the key where it stands, leave
unknown lines alone, and parse with `ProjectReader` before writing.

**`StorePolicy` is the only thing that decides**, asked by the daemon and the CLI alike. Its rules,
in order:

1. Both projects name the other in `peers`. One-sided is refused.
2. Neither project is `offline`.
3. Both projects are the same security class. A `guarded` artifact handed to an `online` project
   reaches an upstream through its peer, and neither end did anything forbidden.
4. The receiving project's `accepts` matches. **The consumer declares; it does not discover.**
5. The receiving project is within quota.

Refusals are **outcomes, not errors**, as `SetEgress` and `WidenTask` already answer.

**If B14 is built, rules 1 to 3 are `TalkPolicy`'s and must be shared rather than copied.** They are
the same sentence about the same file.

## The daemon's interface

Additions to `org.fuin.sokar.Tasks1`, which only grows.

```
type Artifact (
  # sha256:<hex>. Identity is the content; pass it back unchanged.
  digest: string,
  project: string,
  # Container name of the task that produced it.
  task: string,
  name: string,
  bytes: int,
  media: string,
  # Revision the workspace was at, or "" when the task had not committed anything.
  commit: string,
  at: string,
  # Projects it has been handed to, in the order it happened.
  handed: []string
)

type ArtifactOutcome (
  STORED, HANDED, NOT_DECLARED, REFUSED_BY_CLASS, NOT_ACCEPTED, OVER_QUOTA, TOO_LARGE,
  NOT_A_REGULAR_FILE, NO_SUCH_ARTIFACT, ALREADY_HANDED
)

# What a project holds. Never answers across projects in one call: a client that can ask for
# everything is a client that can be asked for everything.
method Artifacts(project: string) -> (artifacts: []Artifact)

# Artifacts as they are produced and handed, for a view that is already open. Streaming only.
method Handovers() -> (artifact: Artifact, event: string, outcome: ArtifactOutcome)

# Hands one to another project. Deliberate, recorded, and refused as an outcome.
method Hand(digest: string, to: string, dryRun: ?bool)
  -> (artifact: ?Artifact, outcome: ArtifactOutcome)

# Removes it from the store. Says what still points at it; does not pretend to recall what was
# already handed over.
method Withdraw(digest: string, reason: ?string)
  -> (outcome: ArtifactOutcome, referencedBy: []string)

error NoSuchArtifact(digest: string)
```

`Hand` takes `dryRun` for the reason `SetEgress` and `WidenTask` do: it is consequential, and what
it would do should be answerable before it is done.

## The command line

```
sokar store list [--project P]
sokar store show <digest>
sokar store hand <digest> --to <project> [--dry-run]
sokar store withdraw <digest> [--reason ...]
sokar store verify [--project P]
sokar store serve ...            # the helper, not for people
```

Rendering only. Every refusal comes from `StorePolicy`, so the CLI and a remote client cannot come
to disagree about what is permitted — the failure that would otherwise be an artifact handed over
remotely that the machine's own tooling says is impossible.

## Lifecycle

| Event | What happens |
|---|---|
| `task run` | The helper starts in phase `BEFORE`; `out/` and `in/` are created `0700` on the host and mounted; the command is recorded in `resume.json`. |
| A file appears in `out/` | Ingest, as above. Watched with a directory watch, with a sweep on helper start so nothing dropped while it was down is missed. |
| `store hand` | Policy, journal, then the file appears in the receiver's `in/` and its `manifest.json`. In that order. |
| `task stop` | The poststop hook reaps the helper by its pid file. `out/` is swept once more before it is given up, so a file written at the end is not lost. |
| `task resume` | The recorded command is replayed. `in/` is rebuilt from the record, so a resumed task finds what it had. |
| A container that never started | `TaskRunner.reapOrphans` covers the helper, since no hook fires for a container that never ran. |
| `task stop --purge` | The store is **not** touched. It is on the host and outliving the container is the point; removing it is `store withdraw`. |
| `sokar panic` | The helper is stopped with everything else. Nothing is removed. |

## Failure modes, and what each must do

| When | What must happen | Why it is listed |
|---|---|---|
| A symlink is dropped in `out/` | Refused and recorded, never followed. | Otherwise the helper reads the operator's vault, ssh key or anything else it can open. |
| The agent holds the file open and keeps writing | The stored copy is what was read; the quota counts what was written. | `stat` then read is a race, and the quota is the thing being cheated. |
| The journal cannot be written | Nothing is published or handed. | "A person sees everything" stops being true and nothing detects it. |
| The quota is exceeded mid-copy | Abort, remove the partial file, name the number. | A partial artifact with a real digest is worse than none. |
| The disk fills | The put fails loudly. | A full disk otherwise presents as "podman cannot start a container". |
| The receiving project does not accept the name | Refused as `NOT_ACCEPTED` and recorded. | The consumer declares; discovery would be the covert channel. |
| An artifact is withdrawn after being handed | Removed here, recorded, and the answer says it cannot be recalled from where it went. | Bytes cannot be un-given, and saying otherwise is the dangerous lie. |
| Two tasks produce identical bytes in one project | One artifact, one set of bytes, both provenances recorded. | Dedup within a project is free and correct. |
| The same bytes in two projects | Two copies. | Cross-project dedup is a digest oracle. |

## What must be proven to fail

Per the rule that a test nobody has watched fail is a test nobody has checked — and that the
**right** thing must fail:

- Drop a symlink to a file outside the mount and assert the store does not contain the target's
  bytes. Assert on the store's content, not on the wording of the refusal.
- Drop a fifo, a directory and a device node. Each refused.
- Write past the quota from a still-open descriptor after the length was `stat`ed, and assert the
  refusal happens and no oversized file is stored.
- Assert the stored file's mode has no executable bit and the inbox mount is read-only, by trying to
  write to it from inside a container.
- Hand across two classes and watch it refuse. Mutate the class in the fixture and watch the refusal
  disappear, to prove the test is reading the class and not something correlated with it.
- Edit one journal line, and separately remove one, and assert verification fails in both cases —
  removal is the case a per-line hash without a chain misses.
- Make the journal unwritable and assert the receiving task's `in/` stays empty.
- Ask for an artifact by a digest belonging to another project and assert the answer is the same as
  for a digest that does not exist anywhere. **The two must be indistinguishable**, or the oracle is
  back.

Everything involving a real mount, a real container writing into `out/`, and a read-only `in/`
belongs in `buildtools/e2e-tier1.sh` — unit tests must run without a container runtime.

## Deliberately not designed here

- **Publishing to a repository manager.** Argued in the requirement: it belongs after the gate, as
  the operator's act on something approved, not as a task's.
- **Scanning artifacts.** Sokar has no scanner and should not grow one. The guarantees are
  provenance, non-execution, declaration and a record.
- **Signing.** Same answer as B14's: the digest is an integrity property and says nothing about
  authorship, authorship comes from the transport, and a key an agent can reach is a key it can
  copy.
- **Cross-machine.** The daemon binds no network interface in any configuration.
- **Expiry.**

## Open questions this design leaves

- **Who writes the committed pointer.** The receipt makes it possible for the agent to; nothing
  makes it certain. If Sokar were to write and commit it, Sokar would be writing into a workspace it
  deliberately never touches. Until that is settled, the store's journal is the record and the
  pointer is a convenience.
- **How the helper learns the commit.** Provenance needs the revision the workspace was at, and
  nothing may run git inside the container or read `/workspace` from the host. Reading it from the
  gate's mirror gives the last *pushed* commit, which is not the same thing and may be
  significantly older.
- **What `in/` should do when the same artifact is handed twice**, or when a newer artifact has the
  same name. Overwriting is a file that changes under a running agent; refusing means a project
  cannot iterate.
- **Whether a directory watch is enough.** A sweep at start and stop covers the gaps this design
  knows about; whether an agent writing a large file in place produces a partial ingest depends on
  how the watch fires, and that has to be measured rather than assumed.
- **Whether the quota should be bytes, count, or both.** A thousand small artifacts is a different
  problem from one enormous one, and `Limits` currently models memory, cpus and pids — none of which
  is a precedent for storage.
