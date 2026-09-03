# Agents

An agent is **its own native binary and its own package**. Sokar finds it by
scanning a directory and talks varlink to it. Nothing in Sokar names an agent,
and nothing lists them — an agent installed after Sokar shipped works with the
binary that is already there.

```
agents/
├── api/          sokar-agent-api       the SPI and the protocol; both sides link it
├── claude/       sokar-agent-claude    → its own binary
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

### 1. `agents/<name>/pom.xml`

Copy an existing one. The only dependency is `sokar-agent-api`. **Do not add a
dependency on another agent** — the build will reject it.

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

install:                      # container build fragments; shell, unavoidably
  as_root: |
    RUN mkdir -p /opt/example && chown agent /opt/example
  as_agent: |
    RUN curl -fsSL https://example.com/install.sh | bash
```

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
