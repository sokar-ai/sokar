#!/usr/bin/env bash
#
# Builds Sokar here and installs it on a machine you already have, for testing by hand.
#
# What it is for: the CI legs rent a machine, run once and delete it. That is the wrong shape for
# sitting in front of an interface and trying things. This puts the same packages CI would publish
# onto a machine that stays, and leaves a daemon running for a person to connect to.
#
# Usage:  SOKAR_VM=user@host buildtools/deploy-vm.sh [--skip-build]
#
#   SOKAR_VM        Where to install. Required.
#   SOKAR_VM_KEY    ssh key to reach it with. Default: ssh's own choice.
#   --skip-build    Install whatever is already in target/, without rebuilding.
#
# The frontend is NOT installed here: it runs as a native application where the person is, and
# reaches this machine over a forwarded socket. The last thing printed is what it needs.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VM="${SOKAR_VM:?set SOKAR_VM=user@host}"
USER_NAME="${VM%@*}"
SSH_OPTS=(-o StrictHostKeyChecking=accept-new)
[ -n "${SOKAR_VM_KEY:-}" ] && SSH_OPTS+=(-i "$SOKAR_VM_KEY")

say() { printf '\n\033[1m-- %s --\033[0m\n' "$1"; }

if [ "${1:-}" != "--skip-build" ]; then
    say "building here"
    # Same profiles CI uses, so what lands is what CI would publish. The local-package profile
    # marks the version '+local.<stamp>' by itself, which sorts above the published build it was
    # made from and below the next CI one - see sokar.snapshot.suffix in the root pom.
    (cd "$ROOT" && ./mvnw -B -Pnative,dist verify -DskipTests)
fi

DEB="$(ls -t "$ROOT"/dist-deb/target/sokar_*.deb | head -1)"
AGENT_DEB="$(ls -t "$ROOT"/agents/stub/target/sokar-agent-stub_*.deb 2>/dev/null | head -1 || true)"
[ -n "$DEB" ] || { echo "no sokar deb in dist-deb/target"; exit 1; }
say "installing $(basename "$DEB")"

scp "${SSH_OPTS[@]}" -q "$DEB" ${AGENT_DEB:+"$AGENT_DEB"} "$VM:/tmp/"

# --force-downgrade because a machine may carry a version that outranks this one. That used to be
# routine: before the '+local' marker a local build had to claim a higher run number to install at
# all, and machines ended up pinned above every future CI build. One such install is why this flag
# is here; it is not needed for a machine that has only ever seen published packages.
ssh "${SSH_OPTS[@]}" "$VM" "
    set -eu
    sudo dpkg -i --force-downgrade /tmp/$(basename "$DEB")
    ${AGENT_DEB:+sudo dpkg -i --force-downgrade /tmp/$(basename "$AGENT_DEB")}
    echo
    echo 'installed: '\$(dpkg-query -W -f='\${Version}' sokar)
    echo 'binary says: '\$(sokar --version)
"

say "making the daemon survive a logout"
# Without this systemd stops everything the user owns when their last session ends - including
# conmon, so every task dies with exit 143 and the workspace is only reachable again by resuming.
# Every machine Sokar rents gets this at creation; a machine somebody keeps has to be told once.
ssh "${SSH_OPTS[@]}" "$VM" "sudo loginctl enable-linger $USER_NAME && loginctl show-user $USER_NAME --property=Linger"

say "restarting the daemon"
# Stopped by name rather than by pid file: a daemon started by hand from a build tree leaves none,
# and a second one silently rebinds the socket - leaving the first running, unreachable, with its
# tasks invisible. Measured once; it cost an afternoon of a task list that showed nothing.
ssh "${SSH_OPTS[@]}" "$VM" "
    pkill -u $USER_NAME -x sokard || true
    sleep 1
    rm -f /run/user/\$(id -u)/sokar/sokard.sock
    XDG_RUNTIME_DIR=/run/user/\$(id -u) nohup sokard > \$HOME/sokard.log 2>&1 &
    sleep 2
    test -S /run/user/\$(id -u)/sokar/sokard.sock \
        || { echo 'the daemon did not come up; its log is '\$HOME/sokard.log; exit 1; }
    echo SOCKET=/run/user/\$(id -u)/sokar/sokard.sock
    echo 'agents it can run: '\$(sokar agents 2>/dev/null | tail -n +2 | awk '{print \$1}' | paste -sd' ')
" | tee /tmp/sokar-deploy-vm.out
SOCKET="$(sed -n 's/^SOCKET=//p' /tmp/sokar-deploy-vm.out)"

say "add this machine in the interface"
echo "  address  $VM"
echo "  socket   ${SOCKET:-could not be read; look in the output above}"
echo
echo "The interface runs where you are, natively, and forwards that socket over ssh."
