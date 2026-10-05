#!/usr/bin/env bash
#
# Compiles and loads Sokar's SELinux policy module. Needed only where SELinux is enforcing;
# everywhere else Sokar works without it.
#
# The module grants a task container one permission - connectto on Sokar's own socket type -
# and grants containers nothing else. Read sokar_socket.te next to this script; it is short.
set -euo pipefail

DIRECTORY="$(cd "$(dirname "$0")" && pwd)"
SOURCE="$DIRECTORY/sokar_socket.te"

[ -f "$SOURCE" ] || { echo "missing $SOURCE" >&2; exit 2; }
[ "$(id -u)" = 0 ] || { echo "run this with sudo: sudo $0" >&2; exit 2; }

for tool in checkmodule semodule_package semodule; do
    command -v "$tool" >/dev/null || {
        echo "$tool is not installed - on Fedora and RHEL: dnf install checkpolicy policycoreutils" >&2
        exit 2
    }
done

# Compiled here rather than shipped compiled: a .pp is tied to the policy version of the
# machine that built it, and Sokar is built on a distribution that has no SELinux at all.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

checkmodule -M -m -o "$WORK/sokar_socket.mod" "$SOURCE"
semodule_package -o "$WORK/sokar_socket.pp" -m "$WORK/sokar_socket.mod"
semodule -i "$WORK/sokar_socket.pp"

echo "installed the sokar_socket policy module"
echo "remove it again with: sudo semodule -r sokar_socket"
