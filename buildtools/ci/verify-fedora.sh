#!/usr/bin/env bash
#
# Refuses to let a snapshot be taken of a machine that cannot run the suite.
#
# Every check here corresponds to a failure that, when it happens later, does not look like a
# missing package - it looks like a Sokar bug. That is the whole reason this runs before the
# snapshot rather than after: a bad snapshot is used by every future run.
set -uo pipefail

BUILD_USER="${BUILD_USER:-build}"
FAILURES=0

pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

echo "-- the reason this machine exists --"
if [ "$(getenforce 2>/dev/null)" = "Enforcing" ]; then
    pass "SELinux is enforcing"
else
    fail "SELinux is '$(getenforce 2>/dev/null || echo absent)', not Enforcing - this machine covers nothing a hosted runner does not"
fi

echo "-- rootless podman --"
if sudo -u "$BUILD_USER" -i podman run --rm docker.io/library/alpine:3.20 true 2>/dev/null; then
    pass "rootless podman runs a container"
else
    fail "rootless podman cannot run a container"
fi

if grep -q "^$BUILD_USER:" /etc/subuid && grep -q "^$BUILD_USER:" /etc/subgid; then
    pass "subuid and subgid are set for '$BUILD_USER'"
else
    fail "no subuid/subgid for '$BUILD_USER' - rootless podman will not start"
fi

echo "-- the things whose absence is silent --"
UID_OF="$(id -u "$BUILD_USER")"
if [ -d "/run/user/$UID_OF" ]; then
    pass "/run/user/$UID_OF exists, so task state has somewhere to live"
else
    fail "/run/user/$UID_OF does not exist - enable-linger did not take"
fi

if dnsmasq --version 2>/dev/null | grep -q nftset; then
    pass "dnsmasq has nftset"
else
    fail "dnsmasq has no nftset - every declared domain would be unreachable, quietly"
fi

for tool in nft nsenter git podman; do
    if command -v "$tool" >/dev/null; then
        pass "$tool is installed"
    else
        fail "$tool is missing"
    fi
done

echo "-- the build --"
if [ -x /opt/graalvm/bin/native-image ]; then
    pass "native-image is present ($(/opt/graalvm/bin/native-image --version 2>/dev/null | head -1 | cut -c1-40))"
else
    fail "no native-image, so nothing can be built here"
fi

for image in docker.io/library/ubuntu:24.04 docker.io/library/alpine:3.20; do
    if sudo -u "$BUILD_USER" -i podman image exists "$image" 2>/dev/null; then
        pass "$image is already pulled"
    else
        fail "$image is not pulled - every run would pay for it"
    fi
done

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "== ready to snapshot =="
    exit 0
fi
echo "== $FAILURES check(s) failed - NOT snapshotting =="
exit 1
