# Claude Code

The Sokar adapter for [Claude Code](https://github.com/anthropics/claude-code).

Two different things get called "the agent", and the difference matters when
something goes wrong:

|                      | Where it lives                                                | What it is                         |
|----------------------|---------------------------------------------------------------|------------------------------------|
| `sokar-agent-claude` | on the **host**, in `/usr/libexec/sokar/agents`               | this adapter, about 6 MB, one file |
| `claude`             | inside the **task image**, at `/home/agent/.local/bin/claude` | the CLI itself, about 320 MB       |

The package does not contain the CLI. It carries a pinned URL and a SHA-256, and
the image build fetches and verifies it — see
[your tooling](../../your-tooling.md).

## Which credential do you have?

Claude Code accepts two kinds, and they are **not interchangeable**. They go in
different headers, and sending one as the other fails as an authentication error
that looks exactly like a wrong key.

| You have                              | Store it as                    | Sokar sends               |
|---------------------------------------|--------------------------------|---------------------------|
| an API key from the Anthropic Console | `vault put claude --type api-key` | `x-api-key: sk-ant-…`  |
| a Claude subscription                 | `vault put claude --type oauth`   | `Authorization: Bearer …` |

The kind is stored with the credential, so no task has to repeat it.

**An API key** comes from the Anthropic Console, as `sk-ant-…`. Usage is billed
to that key.

**A subscription token** comes from the CLI itself. Run `claude setup-token` —
"Set up a long-lived authentication token" — on a machine where you are already
logged in, and store what it prints. This is the one to use if you pay for Claude
rather than for API usage.

## Storing it

```
sokar vault unlock
printf '%s' 'sk-ant-…' | sokar vault put claude --type api-key
```

Unlock **first**: `vault put` reads the credential from standard input, so it has
nothing left to read a passphrase from. The name must be `claude` — Sokar looks
the credential up by the agent's own name. Use `printf`, not `echo`, or a newline
becomes part of your key.

Then:

```
sokar task run
```

Both kinds run the same way: the vault already knows which it holds, and
`sokar vault list` shows it. `--credential-type` on a task overrides it.

## What the container actually gets

Not your credential. Three variables:

```
ANTHROPIC_API_KEY=sokar_pt_…            a phantom token, this task only
ANTHROPIC_UNIX_SOCKET=/run/sokar/vault.sock
ANTHROPIC_BASE_URL=http://localhost:9419
```

For a subscription the variable is `CLAUDE_CODE_OAUTH_TOKEN` instead; the kind
stored in the vault decides which.

Claude Code talks to the socket; Sokar's proxy checks the phantom token, replaces
it with your real credential, and reissues the request to
`https://api.anthropic.com`. Your key never enters the container, and the phantom
token stops working when the task ends.

**Two files are placed as well**, because a container has never been logged in and
the CLI would otherwise run its first-run wizard and stop for input:

```
/home/agent/.claude.json              onboarding answered, /workspace trusted
/home/agent/.claude/.credentials.json the phantom token, where a login would put it
```

The login menu that appears without them belongs to that wizard, not to any check
of the credential — with the wizard marked done, the phantom token is used without
question. The agent decides what those files contain; Sokar writes bytes it does
not parse, over standard input so the token never reaches a command line.

**Both variables are set on purpose.** `ANTHROPIC_UNIX_SOCKET` only selects the
transport. Without `ANTHROPIC_BASE_URL`, Claude Code falls back to its own
compiled-in endpoint — measured, it then resolved `api.anthropic.com` 184 times
in a single run and never touched the socket.

**`api.anthropic.com` stays reachable from the container, deliberately.** It was
withheld from the firewall at first, and that broke the CLI outright: before it
starts interactively it opens a connection to that host, ignoring both the base URL
and the socket. Measured on 2026-09-04, same container and environment, only the
reachability of that one name differing:

| `api.anthropic.com` | interactive `claude` |
|---|---|
| not resolvable | `ENOTFOUND`, quits |
| resolvable, nothing listening | `ConnectionRefused`, quits |
| reachable | starts normally |

What the deny kept inside was the phantom token, which is random, expires with the
task, and is worth nothing to Anthropic. The real credential is what must not get
out, and it never enters the container at all — that is the property the design
defends, and `buildtools/e2e-tier1.sh` checks it directly.

## What it is allowed to reach

```
$ sokar agents --verbose
NAME         BINARY           LABEL                  FROM
claude       claude           Claude Code            /usr/libexec/sokar/agents/sokar-agent-claude
             domains: api.anthropic.com, platform.claude.com, claude.ai, statsig.anthropic.com
             proxied: api.anthropic.com (the credential is swapped in on the way out)
             refused: http-intake.logs.us5.datadoghq.com, raw.githubusercontent.com
             resume:  yes
```

`platform.claude.com` is contacted before an interactive session starts, and the
CLI quits if it cannot reach it - whatever the credential is.

`refused` is a deliberate denial, not an oversight: 2.1.236 resolves a Datadog
log intake during a normal run, and Sokar does not give it one. The CLI works
without it. It is declared rather than merely absent so that a test can tell a
policy from a mistake, and so you can see what is being blocked.

## Bumping the CLI version

`src/main/resources/agent/claude.yaml` pins one version and one digest:

```yaml
install:
  version: "${agent.cli.version}"
  artifacts:
    - url: https://downloads.claude.ai/claude-code-releases/${agent.cli.version}/linux-x64/claude
      sha256: "6c8818fa…"
```

Anthropic publishes a per-version `manifest.json` carrying a SHA-256 per
platform, at
`https://downloads.claude.ai/claude-code-releases/<version>/manifest.json`, so
this is verifiable rather than trusted. Bumping is a two-line change: the version
property in `pom.xml` and the digest here. Until it is bumped, every image build
installs the same bytes.

`sokar agents --supply-chain` reports what is pinned, so "which version ran" is
answerable from the installed adapter rather than from a build log.

## When it will not authenticate

Check what actually reached the proxy — `vault.log` in the task's state
directory, `/run/user/<uid>/sokar/<container>/`:

```
request   POST /v1/messages -> 401 from the provider
```

- **`401 from the provider`** — the request got all the way to Anthropic and it
  rejected the credential. The plumbing works; the key is wrong, expired, or of
  the wrong kind. `sokar vault list` shows which kind is stored.
- **`401 token not accepted`** — the proxy rejected the phantom token. It is from
  another task, or the task has outlived `--token-hours`.
- **`503`** — the vault was locked or the entry removed while the task ran.
  `sokar vault unlock`.
- **no `request` lines at all** — the CLI never used the socket. Check that both
  `ANTHROPIC_UNIX_SOCKET` and `ANTHROPIC_BASE_URL` are set in the container.

A credential-free check of the whole path, needing no account:

```
buildtools/e2e-tier1.sh
```

With a real credential, `buildtools/e2e-tier2.sh` additionally confirms it never
appears inside the container or in any of Sokar's logs.
