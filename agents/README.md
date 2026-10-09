# Agents

An agent is **its own native binary and its own package**. Sokar finds it by
scanning a directory and talks varlink to it. Nothing in Sokar names an agent,
and nothing lists them — an agent installed after Sokar shipped works with the
binary that is already there.

```
agents/
├── api/          sokar-agent-api       the agent contract; both sides link it
└── stub/         sokar-agent-stub      → the agent the acceptance suite drives
```

The two modules here: [api](api/README.md), the contract, and [stub](stub/README.md), the agent the acceptance
suite drives.

**Every shipped agent lives in its own repository**, building against the published
contract, releasing on its own cadence, with this repository containing no reference
to either:

| Agent | Repository |
|---|---|
| Claude Code | [sokar-claude-code](https://github.com/sokar-ai/sokar-claude-code) |
| Pi | [sokar-pi](https://github.com/sokar-ai/sokar-pi) |
| Oh My Pi | [sokar-omp](https://github.com/sokar-ai/sokar-omp) |

The stub stays. Without an agent in the tree the acceptance suite would have nothing
to drive, and a suite that cannot run is one that quietly stops being maintained.

```
$ sokar agents
no agents installed. Looked in:
  ~/.local/share/sokar/agents
  /usr/libexec/sokar/agents

$ sudo apt install sokar-agent-claude
$ sokar agents
NAME         BINARY           LABEL                  FROM
claude       claude           Claude Code            /usr/libexec/sokar/agents/sokar-agent-claude
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
  session_id:                       # optional; where it names the session it runs
    record: { type: system }        # an unattended run: the record naming it ...
    key: session_id                 # ... and its field holding the id
    directory: ".example/sessions"  # an attached agent: its session files, under its home ...
    suffix: ".jsonl"                # ... ending in <suffix>; in the newest, the first record above names
                                    #     the session, else its name without <suffix> does
  ready_marker: "Ready for work."   # text shown once at work, attached, having asked nothing
  ready_within_seconds: 60          # optional; the kit's default is 120
  waiting:                          # optional; what waiting for a person looks like here
    screen:                         # the attached agent's own screen, as tmux draws it
      - contains: "Do you trust this folder?"   # one line, matched without regard to case
        in_last_lines: 5            # optional; the last N non-empty lines, else the whole screen
        for: "trusting the folder"  # optional; what it waits for, as a person reads it
    not_its_screen:                 # optional; a pager or viewer open over the agent
      - contains: "(END)"
        in_last_lines: 1
    last_message:                   # optional; where a finished unattended run's last message is
      record: { type: result }      # the last record whose top-level fields have these values
      text: result                  # and the field of it holding the text
  at_rest:                          # optional; what its screen shows when it waits for its next turn
    shows: ["bypass permissions on"]               # text that must all be on the screen
    lacks: ["esc to interrupt", "Esc to cancel"]   # text that must none be on it

instructions:                 # optional; how the agent takes standing instructions from a file
  arguments: ["--append-system-prompt-file", "{file}"]   # {file} is where the file's path goes

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

**A tool that is a tree of files ships in the agent's own package** instead, under
`packaged`, and is copied into the image rather than fetched:

```yaml
  packaged:
    - source: example/tree.tar.gz   # beside the agent's binary; or an absolute path
      target: /opt/example
```

A relative `source` is read beside the agent's binary, so a copy of the agent in
one account's `~/.local/share/sokar/agents` ships its own tree, and `..` is
refused. **A declared tree that is not there refuses the task**: an image built
without it would start a task with no tool in it.
`sokar agents --supply-chain` lists every artifact and marks the unverified ones,
so an audit reads the roster rather than the build logs.

**`ready_marker` is what a check of the agent waits for**, attached, to prove it reached work
with nothing asked first. The acceptance kit's step *"the … agent reaches work without being asked
anything"* types nothing and waits for this text, within `ready_within_seconds` or the kit's
default. Any question before it - a trust dialog, a login, a setup wizard - blocks it, so the step
fails the day a release adds one, including one nobody has seen. An agent whose ready screen has no
stable text declares none, and the step then fails saying it cannot tell. `sokar agents --verbose`
shows what each agent declares.

**`session_id` is how a task that comes back continues its conversation.** Sokar records the
session a task's agent was running - from an unattended run's records, or from the agent's own
session files when an attached task stops - and starting the task again passes `resume_flag` and the
id, and says it did. An agent that declares nothing starts fresh and says so; nothing is guessed. It
needs `supports_resume`. A session id is an identifier, not a credential, so it may be on a command
line; it is kept with the task and forgotten when the task is removed.

**`waiting` is how Sokar tells a person the agent is waiting for them**, read from outside and
never by a rule of Sokar's own. For an attached task Sokar reads the screen tmux draws every few
seconds, and a `screen` rule that matches makes the task read as *waiting*. A `not_its_screen` rule
that matches - a pager open over the agent - keeps the last reading rather than reporting on the
pager. For a finished unattended run, `last_message` names the record whose text is shown as what it
said last. Rules are literals, not expressions, and bounded when the manifest is read: at most 16 per
list, 200 characters each, 50 lines of region; a declaration past them is refused. An agent that
declares nothing is shown as *cannot say*, never as *not waiting*. A declaration read for a day
without matching once is shown as *unproven*: the wording probably changed with the agent's version.
Prove it in the agent's own repository, at the version it pins, with the kit's *"sokar shows the …
agent in task … waiting for a person"* after driving the agent to a question - and *"… not waiting
for a person"* while it works.

**`at_rest` is when a message or a file may wake the agent.** When a message names a task whose agent rests at its
prompt, or a file arrives in its `/sokar/files`, Sokar types one line of its own into its terminal saying so - only
while the screen matches `at_rest`, and never while a `waiting` rule says a question to a person is open, since the
line would interrupt its work or answer the question. **An agent is typed into only when it declares both**: one
with `at_rest` and no `waiting` rule cannot say when a question is open, so it is never woken and finds what arrived
when it looks, as its guide tells it. `shows` and `lacks` may not both be empty. The values above are
Claude Code's own: each agent declares what its screen shows, measured at the version it pins.

**`instructions` is how a task's agent is told what Sokar gives it there.** Sokar writes one guide - `/sokar/files`
and what arrives there, the builds of its pushes, and its mailbox where it has one - to `/run/sokar/guide/README.md`,
read-only in the task, and adds these arguments, with the guide's path for `{file}`, on every way of starting the
agent - attended, unattended, continued - behind whatever turns its prompts off. A task made before this version
has its mailbox's guide, `/run/sokar/mail/README.md`, instead, and an agent that declares nothing is not told. The flag is the
agent's own, so it is declared, never guessed. The guide is the same for every task of one Sokar version, so a
provider that caches a prompt by its beginning keeps caching it.

### 3. `agents/<name>/src/main/java/org/fuin/sokar/agent/impl/<name>/<Name>Agent.java`

```java
public final class ExampleAgent extends YamlAgent {
    public ExampleAgent() {
        super("example");
    }
}
```

That is the whole class, unless the agent needs behavior the definition cannot
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

Copy the `native` profile from `agents/stub/pom.xml` and change the image
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

[sokar-claude-code](https://github.com/sokar-ai/sokar-claude-code)'s own README is the
worked example — it left this repository with the adapter it documents. Cover at least:

- each credential kind, the `--credential-type` that selects it, and where to get
  it
- what the container actually receives, and what it does not
- how to bump the pinned CLI version and digest
- what to check when authentication fails — the `request` lines in `vault.log`
  distinguish "the provider rejected your key" from "the agent never used the
  proxy", and those have completely different fixes

### The build every agent repository carries

**An agent repository builds, publishes and then proves the published packages on rented
machines.** Three jobs, and the third is the one that is easy to leave out and the only one that
answers the question an operator actually has:

- **build** — unit tests, the native binary, the `.deb` and the `.rpm`. On a pinned
  `ubuntu-24.04` runner, never `ubuntu-latest`: a native image links glibc dynamically, so it must
  be compiled against the oldest glibc it has to run on. 24.04 is not a machine Sokar runs on -
  it needs podman 5, which 24.04 does not ship - but it is where the binary is compiled, because
  one built there starts anywhere a newer Sokar does.
- **publish** — the packages to Artifactory, and a check that they are *indexed* rather than
  merely stored. A `.deb` uploaded without `deb.distribution`, `deb.component` and
  `deb.architecture` is accepted and never appears in the index, with no error anywhere.
- **acceptance** — a matrix of `ubuntu` and `fedora` on rented Hetzner machines, installing
  **from the package repository** rather than from a build tree, driven by `sokar-machines acceptance`
  from `sokar-buildtools`. Ubuntu **26.04** and Fedora 44: the two differ in package format and security
  module, and both have podman 5. The server is destroyed in a `finally`, and `sokar-machines sweep --mine`
  deletes what a killed run left.

Everything before the third job proves the code is right; only the third proves that what an
operator installs is right, and the two have been different before - a package carrying a stale
binary, a package whose dependency would not resolve. Its tier-2 half needs a real credential and
is skipped without one, so a fork or a revoked key loses coverage rather than turning the build
red with no information.

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
| `ended(String line)` | finds no end | the agent's output says how its run ended (finished, or an error and whose), so `task status` can say it |
| `imageLayer()` | the `install:` block | the build needs more than fragments |
| `headlessCommand(RunRequest)` | the shared builder | the command line is genuinely a different shape |

`EnvFileExtractor` and `JsonFieldExtractor` in the agent API already cover most
credential layouts. Reach for an override only when they do not.

Sokar asks `ended` line by line from the end of the task's log, once the log has been quiet for a while; the first
line answered for is how the run ended.

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

### An agent of another vendor, under its own names

A vendor's package need not carry Sokar's name. The package's name is free, the
executable may live anywhere, and one small file is the whole registration:
`/usr/share/sokar/agents.d/<name>.yaml`, naming the executable by its absolute path:

```yaml
executable: /opt/acme/bin/coder
```

Nothing else goes in it: the agent describes itself when Sokar asks it, as every
agent does. A file in `~/.local/share/sokar/agents.d/` wins over the system's of
the same name, so a person can try their own build without root. A description
whose path is not absolute, not a regular file or not executable is not taken;
`sokar agents` and `sokar doctor` name the file and why. The `sokar-agent-*`
files above are still found as before.

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
2. `sokar-app` sees the agent API package and nothing else agent-shaped.
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
