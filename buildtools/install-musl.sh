#!/usr/bin/env bash
# Installs the musl cross-toolchain plus a musl-built zlib, as required by
# `native-image --static --libc=musl` (see .sokar/s5-ffm-native-image.md).
set -euo pipefail

PREFIX="${MUSL_PREFIX:-$HOME/.local/opt}"
TC="$PREFIX/x86_64-linux-musl-native"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

ZLIB_VERSION=1.3.1

mkdir -p "$PREFIX"

if [ ! -x "$TC/bin/x86_64-linux-musl-gcc" ]; then
    echo "==> downloading musl toolchain"
    curl -fSL --retry 3 -o "$WORK/musl.tgz" https://musl.cc/x86_64-linux-musl-native.tgz
    echo "==> extracting to $TC"
    tar -xzf "$WORK/musl.tgz" -C "$PREFIX"
else
    echo "==> musl toolchain already present at $TC"
fi

export PATH="$TC/bin:$PATH"
x86_64-linux-musl-gcc --version | head -1

if [ ! -f "$TC/lib/libz.a" ]; then
    echo "==> building zlib $ZLIB_VERSION against musl"
    curl -fSL --retry 3 -o "$WORK/zlib.tar.gz" "https://zlib.net/fossils/zlib-$ZLIB_VERSION.tar.gz"
    tar -xzf "$WORK/zlib.tar.gz" -C "$WORK"
    cd "$WORK/zlib-$ZLIB_VERSION"
    CC=x86_64-linux-musl-gcc ./configure --static --prefix="$TC"
    make -j"$(nproc)" >/dev/null
    make install >/dev/null
else
    echo "==> musl zlib already present"
fi

echo "==> installed:"
ls -la "$TC/bin/x86_64-linux-musl-gcc" "$TC/lib/libz.a"
echo "==> add to PATH: $TC/bin"
