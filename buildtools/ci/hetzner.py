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
import tempfile
import sys
import time
from contextlib import contextmanager
from pathlib import Path
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


# CI holds the private key here, as the key material itself rather than a path. Locally it is a
# file, which is what --ssh-private-key defaults to.
SSH_KEY_VARIABLE = "SSH"


def private_key(path: str) -> str:
    """
    Returns a path to the private key, taking it from the environment when CI put it there.

    Written to a file because ssh(1) wants one, with 0600 before anything is in it: a key that
    exists world-readable for even a moment is a key that leaked. The file lands in the runner's
    temporary directory, which is discarded with the job.

    :param path: Fallback path, for a developer running this on their own machine.
    :return: Path to a private key file.
    """
    material = os.environ.get(SSH_KEY_VARIABLE, "")
    if not material.strip():
        if not os.path.isfile(path):
            sys.exit(
                f"No private key. Set {SSH_KEY_VARIABLE} to the key itself, or pass "
                f"--ssh-private-key; there is nothing at {path}"
            )
        return path

    # Carriage returns make an otherwise valid key unreadable, and OpenSSH says so only as
    # "error in libcrypto", which names neither the file nor the reason.
    material = material.replace("\r\n", "\n").replace("\r", "\n").strip()
    complain_if_malformed(material)

    target = Path(tempfile.mkdtemp(prefix="sokar-ci-")) / "id"
    target.touch(mode=0o600)
    target.write_text(material + "\n")
    return str(target)


def complain_if_malformed(material: str) -> None:
    """
    Says what is wrong with the key before ssh does, in terms of the secret rather than of crypto.

    Every check here is a way a secret gets damaged between a file and an environment variable,
    and each one produces the same unhelpful "error in libcrypto" from OpenSSH.
    """
    lines = material.split("\n")
    if not lines[0].startswith("-----BEGIN"):
        sys.exit(
            f"{SSH_KEY_VARIABLE} does not start with a PEM header. It begins "
            f"{lines[0][:20]!r} - is it a public key, or a path rather than the key itself?"
        )
    # Checked before the footer: a key whose line breaks were lost fails the footer test too, and
    # "no PEM footer" sends the reader looking for the wrong problem.
    if len(lines) < 3:
        sys.exit(
            f"{SSH_KEY_VARIABLE} is {len(lines)} line(s) long. A private key is many lines, so "
            "its line breaks were lost on the way into the secret - store the file's contents "
            "verbatim, newlines and all."
        )
    if not lines[-1].startswith("-----END"):
        sys.exit(f"{SSH_KEY_VARIABLE} does not end with a PEM footer; it ends {lines[-1][:20]!r}")


def ssh_key(hcloud_client: Client, name: str | None, private_key_file: str):
    """
    Returns the SSH key to create servers with.

    Matched to the private key in hand rather than named, when no name is given. Naming it means
    keeping two things in step - the secret holding the private half and the key registered in the
    project - and when they drift the server is created with a public key nobody holds, which
    shows up as a connection refused twenty lines later. The fingerprint cannot drift.

    :param name: Explicit name, which wins when given.
    :param private_key_file: The private key that will be used to connect.
    :return: The matching key in the project.
    """
    available = list(hcloud_client.ssh_keys.get_all())
    if name:
        for key in available:
            if key.name == name:
                return key
        sys.exit(f"No SSH key named '{name}' in the project. "
                 f"Available: {[k.name for k in available] or 'none'}")

    wanted = fingerprint(private_key_file)
    for key in available:
        if key.fingerprint == wanted:
            print(f"ssh key '{key.name}' matches the private key in hand")
            return key
    sys.exit(
        f"No key in the project matches the private key ({wanted}).\n"
        f"  in the project: {[(k.name, k.fingerprint) for k in available] or 'none'}\n"
        "  add its public half to the project, or pass --ssh-key to use one of the above"
    )


def fingerprint(private_key_file: str) -> str:
    """
    Returns the MD5 fingerprint of a private key's public half, which is what the API reports.

    Derived from the private key so that nothing has to hold the public half as well.
    """
    public = subprocess.run(["ssh-keygen", "-y", "-f", private_key_file],
                            capture_output=True, text=True)
    if public.returncode != 0:
        sys.exit(f"Cannot read {private_key_file}: {public.stderr.strip()}")
    shown = subprocess.run(["ssh-keygen", "-l", "-E", "md5", "-f", "/dev/stdin"],
                           input=public.stdout, capture_output=True, text=True)
    if shown.returncode != 0:
        sys.exit(f"Cannot fingerprint {private_key_file}: {shown.stderr.strip()}")
    # "2048 MD5:aa:bb:.. comment (RSA)" - the API reports the hex pairs without the prefix.
    for field in shown.stdout.split():
        if field.startswith("MD5:"):
            return field[len("MD5:"):]
    sys.exit(f"Could not parse a fingerprint from: {shown.stdout.strip()}")


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


def newest_snapshot(hcloud_client: Client) -> Image:
    """
    Returns the most recent snapshot this project built.

    By label rather than by id, so a workflow does not carry a number that goes stale the next
    time the snapshot is rebuilt. Newest wins, because rebuilding is how the image is updated.
    """
    snapshots = [
        i for i in hcloud_client.images.get_all(type="snapshot", label_selector=LABEL_SELECTOR)
        if i.status == "available"
    ]
    if not snapshots:
        sys.exit(
            f"No snapshot labelled {LABEL_SELECTOR}. Build one first - see "
            "requirements/0048-Fedora-Test-Server-Snapshot.md"
        )
    newest = max(snapshots, key=lambda i: i.created)
    print(f"snapshot {newest.id}: {newest.description} ({newest.created.isoformat()})")
    return newest


@contextmanager
def provisioned(hcloud_client: Client, *, name: str, server_type: str, image_name: str,
                location: str, ssh_key_name: str | None, private_key_file: str,
                keep: bool = False, image_override: Image | None = None):
    """
    Creates a server and destroys it again, whatever happens in between.

    The delete is in a `finally` and is not conditional on success: the expensive mistake is a
    server that outlives a script which failed on line three, not one that is deleted twice.

    :param keep: Leaves the server running, for debugging. Prints what it will cost per day and
        how to remove it, because the whole point of this module is that nothing is left running
        by accident.
    :param image_override: An image already in hand, for a snapshot. Snapshots are found by
        description and label rather than by name, so they cannot be looked up the way a system
        image can.
    """
    key = ssh_key(hcloud_client, ssh_key_name, private_key_file)
    found = image_override if image_override is not None else image(hcloud_client, image_name)

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
