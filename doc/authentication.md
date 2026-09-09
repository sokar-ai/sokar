# Authentication

Two different things need a credential, and they are not the same problem.

- **The provider** — the model API an agent talks to. Anthropic, OpenRouter, whatever a project
  points at.
- **The upstream** — the git remote work is eventually pushed to. That is signed with an ssh key,
  and the key is never in the container either.

Everything below answers one question: **where does the secret exist, and what has to be trusted
with it?** The answers are deliberately narrow, and the narrowest of them is the rule that decides
the rest: *nothing on the daemon's socket ever carries a credential value.*

## The kinds

| kind | what it is | where it comes from |
|---|---|---|
| `api-key` | a long-lived key you were issued | typed in, once |
| `oauth` | a subscription token the agent obtained by logging in | the agent's own login, copied out of its config |
| ssh key seed | 32 bytes an ed25519 signing key is derived from | stored like any other secret, under `ssh.default` |

An API key and a subscription token are **not interchangeable**. They go in different headers under
different prefixes, and sending one as the other fails as an authentication error that looks
exactly like a wrong key. That mapping is the provider's fact, declared once in
`providers/*.yaml`, not restated by each agent:

```yaml
auth_header:
  _default: x-api-key
  oauth: Authorization
auth_prefix:
  _default: ""
  oauth: "Bearer "
```

## At run time, the container never holds the real one

![the container holds a phantom token and presents it to a proxy on a unix socket; the proxy reads the real credential from the vault, drops every credential header the agent sent, adds the real one and reissues the request over TLS; a dashed path shows a direct attempt denied by the ruleset](images/auth-broker.svg)

The agent is given a **phantom token**: random, scoped to the one task, and accepted for a bounded
time (`--token-hours`, eight by default). It presents that to a proxy over a unix socket the
container has bind-mounted. The proxy reads the real credential per request, **drops every
credential header the agent sent** — `authorization`, `x-api-key`, `private-token`,
`proxy-authorization` — adds the right one, and reissues the request upstream over TLS.

So an agent that leaks what it holds has leaked something that stops working when the task ends.

Two details that are easy to get wrong and were both paid for once:

- **A socket, not a port.** The filesystem decides who may connect, rather than a firewall rule
  that has to be right. The socket itself is world-writable and its *parent directory* is `0700` —
  because a rootless container's user is a subordinate uid on the node and cannot open a `0600`
  socket the node's user owns.
- **The ruleset has to deny the upstream host as well.** An agent with a compiled-in base URL will
  otherwise ignore the socket entirely and send the phantom token straight to the provider. The
  proxy is only half the containment.

## Getting a credential in

![three ways a credential reaches the vault: an operator types an API key on standard input, an agent logs itself in and vault import copies the token out of its own config file, and an operator stores an ssh key seed the same way](images/auth-entry.svg)

All three happen **on the node**. Two details of `vault put` that are the difference between a
secret that is written down and one that is not:

- **It never takes the value as an argument.** An argument list is world-readable on the machine.
- **At a terminal it asks, without echoing.** This was a real leak until 2026-09-08: a typed
  credential was read through the echoing stream, so pasting one printed it and left it in the
  scrollback, which many terminals persist to disk. The vault *passphrase* had always been read
  without echo — the product contradicted itself, and the advice to "store it at the machine rather
  than over the wire" was recommending the path that wrote the secret down. A piped value is still
  read from standard input, because a script has no terminal to not echo to.

`vault import` is the one worth knowing about, because it removes the retyping that invites a typo
— and the commonest typo is storing the guide's placeholder rather than the key.

## Doing it from somewhere else

The rule is short: **no secret crosses the varlink socket.** `Credentials` answers names, kinds and
lengths and never a value, there is no `Unlock`, and nothing stores a credential over the wire
either. Both are typed at the node.

**Whether that should stay true is an open question, held open on purpose.** It applies to the
passphrase and to a provider credential together, because they were argued separately and reached
opposite answers within a day on reasoning that moved under both. The discussion — four options and
what each is worth — is written up in
[secrets from elsewhere](../requirements/base/Secrets-From-Elsewhere_design.md). Until it is
decided, what is described below is the rule.

This is not a transport-security claim. The socket is forwarded over ssh, so it is the same
encrypted connection either way, and anybody who can forward it can already run commands on that
node. What the rule buys is narrower and worth stating plainly:

- the plaintext never enters a GUI process — no widget state, no clipboard, no crash dump;
- it never enters the varlink layer, where JSON ends up in logs, traces and echoed errors;
- the invariant is absolute rather than a thing every future code path must remember.

### An API token, remotely

You type it into the ssh session you already have. "At the machine" does not mean walking to the
server room — the socket only reaches a remote interface *because* it is forwarded over ssh, so
that person has an authenticated connection to the node by definition:

```
ssh node
sokar vault put anthropic     # asks at the terminal, and does not echo
```

An interface should say **where**, with the command and the right key name filled in, rather than
offering a disabled field. The key name is not guessable from outside: it is the *provider's* name,
falling back to the *agent's* name for vaults written before that changed, so a client working it
out for itself would report a missing credential for precisely the vault that has one.

**The honest limit:** if ssh is locked to a forced command that forwards the socket and gives no
shell, a remote person genuinely cannot store a new credential. They can only import one that is
already on the node. That is a dead end, not a hidden path, and an interface should say so.

### An OAuth login, remotely

This is the case where the rule costs nothing, because OAuth was designed so the credential never
passes through the client at all.

![an OAuth login driven from a laptop: the browser opens the authorization URL, is redirected to localhost on a forwarded port, and the code reaches the node's listener through the ssh tunnel; the node exchanges it for a token the provider delivers only to the node](images/auth-remote-oauth.svg)

| | |
|---|---|
| crosses to the laptop | the authorization URL — public, PKCE-protected |
| crosses back to the node | the authorization **code** — single-use, and worthless without the verifier that never left the node |
| **never crosses anything** | **the token** — minted by the provider, delivered straight into the node's process |

Sokar performs no OAuth flow of its own today. The login is the agent's, run on the node; what
Sokar adds is `vault import`, which copies the result out of the agent's config file.

#### What was measured, and where it bites

Measured on two machines — a VM as the node, a workstation as the laptop.

- **It works, and it is a *local* forward.** `ssh -L 8484:localhost:8484 node`: the listening
  socket is on the **laptop**, which is the side the browser talks to. The response comes back over
  the same tunnel; nothing else is needed.
- **The port cannot be remapped.** The browser goes to whatever is in the `redirect_uri`, so the
  laptop must listen on that exact port. Read it out of the printed URL rather than assuming one —
  most CLIs pick a free port at random.
- **There is no ordering problem.** ssh binds the laptop's port immediately and only dials the
  node's port when a connection arrives. A forward opened *before* the agent's listener exists
  works as soon as the listener comes up — measured on the same forward.
- **IPv6 is not a trap.** OpenSSH binds `127.0.0.1` *and* `[::1]`, so it does not matter how the
  browser resolves `localhost`.
- **A port collision lies, and this is the one to guard.** With something already on the laptop's
  `127.0.0.1:8484`:

  ```
  ssh exit code: 0
  ssh said:      bind [127.0.0.1]:8484: Address already in use
  forwards actually running: 1
  ```

  Exit **0**, process alive, message only on stderr — and *half* bound, because `[::1]` succeeded.
  `http://[::1]:8484` reached the node while `http://127.0.0.1:8484` hit the squatter and timed
  out. Whether the login works then depends on how the browser resolves `localhost`. **Verify a
  forward by connecting through it, never by ssh's exit code.**
- **An existing connection can take the forward.** A client already holding one does not reconnect
  or re-authenticate:

  ```
  ssh -S <ctl> -O forward -L 8484:localhost:8484 node   → 0
  ssh -S <ctl> -O cancel  -L 8484:localhost:8484 node   → 0
  ```

  So the callback port is open only for the seconds the login needs it.

**A tunnel is not always needed.** Some providers offer a device-code flow — the node shows a code,
the person types it on any device, the node polls for the token — which needs no port and no
redirect at all. Whether a login needs a forward is the *agent's* property, so nothing here should
assume the redirect shape.

### The signing key

`sokar vault agent` runs an ssh-agent that signs with the key seed from the vault and bind-mounts
its socket into the container as `SSH_AUTH_SOCK`. A container can ask for a signature and cannot
obtain the key that produces it — the same shape as the credential proxy, for a different secret.

**A gap, stated rather than hidden:** there is no `vault keygen`. `vault agent --ephemeral`
generates a throwaway key for a single run, but a durable key seed has to be produced elsewhere and
stored with `sokar vault put ssh.default`.

## What never crosses the socket

- a credential value, in either direction;
- the vault passphrase — there is no `Unlock`; it goes into the kernel keyring at the node;
- a recovery secret.

## What the pictures do not show

The vault has to be **open** for any of this. It opens only if its passphrase is already in the
kernel keyring, so a locked vault makes every flow above answer "not yet" rather than "missing" —
and those are different sentences, only one of which is somebody's problem to fix.

And an OAuth token expires. An agent that tries to renew one *inside* a container, holding a
task-scoped phantom token, is [B01](../requirements/base/B01-Refreshable-Task-Tokens.md), which is
open.
