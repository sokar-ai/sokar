"""
Shared pieces for driving the Hetzner Cloud test servers.

The one thing worth reading before anything else: **a server that is not destroyed costs
81 EUR a month**, against 3 cents for the fifteen minutes it is meant to live. So destruction is
structural here rather than a step at the end - `provisioned()` is a context manager that deletes
in a `finally`, and `sweep()` exists because a process killed between two statements cannot clean
up after itself.

Everything is labelled `sokar=ci` so the sweep can find it without a list of names to keep in
step with reality.
"""

from __future__ import annotations

import os
import socket
import subprocess
import sys
import time
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone

from hcloud import Client
from hcloud.images import Image
from hcloud.locations import Location
from hcloud.server_types import ServerType

# Read by the sweep and by anything that creates a server. A label rather than a name prefix: a
# name is chosen per run and can be mistyped, a label is applied by one function.
LABEL = {"sokar": "ci"}
LABEL_SELECTOR = "sokar=ci"

# CI puts the token here; the library's own examples use HCLOUD_TOKEN, so that is the fallback and
# the one a reader will expect locally.
TOKEN_VARIABLES = ("REMOTE_BUILD", "HCLOUD_TOKEN")


def client() -> Client:
    """Returns a client, or exits saying which variables were looked at."""
    for name in TOKEN_VARIABLES:
        token = os.environ.get(name)
        if token:
            return Client(token=token, application_name="sokar-ci")
    sys.exit(
        "No API token. Set one of: " + ", ".join(TOKEN_VARIABLES) + "\n"
        "  locally:  export REMOTE_BUILD=\"$(cat ~/.claude/.ssh/hetzner-api-token.txt)\""
    )


def ssh_key(hcloud_client: Client, name: str):
    """Returns the named SSH key, or exits listing what the project actually has."""
    key = hcloud_client.ssh_keys.get_by_name(name)
    if key is None:
        available = [k.name for k in hcloud_client.ssh_keys.get_all()]
        sys.exit(f"No SSH key named '{name}' in the project. Available: {available or 'none'}")
    return key


def image(hcloud_client: Client, name: str) -> Image:
    """
    Returns the named system image, or exits listing what is there.

    Named images move - fedora-43 sits beside fedora-44 today and one of them will go. Failing
    with the list beats failing with a 404 from three layers down.
    """
    found = hcloud_client.images.get_by_name(name)
    if found is None:
        available = sorted(
            i.name for i in hcloud_client.images.get_all(type="system") if i.name
        )
        sys.exit(f"No image named '{name}'. Available: {available}")
    return found


@contextmanager
def provisioned(hcloud_client: Client, *, name: str, server_type: str, image_name: str,
                location: str, ssh_key_name: str, keep: bool = False):
    """
    Creates a server and destroys it again, whatever happens in between.

    The delete is in a `finally` and is not conditional on success: the expensive mistake is a
    server that outlives a script which failed on line three, not one that is deleted twice.

    :param keep: Leaves the server running, for debugging. Prints what it will cost per day and
        how to remove it, because the whole point of this module is that nothing is left running
        by accident.
    """
    key = ssh_key(hcloud_client, ssh_key_name)
    found = image(hcloud_client, image_name)

    print(f"creating {name}: {server_type}, {image_name}, {location}")
    response = hcloud_client.servers.create(
        name=name,
        server_type=ServerType(name=server_type),
        image=found,
        location=Location(name=location),
        ssh_keys=[key],
        labels=LABEL,
    )
    server = response.server
    response.action.wait_until_finished()
    address = server.public_net.ipv4.ip
    print(f"created  {name} at {address}")

    try:
        yield server, address
    finally:
        if keep:
            print(f"KEEPING {name} at {address} - it is costing money until you run:")
            print(f"    python3 buildtools/ci/sweep.py --now")
        else:
            print(f"deleting {name}")
            try:
                hcloud_client.servers.delete(server).wait_until_finished()
                print(f"deleted  {name}")
            except Exception as ex:  # noqa: BLE001 - a failure here must be loud, not fatal
                print(f"COULD NOT DELETE {name}: {ex}", file=sys.stderr)
                print(f"  delete it by hand, it is billing: {address}", file=sys.stderr)
                raise


def await_ssh(address: str, *, timeout: int = 300, port: int = 22) -> None:
    """
    Waits until the server answers on SSH.

    Polled rather than slept. A cloud image needs 30-60 seconds to finish cloud-init, and a fixed
    sleep is either too short - which is the usual reason these scripts are flaky - or a tax paid
    on every run.
    """
    print(f"waiting for ssh on {address}", end="", flush=True)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with socket.create_connection((address, port), timeout=5):
                print(" - up")
                return
        except OSError:
            print(".", end="", flush=True)
            time.sleep(3)
    print()
    sys.exit(f"{address} never answered on port {port} within {timeout}s")


def ssh(address: str, key_file: str, command: str, *, check: bool = True) -> str:
    """
    Runs one command on the server and returns its output.

    StrictHostKeyChecking is off and the known-hosts file is /dev/null: the host is new every
    time and is identified by an address the API just told us, so there is no key to have known.
    """
    result = subprocess.run(
        ["ssh", "-i", key_file, "-o", "StrictHostKeyChecking=no",
         "-o", "UserKnownHostsFile=/dev/null", "-o", "LogLevel=ERROR",
         "-o", "ConnectTimeout=15", f"root@{address}", command],
        capture_output=True, text=True, timeout=1800,
    )
    if check and result.returncode != 0:
        sys.exit(f"failed on {address}: {command}\n{result.stdout}\n{result.stderr}")
    return (result.stdout + result.stderr).strip()


def sweep(hcloud_client: Client, *, older_than: timedelta, dry_run: bool = True) -> int:
    """
    Deletes servers left behind by a run that could not clean up after itself.

    Age rather than state: a server doing useful work is younger than an hour, and one older than
    that is either forgotten or a run so slow it should be looked at anyway.

    :return: How many were deleted, or would have been.
    """
    cutoff = datetime.now(timezone.utc) - older_than
    deleted = 0
    for server in hcloud_client.servers.get_all(label_selector=LABEL_SELECTOR):
        if server.created > cutoff:
            print(f"keeping {server.name}, created {server.created.isoformat()}")
            continue
        deleted += 1
        if dry_run:
            print(f"WOULD DELETE {server.name}, created {server.created.isoformat()}")
        else:
            print(f"deleting {server.name}, created {server.created.isoformat()}")
            hcloud_client.servers.delete(server).wait_until_finished()
    if deleted == 0:
        print("nothing to sweep")
    return deleted
