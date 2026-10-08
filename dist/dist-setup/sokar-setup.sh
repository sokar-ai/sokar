#!/bin/sh
#
# Prepares a machine to run Sokar. Run as root, once, on a machine that has nothing.
#
# This is the one piece of Sokar that is a file rather than a call: everything else goes through
# the daemon, and a machine being prepared has no daemon yet, no packages, and no user for the
# daemon to run as.
#
# Usage:  sokar-setup.sh [options]
#
#   --user <name>       The account tasks run as. Default: agents
#   --distribution <d>  Which published distribution to install from. Default: releases
#   --with <package>    Also install this package. May be given several times.
#   --between-users off
#                       Removes what an earlier version set up for messaging between the Unix users
#                       of this machine. 'on' is retired with the spool transport that used it.
#   --list              Print what this machine could install, install nothing, and stop
#   --json              With --list: one JSON object on stdout, for a program rather than a person
#   --show              Print every command and run none of them
#   --os-release <file> Read the system description from here instead of /etc/os-release
#   --help              This text
#
# What is always installed: Sokar and the message filter. The filter because without it nothing
# leaves, which is the point of it. Agents and transports are chosen - '--list' says what there is,
# '--with' installs them. A project messages only through a transport it configures; without one its
# tasks work alone.
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
DISTRIBUTION=releases
SHOW=no
LIST=no
JSON=no
BETWEEN=
WITH=
OS_RELEASE=/etc/os-release
BASE=https://fuinorg.jfrog.io/artifactory

while [ $# -gt 0 ]; do
    case "$1" in
        --user) USER_NAME="${2:?--user needs a name}"; shift 2 ;;
        --distribution) DISTRIBUTION="${2:?--distribution needs a name}"; shift 2 ;;
        --with) WITH="$WITH ${2:?--with needs a package name}"; shift 2 ;;
        --between-users) BETWEEN="${2:?--between-users needs 'on' or 'off'}"; shift 2 ;;
        --list) LIST=yes; shift ;;
        --json) JSON=yes; shift ;;
        --show) SHOW=yes; shift ;;
        --os-release) OS_RELEASE="${2:?--os-release needs a file}"; shift 2 ;;
        --help) sed -n '2,41p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
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
    echo "Prepare it by hand from doc/getting-started.md or -fedora.md, or ask for it to be added." >&2
    exit 3
fi

if [ "$SHOW" != yes ] && [ "$(id -u)" != 0 ]; then
    echo "sokar-setup: this has to run as root - it installs packages and makes a user." >&2
    exit 4
fi

say "preparing ${PRETTY_NAME:-$ID} for Sokar, tasks running as '$USER_NAME'"

# ---------------------------------------------------------------- podman 5, before anything else

# Asked of this system's own package source before anything is installed: Sokar refuses podman 4,
# and a release that offers only podman 4 - Ubuntu 24.04 offers 4.9.3 - would otherwise be
# "prepared" into a machine 'sokar doctor' fails. Measured on a fresh 24.04 by the interface's
# wizard. Taking podman from a newer release is not an option either: on 24.04 it upgrades libc and
# some 150 packages with it. So the release is refused, by what it offers rather than by its name.
say "the podman this system offers"
if [ "$SHOW" = yes ]; then
    if [ "$FAMILY" = apt ]; then
        printf '   $ %s\n' "apt-get update -qq && apt-cache policy podman"
    else
        printf '   $ %s\n' "dnf -q repoquery --latest-limit=1 --queryformat '%{version}' podman"
    fi
    note "anything older than podman 5 stops here, before a package is installed"
else
    if [ "$FAMILY" = apt ]; then
        apt-get update -qq
        OFFERED="$(LC_ALL=C apt-cache policy podman | sed -n 's/^ *Candidate: *//p')"
    else
        OFFERED="$(dnf -q repoquery --latest-limit=1 --queryformat '%{version}' podman 2>/dev/null | tail -1)"
    fi
    # An epoch ('1:5.4.2') and a Debian revision ('5.4.2+ds1-2') both come after or before the major.
    OFFERED_MAJOR="$(printf '%s' "${OFFERED#*:}" | sed -n 's/^\([0-9][0-9]*\).*/\1/p')"
    if [ -z "$OFFERED_MAJOR" ] || [ "$OFFERED_MAJOR" -lt 5 ]; then
        echo "sokar-setup: ${PRETTY_NAME:-$ID} offers podman ${OFFERED:-nothing}, and Sokar needs podman 5 or newer." >&2
        echo "Nothing was installed. Ubuntu 26.04, Debian 13 and current Fedora offer it." >&2
        exit 3
    fi
    note "podman $OFFERED"
fi

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

# ---------------------------------------------------------------- where Sokar comes from

say "the repository Sokar is published to"
if [ "$FAMILY" = apt ]; then
    if [ -f /usr/share/keyrings/sokar.gpg ]; then
        note "/usr/share/keyrings/sokar.gpg is already there"
    elif [ "$SHOW" = yes ]; then
        printf '   $ %s\n' "curl -fsSL $BASE/api/security/keypair/sokar-packages/public | gpg --dearmor -o /usr/share/keyrings/sokar.gpg"
    else
        # Dearmored, not the .asc: apt wants the binary form at that path, and the armored file
        # fails with a verification error that never mentions the format.
        curl -fsSL "$BASE/api/security/keypair/sokar-packages/public" \
            | gpg --dearmor -o /usr/share/keyrings/sokar.gpg
        note "wrote /usr/share/keyrings/sokar.gpg"
    fi
    SOURCE_LINE="deb [signed-by=/usr/share/keyrings/sokar.gpg] $BASE/sokar-dist-deb $DISTRIBUTION main"
    if [ -f /etc/apt/sources.list.d/sokar.list ] \
            && [ "$(cat /etc/apt/sources.list.d/sokar.list)" = "$SOURCE_LINE" ]; then
        note "/etc/apt/sources.list.d/sokar.list already says this"
    elif [ "$SHOW" = yes ]; then
        printf '   $ %s\n' "echo '$SOURCE_LINE' > /etc/apt/sources.list.d/sokar.list"
    else
        echo "$SOURCE_LINE" > /etc/apt/sources.list.d/sokar.list
        note "wrote /etc/apt/sources.list.d/sokar.list"
    fi
    run apt-get update -qq
    INSTALL="apt-get install -y -qq"
    HAVE="apt-cache show"
    OFFERS="apt-cache policy sokar | grep -q sokar-dist-deb"
else
    REPO=/etc/yum.repos.d/sokar.repo
    REPO_BODY="[sokar]
name=Sokar
baseurl=$BASE/sokar-dist-rpm/$DISTRIBUTION
enabled=1
gpgcheck=0"
    if [ -f "$REPO" ] && [ "$(cat "$REPO")" = "$REPO_BODY" ]; then
        note "$REPO already says this"
    elif [ "$SHOW" = yes ]; then
        printf '   $ %s\n' "write $REPO for $BASE/sokar-dist-rpm/$DISTRIBUTION"
    else
        printf '%s\n' "$REPO_BODY" > "$REPO"
        note "wrote $REPO"
    fi
    run dnf makecache -q
    INSTALL="dnf install -y -q"
    HAVE="dnf list --available"
    OFFERS="dnf repoquery --repo=sokar --qf %{name} sokar | grep -q sokar"
fi

# The repository has to actually serve Sokar, and this is where that is found out. On a machine
# that already has Sokar installed, the install below says "nothing to do" whether the repository
# works or not - so a misconfigured source would pass silently and be met later as a machine that
# cannot install an agent. Measured on Fedora, where exactly that happened.
if [ "$SHOW" = yes ]; then
    printf '   $ %s\n' "$OFFERS"
elif sh -c "$OFFERS" >/dev/null 2>&1; then
    note "the repository offers sokar"
else
    echo "sokar-setup: $BASE does not offer a 'sokar' package for this system." >&2
    echo "Nothing further would be installable from it. Check the address and the distribution" >&2
    echo "('$DISTRIBUTION'), then run this again." >&2
    exit 5
fi

# ---------------------------------------------------------------- what else this machine could have

# Every published agent package declares 'Provides: sokar-agent' - the test stub deliberately not, so it is
# never offered - every transport 'sokar-transport' and every
# homeserver a transport's conversation runs on 'sokar-homeserver', so
# the repository is the catalogue and nobody keeps a list. The same query the daemon's
# 'Installable' method runs - kept word for word, so the two cannot answer differently.
# One row per installable package, as 'name|kind|state|version|description'. One producer, so the
# human table and the JSON cannot disagree about what this machine offers - and so widening a
# column never changes what a program parses.
#
# 'kind' is 'agent', 'transport' or 'homeserver': what a person chooses between. The virtual package name it was
# read from is the mechanism, and the daemon's Installable method answers the same way.
catalogue() {
    if [ "$FAMILY" = apt ]; then
        for virtual in sokar-agent sokar-transport sokar-homeserver; do
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
        for virtual in sokar-agent sokar-transport sokar-homeserver; do
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

# Always: the filter, because without it nothing leaves a task. Which transport carries messages is
# the machine's choice ('--with'), and a project messages only through one it configures.
say "the message filter"
MESSAGING=no
if $HAVE sokar-message-sluice-filter >/dev/null 2>&1; then
    # shellcheck disable=SC2086
    run $INSTALL sokar-message-sluice-filter
    MESSAGING=yes
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

# ---------------------------------------------------------------- messaging between users

# Where a message crosses from one Unix user's installation to another's. Nothing here exists
# unless somebody asked for it: the default is that another user's mailbox is refused, and a
# machine where this was never run refuses every such send permanently.
SPOOL=/var/spool/sokar
GROUP=agents

drop_for() { # user
    install -d -m 1730 -o "$1" -g "$GROUP" "$SPOOL/drop/$1/tmp" "$SPOOL/drop/$1/new"
    # A directory per user rather than a file per user in one shared directory. Measured on the VM
    # on 2026-09-18: with one shared directory, a group member can create 'keys/<other>.pub' before
    # that user ever publishes - the owner check then rightly disbelieves it, and the sticky bit
    # stops the rightful user removing it, so their messaging is silenced until root steps in.
    # Nobody but root creates anything in 'keys/', so there is no name left to take.
    install -d -m 0755 -o "$1" -g "$GROUP" "$SPOOL/keys/$1"
    # The mode lets the group drop a file in without listing or reading the directory; the sticky
    # bit stops one sender removing another's. The default ACL then hands each dropped file to the
    # recipient alone - without it a file arrives readable by the whole group.
    setfacl -d -m "user:$1:rw-" -m "group::---" -m "other::---" "$SPOOL/drop/$1/tmp" \
        "$SPOOL/drop/$1/new"
}

if [ "$BETWEEN" = on ]; then
    # Retired with the spool transport, which was the only thing that carried a message between two
    # accounts here. Two accounts message through a transport that keeps a conversation.
    echo "sokar-setup: --between-users on is retired with the spool transport; accounts message each" >&2
    echo "other through a transport that keeps a conversation, installed with --with" >&2
    exit 2
fi
if [ "$BETWEEN" = retired-on ]; then
    say "messaging between the users of this machine"
    if ! command -v setfacl >/dev/null 2>&1; then
        # Without a default ACL a dropped file is readable by every member of the group, which is
        # the one thing this scheme must not allow. Refused rather than built wrong.
        echo "sokar-setup: 'setfacl' is not installed, and without it a dropped message would be" >&2
        echo "readable by every member of '$GROUP'. Install acl and run this again." >&2
        exit 5
    fi
    getent group "$GROUP" >/dev/null 2>&1 || run groupadd "$GROUP"
    run install -d -m 0755 -o root -g root "$SPOOL"
    # Root-only: each user gets a directory of their own inside it, made with their drop.
    run install -d -m 0755 -o root -g "$GROUP" "$SPOOL/keys"
    run install -d -m 0755 -o root -g root "$SPOOL/drop"
    note "allowed. Each user publishes their key with 'sokar talk key --publish'."
elif [ "$BETWEEN" = off ]; then
    say "messaging between the users of this machine"
    if [ -d "$SPOOL" ]; then
        run rm -rf "$SPOOL"
        note "taken back - every send between users is refused again"
    else
        note "was not allowed here"
    fi
elif [ -n "$BETWEEN" ]; then
    echo "sokar-setup: --between-users takes 'on' or 'off', not '$BETWEEN'" >&2
    exit 2
fi

if [ -d "$SPOOL/drop" ]; then
    # Only where it was allowed: adding a user never turns this on, which is the difference
    # between a deliberate step and a side effect.
    say "this user's drop"
    if id -nG "$USER_NAME" 2>/dev/null | tr ' ' '\n' | grep -qx "$GROUP"; then
        note "$USER_NAME is already in '$GROUP'"
    else
        run usermod -a -G "$GROUP" "$USER_NAME"
    fi
    if [ -d "$SPOOL/drop/$USER_NAME/new" ]; then
        note "$SPOOL/drop/$USER_NAME is already there"
    elif [ "$SHOW" = yes ]; then
        printf '   $ %s\n' "install -d -m 1730 -o $USER_NAME -g $GROUP $SPOOL/drop/$USER_NAME/{tmp,new} with a default ACL, and -m 0755 $SPOOL/keys/$USER_NAME"
    else
        drop_for "$USER_NAME"
        note "wrote $SPOOL/drop/$USER_NAME"
    fi
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

   What is left is one command, in $USER_NAME's own session and at a terminal (over ssh:
   'ssh -t'), because it belongs to that account and none of it can be done from root:

       sokar setup

   It registers the OCI hooks for that account, starts its daemon, and creates its vault -
   asking for the passphrase, which only a person may type. Each step only when it is not
   done, so running it again is safe. A root script starting another user's service would
   be either wrong or a lie about the session it runs in, and podman reads its hook
   descriptors per user, so root does not know whose configuration to write.

   'sokar doctor' is the real check. This script's exit code says the steps ran; only the
   daemon can say the machine works.

DONE
if [ "$MESSAGING" = yes ]; then
    cat <<MESSAGING_ON
   The message filter is installed. A project's tasks message each other, and anybody else, only
   through a transport the project configures ('--list' says which there are, '--with' installs
   one); without one, its tasks work alone.
MESSAGING_ON
else
    cat <<MESSAGING_OFF
   This machine can run tasks. It cannot exchange messages between tasks: the message
   filter is not installed, and Sokar sends nothing without one.
MESSAGING_OFF
fi
