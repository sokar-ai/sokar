#!/usr/bin/env python3
"""
Runs the acceptance suite on a Fedora server with SELinux enforcing.

This is the leg a GitHub-hosted runner cannot cover: hosted runners are Ubuntu with AppArmor, and
SELinux cannot be turned on from inside a container. Four real defects have been found under
enforcing that did not reproduce on Ubuntu, so a green build without this leg says less than it
appears to.

Boots the prepared snapshot, copies the working tree over, builds, runs the suite, and destroys
the server. The snapshot is what makes it cheap: about a minute to a machine that can already
build, instead of ten minutes of installing.

    export REMOTE_BUILD="$(cat ~/.claude/.ssh/hetzner-api-token.txt)"
    python3 buildtools/ci/remote-tier1.py

The server is destroyed whatever happens. At 0.111 EUR/hour a forgotten one costs 81 EUR a
month, against about 3 cents for a run.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import hetzner  # noqa: E402

# The suite runs as an ordinary user, not root: Sokar drives *rootless* podman, and running it as
# root would test something nobody uses.
BUILD_USER = "build"

REPO = "/home/build/sokar"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--type", default="cpx42", dest="server_type")
    parser.add_argument("--location", default="fsn1")
    parser.add_argument("--ssh-key", default="sokar-ci")
    parser.add_argument("--ssh-private-key",
                        default=str(Path.home() / ".claude" / ".ssh" / "sokar-ci-hetzner"))
    parser.add_argument("--snapshot", type=int, default=None,
                        help="image id; default is the newest snapshot labelled sokar=ci")
    parser.add_argument("--keep", action="store_true",
                        help="leave the server running afterwards, for debugging")
    args = parser.parse_args()

    key_file = Path(args.ssh_private_key)
    if not key_file.is_file():
        sys.exit(f"No private key at {key_file}")

    client = hetzner.client()
    image = (client.images.get_by_id(args.snapshot) if args.snapshot
             else hetzner.newest_snapshot(client))

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    with hetzner.provisioned(client, name=f"sokar-ci-tier1-{stamp}",
                             server_type=args.server_type, image_name=image.name or str(image.id),
                             location=args.location, ssh_key_name=args.ssh_key,
                             keep=args.keep, image_override=image) as (server, address):

        hetzner.await_ssh(address)

        print("\n-- sending the working tree --")
        upload(address, key_file)

        print("\n-- building --")
        remote(address, key_file, f"cd {REPO} && ./mvnw -B -Pnative -DskipTests package "
                                  "-pl app,hooks,agents/claude -am -DquickBuild=true")

        print("\n-- installing as a package would --")
        remote(address, key_file,
               "mkdir -p ~/.local/bin ~/.local/share/sokar/agents && "
               f"cp {REPO}/hooks/target/sokar-hook-* ~/.local/bin/ && "
               f"cp {REPO}/app/target/sokar ~/.local/bin/ && "
               f"sudo mkdir -p /usr/share/sokar/providers && "
               f"sudo cp {REPO}/providers/*.yaml /usr/share/sokar/providers/ && "
               "~/.local/bin/sokar setup")

        print("\n-- what sokar thinks of this machine --")
        remote(address, key_file, "PATH=$HOME/.local/bin:$PATH sokar doctor", check=False)

        print("\n-- tier 1, under SELinux enforcing --")
        remote(address, key_file,
               f"cd {REPO} && PATH=$HOME/.local/bin:$PATH bash buildtools/e2e-tier1.sh")

    return 0


def upload(address: str, key_file: Path) -> None:
    """
    Copies the working tree to the server.

    `git archive` rather than a clone: it sends exactly what is checked out, including changes
    that are not committed, which is what a developer running this locally means by "test this".
    In CI the checkout is already at the commit under test, so the two agree.
    """
    archive = subprocess.run(["git", "archive", "--format=tar", "HEAD"],
                             capture_output=True, check=True).stdout
    print(f"  {len(archive) // 1024} KiB")
    result = subprocess.run(
        ssh_command(address, key_file, BUILD_USER,
                    f"rm -rf {REPO} && mkdir -p {REPO} && tar -x -C {REPO}"),
        input=archive, capture_output=True, timeout=600,
    )
    if result.returncode != 0:
        sys.exit(f"upload failed: {result.stderr.decode(errors='replace')}")


def remote(address: str, key_file: Path, command: str, *, check: bool = True) -> None:
    """Runs one command on the server, streaming its output into this log."""
    result = subprocess.run(ssh_command(address, key_file, BUILD_USER, command), timeout=3600)
    if check and result.returncode != 0:
        sys.exit(f"\nfailed on the remote with exit code {result.returncode}: {command}")


def ssh_command(address: str, key_file: Path, user: str, command: str) -> list[str]:
    """
    Builds the ssh invocation.

    Host key checking is off and known-hosts is /dev/null: the machine is new every time, at an
    address the API has just told us, so there is no key that could have been known.
    """
    return ["ssh", "-i", str(key_file), "-o", "StrictHostKeyChecking=no",
            "-o", "UserKnownHostsFile=/dev/null", "-o", "LogLevel=ERROR",
            "-o", "ConnectTimeout=15", f"{user}@{address}", command]


if __name__ == "__main__":
    sys.exit(main())
