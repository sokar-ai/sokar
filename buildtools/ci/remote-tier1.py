#!/usr/bin/env python3
"""
Runs the acceptance suite on a Hetzner server booted from a prepared snapshot.

This is the leg a GitHub-hosted runner cannot cover: hosted runners are Ubuntu with AppArmor, and
SELinux cannot be turned on from inside a container. Four real defects have been found under
enforcing that did not reproduce on Ubuntu, so a green build without this leg says less than it
appears to.

Boots the prepared snapshot, copies the working tree over, builds, runs the suite, and destroys
the server. The snapshot is what makes it cheap: about a minute to a machine that can already
build, instead of ten minutes of installing.

    export REMOTE_BUILD="$(cat ~/.claude/.ssh/hetzner-api-token.txt)"
    python3 buildtools/ci/remote-tier1.py

Two things come from the environment: REMOTE_BUILD, the Hetzner API token, and SSH, the private
key that reaches the servers it creates. Locally the key is a file instead - see
--ssh-private-key.

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
    parser.add_argument("--ssh-key", default=None,
                        help="name of the key in the Hetzner project; by default the one whose "
                             "fingerprint matches the private key being used")
    parser.add_argument("--ssh-private-key",
                        default=str(Path.home() / ".claude" / ".ssh" / "sokar-ci-hetzner"),
                        help="used when the SSH environment variable is not set")
    parser.add_argument("--os", default="fedora", dest="operating_system",
                        help="which snapshot to boot, by its os label (default: %(default)s)")
    parser.add_argument("--snapshot", type=int, default=None,
                        help="image id; default is the newest snapshot for --os")
    parser.add_argument("--keep", action="store_true",
                        help="leave the server running afterwards, for debugging")
    args = parser.parse_args()

    # The key goes into an agent, never onto a filesystem.
    environment = hetzner.agent(args.ssh_private_key)

    client = hetzner.client()
    image = (client.images.get_by_id(args.snapshot) if args.snapshot
             else hetzner.newest_snapshot(client, args.operating_system))

    # The run id as well as the time: two runs starting in the same second would otherwise ask
    # for the same name, and Hetzner rejects the second.
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    with hetzner.provisioned(client, name=f"sokar-ci-{args.operating_system}-{stamp}-{hetzner.run_id()[-8:]}",
                             server_type=args.server_type, image_name=image.name or str(image.id),
                             location=args.location, ssh_key_name=args.ssh_key,
                             environment=environment,
                             keep=args.keep, image_override=image) as (server, address):

        hetzner.await_ssh(address)

        # Hetzner injects the key a server is created with into root, and only root. The build
        # user's authorized_keys comes from the snapshot, so it holds whoever built the image -
        # which is not whoever is running now. Copying it across makes the snapshot neutral about
        # which key reaches it, which it should have been in the first place.
        print("\n-- giving this run's key access to the build user --")
        hetzner.ssh(address, environment,
                    f"install -d -m 0700 -o {BUILD_USER} -g {BUILD_USER} /home/{BUILD_USER}/.ssh"
                    f" && install -m 0600 -o {BUILD_USER} -g {BUILD_USER}"
                    f" /root/.ssh/authorized_keys /home/{BUILD_USER}/.ssh/authorized_keys")

        print("\n-- sending the working tree --")
        upload(address, environment)

        print("\n-- building --")
        remote(address, environment, f"cd {REPO} && ./mvnw -B -Pnative -DskipTests package "
                                  "-pl app,hooks,agents/claude -am -DquickBuild=true")

        print("\n-- installing as a package would --")
        # Entirely in the user's own directories, with no sudo. Sokar scans
        # ~/.local/share/sokar/providers before /usr/share, and hooks resolve from ~/.local/bin
        # before /usr/libexec - so an unprivileged install is a supported shape, not a shortcut.
        # It is also the shape that matches how a task actually runs: rootless.
        remote(address, environment,
               "mkdir -p ~/.local/bin ~/.local/share/sokar/agents "
               "~/.local/share/sokar/providers && "
               f"cp {REPO}/hooks/target/sokar-hook-* ~/.local/bin/ && "
               f"cp {REPO}/app/target/sokar ~/.local/bin/ && "
               f"cp {REPO}/providers/*.yaml ~/.local/share/sokar/providers/ && "
               "~/.local/bin/sokar setup")

        print("\n-- what sokar thinks of this machine --")
        remote(address, environment, "PATH=$HOME/.local/bin:$PATH sokar doctor", check=False)

        print("\n-- tier 1, under SELinux enforcing --")
        remote(address, environment,
               f"cd {REPO} && PATH=$HOME/.local/bin:$PATH bash buildtools/e2e-tier1.sh")

    return 0


def upload(address: str, environment: dict[str, str]) -> None:
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
        hetzner.ssh_argv(address, f"rm -rf {REPO} && mkdir -p {REPO} && tar -x -C {REPO}",
                         user=BUILD_USER),
        input=archive, env=environment, capture_output=True, timeout=600,
    )
    if result.returncode != 0:
        sys.exit(f"upload failed: {result.stderr.decode(errors='replace')}")


def remote(address: str, environment: dict[str, str], command: str, *,
           check: bool = True) -> None:
    """Runs one command on the server, streaming its output into this log."""
    result = subprocess.run(hetzner.ssh_argv(address, command, user=BUILD_USER),
                            env=environment, timeout=3600)
    if check and result.returncode != 0:
        sys.exit(f"\nfailed on the remote with exit code {result.returncode}: {command}")


if __name__ == "__main__":
    sys.exit(main())
