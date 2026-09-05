#!/usr/bin/env python3
"""
Builds the Fedora snapshot the SELinux test leg boots from.

Provisions a server, customises it, refuses to continue if the result cannot run the suite,
powers it off, snapshots it, and destroys it. The snapshot is what makes the leg affordable: a
run then boots a machine that is ready to build, rather than spending ten minutes installing
before it can start.

    export REMOTE_BUILD="$(cat ~/.claude/.ssh/hetzner-api-token.txt)"
    python3 buildtools/ci/build-snapshot.py

The server is destroyed whatever happens, including when the verification refuses. At
0.111 EUR/hour a forgotten one costs 81 EUR a month, against about 3 cents for a run.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import hetzner  # noqa: E402

HERE = Path(__file__).parent


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--image", default="fedora-44", help="default: %(default)s")
    parser.add_argument("--type", default="cpx42", dest="server_type",
                        help="default: %(default)s")
    parser.add_argument("--location", default="fsn1",
                        help="a location in eu-central: fsn1, nbg1 or hel1 (default: %(default)s)")
    parser.add_argument("--ssh-key", default="sokar-ci",
                        help="name of the key in the Hetzner project (default: %(default)s)")
    parser.add_argument("--ssh-private-key",
                        default=str(Path.home() / ".claude" / ".ssh" / "sokar-ci-hetzner"),
                        help="its private half, to connect with (default: %(default)s)")
    parser.add_argument("--keep", action="store_true",
                        help="leave the server running afterwards, for debugging")
    parser.add_argument("--no-snapshot", action="store_true",
                        help="customise and verify, but do not snapshot - for trying changes")
    args = parser.parse_args()

    key_file = Path(args.ssh_private_key)
    if not key_file.is_file():
        sys.exit(f"No private key at {key_file}")

    client = hetzner.client()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    name = f"sokar-ci-snapshot-{stamp}"

    with hetzner.provisioned(client, name=name, server_type=args.server_type,
                             image_name=args.image, location=args.location,
                             ssh_key_name=args.ssh_key, keep=args.keep) as (server, address):

        hetzner.await_ssh(address)

        print("\n-- customising --")
        run(address, key_file, "customise-fedora.sh")
        run(address, key_file, "verify-fedora.sh")

        if args.no_snapshot:
            print("\n--no-snapshot given, stopping here")
            return 0

        print("\n-- snapshotting --")
        # Powered off first: a snapshot of a running server catches its disk mid-write.
        client.servers.power_off(server).wait_until_finished()
        print("powered off")

        description = f"sokar-ci {args.image} {stamp}"
        response = client.servers.create_image(
            server, description=description, type="snapshot",
            labels={**hetzner.LABEL, "image": args.image},
        )
        response.action.wait_until_finished()
        snapshot = response.image
        print(f"\nsnapshot {snapshot.id}  {description}")
        print(f"  boot the next run from it with:  --snapshot {snapshot.id}")

    return 0


def run(address: str, key_file: Path, name: str) -> None:
    """
    Runs one of the shell scripts beside this file on the server.

    Fed on standard input rather than copied over: it is one call, and it leaves nothing behind
    on a machine that is about to be turned into an image.

    A non-zero exit stops the whole thing - which for verify-fedora.sh is the point. The server
    is still destroyed, because that is handled by the context manager rather than by getting
    here.
    """
    print(f"\n$ {name}")
    result = subprocess.run(
        ["ssh", "-i", str(key_file), "-o", "StrictHostKeyChecking=no",
         "-o", "UserKnownHostsFile=/dev/null", "-o", "LogLevel=ERROR",
         "-o", "ConnectTimeout=15", f"root@{address}", "bash -s"],
        input=(HERE / name).read_text(), capture_output=True, text=True, timeout=2400,
    )
    print((result.stdout + result.stderr).strip())
    if result.returncode != 0:
        sys.exit(f"\n{name} failed with exit code {result.returncode} - not snapshotting")


if __name__ == "__main__":
    sys.exit(main())
