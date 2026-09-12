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
#   3. The dist profile lives in agents/pom.xml so that adding an agent needs no packaging
#      config. It is therefore inherited by the aggregator and by sokar-agent-api, neither
#      of which has a binary to package. A full-reactor build then fails on the aggregator
#      while "-pl agents/stub" passes, which is how it went unnoticed.
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
# The stub, because the real agents live in their own repositories now. What is checked here is
# Sokar's packaging machinery and that an agent package's dependency on sokar resolves - both of
# which are the same whatever the agent is.
AGENT_DEB="$(ls "$ROOT"/agents/stub/target/sokar-agent-stub_*.deb 2>/dev/null | head -1)"
AGENT_RPM="$(ls "$ROOT"/agents/stub/target/sokar-agent-stub-*.rpm 2>/dev/null | head -1)"

for f in "$DEB" "$RPM" "$AGENT_DEB" "$AGENT_RPM"; do
    if [ -z "$f" ]; then
        echo "missing packages under dist-deb, dist-rpm or agents/stub"
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
            "$AGENT_DEB:$ROOT/agents/stub/target/sokar-agent-stub" \
            "$AGENT_RPM:$ROOT/agents/stub/target/sokar-agent-stub"; do
    pkg="${pair%%:*}"
    bin="${pair##*:}"
    if [ -f "$bin" ] && [ "$bin" -nt "$pkg" ]; then
        fail "$(basename "$pkg") is older than $(basename "$bin")"
        STALE=1
    fi
done
[ "$STALE" -eq 0 ] && pass "every package is newer than the binary it carries"

# ------------------------------------------------------------ agents only
#
# Only a real agent module packages itself. See failure mode 3 above.
echo
echo "-- only agents are packaged --"
STRAY="$(ls "$ROOT"/agents/target/*.deb "$ROOT"/agents/target/*.rpm \
            "$ROOT"/agents/api/target/*.deb "$ROOT"/agents/api/target/*.rpm 2>/dev/null)"
if [ -n "$STRAY" ]; then
    fail "a non-agent module produced a package"
    for f in $STRAY; do info "$(basename "$f")"; done
else
    pass "the aggregator and sokar-agent-api produce no package"
fi

# ----------------------------------------------------------- content parity
echo
echo "-- deb and rpm agree --"

DEB_ALL="$(dpkg-deb -c "$DEB" | awk '$1 !~ /^d/ {print substr($6, 2)}' | sort)"
RPM_ALL="$(podman run --rm -v "$(dirname "$RPM")":/pkg:ro,Z fedora:41 \
    rpm -qlp "/pkg/$(basename "$RPM")" 2>/dev/null | sort)"

# Two files the two ecosystems put in different places on purpose, and each is checked on its
# own below; everything else must match.
#
#   the license           Debian Policy 12.5 wants /usr/share/doc/<pkg>/copyright, rpm wants
#                         %license under /usr/share/licenses/<pkg>.
#   the zsh completion    Debian's zsh reads /usr/share/zsh/vendor-completions and has no
#                         site-functions in its fpath; Fedora's is the other way round. Measured
#                         on both, because guessing this wrong ships a file into a directory
#                         nothing reads and the completion simply never appears.
#
# The zsh one is NORMALIZED rather than excluded, so the comparison still fails when one package
# ships it and the other does not. Excluding it would make a forgotten file look like agreement.
zsh_normalized() {
    sed 's#^/usr/share/zsh/\(vendor-completions\|site-functions\)/#/usr/share/zsh/<completions>/#'
}
DEB_FILES="$(echo "$DEB_ALL" | grep -v '^/usr/share/doc/' | zsh_normalized || true)"
RPM_FILES="$(echo "$RPM_ALL" | grep -v '^/usr/share/licenses/' | zsh_normalized || true)"

if [ "$DEB_FILES" = "$RPM_FILES" ]; then
    pass "both packages install the same $(echo "$DEB_FILES" | wc -l) payload file(s)"
else
    fail "the deb and the rpm do not install the same files"
    diff <(echo "$DEB_FILES") <(echo "$RPM_FILES") | while read -r line; do info "$line"; done
fi

if echo "$DEB_ALL" | grep -q '^/usr/share/doc/.*/copyright$'; then
    pass "the deb ships a copyright file where Debian policy requires one"
else
    fail "the deb ships no /usr/share/doc/<package>/copyright"
fi

if echo "$RPM_ALL" | grep -q '^/usr/share/licenses/.*/LICENSE$'; then
    pass "the rpm ships its license under /usr/share/licenses"
else
    fail "the rpm ships no /usr/share/licenses/<package>/LICENSE"
fi

# Each in the directory ITS OWN zsh actually reads. The parity check above only says both ship
# one; it cannot say either is where the shell will look.
if echo "$DEB_ALL" | grep -q '^/usr/share/zsh/vendor-completions/_sokar$'; then
    pass "the deb puts the zsh completion where Debian's zsh looks"
else
    fail "the deb ships no /usr/share/zsh/vendor-completions/_sokar"
fi

if echo "$RPM_ALL" | grep -q '^/usr/share/zsh/site-functions/_sokar$'; then
    pass "the rpm puts the zsh completion where Fedora's zsh looks"
else
    fail "the rpm ships no /usr/share/zsh/site-functions/_sokar"
fi

for pkg_files in "deb:$DEB_ALL" "rpm:$RPM_ALL"; do
    if echo "${pkg_files#*:}" | grep -q '^/usr/share/bash-completion/completions/sokar$'; then
        pass "the ${pkg_files%%:*} ships the bash completion"
    else
        fail "the ${pkg_files%%:*} ships no bash completion"
    fi
done

# ------------------------------------------------------------------ the bill
#
# A package that ships no bill, or one describing something else, is the failure this exists
# for: the automated update gate compares the new bill against the published one, and it cannot
# notice a changed dependency set in a document that was never written or never updated.
echo
echo "-- bills of materials --"

check_bom() {
    local label="$1" package="$2" name="$3" version="$4" body
    case "$package" in
        *.deb) body="$(dpkg-deb --fsys-tarfile "$package" \
                   | tar -xO "./usr/share/sokar/sbom/$name.cdx.json" 2>/dev/null)" ;;
        # rpm2archive, not rpm2cpio: the fedora image has no cpio, so that pipe produced
        # nothing at all and read as a missing file.
        *)     body="$(podman run --rm -v "$(dirname "$package")":/pkg:ro,Z fedora:41 sh -c \
                   "rpm2archive -n - < '/pkg/$(basename "$package")' \
                    | tar -xO './usr/share/sokar/sbom/$name.cdx.json'" 2>/dev/null)" ;;
    esac

    if [ -z "$body" ]; then
        fail "$label ships no bill at /usr/share/sokar/sbom/$name.cdx.json"
        return
    fi
    printf '%s' "$body" | python3 -c "
import json, sys
bom = json.load(sys.stdin)
assert bom.get('bomFormat') == 'CycloneDX', 'not a CycloneDX document'
subject = bom['metadata']['component']
assert subject['name'] == '$name', f\"names {subject['name']}, not $name\"
assert subject['version'] == '$version', f\"version {subject['version']}, not $version\"

def count(items):
    return sum(1 + count(c.get('components')) for c in (items or []))
total = count(bom.get('components'))
assert total > 0, 'lists no components at all'
print(total)
" > /tmp/sokar-bom-count 2>/tmp/sokar-bom-error

    if [ $? -eq 0 ]; then
        pass "$label ships a bill for itself ($(cat /tmp/sokar-bom-count) components)"
    else
        fail "$label ships a bill that $(tail -1 /tmp/sokar-bom-error | sed 's/^AssertionError: //')"
    fi
    rm -f /tmp/sokar-bom-count /tmp/sokar-bom-error
}

# The version a bill records is the MAVEN one, and since snapshot packages carry a build number
# the two are no longer the same string: the package is 0.1.0~snapshot.69, the bill says
# 0.1.0-SNAPSHOT. Derived here rather than compared directly, which is the inverse of what the
# POMs do when they build the package version.
#
# Worth knowing what this therefore does NOT catch: every build of a release line writes the same
# bill version, so a bill left over from an earlier build is invisible to this check. It catches a
# missing bill, a bill for the wrong package, and an empty one.
bom_version() {
    case "$1" in
        *~snapshot.*|*~SNAPSHOT) echo "${1%%~*}-SNAPSHOT" ;;
        *)                       echo "$1" ;;
    esac
}

SOKAR_VERSION="$(dpkg-deb -f "$DEB" Version)"
AGENT_VERSION="$(dpkg-deb -f "$AGENT_DEB" Version)"
check_bom "the sokar deb" "$DEB" "sokar" "$(bom_version "$SOKAR_VERSION")"
check_bom "the sokar rpm" "$RPM" "sokar" "$(bom_version "$SOKAR_VERSION")"
check_bom "the agent deb" "$AGENT_DEB" "sokar-agent-stub" "$(bom_version "$AGENT_VERSION")"
check_bom "the agent rpm" "$AGENT_RPM" "sokar-agent-stub" "$(bom_version "$AGENT_VERSION")"

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
    *-SNAPSHOT)
        fail "version is '$DEB_VERSION': '-SNAPSHOT' sorts ABOVE the release" ;;
    *~SNAPSHOT)
        # The shape this replaced. Flat, so every build of main carried the same version and
        # 'apt upgrade' had nothing to do - a repository called 'snapshots' whose packages never
        # updated.
        fail "version is '$DEB_VERSION': a flat snapshot never supersedes the last one" ;;
    *~snapshot.*)
        pass "a snapshot version uses '~', so it sorts below the release"
        # Asked of dpkg rather than assumed. What has to be true is that this package REPLACES
        # the one before it and still loses to the eventual release - and that the comparison is
        # numeric, so build 10 beats build 9 rather than sorting beside build 1.
        RELEASE="${DEB_VERSION%%~*}"
        RUN="${DEB_VERSION##*~snapshot.}"
        # Without this the arithmetic below chokes on a local build's "+local.<stamp>".
        RUN="${RUN%%+*}"
        if dpkg --compare-versions "$DEB_VERSION" lt "$RELEASE"; then
            pass "it still sorts below the release $RELEASE"
        else
            fail "$DEB_VERSION does not sort below $RELEASE"
        fi
        if dpkg --compare-versions "${RELEASE}~snapshot.$((RUN + 1))" gt "$DEB_VERSION"; then
            pass "the next build supersedes it, so 'apt upgrade' has something to do"
        else
            fail "${RELEASE}~snapshot.$((RUN + 1)) does not sort above $DEB_VERSION"
        fi
        if dpkg --compare-versions "${RELEASE}~snapshot.10" gt "${RELEASE}~snapshot.9"; then
            pass "build numbers compare numerically, so 10 beats 9"
        else
            fail "build 10 does not sort above build 9 - the comparison is lexical"
        fi
        # Where a package came from, which the run number alone cannot say. A local build that
        # wanted to replace a published one used to have to claim a higher run, and that number
        # then outranked every future CI build - so the machine refused to upgrade for good, and
        # said nothing, because refusing was the right answer to the question it was asked. Found
        # on a test VM at 0.1.0~snapshot.9011 against a repository at 0.1.0~snapshot.99.
        case "$DEB_VERSION" in
            *+local.*)
                pass "this package says it was built locally"
                # Both directions, because only having one of them is how it went wrong before:
                # installable over what it was made from, and superseded by what comes next.
                BASE="${DEB_VERSION%%+local.*}"
                NEXT="${RELEASE}~snapshot.$((${BASE##*~snapshot.} + 1))"
                if dpkg --compare-versions "$DEB_VERSION" gt "$BASE"; then
                    pass "it replaces the published $BASE it was built from"
                else
                    fail "$DEB_VERSION does not sort above $BASE, so it cannot be installed over it"
                fi
                if dpkg --compare-versions "$NEXT" gt "$DEB_VERSION"; then
                    pass "the next CI build $NEXT takes the machine back"
                else
                    fail "$NEXT does not sort above $DEB_VERSION - a local build would pin this machine forever"
                fi ;;
            *)
                if [ -n "${GITHUB_RUN_ID:-}" ]; then
                    pass "a CI build carries no local marker"
                else
                    fail "built outside CI and carrying no '+local.' marker: this package can outrank every published one"
                fi ;;
        esac ;;
    *) info "version $DEB_VERSION is not a snapshot" ;;
esac

# --------------------------------------------------------------- real install
#
# Metadata can be right while the package does not install. Both are checked in a clean
# container, including that the agent package's dependency on sokar actually resolves.
# What a binary says about itself has to be what the package says about it. It was not: the
# resource the version is read from was filtered from ${project.version}, so every build ever
# made answered '0.1.0-SNAPSHOT' while its package carried a build number. Nothing compared the
# two, because the check below ran 'sokar --version' and threw the answer away - it proved the
# binary starts, which is not the same question. The cost was not theoretical: the daemon reports
# this same string over the wire so an interface can tell which Sokar it is talking to, and a test
# VM sat three weeks behind on a build nothing could name.
install_check() {
    local label="$1" image="$2" script="$3" expected="$4"
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

    local reported
    reported="$(echo "$out" | sed -n 's/^REPORTED://p' | tail -1)"
    if [ "$reported" = "sokar $expected" ]; then
        pass "the binary names the build it came from ($expected)"
    else
        fail "the package is $expected but the binary says '${reported:-nothing}'"
        info "an interface cannot tell two daemons apart when every build answers the same"
    fi
}

COMMON='
    sokar --version >/dev/null 2>&1 && echo SOKAR-OK
    echo "REPORTED:$(sokar --version 2>/dev/null)"
    sokar setup >/dev/null 2>&1 && echo SETUP-OK
    grep -qho "/usr/libexec/sokar/hooks/sokar-hook-nft" \
        /root/.config/containers/oci/hooks.d/* 2>/dev/null && echo HOOKS-PACKAGED
    sokar agents 2>/dev/null | grep -q stub && echo DISCOVERY-OK
'

install_check "Debian" ubuntu:24.04 "
    export DEBIAN_FRONTEND=noninteractive
    # The same mirror Sokar writes into the images it builds, for the same reason and measured the
    # same way: on 2026-09-11 this step took 582 of the Publish job's 610 seconds, because a plain
    # ubuntu:24.04 fetches from a disrupted archive.ubuntu.com. The Containerfile's rewrite does not
    # reach here - this container is started by the script, not built by Sokar.
    printf '%s\n' 'Acquire::http::Timeout "20";' 'Acquire::Retries "2";' \
        > /etc/apt/apt.conf.d/99-sokar-timeouts
    sed -i 's|^URIs:.*|URIs: http://azure.archive.ubuntu.com/ubuntu/|' \
        /etc/apt/sources.list.d/*.sources 2>/dev/null || true
    apt-get update -qq >/dev/null 2>&1
    apt-get install -y -qq /deb/sokar_*.deb >/dev/null 2>&1
    apt-get install -y -qq /agent/sokar-agent-stub_*.deb >/dev/null 2>&1 && echo AGENT-OK
    $COMMON" "$DEB_VERSION"

install_check "Fedora" fedora:41 "
    dnf install -y -q /rpm/sokar-0*.rpm >/dev/null 2>&1
    dnf install -y -q /agent/sokar-agent-stub-*.rpm >/dev/null 2>&1 && echo AGENT-OK
    $COMMON" "$RPM_VERSION"

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "== all checks passed =="
else
    echo "== $FAILURES check(s) failed =="
fi
exit $((FAILURES > 0 ? 1 : 0))
