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
AGENT="$ROOT/agents/claude/target/sokar-agent-claude"
WORK="$(mktemp -d)"
PROJECT="e2e-tier1"
CONTAINER=""
FAILURES=0

cleanup() {
    [ -n "$CONTAINER" ] && podman rm -f "$CONTAINER" >/dev/null 2>&1
    podman rmi -f "sokar/$PROJECT" >/dev/null 2>&1
    rm -rf "$WORK" "${XDG_DATA_HOME:-$HOME/.local/share}/sokar/build/$PROJECT"
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
cp "$AGENT" "$AGENT_HOME/.sokar-agent-claude.tmp"
mv -f "$AGENT_HOME/.sokar-agent-claude.tmp" "$AGENT_HOME/sokar-agent-claude"

echo "== Tier 1: end to end, no credentials =="

# ------------------------------------------------------------------ discovery
echo
echo "-- discovery --"
if "$SOKAR" agents 2>/dev/null | grep -q '^claude'; then
    pass "sokar found an agent it was never linked against"
else
    fail "sokar did not find the installed agent"
fi

DESCRIBE="$("$AGENT" describe 2>/dev/null)"
CLI_VERSION="$(echo "$DESCRIBE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["definition"]["version"])')"
TOKEN_ENV="$(echo "$DESCRIBE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["definition"]["tokenEnvironment"]["_default"])')"
DOMAINS="$(echo "$DESCRIBE" | python3 -c 'import json,sys; print("\n".join(json.load(sys.stdin)["definition"]["allowedDomains"]))')"
info "CLI version $CLI_VERSION, token variable $TOKEN_ENV"

# ------------------------------------------------------------------ the vault
# A deliberately fake credential, so the token-injection path is exercised with no account
# anywhere. It is stored under the agent's name, which is the scope the broker mints for.
VAULT_ADDED=""
if "$SOKAR" vault list >/dev/null 2>&1; then
    if ! "$SOKAR" vault list 2>/dev/null | grep -q '^claude'; then
        if echo "sk-ant-e2e-not-a-real-key" | "$SOKAR" vault put claude >/dev/null 2>&1; then
            VAULT_ADDED="yes"
        fi
    fi
fi

cat > "$WORK/project.yml" <<EOF
project:
  name: "$PROJECT"
  security_class: "guarded"
image:
  base_image: "ubuntu:24.04"
  snippet: |
    RUN apt-get update && apt-get install -y --no-install-recommends jq \\
        && rm -rf /var/lib/apt/lists/*
EOF

# ------------------------------------------------------------------ the image
echo
echo "-- image build --"
START_LOG="$WORK/start.log"
if (cd "$WORK" && "$SOKAR" task run --keep --no-attach > "$START_LOG" 2>&1); then
    pass "task run built the image and started the container"
else
    fail "task run failed"
    grep -v SLF4J "$START_LOG" | tail -5
fi
CONTAINER="$(grep '^container ' "$START_LOG" | awk '{print $2}')"
IMAGE="$(grep '^image ' "$START_LOG" | awk '{print $2}')"
info "image $IMAGE, container $CONTAINER"

if grep -q "^agent .*claude" "$START_LOG"; then
    pass "the agent contributed a layer to the image"
else
    fail "no agent layer was contributed"
fi

if podman run --rm "$IMAGE" sh -c 'test -x ~/.local/bin/claude' 2>/dev/null; then
    pass "the agent CLI is installed in the image"
    IN_IMAGE="$(podman run --rm "$IMAGE" sh -c '~/.local/bin/claude --version' 2>/dev/null | head -1)"
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
elif grep -q "^token  *$TOKEN_ENV=" "$START_LOG"; then
    pass "a phantom token was injected as $TOKEN_ENV"
    if podman exec "$CONTAINER" sh -c "printenv $TOKEN_ENV" 2>/dev/null | grep -q '^sokar_pt_'; then
        pass "the container sees a phantom token, not a real credential"
    else
        fail "$TOKEN_ENV inside the container is not a phantom token"
    fi
else
    fail "no token line in the output at all"
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
    podman exec "$CONTAINER" sh -c \
        'timeout 45 ~/.local/bin/claude -p hello >/dev/null 2>&1' >/dev/null 2>&1 || true
    sleep 2

    UNDECLARED="$(grep -oE 'config [a-z0-9.-]+ is NXDOMAIN' "$DNS_LOG" 2>/dev/null \
        | awk '{print $2}' | sort -u \
        | grep -vE '\.(fritz\.box|local|localdomain)$' || true)"

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

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "== all checks passed =="
else
    echo "== $FAILURES check(s) failed =="
fi
exit "$FAILURES"
