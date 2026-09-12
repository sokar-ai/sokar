# P01 — Providers As Packages

**Status:** **built and verified on both VMs**, vault re-keying included. Every acceptance
criterion is met. What follows is the design as built; the trigger and the measurements that
justified it are further down. The trigger this was waiting for -
a second agent reaching a provider the first does not - is [A01](../base/A01-Pi-Forge-Subscription.md),
now being built. The extraction should follow it rather than precede it, from two real
implementations rather than one.

An agent declares its provider inline today: one upstream, one auth header, one
prefix per credential kind, all inside the agent's own definition. With one agent
that is right. With several it stops being right, because the same provider is then
described in as many places as there are agents that reach it, and the descriptions
drift.

The proposal is to make a provider what an agent already is: its own declaration,
its own package, discovered by a directory scan, naming itself. An agent then says
which providers it can drive rather than restating each one.

## What belongs to which

The split is not obvious and getting it wrong is the main risk, so it is written
down before anything is built:

| Knowledge | Belongs to | Why |
|---|---|---|
| how a session is started, what a fresh container must be told | the **agent** | its own first-run behavior, nothing to do with who serves the model |
| which command line runs a prompt, how output is formatted | the **agent** | its interface |
| upstream endpoint, auth header, value prefix | the **provider** | the same for every agent reaching it |
| how a credential is obtained, stored, and renewed | the **provider** | a sign-in belongs to whoever the account is with |
| whether the endpoint can be redirected | **both** | the provider must offer it and the agent must honor it |

The last row is why this is not a clean cut. Redirection is a property of the pair,
not of either side, and the pair is what a task actually runs.

## Acceptance

- A provider is declared once and used by more than one agent without being restated.
- Nothing outside a provider's own directory names it, enforced the way agent names
  already are.
- An agent that drives several providers gets one credential per provider, and the
  vault can express that.
- The broker forwards to the provider the task chose, not to a constant compiled
  into an agent.
- Adding a provider needs no change to Sokar and no rebuild of any agent.

## Notes

Evidence this is real rather than tidy-minded: a provider serving a compatible
dialect ([P08](P08-Provider-Zhipu.md)) is already reachable only by making the
broker's upstream a variable, and two of the candidate agents authenticate per
provider rather than per agent ([A10](../agents/A10-Agent-OpenCode.md), and Pi, whose
requirements now live in `sokar-pi`).

There is already one place where the two are mixed: what the first agent writes into
a fresh container is partly its own first-run state and partly the shape its
provider expects a stored credential to have. Those are two different things in one
file.

## What is actually data, measured 2026-09-04

The first agent's hand-written Java is 303 lines, and it does not divide evenly:

| file | lines | shape |
|---|---|---|
| `ClaudeStreamJsonFormatter` | 87 | real logic - parsing a stream format |
| `ClaudeCredentialExtractor` | 86 | a path and a field name in the host's file |
| `ClaudeContainerSetup` | 73 | two file templates with a token substituted |
| `ClaudeAgent` | 50 | wiring |

Around 160 of those lines say "put this value in this field of this file". `credentials()`
is `{"apiKey": token}` or `{"claudeAiOauth": {"accessToken": token, ...}}` chosen by
credential kind - data wearing a method. Only the formatter is genuinely code, and it
belongs to the agent rather than the provider in any case.

So a declarative form is plausible: a provider names the variable it wants, and the file it
expects a credential in, with its path, format and placeholder. That is the candidate shape
to test - **against the second agent, not the first**. Designing it now would encode one
agent's assumptions: JSON, a single token, two files, a fabricated far-future expiry. Pi
already breaks two of those - its endpoint is redirected by a TypeScript extension it
auto-discovers, not by a variable or a credential file.

## The mixing, named

`ClaudeContainerSetup` writes two files for two different owners: `.claude.json` is the
agent's own first-run state - onboarding answered, workspace trusted - while
`.credentials.json` is the shape *the provider* expects a stored credential to have. One
class, two owners. Splitting those is worth doing on its own, before any package boundary
exists, because it makes the eventual boundary obvious rather than arbitrary.

## The endpoint belongs to the pair, and now there is proof

The table above says redirection is a property of both sides. [A01](../base/A01-Pi-Forge-Subscription.md)
shows what that costs in practice: one agent takes a socket path in a variable, the other can
only address a URL and needs a listener bound inside its container's namespace. The provider
is the same in both cases. So a provider declaration cannot carry "how to reach it" alone -
the agent has to declare what shape of endpoint it can use, and Sokar satisfies it.

## The duplication is real, measured 2026-09-05

The file argued with itself: the status line said the trigger was a second agent reaching
a provider the first does not, the checklist said it was two agents reaching the **same**
provider. The stricter one has now fired, by experiment rather than by argument.

Pi was pointed at Anthropic - the provider the first agent already uses - and asked a real
question through the broker:

```
request   POST /v1/messages?beta=true -> 200 from the provider
```

The answer came back, and `sk-ant-` appeared nowhere in the container. Two agents, one
provider, and Anthropic is now described in two places.

**What the switch cost is the actual finding.** Pointing one agent at a provider the other
already reaches needed seven changes in two languages:

| where | what |
|---|---|
| `pi.yaml` | `token_env`, `proxy.upstream`, `auth_header`, `auth_prefix`, `allowed_domains` |
| `PiRoutingExtension.java` | `PROVIDER`, `DIALECT_PATH` - **constants, so a 15 MB binary had to be rebuilt** |

Five of those seven are data the other agent already states, in its own words, about the
same provider: `api.anthropic.com`, `x-api-key`, an empty prefix. The two that are not data
are the ones that make this a rebuild rather than an edit - and a provider that ships as a
declaration is exactly the thing that would remove them.

A third data point arrived the same day and points the same way:
[Copilot CLI](../agents/A08-Agent-Copilot-CLI.md) models the provider dialect as a value of its own,
`COPILOT_PROVIDER_TYPE` being `openai`, `azure` or `anthropic` - an agent that has already
made the split this requirement proposes.

The experiment is on the branch `probe/pi-anthropic`, deliberately not merged.

## The design, settled 2026-09-05

### A provider is a declaration, not a package

The open question was whether a provider needs its own *package* or only its own
*declaration*. **Declaration**, and the reason is the measurement already in this file: an
agent needs a binary because it has behavior that cannot be expressed as data - a stream
formatter, first-run container setup, the quirks of one CLI. A provider, measured across
the two that exist, is *entirely* data: an upstream, a header, a prefix, a path. There is
nothing to execute.

So a provider is a YAML file found by a directory scan, in the same two-location shape
agents already use:

```
~/.local/share/sokar/providers/     an operator's own, and it wins
/usr/share/sokar/providers/         what a package installed
```

`share`, not `libexec`: these are data files, not executables. That already satisfies
"adding a provider needs no change to Sokar and no rebuild of any agent" without a binary,
a varlink connection or an installable of its own - and a package can still *carry* one,
because shipping and declaring are different questions.

### The split, as files

A provider says where it is and how to speak to it:

```yaml
# /usr/share/sokar/providers/anthropic.yaml
name: anthropic
label: Anthropic
upstream: https://api.anthropic.com
dialects:
  anthropic-messages: ""          # native first: the dialect this provider is
auth_header:
  _default: x-api-key
  oauth: Authorization
auth_prefix:
  _default: ""
  oauth: "Bearer "
token_env:
  _default: ANTHROPIC_API_KEY
  oauth: CLAUDE_CODE_OAUTH_TOKEN
```

```yaml
# openrouter.yaml - one provider, two dialects at two paths
dialects:
  openai: "/api/v1"
  anthropic-messages: "/api"
```

An agent says what it speaks and what it can address:

```yaml
provider:
  default: openrouter
  dialect: openai       # or 'native' - see below
  endpoint: url         # socket | url, unchanged, and still the agent's own property
```

### `dialect: native` is what makes a provider-agnostic agent expressible

Claude Code speaks one wire format wherever it points, so it says
`dialect: anthropic-messages`. Copilot CLI's BYOK mode is told which to speak, so it says
`openai`. Pi is neither: it *knows* providers, and speaks whatever each one's own dialect
is - so it says `native`, meaning "the first dialect the provider declares".

That is the whole of what the Pi probe had to change in Java. With this, the same switch
is `--provider anthropic` and no rebuild, which is the acceptance test for this
requirement rather than a description of it.

### An agent can drive a provider it has never heard of

The last open question, answered by the shape: yes, when the provider serves a dialect the
agent speaks. Nothing pairs them by name. A provider added tomorrow that declares
`openai` is drivable by every agent that speaks `openai`, and by every `native` agent that
recognizes its name.

### The vault is keyed by provider

Forced by the probe rather than argued: pointing Pi at Anthropic meant storing the *same*
Anthropic key a second time, under the name `pi`, beside the copy under `claude`. Two
entries, one credential, and neither name says which provider it is for.

`sokar vault put anthropic` replaces `sokar vault put claude`. **Measured on Fedora:** one
entry under `anthropic`, and both agents authenticated against it in turn - each with its own
phantom token, each reaching `POST /v1/messages?beta=true -> 200`.

**An older vault is not broken.** An entry under the agent's own name is used when there is
none under the provider's, so nobody's stored credential stops working on upgrade; a task says
where to move it. Verified by running one: a credential stored under the agent's name still
authenticated, and printed

```
credential stored under 'claude', which is this agent's name; it belongs to 'anthropic'.
          Move it with: sokar vault put anthropic
```

The rule is four lines and its cases are not obvious, so it is a pure function with its own
tests rather than a condition inside a command.

**One thing this exposed.** `vault serve` took the vault key in an option called `--agent`, which
was true when the two were the same name and misleading afterwards. It is now `--credential`.
That option was the last place a provider's name traveled under an agent's label, and it was
found by a task failing to authenticate rather than by reading the code.


## Built, and measured on both VMs, 2026-09-05

`sokar agents --verbose`, with two agents and two providers installed and neither agent naming
a header, a prefix or a host:

```
claude   claude   Claude Code
         speaks:  anthropic-messages
         provider: anthropic (default) -> api.anthropic.com
         provider: openrouter -> openrouter.ai
pi       pi       Pi
         speaks:  native
         provider: anthropic -> api.anthropic.com
         provider: openrouter (default) -> openrouter.ai
```

**Claude Code can now reach OpenRouter and nobody wrote that.** It falls out of the pair: the
agent speaks `anthropic-messages`, OpenRouter serves that dialect under `/api`, so the two match.
That is the acceptance criterion "an agent can drive a provider it has never heard of", and it
arrived without being aimed at.

The acceptance test is the probe that started this, repeated as a flag:

```
$ sokar task run --agent pi --provider anthropic
vault     .../vault.sock -> https://api.anthropic.com
    pi.registerProvider("anthropic", { baseUrl: "http://127.0.0.1:9419", apiKey: "sokar_pt_..." })
request   POST /v1/messages?beta=true -> 200 from the provider
```

Seven changes in two languages and a 15 MB rebuild became one flag. Tier 1 and tier 2 pass on
Fedora and Ubuntu.

### Three things this got wrong first, all found by running it

- **The agent stopped being given a token variable.** Removing `token_env` from the definitions
  was right, but two of the three places that asked for it were not moved onto the provider's
  answer, so the setup files were silently not placed. Unit tests passed throughout.
- **The provider's host was no longer in the firewall's allow list.** The agent used to name it
  and no longer does, and nothing added it back - which would have stopped Claude Code from
  starting at all, since it contacts the provider before it runs.
- **The client never sent the provider's name.** The record carried it, the agent read it, and
  the varlink call did not include it, so a task got `registerProvider("")`. Both sides compiled
  and every test passed. It is now a record method compared against the record's own components,
  so the next field that is added breaks a test rather than a task.

## To be checked

- ~~**Whether the duplication is real yet.**~~ **Answered by measurement, 2026-09-05** -
  see below. It is real.
- ~~Whether a provider needs to be a separate *package* or only a separate
  *declaration*.~~ **Declaration**, see above.
- ~~Whether an agent can drive a provider it has never heard of.~~ **Yes**, when the
  provider serves a dialect the agent speaks. See above.
- Whether `dialect: native` survives a provider whose name the agent does not recognize.
  Pi registers a provider *by name*, so a provider it has never heard of may need its
  dialect stated after all. Testable the moment a third provider exists.
