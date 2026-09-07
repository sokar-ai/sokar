#!/usr/bin/env bash
#
# Tier 1 end-to-end test: everything checkable WITHOUT a provider account.
#
# It covers the failures that actually happen:
#
#   1. the agent CLI never reaches the image
#   2. the firewall is not applied, or is applied too late
#   3. the credential lands in the wrong environment variable for its type
#   4. the agent reaches for a host its definition does not declare
#
# Only (4) needs the agent to run, and it runs with a DELIBERATELY FAKE credential.
# Authentication is expected to fail; what is measured is which hosts it tried to reach,
# not whether it got in. That is what finds a missing allowed_domains entry - the class of
# bug that breaks an agent for everyone - without needing an account for any provider.
#
# Requires podman and a native build:  JAVA_HOME=<graalvm> ./mvnw -Pnative package -DskipTests
set -uo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

SOKAR="$ROOT/app/target/sokar"

# Which agent this suite drives. The stub lives in this repository, so the suite keeps working
# when the real agents move to their own - and it asks for a granted name and a refused one on
# purpose, which turns domain coverage from an observation into an assertion.
#
# Name another to point the same checks at it:  SOKAR_E2E_AGENT=claude buildtools/e2e-tier1.sh
AGENT_NAME="${SOKAR_E2E_AGENT:-stub}"
AGENT_MODULE="${SOKAR_E2E_AGENT_MODULE:-agents/$AGENT_NAME}"
AGENT="$ROOT/$AGENT_MODULE/target/sokar-agent-$AGENT_NAME"
WORK="$(mktemp -d)"
PROJECT="e2e-tier1"
CONTAINER=""
FAILURES=0

cleanup() {
    [ -n "$CONTAINER" ] && podman rm -f "$CONTAINER" >/dev/null 2>&1
    # This run's own passphrase, cached under a key derived from its own vault path. The
    # operator's stays where it was, which is the point of keying it that way.
    [ -n "${SOKAR_VAULT:-}" ] && "$SOKAR" vault unlock --forget >/dev/null 2>&1
    podman rmi -f "sokar/$PROJECT" >/dev/null 2>&1
    # Its own state directories too. They outlive the container - the poststop hook reaps
    # what is running, nothing removes the files - and they hold this run's dead token.
    rm -rf "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/sokar/sokar-$PROJECT-"*
    [ -n "${STAGED_SETS:-}" ] && rm -rf "$STAGED_SETS"
    rm -rf "$WORK" "${XDG_DATA_HOME:-$HOME/.local/share}/sokar/build/$PROJECT"
    rm -rf "${XDG_DATA_HOME:-$HOME/.local/share}/sokar/build/$PROJECT-fail"
    rm -rf "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/sokar/sokar-$PROJECT-fail-"*
    # The gate mirror too: it outlives the container, and a mirror left from an earlier run
    # already holds the ref this run pushes, so the push fails as a non-fast-forward and
    # reads as a broken gate.
    rm -rf "${XDG_DATA_HOME:-$HOME/.local/share}/sokar/mirrors/$PROJECT.git"
    :
}
trap cleanup EXIT

pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
info() { printf '        %s\n' "$1"; }

for binary in "$SOKAR" "$AGENT"; do
    [ -x "$binary" ] || {
        echo "missing $binary"
        echo "run: JAVA_HOME=<graalvm> ./mvnw -Pnative package -DskipTests"
        exit 2
    }
done
command -v podman >/dev/null || { echo "podman is not installed"; exit 2; }

# XDG_DATA_HOME is deliberately NOT overridden. podman keeps its whole container storage
# under it, so pointing it at a temp directory rebuilds every layer from scratch and then
# leaves gigabytes behind that a normal user cannot delete - the overlay directories belong
# to mapped uids. The agent is installed where a package would install it instead, and only
# what this script created is cleaned up.
AGENT_HOME="${XDG_DATA_HOME:-$HOME/.local/share}/sokar/agents"
mkdir -p "$AGENT_HOME"
# Copy then rename: a plain cp over a binary that is currently executing fails with
# "Text file busy", and a previous run's agent process may still be finishing.
cp "$AGENT" "$AGENT_HOME/.sokar-agent-$AGENT_NAME.tmp"
mv -f "$AGENT_HOME/.sokar-agent-$AGENT_NAME.tmp" "$AGENT_HOME/sokar-agent-$AGENT_NAME"

echo "== Tier 1: end to end, no credentials =="

# ------------------------------------------------------------------ discovery
echo
echo "-- discovery --"
# Captured first: under 'set -o pipefail' a non-zero 'sokar agents' - which is what an
# unrelated, unusable agent installed on the machine produces - would fail this check however
# well the grep did.
AGENTS_LISTED="$("$SOKAR" agents 2>/dev/null || true)"
if echo "$AGENTS_LISTED" | grep -q "^$AGENT_NAME"; then
    pass "sokar found an agent it was never linked against"
else
    fail "sokar did not find the installed agent"
fi

DESCRIBE="$("$AGENT" describe 2>/dev/null)"
CLI_VERSION="$(echo "$DESCRIBE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["definition"]["version"])')"
# Which variable carries the token is the PROVIDER's fact now, not the agent's, so it is read
# back from the run rather than from the agent's own description - where it no longer appears.
TOKEN_ENV=""
DOMAINS="$(echo "$DESCRIBE" | python3 -c 'import json,sys; print("\n".join(json.load(sys.stdin)["definition"]["allowedDomains"]))')"

# Read rather than assumed, so nothing here names one agent's tool, prompt flag or provider.
AGENT_BINARY="$(echo "$DESCRIBE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["definition"]["binary"])')"
PROMPT_FLAG="$(echo "$DESCRIBE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["definition"]["headless"].get("promptFlag") or "")')"
PROVIDER="$(echo "$DESCRIBE" | python3 -c 'import json,sys; p=json.load(sys.stdin)["definition"].get("provider") or {}; print(p.get("default") or "")')"
SOCKET_ENV="$(echo "$DESCRIBE" | python3 -c 'import json,sys; p=json.load(sys.stdin)["definition"].get("provider") or {}; print(p.get("socketEnvironment") or "")')"
BASE_URL_ENV="$(echo "$DESCRIBE" | python3 -c 'import json,sys; p=json.load(sys.stdin)["definition"].get("provider") or {}; print(p.get("baseUrlEnvironment") or "")')"
info "agent $AGENT_NAME, tool $AGENT_BINARY, provider ${PROVIDER:-none}, CLI version $CLI_VERSION"

# ------------------------------------------------------------------ the vault
# A vault of this run's own, never the operator's. Reading theirs made the result depend on
# what they happened to have stored: with a real credential present this script silently
# tested that instead of its own fake one, and reported failures that were not real.
#
# Only the vault is redirected, not the whole data directory: the container runtime keeps
# its image store there, and pointing that at a temp directory rebuilds every layer and
# leaves directories behind that a normal user cannot delete.
export SOKAR_VAULT="$WORK/vault.bin"
FAKE_CREDENTIAL="sk-ant-e2e-not-a-real-key"

VAULT_READY=""
if "$SOKAR" vault unlock --passphrase-command "printf e2e-tier1" >/dev/null 2>&1; then
    if printf '%s' "$FAKE_CREDENTIAL" \
            | "$SOKAR" vault put "${PROVIDER:-$AGENT_NAME}" --type api-key >/dev/null 2>&1; then
        VAULT_READY="yes"
    fi
fi
[ -n "$VAULT_READY" ] || {
    echo "could not prepare this run's own vault at $SOKAR_VAULT"
    exit 2
}

cat > "$WORK/project.yml" <<EOF
project:
  name: "$PROJECT"
  security_class: "guarded"
image:
  base_image: "ubuntu:24.04"
  snippet: |
    RUN apt-get update && apt-get install -y --no-install-recommends jq \\
        && rm -rf /var/lib/apt/lists/*
egress:
  sets: [maven, git-hosting]
EOF

# The sets a project names have to exist somewhere sokar looks. A packaged install puts them in
# /usr/share/sokar/egress; this script usually runs against a BUILD TREE, where nothing has been
# installed - so they are staged into the operator's own location, which is the other place
# sokar scans and is exactly how an operator would add one. Removed again in cleanup.
STAGED_SETS=""
if ! ls /usr/share/sokar/egress/*.yaml >/dev/null 2>&1; then
    STAGED_SETS="${XDG_DATA_HOME:-$HOME/.local/share}/sokar/egress"
    mkdir -p "$STAGED_SETS"
    cp "$ROOT"/egress/*.yaml "$STAGED_SETS/" 2>/dev/null || {
        echo "no curated sets in $ROOT/egress and none installed"
        exit 2
    }
fi

# ------------------------------------------------------------------ the image
echo
echo "-- image build --"
START_LOG="$WORK/start.log"
# --clearance deny: an acceptance run must not raise a prompt on somebody's desktop and
# then wait for it. A blocked destination is data here, not a question.
# --agent, not "whatever is installed": another agent on the machine would otherwise decide
# what this run measures, or refuse it outright for being ambiguous.
if (cd "$WORK" && "$SOKAR" task run --agent "$AGENT_NAME" --keep --no-attach --clearance deny \
        > "$START_LOG" 2>&1); then
    pass "task run built the image and started the container"
else
    fail "task run failed"
    grep -v SLF4J "$START_LOG" | tail -5
fi
CONTAINER="$(grep '^container ' "$START_LOG" | awk '{print $2}')"
IMAGE="$(grep '^image ' "$START_LOG" | awk '{print $2}')"
info "image $IMAGE, container $CONTAINER"

if grep -q "^agent .*$AGENT_NAME" "$START_LOG"; then
    pass "the agent contributed a layer to the image"
else
    fail "no agent layer was contributed"
fi

# command -v, not a fixed path: where an agent puts its tool is the agent's business, and two
# of them already disagree - one installs into ~/.local/bin, another into /usr/local/bin.
if podman run --rm "$IMAGE" sh -c "command -v '$AGENT_BINARY'" >/dev/null 2>&1; then
    pass "the agent CLI is installed in the image"
    IN_IMAGE="$(podman run --rm "$IMAGE" sh -c "'$AGENT_BINARY' --version" 2>/dev/null | head -1)"
    if echo "$IN_IMAGE" | grep -q "$CLI_VERSION"; then
        pass "the installed CLI is the pinned version ($IN_IMAGE)"
    else
        fail "the image has '$IN_IMAGE' but the definition pins $CLI_VERSION"
    fi
else
    fail "the agent CLI is not in the image"
fi

if podman run --rm "$IMAGE" sh -c 'command -v jq' >/dev/null 2>&1; then
    pass "the project's own tooling is in the image"
else
    fail "the project snippet did not reach the image"
fi

if podman run --rm "$IMAGE" sh -c 'test "$(whoami)" = agent' 2>/dev/null; then
    pass "the image runs as the unprivileged agent user"
else
    fail "the image does not default to the agent user"
fi

# --------------------------------------------------------------- the container
echo
echo "-- container hardening --"
[ -n "$CONTAINER" ] || { echo "no container to check"; exit 1; }

if podman exec "$CONTAINER" sh -c 'grep -q "^NoNewPrivs:.1" /proc/self/status' 2>/dev/null; then
    pass "NoNewPrivs is set"
else
    fail "NoNewPrivs is not set"
fi

if podman exec "$CONTAINER" sh -c 'grep -q "^CapEff:.0000000000000000" /proc/self/status' 2>/dev/null; then
    pass "all capabilities are dropped"
else
    fail "the container holds capabilities"
fi

STATE_DIR="$(grep '^sidecar ' "$START_LOG" | awk '{print $2}' | xargs dirname 2>/dev/null)"
if [ -n "$STATE_DIR" ] && grep -q 'nft createRuntime ok' "$STATE_DIR/hooks.log" 2>/dev/null; then
    pass "the nft hook ran at container-create time"
else
    fail "the nft hook did not report success"
fi

if podman exec "$CONTAINER" bash -c 'timeout 5 bash -c "echo > /dev/tcp/1.1.1.1/443"' 2>/dev/null; then
    fail "egress to an undeclared address is OPEN"
else
    pass "egress to an undeclared address is denied"
fi

# ------------------------------------------------------------------ the token
echo
echo "-- credential injection --"
if grep -q '^token .*none' "$START_LOG"; then
    info "the vault holds no credential, so no token was injected"
    info "(the wiring is covered by TaskRunCommandTest; this checks the live path)"
elif grep -qE "^token  +[A-Z0-9_]+=" "$START_LOG"; then
    TOKEN_ENV="$(grep -E "^token  +" "$START_LOG" | head -1 | awk "{print \$2}" | cut -d= -f1)"
    pass "a phantom token was injected as $TOKEN_ENV"
    if podman exec "$CONTAINER" sh -c "printenv $TOKEN_ENV" 2>/dev/null | grep -q '^sokar_pt_'; then
        pass "the container sees a phantom token, not a real credential"
    else
        fail "$TOKEN_ENV inside the container is not a phantom token"
    fi
else
    fail "no token line in the output at all"
fi

# --------------------------------------------------------- credential exchange
#
# A phantom token is only half a credential scheme. The agent gets 'sokar_pt_...' instead
# of the real key, which is the point - but something has to accept it and swap it for the
# real credential on the way out, or the provider just answers 401 and the agent looks
# misconfigured. That is exactly what happened before the proxy existed.
#
# Sokar runs a per-task proxy on a unix socket the container has bind-mounted. The agent is
# pointed at it through its own socket variable (ANTHROPIC_UNIX_SOCKET for Claude Code); the
# proxy verifies the token, injects the real credential and reissues the request upstream.
#
# All of this is checkable WITHOUT a provider account, which is why it lives in tier 1. The
# vault holds a deliberately fake key, so a request that reaches the provider comes back
# rejected - and that rejection is the proof: it is the PROVIDER's error message, not the
# proxy's, so the whole chain ran. Only a 200 needs a real account, and that is tier 2.
echo
echo "-- credential exchange --"

PHANTOM_VAR="$(podman exec "$CONTAINER" sh -c \
    'env | grep -E "=sokar_pt_" | cut -d= -f1' 2>/dev/null | head -1)"

if [ -z "$PHANTOM_VAR" ]; then
    info "no phantom token in this run, so there is nothing to redeem"
else
    # Taken from the agent's own description, not matched by name. An earlier version looked
    # for a variable ending in UNIX_SOCKET, which is Claude Code's spelling and not a rule -
    # an agent naming its socket anything else had its base URL tested as a socket path, which
    # fails as "not a socket in the container" and reads like a broken mount.
    SOCKET_VAR="${SOCKET_ENV:-$BASE_URL_ENV}"

    if [ -z "$SOCKET_VAR" ]; then
        fail "$PHANTOM_VAR holds a phantom token that nothing can redeem"
        info "the agent is pointed at no proxy, so it talks straight to the provider and"
        info "presents 'sokar_pt_...' as if it were a real key"
    else
        pass "the agent is pointed at a redemption endpoint ($SOCKET_VAR)"

        SOCKET="$(podman exec "$CONTAINER" sh -c "printenv $SOCKET_VAR" 2>/dev/null)"
        if podman exec "$CONTAINER" sh -c "test -S '$SOCKET'" 2>/dev/null; then
            pass "the proxy socket is mounted in the container"
        else
            fail "$SOCKET_VAR names '$SOCKET', which is not a socket in the container"
        fi

        # The whole point, and the one check a wrong answer cannot fake: the reply must be
        # the PROVIDER's rejection of the fake key, not the proxy's rejection of the token.
        # If the proxy answered, the swap never happened.
        REPLY="$(podman exec "$CONTAINER" sh -c "curl -s --max-time 30 --unix-socket '$SOCKET' \
            -H \"x-api-key: \$$PHANTOM_VAR\" -H 'anthropic-version: 2023-06-01' \
            -H 'content-type: application/json' -X POST http://api.anthropic.com/v1/messages \
            -d '{\"model\":\"claude-3-5-haiku-20241022\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}'" \
            2>/dev/null)"

        if echo "$REPLY" | grep -q "sokar:"; then
            fail "the proxy rejected this task's own token"
            info "$(echo "$REPLY" | head -c 200)"
        elif echo "$REPLY" | grep -qi "api key\|authentication"; then
            pass "a request through the proxy reached the provider and was answered by it"
            info "the provider rejected the fake key, which is the expected end of this path"
        elif [ -z "$REPLY" ]; then
            fail "a request through the proxy got no answer at all"
            info "the proxy is listening but nothing came back - check vault.log in the"
            info "container state directory"
        else
            fail "unexpected answer through the proxy"
            info "$(echo "$REPLY" | head -c 200)"
        fi

        # The provider is reachable on purpose: agents check it is up before they start, and
        # withholding it stopped them dead. What must never be in the container is the real
        # credential, so that is what this checks - the direct route carries the phantom token
        # or nothing, and the phantom token is worth nothing outside this task.
        if podman exec "$CONTAINER" sh -c 'env' 2>/dev/null | grep -q "$FAKE_CREDENTIAL"; then
            fail "the real credential is in the container's environment"
        else
            pass "the container holds no credential, only a task-scoped token"
        fi
    fi
fi

# ------------------------------------------------------------- domain coverage
#
# The check this whole script exists for. The agent runs with the FAKE credential, so
# authentication fails - that is expected and is not what is being measured. What is measured
# is which names it asked the resolver for. The resolver answers only the domains the agent's
# own definition declares and returns NXDOMAIN for everything else, and it logs every query.
#
# So a name that appears in the log as NXDOMAIN is a host the agent needs and did not declare.
# That is the failure that breaks an agent for every user of it, and this finds it without an
# account with any provider.
echo
echo "-- domain coverage --"

STATE_DIR="$(grep '^sidecar ' "$START_LOG" | awk '{print $2}' | xargs dirname 2>/dev/null)"
DNS_LOG="$STATE_DIR/dnsmasq.log"

if [ ! -f "$DNS_LOG" ]; then
    fail "the resolver did not start, so coverage cannot be measured"
else
    pass "the container has a working resolver"

    # A short, cheap prompt. It will fail to authenticate; the connection attempts are the point.
    # The prompt flag is the agent's own: some take it positionally, which is an empty flag.
    podman exec "$CONTAINER" sh -c \
        "timeout 45 '$AGENT_BINARY' $PROMPT_FLAG hello >/dev/null 2>&1" >/dev/null 2>&1 || true
    sleep 2

    # Names the agent's definition says it deliberately does not get. Without this the check
    # pressures whoever runs it into allowing telemetry, which is the opposite of the point:
    # Claude Code 2.1.236 resolves a Datadog log intake, and refusing it is correct.
    # Two kinds of name that are absent from allowed_domains on purpose, and neither is a
    # bug: one the agent is deliberately denied, one it reaches through the credential proxy
    # instead. Without both, this check reports the containment working as a failure.
    REFUSED="$("$SOKAR" agents --verbose 2>/dev/null \
        | sed -n -e 's/^ *refused: *//p' -e 's/^ *proxied: \([^ ]*\).*/\1/p' \
        | tr ',' '\n' | tr -d ' ' | grep . || true)"

    # A resolver appends its search domains to every unqualified lookup, so one query for
    # 'example.com' can appear as 'example.com.<search>' as well. Those are the same query and
    # must not read as an undeclared name - on a cloud VM the search domain is something like
    # 'ku0h....ex.internal.cloudapp.net', and on a home network it is the router's.
    #
    # Taken from the container's own resolver rather than a fixed list: a hardcoded suffix only
    # covers the network its author happened to be on.
    SEARCH="$(podman exec "$CONTAINER" sh -c 'sed -n "s/^search //p" /etc/resolv.conf' \
        2>/dev/null | tr ' ' '\n' | sed 's/\.$//' | grep . || true)"

    UNDECLARED="$(grep -oE 'config [a-z0-9.-]+ is NXDOMAIN' "$DNS_LOG" 2>/dev/null \
        | awk '{print $2}' | sort -u \
        | grep -vE '\.(local|localdomain)$' || true)"

    # Strip a trailing search domain, so the name is compared as the agent meant it.
    for suffix in $SEARCH; do
        UNDECLARED="$(echo "$UNDECLARED" | sed "s/\.${suffix//./\\.}$//")"
    done
    UNDECLARED="$(echo "$UNDECLARED" | sort -u | grep . || true)"

    if [ -n "$REFUSED" ]; then
        BLOCKED="$(echo "$UNDECLARED" | grep -Fxf <(echo "$REFUSED") || true)"
        UNDECLARED="$(echo "$UNDECLARED" | grep -Fxvf <(echo "$REFUSED") || true)"
        for name in $BLOCKED; do
            info "blocked on purpose: $name"
        done
    fi

    QUERIED="$(grep -oE 'query\[[A-Z]+\] [a-z0-9.-]+' "$DNS_LOG" 2>/dev/null \
        | awk '{print $2}' | sort -u | wc -l)"
    info "the agent asked for $QUERIED distinct name(s)"

    if [ -z "$UNDECLARED" ]; then
        pass "every name the agent resolved is declared in allowed_domains"
    else
        fail "the agent needs names its definition does not declare:"
        echo "$UNDECLARED" | while read -r name; do
            [ -n "$name" ] && info "  $name"
        done
        info "add them to allowed_domains in the agent's definition, or the agent will"
        info "fail for every user in a way that looks like a credential problem"
    fi
fi

# --------------------------------------------------------------- project egress
# What the PROJECT declared, as opposed to what the agent needs. Checked against a real
# container because every layer between the file and the packet has been wrong at least once:
# the package that ships the sets, the binary that reads them, the resolver that answers for
# them, and the firewall rule that decides which port.
echo
echo "-- project egress --"

if [ -n "$STAGED_SETS" ]; then
    pass "the curated sets are readable from an operator's own directory"
    info "staged in $STAGED_SETS; a packaged install ships them in /usr/share/sokar/egress"
elif [ "$(ls /usr/share/sokar/egress/*.yaml 2>/dev/null | wc -l)" -ge 9 ]; then
    pass "the package installed the curated sets"
else
    fail "no curated sets anywhere sokar looks"
fi

if "$SOKAR" shield sets 2>/dev/null | grep -q '^maven'; then
    pass "sokar shield sets lists them"
else
    fail "sokar shield sets found nothing"
fi

# A typo must stop the run before an image is built, not resolve to nothing.
mkdir -p "$WORK/typo"
cat > "$WORK/typo/project.yml" <<EOF
project:
  name: "$PROJECT-typo"
  security_class: "guarded"
image:
  base_image: "ubuntu:24.04"
egress:
  sets: [mvn]
EOF
if (cd "$WORK/typo" && "$SOKAR" task run --agent "$AGENT_NAME" --dry-run 2>&1 || true) \
        | grep -q "Unknown egress set"; then
    pass "an unknown set name stops the run"
else
    fail "an unknown set name did not stop the run"
fi

# The report has to say who granted what, or an operator cannot audit it.
if grep -qE 'repo\.maven\.apache\.org +set maven' "$START_LOG"; then
    pass "the start report names the set that granted each host"
    # Shown on success too, not only on failure: this report is the auditable artifact of the
    # whole feature - what a task may reach and who granted it - and a run that only proves it
    # exists leaves nobody able to read it afterwards. One example per origin, so the output
    # stays short however many hosts a set carries.
    sed -n '/^reachable/,/NXDOMAIN/p' "$START_LOG" \
        | sed -e 's/^reachable *//' -e 's/^ *//' \
        | grep -v '^ports 80 and 443' \
        | awk -F'  +' 'NF >= 2 && !seen[$2]++ { print $1 "  (" $2 ")" }' \
        | while read -r line; do info "$line"; done
else
    fail "the start report does not name granting sets"
    grep -A3 '^reachable' "$START_LOG" | while read -r line; do info "$line"; done
fi

if grep -q "gate now rests on this container holding no credential" "$START_LOG"; then
    pass "a guarded project reaching a forge is warned about it"
    grep 'gate now rests' "$START_LOG" | sed 's/^ *//' | head -1 \
        | while read -r line; do info "$line"; done
else
    fail "no forge warning for a guarded project that declared git-hosting"
fi

# The declaration governs the resolver.
if podman exec "$CONTAINER" getent hosts repo.maven.apache.org >/dev/null 2>&1; then
    pass "a host the project declared resolves"
else
    fail "a host the project declared does not resolve"
fi
if podman exec "$CONTAINER" getent hosts pypi.org >/dev/null 2>&1; then
    fail "an undeclared host resolved"
else
    pass "an undeclared host is still NXDOMAIN"
fi

# And the firewall governs the port. curl only: the container cannot install anything, which is
# the point. 28 is a timeout - what a dropped packet looks like; 35/52/56 mean the TCP
# connection got through and only TLS failed.
egress_port() {
    podman exec "$CONTAINER" sh -c \
        "curl -s -o /dev/null --max-time 12 https://$1:$2/ ; echo \$?" 2>/dev/null
}
case "$(egress_port github.com 443)" in
    0|35|52|56) pass "port 443 to a declared host is open" ;;
    *) fail "port 443 to a declared host is not open" ;;
esac
case "$(egress_port github.com 22)" in
    28|7) pass "port 22 to the same host is blocked, so no push goes around the gate" ;;
    *) fail "port 22 to a declared host was reachable" ;;
esac

# ------------------------------------------------------------ workspace and gate
#
# The agent gets its repository through Sokar's git gate, not from a bind mount: the
# container clones over HTTP from a mirror on the host and pushes back to a ref the
# operator reviews. Nothing the agent writes reaches the real upstream unreviewed.
#
# Worth checking here because the failure mode was invisible. The firewall rule that
# opens the gate port needs the address the container reaches the host on; that was
# resolved from a name podman only writes inside the container, so on the host it
# threw, the rule was quietly omitted, and every push hung until it timed out. A
# working clone proves nothing - only a push does.
echo
echo "-- workspace and gate --"

if podman exec "$CONTAINER" sh -c 'test -d /workspace/.git' 2>/dev/null; then
    pass "the workspace is a git repository the agent can work in"

    if podman exec "$CONTAINER" sh -c 'cd /workspace && git remote get-url sokar' \
            >/dev/null 2>&1; then
        pass "the workspace has a remote pointing back at the gate"
    else
        fail "the workspace has no gate remote, so the agent cannot hand work back"
    fi

    PUSH_OUT="$(podman exec "$CONTAINER" sh -c 'cd /workspace \
        && git -c user.email=agent@localhost -c user.name=agent commit -q --allow-empty \
             -m "e2e: work from the agent" \
        && timeout 30 git push sokar HEAD:"$SOKAR_TASK_REF"' 2>&1)" && PUSHED=0 || PUSHED=1

    if [ "$PUSHED" -eq 0 ]; then
        pass "the agent can push its work through the gate"
    else
        fail "the agent could not push through the gate"
        echo "$PUSH_OUT" | tail -3 | while read -r line; do info "  $line"; done
        info "check that the ruleset opens the gate port - a missing rule looks"
        info "exactly like this, as a connect timeout rather than a refusal"
        # A hang prints nothing, which is the least useful failure there is. Say where the
        # container was pointed and what happened when it tried, so the next reader does not
        # have to reproduce it to find out.
        # sokar already checks the container's /etc/hosts against the address it firewalled
        # open, and says so on stderr - but that goes into the start log, which nothing read.
        # A diagnosis that is produced and then discarded is worse than none.
        grep '^sokar:' "$START_LOG" 2>/dev/null | while read -r line; do info "  $line"; done
        info "the remote the agent was given:"
        podman exec "$CONTAINER" sh -c 'cd /workspace && git remote get-url sokar' 2>&1 \
            | while read -r line; do info "    $line"; done
        info "what it resolves to inside the container:"
        podman exec "$CONTAINER" sh -c \
            'getent hosts host.containers.internal || echo "  does not resolve"' 2>&1 \
            | while read -r line; do info "    $line"; done
        info "the connection attempt:"
        podman exec "$CONTAINER" sh -c \
            'cd /workspace && GIT_TRACE=1 GIT_CURL_VERBOSE=1 timeout 15 git ls-remote sokar 2>&1 \
             | grep -iE "connect|trying|refused|timed out|resolve" | head -5' 2>&1 \
            | while read -r line; do info "    $line"; done
    fi

    # The endpoint the agent pushes to must not be an endpoint anything else can reach. The
    # token was the only defence while the gate bound every interface; the bind is the second.
    GATE_PORT="$(grep '^gate ' "$START_LOG" | sed -n 's|.*:\([0-9]\+\)/.*|\1|p' | head -1)"
    if [ -n "$GATE_PORT" ]; then
        # Java binds a dual-stack socket, so loopback reads as [::ffff:127.0.0.1] here.
        if ss -ltnH "sport = :$GATE_PORT" \
                | grep -qE '(127\.0\.0\.1|\[::1\]|\[::ffff:127\.0\.0\.1\]):'"$GATE_PORT"; then
            pass "the gate listens on loopback only"
        elif ss -ltnH "sport = :$GATE_PORT" | grep -q .; then
            fail "the gate is bound where the local network can reach it"
            ss -ltnH "sport = :$GATE_PORT" | while read -r line; do info "  $line"; done
            grep '^sokar:' "$START_LOG" 2>/dev/null | while read -r line; do info "  $line"; done
        else
            fail "nothing is listening on the gate port $GATE_PORT"
        fi

        # And measured from off the loopback, because a bind is what was assumed last time.
        # This machine's own routable address stands in for another machine: a packet to it
        # leaves the loopback, which is the whole question.
        LAN_ADDRESS="$(ip -4 -o route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p')"
        if [ -n "$LAN_ADDRESS" ]; then
            if timeout 5 bash -c "echo > /dev/tcp/$LAN_ADDRESS/$GATE_PORT" 2>/dev/null; then
                fail "the gate answers on $LAN_ADDRESS, so the local network can reach it"
            else
                pass "the gate does not answer on this machine's network address"
            fi
        else
            info "no routable address on this machine, so the network check was skipped"
        fi
    else
        fail "the start log does not say what port the gate is on"
    fi

    if [ "$PUSHED" -eq 0 ]; then
        if (cd "$WORK" && "$SOKAR" gate pending 2>/dev/null) \
                | grep -q "e2e: work from the agent"; then
            pass "the pushed work is waiting for review on the host"
        else
            fail "the push succeeded but nothing is pending for review"
        fi
    fi
else
    fail "no workspace in the container, so the agent has nothing to work on"
fi

# ------------------------------------------------- what a failed run leaves behind
echo
echo "-- a failed run --"

# Deliberately broken, so the failure happens AFTER the credential proxy is listening and
# before any container exists. That is the gap the poststop hook cannot cover: it only fires
# for a container that ran, so nothing else stops what was started before it. Measured before
# this check existed: every failed run stranded a proxy holding its socket, and the next run
# then failed for a reason that had nothing to do with what changed.
FAIL_PROJECT="$PROJECT-fail"
FAIL_DIR="$WORK/failing"
mkdir -p "$FAIL_DIR"
cat > "$FAIL_DIR/project.yml" <<EOF
project:
  name: "$FAIL_PROJECT"
  security_class: "guarded"
image:
  base_image: "sokar-no-such-base-image:0"
EOF

if (cd "$FAIL_DIR" && "$SOKAR" task run --agent "$AGENT_NAME" --keep --no-attach --clearance deny \
        > "$FAIL_DIR/start.log" 2>&1); then
    fail "a task with an unbuildable image reported success"
else
    pass "a task that cannot build its image fails rather than starting"
fi

LEFTOVERS="$(pgrep -f "$FAIL_PROJECT" 2>/dev/null | grep -v "^$$\$" | wc -l)"
if [ "$LEFTOVERS" -eq 0 ]; then
    pass "the failed run left no helper processes behind"
else
    fail "the failed run left $LEFTOVERS process(es) running"
    pgrep -af "$FAIL_PROJECT" 2>/dev/null | head -3 | while read -r line; do info "$line"; done
fi

FAIL_STATE="$(find "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/sokar" -maxdepth 1 \
    -name "sokar-$FAIL_PROJECT-*" 2>/dev/null | head -1)"
if [ -z "$FAIL_STATE" ] || [ -z "$(find "$FAIL_STATE" -name '*.pid' 2>/dev/null)" ]; then
    pass "the failed run left no pid files claiming live helpers"
else
    fail "the failed run left pid files in $FAIL_STATE"
fi

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "== all checks passed =="
else
    echo "== $FAILURES check(s) failed =="
fi
exit "$FAILURES"
