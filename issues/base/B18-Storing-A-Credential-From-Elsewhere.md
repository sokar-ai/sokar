# B18 — Storing A Credential From Elsewhere

**Status:** later; whether to build it is parked, `sokar vault login` is built from the CLI, `Login` held open as nice to have.

**What must be true.** A person at an interface can store a credential in a machine's vault, the
reply never carries the value back, and nothing keeps a copy.

## Why

**Whether to build it is parked.** The rule was decided, then refined, then the passphrase was
examined and two of the three original arguments turned out not to survive. Decided: hold both
secrets together and decide them together, in
[the secrets-from-elsewhere design](Secrets-From-Elsewhere_design.md).

**Until then a credential is entered at the node, over ssh** - the same rule as the passphrase.
Nothing below is on the contract, and an interface asks for a secret nowhere.

What follows is *what must be true if it is built*, which is worth having written down either way:
the reasoning is what a later decision will be taken on.

Somebody running Sokar on a machine they are not sitting at cannot get a credential into the vault
from the interface. Today the honest instruction is *"open a shell on that node and type it
there"*, and for a long-lived API key that instruction is worse than it sounds.

### What was decided, and what changed it

**First: no secret crosses the daemon's socket, either half.** `Credentials` answers names, kinds
and lengths and never a value; there is no `Unlock`; and storing one was refused on the same
reasoning.

**Then the reasoning was tested and did not survive intact.** The argument for it was that the
plaintext should not pass through a GUI. But the recommended alternative — paste it into an ssh
session — puts the key through the browser, the clipboard, a terminal emulator's paste buffer and
its **scrollback**, which many terminals persist to disk. Measured: `vault put` read a typed
credential through the echoing stream, while the vault passphrase had always been read without
echo. The product contradicted itself, and the advice pointed at the path that wrote the secret
down. That is fixed, and it is why this requirement exists rather than a note saying "no".

**The refined rule, decided: a secret may be *transferred*, and must never be *stored*.**
Those are different things and collapsing them was the error:

- **Transfer** — a secret in flight through a process. Unavoidable on every path, including the
  one that was being recommended as safer.
- **Storage** — a secret at rest where it does not belong. That is the real risk, and it is the one
  worth an absolute rule.

**And the strongest point is neither.** A long-lived API key is carried around whatever route it
takes; the mitigation that actually works is a short lifetime. Sokar already believes this — a
phantom token is random, task-scoped and expiring precisely so a leak decays. The provider key is
the one long-lived secret left in the system, and that is the more valuable thing to fix.

## Not this requirement's to enforce, and that is the point

**A client must not persist what it transfers**: not in local storage, not in a form draft, not in
a crash report, not in an undo buffer. It clears the field after sending and never re-displays the
value.

The daemon cannot check any of that. It is a requirement on whoever builds the interface, and it is
recorded here so that it is a stated obligation rather than an assumption — the same shape as
"never write a revealed secret to a log", which was always the client's to keep.

## The shape

```
method StoreCredential(
  name: string,       # provider or agent; the daemon resolves what it is stored under
  value: string,      # the secret, in flight and stored nowhere else
  type: ?string       # "api-key", "oauth"; inferred when absent
) -> (
  outcome: StoreOutcome,   # STORED, REPLACED, VAULT_LOCKED, UNBROKERABLE, REFUSED
  storedAs: string,        # the key it actually went under
  kind: string,
  length: int,             # so a client can show that something arrived without showing what
  detail: string
)
```

`length` rather than the value is deliberate and already the convention: it is how somebody
confirms the paste worked without the confirmation itself becoming a place the secret is shown.

## Answered while writing this: a credential is not routed to a project

The interface asked for *"which keys reach which project"*, answerable in both directions, with
links made and unmade. **That question has no answer here, because the system has no such
relation.** A credential is keyed by the **provider's** name — falling back to the **agent's** name
for vaults written before that changed — and nothing in a project file names a key.

Restated correctly it becomes *"which agents may this project use"*, and that is the **agent
roster**, which was already refused: an explicit per-project list of agents does not exist, for the
same reason "all sets, including ones installed later" was refused for egress.

So there is nothing to build, and the useful output is the sentence rather than a method. Recorded
here rather than left looking like work somebody forgot.

**The name resolution is worth stating separately**, because it is the one part a client must not
compute: a caller intersecting "providers" with "stored credential names" would report a missing
credential for precisely the vault that has one, since the fallback key is invisible from outside.
That is why `StoreCredential` takes a name and the daemon decides what it is stored under.

## The rest of the shape, so it is not only in a channel message

`StoreCredential` is the half with a secret in it. Three more were designed beside it and existed
nowhere durable, which is how a design becomes something two people remember differently.

**`Providers` is the one worth building first**, whatever is decided above: it is a read with no
secret in it, and it is most of what an interface needs.

```
method Providers() -> (providers: []Provider, readable: bool)

type Provider (
  name: string,            # as an agent names it, and as 'vault put' takes it
  label: string,
  upstream: string,
  dialects: []string,
  authenticated: bool,     # MEANINGLESS unless 'readable' - the same trap as Credentials
  credentialType: string,  # "api-key", "oauth", or "" when none is stored
  credentialName: string,  # the key it is stored under, by the rule above
  storeCommand: string     # the exact line to run at the machine
)
```

`storeCommand` is the whole answer to *"where do I type it"*, and it exists because a client
cannot build that line: the key name is the provider's, falling back to the agent's, and the
fallback is invisible from outside.

```
method ImportCredential(agent: string, dryRun: ?bool) -> (
  outcome: ImportOutcome, name: string, type: string, length: int, detail: string
)
```

**`ImportCredential` moves no secret at all** - the daemon reads the agent's own config file on its
own disk, and only an agent name crosses. It is therefore unaffected by whatever the parked
decision decides, and is the better default wherever an agent can log itself in.

### Built: `sokar vault login`, which is this flow from the CLI

The half below was parked as *later*. What was built instead is the same thing driven from a
terminal rather than from an interface, and it is worth saying that they are one mechanism:

- the agent's own login runs in a **throwaway container** - not on the node, because the agent's
  tooling lives in the task image and a fresh machine has nothing to log in with;
- the container **shares the node's network**, so a redirect to `localhost` lands on the node;
- reaching it from another machine is the **`ssh -L` forward measured for the interface**, with
  the same findings: a local forward, a port that cannot be remapped, no ordering problem, IPv6
  not a trap, and a collision that exits zero while binding only half.

**One thing the design did not anticipate, found by somebody following the guide.** A container
has no browser and no display, so the agent's attempt to open one neither succeeds nor reports
anything - leaving a person at a prompt that never continues, inside a container they did not know
how to leave. The login image now installs a shim at `xdg-open`, `sensible-browser` and
`www-browser`, and sets `BROWSER`, which prints the URL and exits zero. The agent believes a
browser opened and waits for the callback, which is what it should do.

**An interface would need exactly the same shim**, because the container is the same one. That is
the part of `Login` below which is now answered rather than designed.

### `Login` - later, and nice to have rather than needed

**Kept open deliberately, and not blocking anything.** What follows is a design nobody should
build yet, with the reason it is not urgent written beside it so it is not rediscovered as a gap.

**Three different things get called "login", and this is only the first of them:**

| | what happens | where |
|---|---|---|
| getting the credential | the agent's own flow - `claude setup-token` and its equivalents - opens a browser and produces a long-lived token | **on the node**, once, outside any container |
| a task authenticating | nothing logs in: the agent holds a phantom token and the broker swaps the real credential in on the way out | in the container, per request |
| logging in inside a container | blocked and pointless - the ruleset denies the provider's own host so an agent must use the proxy socket, and the value would land in a container that is removed | - |

**Why it is not needed.** Somebody at a remote interface already holds a terminal on that machine:
the socket only reaches them because it is forwarded over ssh. So this would save them typing one
command in a window they already have, and the browser half works remotely through an `ssh -L`
forward either way. The complete story without it is: run the agent's own login on the node, then
`ImportCredential`, which moves no secret at all.

**Why it is not free.** Nothing tells Sokar how to log an agent in - `AgentDefinition` has no such
field and the agent protocol has two verbs, `describe` and `serve`. The knowledge exists only in
prose, in each agent's own documentation. Building this means a new field in the **agent manifest**,
landing in every agent repository, after which Sokar owns a flow it cannot test for agents it does
not ship. That is the shape of the instructions problem: per-agent knowledge that goes stale
silently, where being wrong produces a confident failure rather than an obvious one.

**The smaller version, if the gap turns out to be real.** Two strings the agent declares and Sokar
only *displays*, never runs - a login command and a documentation link - shown beside the
`storeCommand` that already says where to put the value. Same principle as instructions: Sokar
shows what an agent says about itself and runs nothing. Worth doing only if somebody is observed
getting stuck at *"where does the value come from?"*.

```
method Login(agent: string, dryRun: ?bool) -> (
  outcome: LoginOutcome,   # WAITING, FORWARD_NEEDED, SUCCEEDED, FAILED, UNSUPPORTED, VAULT_LOCKED
  url: string,             # what a person must open
  callbackPort: int,       # 0 when the flow needs no forward
  userCode: string,        # for a device-code flow, which needs no tunnel at all
  detail: string
)
```

Used with `more`, because a login prints a URL and then waits for a person. `callbackPort` is read
out of the printed `redirect_uri` rather than assumed - most CLIs pick a free port at random - and
it is `0` rather than absent because **a device-code flow needs no port, no forward and no browser
on the client's machine.** Whether a forward is needed is the agent's property, so nothing may be
built as though the redirect flow were the only one.

The measurements this would rest on are already taken and are in
[secrets from elsewhere](Secrets-From-Elsewhere_design.md): the forward is a local one, the port
cannot be remapped, there is no ordering problem, IPv6 is not a trap, and a port collision exits
zero while binding only half. They keep whether or not this is ever built.

An OAuth login is also the case where the parked decision matters least: the token is minted by the
provider and delivered straight into the node's process, so it never crosses anything either way.

## Notes

The engineering cost is real and should be stated rather than discovered. Today *"the contract
never carries a value"* is a property somebody can verify by reading the interface description.
After this it becomes a property that every future logging change, error message and streamed reply
has to preserve. That is a worse kind of guarantee, accepted knowingly.

## Acceptance

- A credential can be stored over the contract, and the reply names it — name, kind and length —
  and never returns the value.
- The value appears in no log, no trace, no error message that echoes the request, no streamed
  reply and no operation record, on any path, including failures.
- A credential already on the node can be adopted without any value crossing at all.
- The key it is stored under is computed by the daemon, not by the caller.
- Storing a credential the vault cannot accept — locked, or a kind the provider cannot broker —
  is refused by name rather than reported as success.
- The CLI never echoes a typed credential, so no path is the weak one.
- **Seen to fail:** a test that stores a credential through every path, failures included, and
  searches the logs, traces, error messages, streamed replies and operation records for the value
  goes red when any of them carries it; a test that stores into a locked vault or for an
  unbrokerable kind goes red when the reply says stored; a test that types a credential at the CLI
  goes red when it is echoed.

## To be checked

- **Whether the daemon should refuse to store a credential with no expiry**, or merely say so.
  The lifetime argument above says the long-lived key is the problem; refusing one outright would
  break every provider that only issues those.
- **Whether `Import` should be preferred in the interface**, since it moves no secret at all. It
  looks like the better default wherever the agent can log itself in, with typing as the fallback
  rather than the first offer.
