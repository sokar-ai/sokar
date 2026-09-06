# Agents

An agent is **its own native binary and its own package**. Sokar finds it by
scanning a directory and talks varlink to it. Nothing in Sokar names an agent,
and nothing lists them — an agent installed after Sokar shipped works with the
binary that is already there.

```
agents/
├── api/          sokar-agent-api       the SPI and the protocol; both sides link it
├── claude/       sokar-agent-claude    → its own binary
├── pi/           sokar-agent-pi        → its own binary, and it ships its own CLI
└── <yours>/
```

```
$ sokar agents
no agents installed. Looked in:
  ~/.local/share/sokar/agents
  /usr/libexec/sokar/agents

$ cp sokar-agent-claude ~/.local/share/sokar/agents/
$ sokar agents
NAME         BINARY           LABEL                  FROM
claude       claude           Claude Code            ~/.local/share/sokar/agents/sokar-agent-claude
```

No rebuild. No re-sign. The `sokar` binary contains no occurrence of the string
`claude` — checkable with `strings`.

An agent developed outside this repository needs nothing from it but the
published `sokar-agent-api`.

## Onboarding a new agent

Six files, none of them outside this directory except two lines of `pom.xml`.

Start with a `README.md` in the new directory whose first line links the upstream
project — the exact repository, and the npm package or download URL the build
installs. Names are not unique in this field, and an agent that only says "Pi" or
"Codex" cannot be told apart from the fork next to it.

### 1. `agents/<name>/pom.xml`

Copy an existing one. Two dependencies: `sokar-agent-api`, and `sokar-wire` for
`Json`. Those are the only Sokar artifacts on Maven Central, so they are also the
only ones an agent in its own repository can resolve. **Do not add a dependency on
another agent** — the build will reject it.

Declare **two** things:

- **its own `<version>`**, not Sokar's. This is the package version, and it is
  what makes the agent releasable on its own schedule. Sokar's dependencies still
  resolve because the root POM pins them with `${sokar.version}` rather than
  `${project.version}` — a parent's `${project.version}` is interpolated against
  the module that inherits it, so it would resolve to *your* version and find
  nothing.
- **`agent.cli.version`**, the CLI version this agent installs.

### 2. `agents/<name>/src/main/resources/agent/<name>.yaml`

The definition. Everything that is data lives here:

```yaml
name: example                 # must match the directory and the class below
label: Example
binary: example-cli           # what it is invoked as inside the container

git_identity:                 # commits are attributed to the agent, not the operator
  name: Example
  email: noreply@example.com

headless:                     # the agent's own spelling of the common ideas
  prompt_flag: "-p"
  model_flag: "--model"
  max_turns_flag: "--max-turns"
  verbose_flag: "--verbose"
  output_format_flags: ["--output-format", "json"]

session:
  supports_resume: true
  resume_flag: "--resume"

provider:
  token_env:
    oauth: EXAMPLE_OAUTH_TOKEN
    _default: EXAMPLE_API_KEY
  base_url_env: EXAMPLE_BASE_URL

allowed_domains:              # what the shield and the resolver must permit
  - api.example.com

install:                      # what the image build fetches, pinned and verified
  version: "1.4.0"
  artifacts:
    - url: https://example.com/releases/1.4.0/example-cli-linux-amd64
      sha256: "…64 lower-case hex…"
      target: /home/agent/.local/bin/example-cli
      mode: "0755"
  as_root: |                  # anything else; shell, unavoidably
    RUN mkdir -p /opt/example && chown agent /opt/example
```

**A digest is required.** The fetch and the check are generated into one `RUN`
with `set -e`, so a mismatch fails the layer and nothing is installed — verified
against a real container: correct digest installs and runs, wrong digest exits 1
with `sha256sum: FAILED`.

Where a publisher offers no digest, say so rather than dropping the field:

```yaml
    - url: https://example.com/install.sh
      unverified: true
      reason: "the publisher offers no digest"
      target: /home/agent/.local/bin/example-cli
```

An artifact marked `unverified` with no `reason` is rejected. A gap that has to
be written down is a gap somebody notices; a missing field is a habit.
`sokar agents --supply-chain` lists every artifact and marks the unverified ones,
so an audit reads the roster rather than the build logs.

### 3. `agents/<name>/src/main/java/org/fuin/sokar/agent/impl/<name>/<Name>Agent.java`

```java
public final class ExampleAgent extends YamlAgent {
    public ExampleAgent() {
        super("example");
    }
}
```

That is the whole class, unless the agent needs behaviour the definition cannot
express — see *When YAML is not enough*.

### 4. `agents/<name>/src/main/resources/META-INF/services/org.fuin.sokar.agent.api.Agent`

One line: the fully qualified class name. This is how `ServiceLoader` finds it,
and native-image resolves it at build time with no metadata of any kind
(verified, spike S9).

### 5. A `main`, and the native profile

```java
public static void main(String[] args) {
    AgentMain.run(new ExampleAgent(), args);
}
```

Copy the `native` profile from `agents/claude/pom.xml` and change the image
name. The binary answers two modes: `serve <socket>` for Sokar, and `describe`
for a human — `sokar-agent-example describe | jq` is what to reach for when
Sokar will not use an agent that looks installed.

### 6. `agents/pom.xml`

One `<module>` line, and only so this repository builds it. An agent maintained
elsewhere skips even this.

Nothing in `core`, `app`, or any other agent changes, and there is no file
anywhere that lists agents.

### 7. `agents/<name>/README.md`

The one piece of documentation an agent must ship, because nothing else can hold
it: **which credentials the agent accepts, and where an operator gets each one.**

Sokar cannot answer that. It knows the variable a credential goes in, because the
definition declares it, and `sokar agents --verbose` prints what an agent needs to
reach — but the difference between an API key and a subscription token, which
console issues it, and which `--credential-type` matches, is knowledge about a
vendor. Put it beside the vendor's adapter, not in Sokar's own documentation,
for the same reason the code lives here.

[`claude/README.md`](claude/README.md) is the worked example. Cover at least:

- each credential kind, the `--credential-type` that selects it, and where to get
  it
- what the container actually receives, and what it does not
- how to bump the pinned CLI version and digest
- what to check when authentication fails — the `request` lines in `vault.log`
  distinguish "the provider rejected your key" from "the agent never used the
  proxy", and those have completely different fixes

### What you do *not* have to write

No native-image metadata. `ServiceLoader` classes are resolved by native-image on
its own, but the resources those classes read are not — an unregistered
definition means the agent is discovered and then fails to construct, with
`No agent definition at /agent/<name>.yaml`. `sokar-agent-api` registers the glob
`agent/*.yaml` once, and resource registration is a pattern over the whole
classpath, so it covers every agent that will ever exist.

## When YAML is not enough

Override a method on `Agent`, **in your own module**:

| Method | Default | Override when |
|---|---|---|
| `credentialExtractor()` | finds nothing | the login credential is not a dotenv variable or a JSON field |
| `logFormatter()` | passes lines through | the agent emits a structured stream worth rendering |
| `imageLayer()` | the `install:` block | the build needs more than fragments |
| `headlessCommand(RunRequest)` | the shared builder | the command line is genuinely a different shape |

`EnvFileExtractor` and `JsonFieldExtractor` in the SPI already cover most
credential layouts. Reach for an override only when they do not.

**Never** add a branch outside your module that tests an agent's name. That is
the failure this structure exists to prevent: the next agent falls into the
`else` and is quietly wrong, with nothing to indicate it.

## Packaging

Every agent gets a `.deb` and an `.rpm` from the shared `dist` profile in
`agents/pom.xml`. **You write no packaging configuration** — declare the module's
`<version>` and `agent.cli.version`, and the rest follows:

```
mvn -Pnative,dist verify
  → target/sokar-agent-claude_1.0.0~SNAPSHOT_amd64.deb
  → target/sokar-agent-claude-1.0.0~SNAPSHOT-1.x86_64.rpm
```

Both install one file, `/usr/libexec/sokar/agents/<name>`, which is where Sokar
looks. Nothing is registered and no post-install script runs: installing the
package is the whole integration.

**The package version is the agent module's own version**, and nothing else.
Sokar's release line does not appear in the artifact — that is the point of
shipping agents separately. An agent released against an unchanged CLI is still
an upgrade, which a version keyed on the CLI alone could not express.

The **CLI version lives in the description**, so the package still answers what
an image build will fetch:

```
$ dpkg -l | grep sokar-agent
ii  sokar-agent-claude  1.0.0~SNAPSHOT  amd64  Sokar agent for Claude Code 2.1.236

$ dpkg -s sokar-agent-claude | grep ^Description
Description: Sokar agent for Claude Code 2.1.236
```

`agent.cli.version` is written once, in the agent's `pom.xml`, and filtered into
its `agent.yaml` and the package description. Bumping a CLI is that line plus the
new digest — and the module `<version>`, since the package contents changed.

Snapshot builds render as `1.0.0~SNAPSHOT`, because `~` sorts *below* everything
in both Debian and RPM ordering. Left as `-SNAPSHOT` it would sort above the
release and `apt` would refuse to upgrade from a snapshot to the real thing.

## Protocol versions

`AgentProtocol.VERSION` is the contract. Sokar and the agents ship as separate
packages, so an untested combination is a matter of time: an agent states which
version it speaks, and Sokar refuses one it does not know rather than reading a
field that means something else now.

Raise it when a change would make an older Sokar misread a newer agent, or the
reverse. Adding an optional field does not qualify; renaming or removing one
does.

## When an agent will not start

A broken agent is reported against its own name and the others keep working —
one bad package must not make the machine look as though it has no agents.

```
$ sokar agents
claude       claude           Claude Code            …/sokar-agent-claude
sokar: sokar-agent-broken is installed but unusable:
       …/sokar-agent-broken exited without becoming ready, saying: config file missing
```

A binary that died and one that hung are different problems, and the message
says which.

## Enforcement

`AgentIsolationTest` in `sokar-app` runs three checks:

1. No class outside `org.fuin.sokar.agent..` depends on an agent implementation.
2. `sokar-app` sees the SPI package and nothing else agent-shaped.
3. **No agent name appears as a string literal outside `agents/`.** ArchUnit
   reads types and members, not constant-pool literals, so this one is a source
   scan. It takes the list of names from the `agent/*.yaml` files themselves, so
   it needs no maintaining when you add one.

Rule 3 is the interesting one. It is what would catch the mistake the reference
implementation makes: `claude.yaml` there declares
`capabilities.log_format: claude-stream-json`, the roster parses it into a typed
field and carries it all the way to the code that needs it — and that code then
writes `if effective_agent == "claude"` instead of reading it. A new agent
declaring the same capability gets plain text, silently, with no error anywhere.
