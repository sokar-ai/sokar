#!/bin/sh
#
# Prepares a machine to run Sokar. Run as root, once, on a machine that has nothing.
#
# This is the one piece of Sokar that is a file rather than a call: everything else goes through
# the daemon, and a machine being prepared has no daemon yet, no packages, and no user for the
# daemon to run as. See issues/base/B62.
#
# Usage:  sokar-setup.sh [options]
#
#   --user <name>       The account tasks run as. Default: agents
#   --distribution <d>  Which published distribution to install from. Default: snapshots
#   --with <package>    Also install this package. May be given several times.
#   --list              Print what this machine could install, install nothing, and stop
#   --json              With --list: one JSON object on stdout, for a program rather than a person
#   --show              Print every command and run none of them
#   --os-release <file> Read the system description from here instead of /etc/os-release
#   --help              This text
#
# What is always installed: Sokar, the message filter and the local transport. The filter because
# without it nothing leaves, which is the point of it. Agents and any further transports are
# chosen - '--list' says what there is, '--with' installs them.
#
# Exit codes, which a caller may rely on:
#   0  the machine is prepared, or already was
#   2  the arguments do not make sense
#   3  this operating system is not one Sokar knows how to prepare
#   4  not running as root
#   5  a check failed, and the machine is not usable as it stands
#
# Running it twice is safe and changes nothing the second time.
set -eu

USER_NAME=agents
DISTRIBUTION=snapshots
SHOW=no
LIST=no
JSON=no
WITH=
OS_RELEASE=/etc/os-release
BASE=https://fuinorg.jfrog.io/artifactory

while [ $# -gt 0 ]; do
    case "$1" in
        --user) USER_NAME="${2:?--user needs a name}"; shift 2 ;;
        --distribution) DISTRIBUTION="${2:?--distribution needs a name}"; shift 2 ;;
        --with) WITH="$WITH ${2:?--with needs a package name}"; shift 2 ;;
        --list) LIST=yes; shift ;;
        --json) JSON=yes; shift ;;
        --show) SHOW=yes; shift ;;
        --os-release) OS_RELEASE="${2:?--os-release needs a file}"; shift 2 ;;
        --help) sed -n '2,36p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "sokar-setup: unexpected argument '$1'" >&2; exit 2 ;;
    esac
done

# With --json, stdout is reserved for the one object a program parses, so everything a person
# would read moves to stderr. File descriptor 3 stays pointed at the real stdout, and only the
# JSON is written there.
if [ "$JSON" = yes ]; then
    exec 3>&1 1>&2
else
    exec 3>&1
fi

say()  { printf '\n== %s ==\n' "$1"; }
note() { printf '   %s\n' "$1"; }

# Every command goes through this, so --show prints exactly what would run and nothing is done
# that the person was not shown. That is the reason they are willing to give this script root.
run() {
    printf '   $ %s\n' "$*"
    [ "$SHOW" = yes ] && return 0
    "$@"
}

# ---------------------------------------------------------------- what this machine is

[ -r "$OS_RELEASE" ] || { echo "sokar-setup: cannot read $OS_RELEASE" >&2; exit 3; }
# shellcheck disable=SC1090
. "$OS_RELEASE"
ID="${ID:-unknown}"
ID_LIKE="${ID_LIKE:-}"

case " $ID $ID_LIKE " in
    *" debian "*|*" ubuntu "*) FAMILY=apt ;;
    *" fedora "*|*" rhel "*)   FAMILY=dnf ;;
    *) FAMILY=none ;;
esac

if [ "$FAMILY" = none ]; then
    # Refused rather than attempted. A half-prepared machine is worse than an unprepared one,
    # because whatever runs next believes it.
    echo "sokar-setup: this is '${PRETTY_NAME:-$ID}', which Sokar does not know how to prepare." >&2
    echo "Prepare it by hand from doc/getting-started-debian.md or -fedora.md, or ask for it to be added." >&2
    exit 3
fi

if [ "$SHOW" != yes ] && [ "$(id -u)" != 0 ]; then
    echo "sokar-setup: this has to run as root - it installs packages and makes a user." >&2
    exit 4
fi

say "preparing ${PRETTY_NAME:-$ID} for Sokar, tasks running as '$USER_NAME'"

# ---------------------------------------------------------------- packages

say "the packages Sokar shells out to"
if [ "$FAMILY" = apt ]; then
    export DEBIAN_FRONTEND=noninteractive
    run apt-get update -qq
    # uidmap, slirp4netns, passt, fuse-overlayfs and crun are what rootless podman needs and what
    # a minimal image leaves out. podman itself and the rest come with the sokar package.
    run apt-get install -y -qq ca-certificates curl gnupg uidmap slirp4netns passt \
        fuse-overlayfs crun
else
    run dnf install -y -q ca-certificates curl gnupg2 shadow-utils slirp4netns passt \
        fuse-overlayfs crun
fi

# ---------------------------------------------------------------- what else this machine could have

# Every agent package declares 'Provides: sokar-agent' and every transport 'sokar-transport', so
# the repository is the catalogue and nobody keeps a list. The same query the daemon's
# 'Installable' method runs - kept word for word, so the two cannot answer differently.
# One row per installable package, as 'name|kind|state|version|description'. One producer, so the
# human table and the JSON cannot disagree about what this machine offers - and so widening a
# column never changes what a program parses.
#
# 'kind' is 'agent' or 'transport': what a person chooses between. The virtual package name it was
# read from is the mechanism, and the daemon's Installable method answers the same way.
catalogue() {
    if [ "$FAMILY" = apt ]; then
        for virtual in sokar-agent sokar-transport; do
            kind=${virtual#sokar-}
            apt-cache showpkg "$virtual" 2>/dev/null \
                | sed -n '/^Reverse Provides:/,$p' | tail -n +2 | awk 'NF {print $1}' | sort -u \
                | while read -r name; do
                    [ -n "$name" ] || continue
                    version=$(dpkg-query -W -f='${Version}' "$name" 2>/dev/null || true)
                    if [ -n "$version" ]; then
                        state=installed
                    else
                        state=available
                        version=$(apt-cache show "$name" 2>/dev/null \
                            | sed -n 's/^Version: //p' | head -1)
                    fi
                    printf '%s|%s|%s|%s|%s\n' "$name" "$kind" "$state" "$version" \
                        "$(apt-cache show "$name" 2>/dev/null \
                            | sed -n 's/^Description: //p' | head -1)"
                done
        done
    else
        for virtual in sokar-agent sokar-transport; do
            kind=${virtual#sokar-}
            dnf repoquery --qf '%{name}|%{version}-%{release}|%{summary}' \
                --whatprovides "$virtual" 2>/dev/null | sort -u \
                | while IFS='|' read -r name version summary; do
                    [ -n "$name" ] || continue
                    state=available
                    rpm -q "$name" >/dev/null 2>&1 && state=installed
                    printf '%s|%s|%s|%s|%s\n' "$name" "$kind" "$state" "$version" "$summary"
                done
        done
    fi
}

# The same rows, as one object. Written from the piped form rather than from the table: a format
# a program parses must not change when a column is widened.
catalogue_json() {
    printf '{"packages":['
    catalogue | awk -F'|' '
        function escape(text) {
            gsub(/\\/, "\\\\", text); gsub(/"/, "\\\"", text); return text
        }
        NF >= 5 {
            printf "%s{\"name\":\"%s\",\"kind\":\"%s\",\"description\":\"%s\",",
                (seen++ ? "," : ""), escape($1), escape($2), escape($5)
            printf "\"installed\":%s,\"version\":\"%s\"}",
                ($3 == "installed" ? "true" : "false"), escape($4)
        }'
    printf ']}\n'
}

if [ "$LIST" = yes ]; then
    if [ "$JSON" = yes ]; then
        # The object alone on the real stdout; everything else went to stderr from the start, so
        # a caller parses what it reads without stripping anything.
        catalogue_json >&3
        [ -n "$(catalogue)" ] || echo "nothing yet - no package in this repository declares that"\
            " it provides an agent or a transport." >&2
        exit 0
    fi
    say "what this machine could install"
    printf '   %-34s %-11s %-10s %s\n' PACKAGE KIND STATE DESCRIPTION
    found="$(catalogue)"
    if [ -n "$found" ]; then
        printf '%s\n' "$found" | while IFS='|' read -r name kind state version description; do
            printf '   %-34s %-11s %-10s %s\n' "$name" "$kind" "$state" "$description"
        done
    else
        note "nothing yet - no package in this repository declares that it provides an agent"
        note "or a transport. That line is one per package, and the repositories that build"
        note "them add it; until they do, there is nothing here to choose."
    fi
    cat <<LISTED

   Install one with:  sokar-setup.sh --with <package>
   Always installed, and not listed as a choice: sokar, the message filter. Without the
   filter nothing leaves a task, which is what it is for - there is no second one to pick.
LISTED
    exit 0
fi


# ---------------------------------------------------------------- Sokar itself

say "Sokar"
# Plain install, deliberately not '--reinstall'. This script prepares a machine; it does not
# update one. '--reinstall' fails outright on a machine carrying a version the repository does not
# have - a local build, say - and it would make running this twice do work the second time.
# Re-fetching a snapshot whose version string did not change is 'apt install --reinstall sokar',
# and that belongs in an operator's hands, not here.
# shellcheck disable=SC2086
run $INSTALL sokar

# Always: the filter, because without it nothing leaves a task, and the local transport, because
# a machine whose tasks only talk to each other still has to be able to.
say "the message filter and the local transport"
if $HAVE sokar-message-sluice-filter >/dev/null 2>&1; then
    # shellcheck disable=SC2086
    run $INSTALL sokar-message-sluice-filter sokar-message-transport-local
else
    note "not published yet - this machine can run tasks but not exchange messages"
fi

if [ -n "$WITH" ]; then
    say "what you asked for"
    # shellcheck disable=SC2086
    run $INSTALL $WITH
fi

# ---------------------------------------------------------------- the account tasks run as

say "the account tasks run as"
if id -u "$USER_NAME" >/dev/null 2>&1; then
    note "$USER_NAME exists"
else
    run useradd -m -s /bin/bash "$USER_NAME"
fi

# Without linger, systemd stops everything the user owns when their last session ends - including
# conmon, so every task dies with exit 143 and its workspace is only reachable again by resuming.
if [ "$(loginctl show-user "$USER_NAME" --property=Linger 2>/dev/null)" = "Linger=yes" ]; then
    note "linger is already enabled, so a task survives the session ending"
else
    run loginctl enable-linger "$USER_NAME"
fi

# Rootless podman cannot map a container's users without these. useradd usually allocates them,
# and 'usually' is not something to find out later as a container that will not start.
if grep -q "^$USER_NAME:" /etc/subuid 2>/dev/null && grep -q "^$USER_NAME:" /etc/subgid 2>/dev/null
then
    note "subuid and subgid ranges are already allocated"
elif [ "$SHOW" = yes ]; then
    printf '   $ %s\n' "usermod --add-subuids 100000-165535 --add-subgids 100000-165535 $USER_NAME"
else
    run usermod --add-subuids 100000-165535 --add-subgids 100000-165535 "$USER_NAME"
fi

# ---------------------------------------------------------------- what would fail silently

say "the one thing that fails silently if it is missing"
if [ "$SHOW" = yes ]; then
    printf '   $ %s\n' "dnsmasq --version | grep -q nftset"
elif dnsmasq --version 2>/dev/null | grep -q nftset; then
    note "dnsmasq has nftset support"
else
    # A dnsmasq without it accepts the configuration and opens nothing: names resolve, nothing
    # connects, and there is no error to read. Refused here rather than met as a broken task.
    echo "sokar-setup: this dnsmasq has no nftset support, so a declared domain would resolve" >&2
    echo "and never be reachable. Install dnsmasq 2.87 or later before running a task." >&2
    exit 5
fi

# ---------------------------------------------------------------- what is left for the person

say "prepared"
[ "$SHOW" = yes ] || note "sokar $(sokar --version 2>/dev/null | awk '{print $2}')"
cat <<DONE

   What is left, in $USER_NAME's own session rather than here - a root script starting
   another user's service is either wrong or a lie about the session it runs in:

       systemctl --user enable --now sokard
       sokar doctor

   'sokar doctor' is the real check. This script's exit code says the steps ran; only the
   daemon can say the machine works.

   This machine can run tasks. It cannot exchange messages between tasks yet: the message
   filter and the local transport are not published as packages, and Sokar sends nothing
   without a filter installed.
DONE
