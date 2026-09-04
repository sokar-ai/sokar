#!/usr/bin/env bash
#
# Builds what the sokar-agent-pi package ships: the Pi coding agent with its dependencies, and
# the Node runtime it needs. Both end up inside the package, so a task image is built with no
# network access at all - see the 'packaged' section of pi.yaml.
#
# Everything runs in a pinned container rather than on the build machine, so no Node toolchain
# has to be installed to build Sokar, and the toolchain itself is a stated version rather than
# whatever the machine happens to have.
#
# Skipped, with a message, when there is no container runtime: the rest of Sokar still builds.
#
# Every container maps the build user in, so what it writes belongs to the build user. Without
# that, the tree is written as root and the next build cannot even delete it.
set -euo pipefail

MODULE="$(cd "$(dirname "$0")/../agents/pi" && pwd)"
TARGET="$MODULE/target/tree"
NODE_VERSION="${NODE_VERSION:-22.20.0}"
NODE_SHA256="${NODE_SHA256:-eeaccb0378b79406f2208e8b37a62479c70595e20be6b659125eb77dd1ab2a29}"
BUILDER="${PI_BUILDER_IMAGE:-docker.io/library/node:${NODE_VERSION}-slim}"

if ! command -v podman >/dev/null 2>&1; then
    echo "build-pi-tree: no podman, so the Pi tree is not built; sokar-agent-pi will be incomplete"
    exit 0
fi

rm -rf "$TARGET"
mkdir -p "$TARGET/pi" "$TARGET/node"
cp "$MODULE/src/main/npm/package.json" "$MODULE/src/main/npm/package-lock.json" "$TARGET/pi/"

# npm ci, not npm install: it installs exactly the lockfile, refuses to update it, and checks
# every package against the integrity hash recorded there. That is what makes this reproducible
# rather than "whatever the registry served today".
podman run --rm --userns=keep-id -v "$TARGET:/out:z" -w /out/pi "$BUILDER" sh -c '
    set -eu
    npm ci --omit=dev --no-audit --no-fund
    rm -f package.json package-lock.json
'

# The published runtime, checked against the digest nodejs.org publishes beside it. Fetched with
# node's own fetch rather than curl: the container runs as the build user, so it cannot install
# packages, and the builder image already has the one tool needed.
podman run --rm --userns=keep-id -v "$TARGET:/out:z" "$BUILDER" sh -c "
    set -eu
    node -e \"
        const { writeFileSync } = require('fs');
        fetch('https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.gz')
            .then(r => { if (!r.ok) { throw new Error('HTTP ' + r.status); } return r.arrayBuffer(); })
            .then(b => writeFileSync('/tmp/node.tar.gz', Buffer.from(b)));
    \"
    echo '${NODE_SHA256}  /tmp/node.tar.gz' | sha256sum -c -
    tar -xzf /tmp/node.tar.gz -C /out/node --strip-components=1
    # Only the runtime is used: Pi is started as 'node <cli.js>'. npm, npx and the headers are
    # build-time tools that would otherwise ride along in every installation.
    rm -rf /out/node/include /out/node/share /out/node/lib/node_modules
    rm -f /out/node/bin/npm /out/node/bin/npx /out/node/bin/corepack
"

# Prebuilt binaries for platforms a Linux container will never run. Dead weight in a package
# that is already large, and confusing to anyone auditing what is shipped.
podman run --rm --userns=keep-id -v "$TARGET:/out:z" "$BUILDER" sh -c '
    set -eu
    find /out/pi -type d \( -name darwin -o -name win32 -o -name "darwin-*" -o -name "win32-*" \) \
        -prune -exec rm -rf {} + 2>/dev/null || true
    find /out/pi -type d -name examples -prune -exec rm -rf {} + 2>/dev/null || true
'

# One tarball rather than two directories: one file for the packagers to carry, one file for an
# operator to check, and one unpack instead of copying tens of thousands of files per task.
tar -czf "$MODULE/target/pi-tree.tar.gz" -C "$TARGET" pi node

echo "build-pi-tree: pi $(du -sh "$TARGET/pi" | cut -f1), node $(du -sh "$TARGET/node" | cut -f1),"\
     "packaged $(du -h "$MODULE/target/pi-tree.tar.gz" | cut -f1)"
