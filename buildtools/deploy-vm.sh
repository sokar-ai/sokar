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

# What run number to build with, so the package OUTRANKS the one the machine already has.
#
# The '+local.<stamp>' suffix alone does not do it: '0.1.0~snapshot.0+local...' sorts BELOW
# '0.1.0~snapshot.162', because dpkg compares the run number first. A machine installed that way
# accepts it only with --force-downgrade and then has 'unattended-upgrades' quietly replace it
# with the published build within the hour - measured twice on 2026-09-19, the second time while
# another agent was about to test against what was installed.
#
# So the number is asked of the machine itself and raised by a fraction: above what is there,
# below the next CI build.
# It is asked of APT, not only of what is installed: what overwrites a local build is whatever the
# repository OFFERS, so that is the number to beat. Asking dpkg alone was not enough - a machine
# already carrying a too-low local build would stay too low for ever, which happened here.
RUN="${SOKAR_SNAPSHOT_RUN:-}"
if [ -z "$RUN" ]; then
    # LC_ALL=C, or apt answers in the machine's language and 'Candidate:' is 'Installationskandidat:'
    # - which matched nothing and silently made every local build the lowest version there is.
    # Found by asking the VM what it actually printed.
    # The index is refreshed first, or the number beaten is whatever this machine last heard of.
    # Measured on 2026-09-19: the VM offered 157 while 162 was published, so a build made to
    # outrank 157 would have been replaced the moment anything ran 'apt update'.
    ssh "${SSH_OPTS[@]}" "$VM" "sudo apt-get update -qq >/dev/null 2>&1 || true" || true
    VERSIONS="$(ssh "${SSH_OPTS[@]}" "$VM" "
        dpkg-query -W -f='\${Version}\n' sokar 2>/dev/null || true
        LC_ALL=C apt-cache policy sokar 2>/dev/null | sed -n 's/.*Candidate: *//p' || true
        LC_ALL=C apt-cache madison sokar 2>/dev/null | awk -F'|' '{print \$2}' || true
    " 2>/dev/null || true)"
    # '0.1.0~snapshot.162' or '0.1.0~snapshot.162.1+local...' - take the run numbers out and keep
    # the highest, comparing them as version numbers rather than as text.
    HIGHEST="$(printf '%s\n' "$VERSIONS" \
        | sed -n 's/.*~snapshot\.\([0-9][0-9.]*\).*/\1/p' \
        | sort -V | tail -1)"
    RUN="${HIGHEST:-0}.1"
fi
say "building as ~snapshot.$RUN (above anything that machine has or is offered)"

if [ "${1:-}" != "--skip-build" ]; then
    say "building here"
    # Same profiles CI uses, so what lands is what CI would publish. The local-package profile
    # adds '+local.<stamp>' - see sokar.snapshot.suffix in the root pom - and the run number above
    # is what actually puts it ahead of the installed package.
    (cd "$ROOT" && ./mvnw -B -Pnative,dist verify -DskipTests -Dsokar.snapshot.run="$RUN")
fi

DEB="$(ls -t "$ROOT"/dist-deb/target/sokar_*.deb | head -1)"
AGENT_STUB="$ROOT/agents/stub/target/sokar-agent-stub"
[ -x "$AGENT_STUB" ] || AGENT_STUB=""
[ -n "$DEB" ] || { echo "no sokar deb in dist-deb/target"; exit 1; }
say "installing $(basename "$DEB")"

scp "${SSH_OPTS[@]}" -q "$DEB" ${AGENT_STUB:+"$AGENT_STUB"} "$VM:/tmp/"

# --force-downgrade because a machine may carry a version that outranks this one. That used to be
# routine: before the '+local' marker a local build had to claim a higher run number to install at
# all, and machines ended up pinned above every future CI build. One such install is why this flag
# is here; it is not needed for a machine that has only ever seen published packages.
ssh "${SSH_OPTS[@]}" "$VM" "
    set -eu
    sudo dpkg -i --force-downgrade /tmp/$(basename "$DEB")
    # The acceptance suite runs '--agent stub', so it goes in - for this user only. As a package it
    # was an agent every account could pick, on a machine people also use, and a second agent there
    # turned the operator's own Start into 'Several agents are installed'.
    if dpkg -s sokar-agent-stub >/dev/null 2>&1; then sudo dpkg -r sokar-agent-stub; fi
    ${AGENT_STUB:+install -D -m 0755 /tmp/sokar-agent-stub \$HOME/.local/share/sokar/agents/sokar-agent-stub}
    echo
    echo 'installed: '\$(dpkg-query -W -f='\${Version}' sokar)
    echo 'binary says: '\$(sokar --version)
"

say "checking nothing shadows what was just installed"
# A stale copy in ~/.local/bin comes first on PATH and silently wins. An acceptance run against
# this machine then measures a binary nobody installed: on 2026-09-11 a whole suite ran the CLI
# from before the lifecycle cut and failed on verbs that had been renamed, while the package with
# those verbs sat unused in /usr/bin.
ssh "${SSH_OPTS[@]}" "$VM" "
    found=\$(bash -lc 'command -v sokar')
    if [ \"\$found\" != /usr/bin/sokar ]; then
        echo \"PATH finds \$found, not the package at /usr/bin/sokar\"
        echo \"  \$found says: \$(\$found --version 2>&1 | head -1)\"
        echo \"  the package says: \$(/usr/bin/sokar --version 2>&1 | head -1)\"
        echo \"Remove the shadowing copy, or everything after this measures the wrong binary.\"
        exit 1
    fi
    echo \"sokar on PATH is the package: \$(sokar --version)\"
"

# Without this systemd stops everything the user owns when their last session ends - including
# conmon, so every task dies with exit 143 and the workspace is only reachable again by resuming.
# Every machine Sokar rents gets this at creation; a machine somebody keeps has to be told once.
ssh "${SSH_OPTS[@]}" "$VM" "sudo loginctl enable-linger $USER_NAME && loginctl show-user $USER_NAME --property=Linger"

say "restarting the daemon"
# Through the user unit the package installs, so what runs here is supervised and comes back after
# a failure - and so this script stops being the only thing that knows how to start a daemon.
#
# The socket is no longer cleaned up by hand. It used to be, because stopping by name left one
# behind and a second daemon then silently rebound it - leaving the first running, unreachable,
# with its tasks invisible; measured once, and it cost an afternoon of a task list that showed
# nothing. Both halves of that are fixed in the daemon itself now: it unlinks on a signal, and it
# refuses to bind over a socket somebody is still answering on. Deleting the file here would walk
# straight past the second guard.
ssh "${SSH_OPTS[@]}" "$VM" "
    if [ -f /usr/lib/systemd/user/sokard.service ]; then
        systemctl --user daemon-reload
        systemctl --user enable sokard >/dev/null
        systemctl --user restart sokard
        echo 'daemon: systemd user unit, '\$(systemctl --user is-active sokard)
    else
        # An older package, or a binary somebody copied. Kept so this script still works against
        # a machine that has not been updated yet.
        pkill -u $USER_NAME -x sokard || true
        sleep 1
        XDG_RUNTIME_DIR=/run/user/\$(id -u) nohup sokard > \$HOME/sokard.log 2>&1 &
        echo 'daemon: started by hand, no unit in this package'
    fi
    sleep 2
    test -S /run/user/\$(id -u)/sokar/sokard.sock \
        || { echo 'the daemon did not come up'; systemctl --user status sokard --no-pager 2>&1 | tail -20; exit 1; }
    echo SOCKET=/run/user/\$(id -u)/sokar/sokard.sock
    echo 'agents it can run: '\$(sokar agents 2>/dev/null | tail -n +2 | awk '{print \$1}' | paste -sd' ')
" | tee /tmp/sokar-deploy-vm.out
SOCKET="$(sed -n 's/^SOCKET=//p' /tmp/sokar-deploy-vm.out)"

say "add this machine in the interface"
echo "  address  $VM"
echo "  socket   ${SOCKET:-could not be read; look in the output above}"
echo
echo "The interface runs where you are, natively, and forwards that socket over ssh."
