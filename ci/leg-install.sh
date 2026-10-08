#!/bin/sh
# Installs what ci/leg-build.sh built into the account that runs it, where a package would put it: the binaries
# ci/leg-binaries names (an agent among the agents, the rest on the PATH), the providers, the egress lists, and the
# stub build reader under the forge name the suite's projects give it.
#
# Run from the tree's root after ci/leg-build.sh. LegTreePathsTest in acceptance/legs checks every path here and in
# ci/leg-binaries against the reactor's modules and the image names their poms give, and runs this script against a
# tree laid out as a build leaves it.
set -eu
bin="$HOME/.local/bin"
share="$HOME/.local/share/sokar"
mkdir -p "$bin" "$share/agents" "$share/providers" "$share/egress"
agents=0
while read -r binary; do
    [ -n "$binary" ] || continue
    case "$binary" in
        */sokar-agent-*) cp "$binary" "$share/agents/"; agents=$((agents + 1)) ;;
        *) cp "$binary" "$bin/" ;;
    esac
done < ci/leg-binaries
[ "$agents" -gt 0 ] || { echo "ci/leg-binaries names no agent, so no scenario that starts a task can run" >&2; exit 1; }
cp providers/*.yaml "$share/providers/"
cp egress/*.yaml "$share/egress/"
if [ -x builds/stub/target/sokar-build-stub ]; then
    install -D -m 0755 builds/stub/target/sokar-build-stub "$share/builds/stub-forge"
fi
