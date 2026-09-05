#!/usr/bin/env bash
#
# Turns a stock Fedora cloud image into a machine that can build and test Sokar.
#
# Run over SSH as root by build-snapshot.py, then snapshotted. Everything here is paid for once,
# at snapshot time, instead of on every run: a cold image needs about ten minutes of installing
# before it can start a four-minute build.
#
# The specific items are not arbitrary - each one is something whose absence has already cost
# time on some machine:
#
#   subuid/subgid       rootless podman does not start at all without them
#   enable-linger       /run/user/<uid> does not exist for a user with no login session, and
#                       that is where Sokar keeps task state
#   dnsmasq nftset      the egress filter silently allows nothing without it
#   musl toolchain      the three hooks are statically linked, and musl.cc is unreachable from
#                       some networks, so it comes from the release mirror
set -euo pipefail

BUILD_USER="${BUILD_USER:-build}"
GRAALVM_VERSION="${GRAALVM_VERSION:-25.0.2}"
# The GraalVM Community build, and the digest its own release publishes beside it as .sha256.
# Verified rather than trusted, like every other download this project makes.
GRAALVM_URL="${GRAALVM_URL:-https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${GRAALVM_VERSION}/graalvm-community-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz}"
GRAALVM_SHA256="${GRAALVM_SHA256:-e0be791c8fda4d03b6b0a0cb824fef3149736170057b3a515252b44419606af0}"

echo "==> packages"
dnf -q -y install \
    podman nftables dnsmasq git util-linux shadow-utils \
    policycoreutils policycoreutils-python-utils selinux-policy-devel \
    tar gzip which findutils python3 \
    >/dev/null

echo "==> build user '$BUILD_USER'"
id "$BUILD_USER" >/dev/null 2>&1 || useradd --create-home --shell /bin/bash "$BUILD_USER"

# Rootless podman maps container uids onto a range the host has reserved for this user. Without
# an entry, 'podman run' fails with a message about newuidmap that reads like a permissions bug.
grep -q "^$BUILD_USER:" /etc/subuid || usermod --add-subuids 200000-265535 "$BUILD_USER"
grep -q "^$BUILD_USER:" /etc/subgid || usermod --add-subgids 200000-265535 "$BUILD_USER"

# A process started by a service is not a login session, so /run/user/<uid> is not created and is
# removed when the last session ends. Sokar keeps a task's state there.
loginctl enable-linger "$BUILD_USER"

echo "==> ssh access for '$BUILD_USER'"
install -d -m 0700 -o "$BUILD_USER" -g "$BUILD_USER" "/home/$BUILD_USER/.ssh"
install -m 0600 -o "$BUILD_USER" -g "$BUILD_USER" \
    /root/.ssh/authorized_keys "/home/$BUILD_USER/.ssh/authorized_keys"

echo "==> graalvm $GRAALVM_VERSION"
GRAAL_DIR="/opt/graalvm"
if [ ! -x "$GRAAL_DIR/bin/native-image" ]; then
    curl -fSL --retry 3 -o /tmp/graalvm.tgz "$GRAALVM_URL"
    echo "$GRAALVM_SHA256  /tmp/graalvm.tgz" | sha256sum -c -
    mkdir -p "$GRAAL_DIR"
    tar -xzf /tmp/graalvm.tgz -C "$GRAAL_DIR" --strip-components=1
    rm -f /tmp/graalvm.tgz
fi
"$GRAAL_DIR/bin/native-image" --version | head -1

cat > /etc/profile.d/graalvm.sh <<PROFILE
export JAVA_HOME=$GRAAL_DIR
export GRAALVM_HOME=$GRAAL_DIR
export PATH=\$JAVA_HOME/bin:\$PATH
PROFILE

echo "==> SELinux enforcing"
# Hetzner's fedora-44 image ships SELINUX=permissive - measured, not assumed. The policy is
# installed and /sys/fs/selinux is there, so only the mode is wrong. That matters more here than
# anywhere else: enforcing is the single thing this machine covers that a hosted runner cannot,
# and a permissive snapshot would pass every test while proving nothing.
#
# A relabel as well as a flag: everything installed above was written while permissive, so some
# of it carries no label or the wrong one. The reboot that applies this is done by the caller.
sed -i 's/^SELINUX=.*/SELINUX=enforcing/' /etc/selinux/config
grep -q '^SELINUX=enforcing' /etc/selinux/config
touch /.autorelabel
echo "    set to enforcing; a relabel and reboot are needed before it takes effect"

echo "==> base images the suite pulls"
sudo -u "$BUILD_USER" -i podman pull -q docker.io/library/ubuntu:24.04 >/dev/null
sudo -u "$BUILD_USER" -i podman pull -q docker.io/library/alpine:3.20 >/dev/null

echo "==> done"
