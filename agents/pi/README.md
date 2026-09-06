# Pi

The Sokar adapter for [Pi](https://github.com/earendil-works/pi), the Pi Agent
Harness — installed from npm as `@earendil-works/pi-coding-agent` and run as
`pi`.

**Not [Oh My Pi](https://github.com/can1357/oh-my-pi).** That is a separate
project, a fork of a different Pi, published as `@oh-my-pi/pi-coding-agent` and
run as `omp`. Nothing here installs it. The two were confused in the
requirements until 2026-09-05; the code never was.

Two different things get called "the agent", and the difference matters when
something goes wrong:

|                  | Where it lives                                    | What it is                          |
|------------------|---------------------------------------------------|-------------------------------------|
| `sokar-agent-pi` | on the **host**, in `/usr/libexec/sokar/agents`   | this adapter, about 6 MB, one file  |
| `pi`             | inside the **task image**, at `/usr/local/bin/pi` | the CLI itself, a tree under `/opt` |

**Unlike Claude Code, the package carries the tool.** Pi is 162 npm packages and
a Node runtime, with no single URL to pin, so verification happens once where the
package is built — against a lockfile pinning every dependency by integrity hash
— and the image build then downloads nothing at all. That makes the package about
70 MB instead of 6, which is the trade: see [your tooling](../../your-tooling.md).

## Storing the credential

Pi has one credential kind here, an OpenRouter API key, so there is no `--type`
to state. It is stored under the provider's name, so `--provider anthropic` picks
up an Anthropic key stored the same way without anything being restated:

```
sokar vault unlock
printf '%s' 'sk-or-…' | sokar vault put openrouter
```

Unlock **first**: `vault put` reads the credential from standard input, so it has
nothing left to read a passphrase from. The name must be `pi` — Sokar looks the
credential up by the **provider's** name, not this agent's. That is what lets a
second agent reaching OpenRouter use the same entry instead of storing another
copy of the same secret. Use `printf`, not `echo`, or a newline becomes part of
your key.

## What the container actually gets

Not your credential, and not an environment variable either. **One file:**

```
/home/agent/.pi/agent/extensions/sokar-route.ts
```

```typescript
export default function (pi) {
    pi.registerProvider("openrouter", { baseUrl: "http://127.0.0.1:9419/api/v1", apiKey: "sokar_pt_…" });
}
```

`sokar_pt_…` is a phantom token for this task only. Pi sends it to the broker,
which checks it, swaps in your real key and reissues the request to
`https://openrouter.ai`. Your key never enters the container, and the token stops
working when the task ends.

Three things about that file are load-bearing:

- **It is a file, not a variable.** Only Azure has a base-URL variable in Pi;
  every other provider's endpoint is fixed unless an extension overrides it.
  Extensions are auto-discovered from `~/.pi/agent/extensions/*.ts`, so this is
  something to place rather than something to run. It is also written *after* the
  container exists, by which time its environment is already fixed.
- **`/api/v1` belongs to the provider, not to Pi.** OpenRouter serves the OpenAI
  dialect there; a base URL without it answers 404, and one ending in `/v1`
  answers "model not found" to a client that appends its own version segment.
- **The address is literal, not `localhost`.** Node resolves `localhost` to `::1`
  first and the relay binds IPv4, so `localhost` is a connection refused.

## Why there is a relay

Pi can only address a URL. A host-side listener is either unreachable from the
container or bound to every interface — measured on one machine:

```
host itself                                : 200
container -> host loopback via 169.254.1.2 : refused
container -> its own 127.0.0.1             : refused
```

So the **listening end** moves into the task's network namespace:
`sokar vault relay` binds `127.0.0.1:9419` there and forwards to the broker's
unix socket on the host. It resolves nothing and connects to nothing but a local
file.

**The broker itself stays on the host.** Putting it in the namespace was tried
and fails: it keeps the host's *mount* namespace, so it reads the host's
`/etc/resolv.conf`, finds a resolver that does not exist there, and every request
fails as "could not reach the provider". Its egress would also have been governed
by the task's own firewall rather than the host's.

## What it is allowed to reach

```
$ sokar agents --verbose
NAME   BINARY   LABEL   FROM
pi     pi       Pi      /usr/libexec/sokar/agents/sokar-agent-pi
       domains: openrouter.ai
       proxied: openrouter.ai (the credential is swapped in on the way out)
       resume:  yes
```

One domain, which is the whole allowance — Pi needs nothing else at start-up,
unlike Claude Code.

## Bumping the CLI version

Four edits today, in four files, with nothing checking that they agree:

| file | what |
|---|---|
| `pom.xml` | `agent.cli.version`, filtered into `pi.yaml` |
| `src/main/npm/package.json` | the same version again, for npm |
| `src/main/npm/package-lock.json` | regenerated, carrying an integrity hash per package |
| `buildtools/build-pi-tree.sh` | `NODE_VERSION` and `NODE_SHA256`, the runtime shipped beside it |

The Node tarball is checked against the digest nodejs.org publishes beside it, so
that half is verifiable rather than trusted. `npm ci` — never `npm install` —
installs exactly the lockfile and refuses to update it.

`sokar agents --supply-chain` reports what is pinned, so "which version ran" is
answerable from the installed adapter rather than from a build log.

## When it will not authenticate

Check what actually reached the proxy — `vault.log` in the task's state
directory, `/run/user/<uid>/sokar/<container>/`:

```
request   POST /api/v1/chat/completions -> 200 from the provider
```

- **`401 from the provider`** — the request reached OpenRouter and it rejected the
  credential. The plumbing works; the key is wrong or out of credit.
- **`401 token not accepted`** — the proxy rejected the phantom token. It is from
  another task, or the task has outlived `--token-hours`.
- **no `request` lines at all** — Pi never used the endpoint. Check `relay.log` in
  the same directory, and that the extension file exists in the container.
- **`404` or "model not found"** — the base URL lost its `/api/v1`, or gained a
  second `/v1`.

## Checking it

**There is no acceptance suite for this agent yet.** `buildtools/e2e-tier1.sh` no
longer names any agent — `SOKAR_E2E_AGENT=pi` points it here — but it has never been
run that way, so what it would find is unknown. Tier 2 is not in this repository at
all: it asks whether *this agent* authenticates against *this provider*, which is the
agent's question, and it belongs with Pi when Pi moves to its own repository.

What Pi has instead is a run that was measured by hand on 2026-09-04: a real
prompt answered (`"text":"SOKARLIVE"`), `request POST /api/v1/chat/completions ->
200 from the provider` in `vault.log`, and no `sk-or-` anywhere in the
container's environment or filesystem. Making that a suite is the obvious next
piece of work here.
