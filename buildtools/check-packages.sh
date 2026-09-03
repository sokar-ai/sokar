#!/usr/bin/env bash
#
# Checks the built .deb and .rpm packages against each other and against a real install.
#
# There are two failure modes this exists for, and both have already happened once:
#
#   1. The deb and the rpm list their contents in different plugin syntaxes, in different
#      modules. Adding a file to one and forgetting the other produces two packages that
#      claim to be the same release and are not.
#   2. jdeb and the rpm plugin bind to 'verify', not 'package'. Running "-Pnative,dist
#      package" rebuilds the binaries and leaves the packages untouched, so a package can
#      quietly contain a binary from an earlier build.
#
# Needs podman, and pulls ubuntu:24.04 and fedora:41.
#
# Usage: buildtools/check-packages.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
info() { printf '        %s\n' "$1"; }

echo "== packages =="

DEB="$(ls "$ROOT"/dist-deb/target/sokar_*.deb 2>/dev/null | head -1)"
RPM="$(ls "$ROOT"/dist-rpm/target/sokar-*.rpm 2>/dev/null | head -1)"
AGENT_DEB="$(ls "$ROOT"/agents/claude/target/sokar-agent-claude_*.deb 2>/dev/null | head -1)"
AGENT_RPM="$(ls "$ROOT"/agents/claude/target/sokar-agent-claude-*.rpm 2>/dev/null | head -1)"

for f in "$DEB" "$RPM" "$AGENT_DEB" "$AGENT_RPM"; do
    if [ -z "$f" ]; then
        echo "missing packages under dist-deb, dist-rpm or agents/claude"
        echo "run: JAVA_HOME=<graalvm> ./mvnw -Pnative,dist verify -DskipTests"
        echo "note the phase: 'package' builds the binaries and no packages at all"
        exit 2
    fi
done

# --------------------------------------------------------------- freshness
#
# A package older than the binary it is supposed to contain is the 'package' vs 'verify'
# trap. Everything below would still pass while testing a stale binary, so this is first.
echo
echo "-- freshness --"
STALE=0
for pair in "$DEB:$ROOT/app/target/sokar" "$RPM:$ROOT/app/target/sokar" \
            "$AGENT_DEB:$ROOT/agents/claude/target/sokar-agent-claude" \
            "$AGENT_RPM:$ROOT/agents/claude/target/sokar-agent-claude"; do
    pkg="${pair%%:*}"
    bin="${pair##*:}"
    if [ -f "$bin" ] && [ "$bin" -nt "$pkg" ]; then
        fail "$(basename "$pkg") is older than $(basename "$bin")"
        STALE=1
    fi
done
[ "$STALE" -eq 0 ] && pass "every package is newer than the binary it carries"

# ----------------------------------------------------------- content parity
echo
echo "-- deb and rpm agree --"

DEB_FILES="$(dpkg-deb -c "$DEB" | awk '$1 !~ /^d/ {print substr($6, 2)}' | sort)"
RPM_FILES="$(podman run --rm -v "$(dirname "$RPM")":/pkg:ro,Z fedora:41 \
    rpm -qlp "/pkg/$(basename "$RPM")" 2>/dev/null | sort)"

if [ "$DEB_FILES" = "$RPM_FILES" ]; then
    pass "both packages install the same $(echo "$DEB_FILES" | wc -l) file(s)"
else
    fail "the deb and the rpm do not install the same files"
    diff <(echo "$DEB_FILES") <(echo "$RPM_FILES") | while read -r line; do info "$line"; done
fi

DEB_VERSION="$(dpkg-deb -f "$DEB" Version)"
RPM_VERSION="$(podman run --rm -v "$(dirname "$RPM")":/pkg:ro,Z fedora:41 \
    rpm -qp --qf '%{VERSION}' "/pkg/$(basename "$RPM")" 2>/dev/null)"

if [ "$DEB_VERSION" = "$RPM_VERSION" ]; then
    pass "both packages are version $DEB_VERSION"
else
    fail "version disagreement: deb says $DEB_VERSION, rpm says $RPM_VERSION"
fi

# The whole point of the '~': a snapshot must sort BELOW the release it precedes, or apt
# and dnf both refuse to upgrade from it.
case "$DEB_VERSION" in
    *~SNAPSHOT) pass "a snapshot version uses '~', so it sorts below the release" ;;
    *-SNAPSHOT) fail "version is '$DEB_VERSION': '-SNAPSHOT' sorts ABOVE the release" ;;
    *) info "version $DEB_VERSION is not a snapshot" ;;
esac

# --------------------------------------------------------------- real install
#
# Metadata can be right while the package does not install. Both are checked in a clean
# container, including that the agent package's dependency on sokar actually resolves.
install_check() {
    local label="$1" image="$2" script="$3"
    echo
    echo "-- installs on $label --"
    local out
    out="$(podman run --rm -v "$(dirname "$DEB")":/deb:ro,Z \
        -v "$(dirname "$RPM")":/rpm:ro,Z \
        -v "$(dirname "$AGENT_DEB")":/agent:ro,Z \
        "$image" sh -c "$script" 2>&1)"

    for expect in "SOKAR-OK" "AGENT-OK" "SETUP-OK" "HOOKS-PACKAGED" "DISCOVERY-OK"; do
        if echo "$out" | grep -q "$expect"; then
            case "$expect" in
                SOKAR-OK) pass "the sokar package installs and the binary runs" ;;
                AGENT-OK) pass "the agent package installs; its dependency on sokar resolves" ;;
                SETUP-OK) pass "sokar setup writes the podman hook descriptors" ;;
                HOOKS-PACKAGED) pass "the descriptors point at the packaged hook binaries" ;;
                DISCOVERY-OK) pass "sokar discovers the agent it was never linked against" ;;
            esac
        else
            fail "$expect on $label"
            echo "$out" | tail -5 | while read -r line; do info "$line"; done
        fi
    done
}

COMMON='
    sokar --version >/dev/null 2>&1 && echo SOKAR-OK
    sokar setup >/dev/null 2>&1 && echo SETUP-OK
    grep -qho "/usr/libexec/sokar/hooks/sokar-hook-nft" \
        /root/.config/containers/oci/hooks.d/* 2>/dev/null && echo HOOKS-PACKAGED
    sokar agents 2>/dev/null | grep -q claude && echo DISCOVERY-OK
'

install_check "Debian" ubuntu:24.04 "
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq >/dev/null 2>&1
    apt-get install -y -qq /deb/sokar_*.deb >/dev/null 2>&1
    apt-get install -y -qq /agent/sokar-agent-claude_*.deb >/dev/null 2>&1 && echo AGENT-OK
    $COMMON"

install_check "Fedora" fedora:41 "
    dnf install -y -q /rpm/sokar-0*.rpm >/dev/null 2>&1
    dnf install -y -q /agent/sokar-agent-claude-*.rpm >/dev/null 2>&1 && echo AGENT-OK
    $COMMON"

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "== all checks passed =="
else
    echo "== $FAILURES check(s) failed =="
fi
exit $((FAILURES > 0 ? 1 : 0))
