# B15 — Handing Artifacts Between Tasks, design

How [B15](B15-Handing-Artifacts-Between-Tasks.md) would be built. **Nothing here exists yet**: every
class, path, method and file below is a proposal, and none of it has been measured. Facts about the
running system are quoted from the code and marked where it matters.

It shares its policy with [B14](B14-Talking-Between-Tasks.md)'s design, deliberately. Its record is its
own: B14 records in a hash-chained log on each host.
Where that is so, this document points there rather than restating it — two versions of one decision
is the failure `TaskInventory` and `TaskControl` exist to prevent.

## Scope of the first version

**This design predates [B15](B15-Handing-Artifacts-Between-Tasks.md)'s decision that artifacts travel
through the project's message transport.** It treats podman's store as the storage, which B15 no
longer has. What below is Sokar's - the drop directory, ingest, the record, the policy, delivery, the
daemon's interface - stays Sokar's; storing and fetching bytes move to the message transport's
artifact verbs, the record below is plain rather than chained, the quota per project gives way to a
limit per artifact, retention is the messaging system's, the gate shows no artifacts, nothing crosses projects, and the design
is redone along those lines before anything is built.

One machine. A task produces artifacts; a person sees them; another task in a declared peer project
receives the ones its own project asked for. Out of scope: anything crossing machines, publishing to
a repository manager, expiry, and any inspection of what an artifact contains.

## What is reused

The two host-side halves of this — a content-addressed store, and a way to get bytes into a
container — do not need building. **podman already has both**, and Sokar already requires podman.

| Need | Reused | Left to build |
|---|---|---|
| Content-addressed storage | `podman artifact add` / `ls` / `inspect` / `rm` — the local OCI artifact store | The per-project namespace and the index over it |
| Delivery into a container | `podman run --mount type=artifact,src=…,dst=…`, read-only, one blob selectable by digest | The mid-run case, which a mount cannot serve |
| Identity | The OCI digest, which the store computes anyway | Nothing |
| Provenance format | in-toto attestation | Filling it in |
| Policy, refusals, record, quota | Nothing | All of it |

`podman artifact` was experimental when it appeared and **is stable as of podman 5.6**; 5.7 added
`inspect --format` and artifact lifecycle events. Both acceptance legs are above that floor —
Ubuntu 26.04 ships podman 5.7.x, Fedora 44 ships 5.8.x — but Sokar today only refuses podman *4*,
so **using this raises the minimum to 5.6**, which needs a `doctor` probe that names it rather than
a put that fails strangely.

What podman does **not** provide is everything this requirement is actually about. Its store is per
*user*, not per project, and it has no notion of who may hand what to whom. The namespace, the
policy, the quota, the record and the refusals below are unchanged by this.

## The shape

```
  task A container                     host                              client
  ┌──────────────────────┐   ┌──────────────────────────────┐        ┌──────────────┐
  │ agent writes a file  │   │ sokar store serve (A)        │        │ frontend/CLI │
  │  /run/sokar/out/  ───┼──▶│  1 rename out of reach       │        └──────┬───────┘
  │                      │   │  2 copy, count, quota        │               │
  │  /run/sokar/in/   ◀──┼───│  3 podman artifact add       │─── podman's   │ varlink
  │    (read-only)       │   │  4 journal (chained)         │    artifact   │
  └──────────────────────┘   │  5 publish                   │    store      │
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
| `store/` (new) | `Artifact`, `ArtifactIndex`, `ArtifactJournal`, `StorePolicy`, `StoreService`, `PutOutcome`, `StoreException`. There is no `ArtifactStore` and no `Digest`: podman holds the bytes and computes the digest. |
| `core/project` | `Store` record on `Project`; `ProjectReader` learns one key. |
| `app/` | `StoreCommand` and subcommands, `StoreServeCommand`, `StoreWiring`, `StoreEdit`, one entry in `TaskHelpers`, one method on `SokarPaths`, and one more `Probe` in `DoctorCommand` for the podman floor. |
| `runtime/` | `Podman` learns the `artifact` subcommands; `ContainerSpec` learns `--mount type=artifact`. |
| `daemon/` | New types and methods on `org.fuin.sokar.Tasks1`. |
| `agents/api` | Nothing. This needs no agent capability at all — see below. |

**A naming note before it causes confusion:** `InstallArtifact` already exists in `agents/api` and
means something else entirely — a file an agent's installation downloads. The type here is
`org.fuin.sokar.store.Artifact`, in a different module, and the two should never appear in one
sentence without qualification.

## Why a drop directory rather than a protocol

B14 needs the agent to take part in a second repository and to read what arrives there mid-run, which
is a convention it has to be told about. **Producing a file is less than that.** Every agent can write a file, so the mechanism can be a
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

### Delivery: a mount at start, the same directory mid-run

**At container start** an artifact the project already accepts is given to podman directly:
`--mount type=artifact,src=<ref>,dst=/run/sokar/in/<name>`. Nothing is copied and nothing is
extracted, and read-only is the runtime's property rather than a permission Sokar has to keep
correct. A single blob can be selected by digest where an artifact holds several.

**Mid-run it cannot be.** A mount is decided at `create`/`run`, which is the shape the ruleset and
the resolver already have: what a container has is built when it starts. So an
artifact handed to a task that is already running is **extracted** into that task's own `in/`
instead. The two paths deliberately land in the same directory, so an agent has one place to look
and never has to know which way it arrived.

## Ingest, step by step, and why the order is that order

1. **Rename out of reach.** `Files.move` from `out/` into a staging directory in the task's state
   directory that is **not** mounted into the container. After this the agent has no path to the
   file. Doing anything else first is a race with a process that is still running.
2. **Refuse anything that is not a regular file.** Opened with `LinkOption.NOFOLLOW_LINKS`, checked
   before it is read. **A symbolic link is refused, never followed** — an agent that drops a link to
   the operator's vault file, `~/.ssh/id_ed25519` or `/etc/passwd` would otherwise have the helper
   read it, hash it, store it and hand it to another project. This is the single most important line
   in this design.
3. **Copy it to a private file, counting bytes as they are copied.** A rename is not enough on its
   own: the agent may still hold an open descriptor to that inode and write through it, so what is
   handed on must be a copy nothing else has a handle on. The count comes from the copy rather than
   from `stat`, because the length can change between the two and the quota is the thing being
   cheated. Exceeding the quota or the per-artifact limit aborts the copy, removes the partial file,
   and names the number.
4. **`podman artifact add` the copy.** The digest is the store's own, so nothing hashes twice, and a
   failure here is a failure to store rather than a half-stored artifact.
5. **Journal, then publish.** The entry is appended before the artifact is visible anywhere. A
   journal that cannot be written means the artifact is not published — the same ordering B14 uses,
   and for the same reason.
6. **Receipt.** A line is written into `out/.receipts/<name>.json` giving the digest, so the agent
   knows what was stored. Nothing about it is committed into the work; the gate shows it from the
   journal.

Failure at any step leaves the file in staging and says so, rather than deleting the only copy of
something a run spent an hour producing.

## The store is podman's

Sokar lays out no directory of its own. It owns the **names** and the **index**; podman owns the
bytes.

**The reference carries the project** — `sokar/<project>/<name>` — so `podman artifact ls` is
readable by a person looking for what a project holds. It is not the authority: podman has no notion
of a project and cannot be asked to enforce one, so Sokar's own index decides what exists, and the
journal below is what it is rebuilt from.

**Nothing in a task can reach the store.** No podman socket is mounted into any container, which is
what keeps a per-*user* store from being a per-machine channel. That was already true; it becomes
load-bearing here, so it is asserted in a test rather than assumed.

**The deduplication nuance, written down because it reads like a contradiction of the
requirement.** OCI blobs are addressed by digest, so two projects producing identical bytes share
one blob on disk whether or not Sokar would like them to. The rule the requirement states is about
what is *observable*: the index is per project, `Artifacts` never answers across projects, and a
digest belonging to another project is answered exactly as a digest that exists nowhere. The
physical deduplication happens; the oracle does not — unless somebody measures free disk, which is
an open question below rather than a solved one.

**Not executable, and read-only where it lands.** An artifact mount is read-only by construction,
and anything extracted into an inbox is written `0644` with no executable bit, whatever the dropped
file carried.

## The record

**`~/.local/state/sokar/artifacts/<project>.jsonl`**, `0600`, one JSON object per line, chained:
`prev` is the previous line's `hash`, the first line's `prev` is 64
zeros, `hash` is SHA-256 over the line's canonical `Json.write` serialization with `hash` removed.
`sokar store verify <project>` walks it and names the first line that does not verify.

**Each line carries an in-toto attestation rather than a shape invented here.** in-toto is
CNCF-graduated and its subject is exactly this: recording who did what, where and how. A statement
names a *subject* — an artifact, by digest, which is how in-toto already addresses one — and a
*predicate* carrying the rest. It is a format, not a service, so it costs nothing at runtime and
means the record can be read by tooling nobody here wrote.

```json
{"seq":12,"prev":"…","hash":"…","statement":{
  "_type":"https://in-toto.io/Statement/v1",
  "subject":[{"name":"build.tar.zst","digest":{"sha256":"9c1e…"}}],
  "predicateType":"https://sokar.fuin.org/Handover/v1",
  "predicate":{"event":"put","project":"sokar","task":"sokar-a1b2","bytes":48210114,
               "media":"application/zstd","commit":"6e4187a…","at":"2026-09-08T11:02:44Z"}}}
```

**The statement is not wrapped in a signed DSSE envelope**, which is how in-toto is usually carried.
The chain is the integrity mechanism here and no key goes near a container. An envelope becomes
worth adding under exactly the condition B14 names: a record that leaves the machine which produced
it — which is why B14, whose record does, signs on the host.

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
| `task run` | The helper starts in phase `BEFORE`; `out/` and `in/` are created `0700` on the host and mounted; artifacts the project already accepts are mounted with `--mount type=artifact`; the command is recorded in `resume.json`. |
| A file appears in `out/` | Ingest, as above. Watched with a directory watch, with a sweep on helper start so nothing dropped while it was down is missed. |
| `store hand` | Policy, journal, then it appears in the receiver's `in/` and its `manifest.json` — mounted if the receiver has not started, extracted if it is already running. In that order. |
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
| The same bytes in two projects | One blob on disk, two index entries, and no way to observe the sharing. | The oracle is closed at Sokar's API, not at podman's storage layer. |
| A hand-over to a task that is already running | Extracted into its `in/`, never mounted. | A mount is decided at create; pretending otherwise produces an artifact nobody can find. |
| podman is older than 5.6 | `doctor` says so by name and a put refuses. | The artifact suite was experimental before that, and an experimental store is not a record. |

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
  back. This is now a test of Sokar's index rather than of its storage, because podman's store does
  deduplicate across projects.
- Assert that no podman socket is reachable from inside a task container. It was always true; it is
  now the thing that keeps a per-user store from being a per-machine channel.
- Run the put path against a podman older than 5.6 and watch `doctor` name it, rather than watching
  a put fail for a reason nobody can read.

Everything involving a real mount, a real container writing into `out/`, and a read-only `in/`
belongs in the acceptance suite — unit tests must run without a container runtime.

## Alternatives considered

The question this design was re-examined against: can an existing, maintained, open-source tool take
the host-side halves, leaving only an adapter? For storage, **yes** — and it is already installed.
For everything else, no.

| Tool | What it gives | Verdict |
|---|---|---|
| **[`podman artifact`](https://docs.podman.io/en/latest/markdown/podman-artifact.1.html)** | A local OCI artifact store with `add`/`ls`/`inspect`/`extract`/`push`/`rm`, digests, and `--mount type=artifact` into a container. Stable [since podman 5.6](https://github.com/podman-container-tools/podman/releases/tag/v5.6.0); Ubuntu 26.04 ships 5.7.x, Fedora 44 ships 5.8.x | **Taken.** No new dependency, no port, no credential, no egress. |
| **[in-toto](https://in-toto.io/) attestations** (CNCF graduated, February 2025) | A standard statement for *who did what, where and how*, addressed by artifact digest | **Taken**, as the journal's line format. A format, not a service. |
| [zot](https://github.com/project-zot/zot) + [ORAS](https://oras.land/docs/) | A full OCI registry with htpasswd auth and identity-based **per-repository** authorization over glob paths — the same shape the git gate already runs on loopback | **Later, not now.** The right answer once artifacts must leave the machine; `podman artifact push` reaches it with the format unchanged, so nothing has to be redesigned to get there. |
| [git-annex](https://git-annex.branchable.com/special_remotes/) | The honest version of the LFS idea: pointers in git, content-addressed bytes, and a plain local directory as a special remote — **no server** | Rejected, but on cost rather than principle: a Haskell runtime, symlink-based worktrees that are awkward inside a container, and the *agent* would have to drive it. Evidence of current maintenance is indirect. |
| Git LFS, Artifactory, Nexus | — | Rejected in the requirement: a second service, a second credential, permanent egress, and an incomplete mirror. |

**What did not get taken off the pile:** the per-project namespace, the class rules, the quota, the
record, the refusals, and the drop directory with its symlink and descriptor traps. Those are the
requirement, and no store answers them.

## Deliberately not designed here

- **Publishing to a repository manager.** Argued in the requirement: it belongs after the gate, as
  the operator's act on something approved, not as a task's.
- **Scanning artifacts.** Sokar has no scanner and should not grow one. The guarantees are
  provenance, non-execution, declaration and a record.
- **Signing.** The digest is an integrity property and says nothing about authorship, authorship
  comes from the transport, and a key an agent can reach is a key it can copy. B14 signs on the host
  only because its record leaves the machine; this one does not.
- **Cross-machine.** The daemon binds no network interface in any configuration.
- **Expiry.**

## Open questions this design leaves

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
- **Whether free disk is a usable oracle.** podman deduplicates blobs by digest across the whole
  user store, so writing bytes that already exist consumes nothing. A project that can observe its
  own quota accounting closely enough might learn that another project holds the same bytes. The fix
  if it matters is to charge a project for what it put rather than for what was stored, which is
  cheap — what is unknown is whether the difference is measurable from inside a task at all.
- **How the reference is named.** `sokar/<project>/<name>` is readable and it is also a namespace
  podman does not enforce, so two Sokars sharing a user account would collide. Nothing does that
  today, and the alternative — an opaque reference and a lookup — costs the `podman artifact ls`
  legibility that makes the store debuggable by hand.
- **Whether the podman floor should be 5.6 or higher.** 5.7 added artifact lifecycle events, which
  would let the helper notice changes it did not make instead of assuming it is the only writer.
  Both test legs are already above 5.7; the floor is about what an operator's machine must have.
- **Whether the quota should be bytes, count, or both.** A thousand small artifacts is a different
  problem from one enormous one, and `Limits` currently models memory, cpus and pids — none of which
  is a precedent for storage.
