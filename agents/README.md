# Agents

An agent is a **module**. Sokar discovers it at runtime and never names it.

```
agents/
├── api/          sokar-agent-api       the SPI; depends on sokar-core
├── bundle/       sokar-agents-bundle   the one file that names agents
├── claude/       sokar-agent-claude    depends on sokar-agent-api only
└── <yours>/
```

The dependency arrow points one way. An agent knows about Sokar; Sokar does not
know about agents. Two tests in `sokar-app` fail the build if that stops being
true — see *Enforcement* below.

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

### 5. `agents/pom.xml`

One `<module>` line.

### 6. `agents/bundle/pom.xml`

One `<dependency>`. **This is the only file in the repository that names
agents.** A native image cannot load code at runtime, so the binary has to
contain its agents at build time and something has to list them; this is that
something. It is a packaging fact, not a code dependency.

Nothing in `core`, `app`, or any other agent changes.

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
