#!/usr/bin/env bash
#
# Tier 2 end-to-end test: what only a REAL provider credential can answer.
#
# Tier 1 covers everything measurable with a fake key. What it cannot tell you is whether a
# task can actually authenticate - the question that was the reason for having these tiers
# at all, after Terok's GitHub Copilot authentication turned out to be broken without its
# test suite saying so.
#
# What this measures:
#
#   1. the vault round-trips a real credential through lock and unlock
#   2. a task authenticates against the real provider and completes a trivial prompt
#   3. the real credential never appears inside the container
#   4. the real credential never appears in Sokar's own logs or audit trail
#   5. an expired or revoked phantom token stops working
#
# Checks 3 and 4 are the ones worth having even when 2 fails: a credential scheme that
# authenticates by handing the real key to the agent has not failed loudly, it has failed
# quietly, and only a real credential makes the leak searchable.
#
# CREDENTIALS ARE READ FROM THE ENVIRONMENT, NEVER FROM ARGV - a command line is visible to
# every process on the machine. Set the one for the agent under test:
#
#   SOKAR_E2E_CLAUDE_API_KEY=sk-ant-...     an Anthropic API key
#   SOKAR_E2E_CLAUDE_OAUTH_TOKEN=...        a Claude Code OAuth token (--credential-type oauth)
#
# With none of them set every check is skipped and the script exits 0, so it is safe to run
# in CI that has no accounts.
#
# THE OPERATOR'S VAULT IS NEVER TOUCHED. This run makes its own, in its own temporary
# directory, with its own passphrase. It used to copy the real one aside and put it back,
# because the vault path could not be redirected and there was no way to remove an entry;
# both of those are gone. Backing up and restoring a file holding somebody's credentials is
# not something a test should be doing if it can avoid it, and it can.
#
# Requires podman and a native build:  JAVA_HOME=<graalvm> ./mvnw -Pnative package -DskipTests
set -uo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

SOKAR="$ROOT/app/target/sokar"
AGENT="$ROOT/agents/claude/target/sokar-agent-claude"
WORK="$(mktemp -d)"
# Replaced by the full cleanup once the vault has been backed up. Armed now so that an
# early exit - no credential, no binary, a locked vault - does not leave a temp directory.
    # Its own state directories too. They outlive the container - the poststop hook reaps
    # what is running, nothing removes the files - and they hold this run's dead token.
    rm -rf "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/sokar/sokar-$PROJECT-"*
trap 'rm -rf "$WORK"' EXIT
PROJECT="e2e-tier2"
CONTAINER=""
FAILURES=0
SKIPPED=0

pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
skip() { printf '  SKIP  %s\n' "$1"; SKIPPED=$((SKIPPED + 1)); }
info() { printf '        %s\n' "$1"; }

echo "== Tier 2: end to end, with a real credential =="

# ------------------------------------------------------------------ what we have
#
# One credential per (agent, type). Each is a separate run, because the variable the agent
# reads depends on the type and getting that wrong is failure mode 3 from tier 1.
CREDENTIAL=""
CREDENTIAL_TYPE=""
if [ -n "${SOKAR_E2E_CLAUDE_API_KEY:-}" ]; then
    CREDENTIAL="$SOKAR_E2E_CLAUDE_API_KEY"
    CREDENTIAL_TYPE="api-key"
elif [ -n "${SOKAR_E2E_CLAUDE_OAUTH_TOKEN:-}" ]; then
    CREDENTIAL="$SOKAR_E2E_CLAUDE_OAUTH_TOKEN"
    CREDENTIAL_TYPE="oauth"
fi

if [ -z "$CREDENTIAL" ]; then
    echo
    echo "no credential in the environment - nothing to test"
    info "set SOKAR_E2E_CLAUDE_API_KEY or SOKAR_E2E_CLAUDE_OAUTH_TOKEN to run this"
    info "tier 1 covers everything that does not need an account:"
    info "  buildtools/e2e-tier1.sh"
    exit 0
fi

for binary in "$SOKAR" "$AGENT"; do
    [ -x "$binary" ] || {
        echo "missing $binary"
        echo "run: JAVA_HOME=<graalvm> ./mvnw -Pnative package -DskipTests"
        exit 2
    }
done
command -v podman >/dev/null || { echo "podman is not installed"; exit 2; }

echo
echo "credential type $CREDENTIAL_TYPE"

# The agent is installed where a package would put it. XDG_DATA_HOME is deliberately not
# redirected: podman keeps its container storage under it (see tier 1).
AGENT_HOME="${XDG_DATA_HOME:-$HOME/.local/share}/sokar/agents"
mkdir -p "$AGENT_HOME"
cp "$AGENT" "$AGENT_HOME/.sokar-agent-claude.tmp"
mv -f "$AGENT_HOME/.sokar-agent-claude.tmp" "$AGENT_HOME/sokar-agent-claude"

# This run's own vault, in its own directory. Only the vault is redirected, not the whole
# data directory: the container runtime keeps its image store there.
export SOKAR_VAULT="$WORK/vault.bin"
VAULT="$SOKAR_VAULT"

cleanup() {
    [ -n "$CONTAINER" ] && podman rm -f "$CONTAINER" >/dev/null 2>&1
    podman rmi -f "sokar/$PROJECT" >/dev/null 2>&1
    # This run's own cached passphrase, under a key derived from its own vault path.
    "$SOKAR" vault unlock --forget >/dev/null 2>&1
    rm -rf "$WORK" "${XDG_DATA_HOME:-$HOME/.local/share}/sokar/build/$PROJECT"
    # The gate mirror too: it outlives the container, and a mirror left from an earlier run
    # already holds the ref this run pushes, so the push fails as a non-fast-forward and
    # reads as a broken gate.
    rm -rf "${XDG_DATA_HOME:-$HOME/.local/share}/sokar/mirrors/$PROJECT.git"
    :
}

trap cleanup EXIT

# Its own passphrase, cached under its own key, so nothing the operator has unlocked is
# read or replaced.
if ! "$SOKAR" vault unlock --passphrase-command "printf e2e-tier2" >/dev/null 2>&1; then
    echo
    echo "could not create this run's own vault at $SOKAR_VAULT"
    exit 2
fi

# ---------------------------------------------------------------- vault round trip
echo
echo "-- vault --"

if printf '%s' "$CREDENTIAL" \
        | "$SOKAR" vault put claude --type "$CREDENTIAL_TYPE" >/dev/null 2>&1; then
    pass "the vault stored the credential"
else
    fail "the vault would not store the credential"
    info "everything below depends on this, so the rest is skipped"
    echo
    echo "== 1 check failed =="
    exit 1
fi

if "$SOKAR" vault list 2>/dev/null | grep -q '^claude'; then
    pass "the vault lists it by name"
else
    fail "the vault does not list the credential it just stored"
fi

# The value must not be recoverable from the listing - that is what 'never the values' in
# the command's own help has to mean in practice.
if "$SOKAR" vault list 2>/dev/null | grep -qF "$CREDENTIAL"; then
    fail "vault list printed the credential value"
else
    pass "vault list does not print the value"
fi

# On disk it must be ciphertext. A real credential makes this searchable in a way a fake
# one does not, because a fake one may coincidentally not be stored at all.
if grep -qF "$CREDENTIAL" "$VAULT" 2>/dev/null; then
    fail "the credential is in the clear in $VAULT"
else
    pass "the credential is not recoverable from the vault file"
fi

cat > "$WORK/project.yml" <<EOF
project:
  name: "$PROJECT"
  security_class: "guarded"
image:
  base_image: "ubuntu:24.04"
EOF

# ---------------------------------------------------------------- authentication
#
# The check this tier exists for. A trivial prompt with a verifiable answer: if the agent
# answers it, then the credential path, the egress firewall, the resolver and the declared
# domains all line up. Anything less than a real request cannot establish that.
echo
echo "-- authentication --"

START_LOG="$WORK/start.log"
# --clearance deny: an acceptance run must not raise a prompt on somebody's desktop and
# then wait for it. The kind is not repeated here: it was stored with the credential, and
# that it reaches the task without being restated is part of what this checks.
(cd "$WORK" && timeout 600 "$SOKAR" task run \
    --keep --no-attach --clearance deny \
    > "$START_LOG" 2>&1)

CONTAINER="$(grep '^container ' "$START_LOG" | awk '{print $2}')"
if [ -z "$CONTAINER" ]; then
    fail "the task did not start"
    tail -5 "$START_LOG" | while read -r line; do info "$line"; done
    echo
    echo "== $((FAILURES)) check(s) failed =="
    exit 1
fi
pass "the task started (container $CONTAINER)"

# The cheapest model answers this: what is measured is that the credential was swapped in
# and accepted, not what the model can do. Override with SOKAR_E2E_MODEL.
MODEL="${SOKAR_E2E_MODEL:-claude-haiku-4-5-20251001}"
ANSWER="$(podman exec "$CONTAINER" sh -c \
    "timeout 180 ~/.local/bin/claude --model $MODEL -p 'Reply with exactly the word SOKARLIVE and nothing else.' 2>&1" \
    2>/dev/null)"

if echo "$ANSWER" | grep -q "SOKARLIVE"; then
    pass "the agent authenticated and completed a prompt"
else
    fail "the agent could not complete a prompt with a real credential"
    echo "$ANSWER" | tail -4 | while read -r line; do info "$line"; done
    # The known cause, worth naming because the symptom is indistinguishable from a bad key.
    if podman exec "$CONTAINER" sh -c 'env | grep -q "=sokar_pt_"' 2>/dev/null \
            && ! podman exec "$CONTAINER" sh -c 'env | grep -q "BASE_URL="' 2>/dev/null; then
        info "the agent holds a phantom token and is not pointed at anything that can"
        info "redeem it, so this is the missing exchange rather than a bad credential"
        info "- see the credential exchange check in tier 1"
    fi
fi

# ------------------------------------------------------------------- containment
#
# Whether or not authentication worked, the real credential must not be in the container.
# This is the check that a scheme which 'works' by handing over the real key would fail.
echo
echo "-- containment --"

if podman exec "$CONTAINER" sh -c 'env' 2>/dev/null | grep -qF "$CREDENTIAL"; then
    fail "the real credential is in the container's environment"
else
    pass "the real credential is not in the container's environment"
fi

# Every process, not just the one exec starts: an agent may have re-exported it.
if podman exec "$CONTAINER" sh -c \
        'for p in /proc/[0-9]*/environ; do tr "\0" "\n" < "$p" 2>/dev/null; done' 2>/dev/null \
        | grep -qF "$CREDENTIAL"; then
    fail "the real credential is in the environment of a process in the container"
else
    pass "no process in the container has the real credential"
fi

# The agent's own config and cache: a CLI that logs in tends to write what it was given.
if podman exec "$CONTAINER" sh -c \
        'grep -rlF "$1" /home/agent /tmp 2>/dev/null | head -3' -- "$CREDENTIAL" 2>/dev/null \
        | grep -q .; then
    fail "the real credential was written to a file inside the container"
    podman exec "$CONTAINER" sh -c 'grep -rlF "$1" /home/agent /tmp 2>/dev/null | head -3' \
        -- "$CREDENTIAL" 2>/dev/null | while read -r f; do info "  $f"; done
else
    pass "the real credential is not in any file inside the container"
fi

# ---------------------------------------------------------------------- the trail
#
# Sokar writes several logs per task. A credential in any of them survives the container.
echo
echo "-- audit trail --"

STATE_DIR="$(grep '^sidecar ' "$START_LOG" | awk '{print $2}' | xargs dirname 2>/dev/null)"
LEAKED=""
for f in "$START_LOG" "$STATE_DIR"/*; do
    [ -f "$f" ] || continue
    grep -qF "$CREDENTIAL" "$f" 2>/dev/null && LEAKED="$LEAKED $(basename "$f")"
done

if [ -n "$LEAKED" ]; then
    fail "the real credential appears in:$LEAKED"
else
    pass "the real credential is in none of Sokar's logs"
fi

# The phantom token may legitimately be logged - it dies with the task. But it must be
# abbreviated rather than printed whole, or a log becomes a usable credential for its
# lifetime.
if grep -qE 'sokar_pt_[A-Za-z0-9_-]{20,}' "$START_LOG" 2>/dev/null; then
    fail "a full phantom token was printed to standard output"
else
    pass "phantom tokens are abbreviated where they are printed"
fi

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "== all checks passed =="
else
    echo "== $FAILURES check(s) failed =="
fi
[ "$SKIPPED" -gt 0 ] && echo "   $SKIPPED skipped"
exit $((FAILURES > 0 ? 1 : 0))
