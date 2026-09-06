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
from pathlib import Path
from datetime import datetime, timedelta, timezone

from hcloud import APIException, Client
from hcloud.images import Image
from hcloud.locations import Location
from hcloud.server_types import ServerType

# Read by the sweep and by anything that creates a server. A label rather than a name prefix: a
# name is chosen per run and can be mistyped, a label is applied by one function.
LABEL = {"sokar": "ci"}
LABEL_SELECTOR = "sokar=ci"

# Identifies the run that made a server, so a run can delete its own rather than deleting by age
# and catching somebody else's. GITHUB_RUN_ID in CI; a timestamp locally, which is enough to tell
# two developers apart and does not pretend to be more.
RUN_LABEL = "run"


def run_id() -> str:
    """
    Returns a label value identifying this run, uniquely.

    The leg matters as well as the run: a matrix gives every leg the same GITHUB_RUN_ID, so
    without it one leg's cleanup deletes the other leg's server part way through - which shows up
    as ssh dying mid-build and is close to undebuggable.
    """
    from_ci = os.environ.get("GITHUB_RUN_ID", "").strip()
    leg = os.environ.get("SOKAR_CI_LEG", "").strip()
    if from_ci:
        return f"{from_ci}-{leg}" if leg else from_ci
    return "local-" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")

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


def agent(path: str) -> dict[str, str]:
    """
    Loads the private key into an ssh-agent and returns the environment that reaches it.

    The key never touches a filesystem. ssh(1) takes a key by path and has no way to be handed the
    material directly, so the alternative was writing the secret to a temporary file - which also
    made the exact bytes matter, and a stray carriage return then failed as "error in libcrypto"
    with nothing naming the file or the reason.

    The agent dies with this process, so nothing outlives the run.

    :param path: A key file, for a developer running this on their own machine. Ignored when the
        key is in the environment.
    :return: Environment variables that reach the agent, for ssh and scp.
    """
    material = os.environ.get(SSH_KEY_VARIABLE, "")
    if material.strip():
        # Carriage returns make an otherwise valid key unreadable, and ssh-add explains that no
        # better than ssh did.
        material = material.replace("\r\n", "\n").replace("\r", "\n").strip() + "\n"
        complain_if_malformed(material)
    else:
        if not os.path.isfile(path):
            sys.exit(
                f"No private key. Set {SSH_KEY_VARIABLE} to the key itself, or pass "
                f"--ssh-private-key; there is nothing at {path}"
            )
        material = Path(path).read_text()

    started = subprocess.run(["ssh-agent", "-s"], capture_output=True, text=True)
    if started.returncode != 0:
        sys.exit(f"Could not start ssh-agent: {started.stderr.strip()}")

    environment = dict(os.environ)
    for line in started.stdout.split("\n"):
        if "=" in line and ";" in line:
            name, _, rest = line.partition("=")
            environment[name.strip()] = rest.split(";")[0]

    added = subprocess.run(["ssh-add", "-"], input=material, capture_output=True, text=True,
                           env=environment)
    if added.returncode != 0:
        sys.exit(f"ssh-agent would not take the key: {added.stderr.strip()}")
    return environment


def complain_if_malformed(material: str) -> None:
    """
    Says what is wrong with the key before ssh-add does, in terms of the secret rather than crypto.

    Every check here is a way a secret gets damaged between a file and an environment variable.
    """
    lines = material.strip().split("\n")
    if not lines[0].startswith("-----BEGIN"):
        sys.exit(
            f"{SSH_KEY_VARIABLE} does not start with a PEM header. It begins "
            f"{lines[0][:20]!r} - is it a public key, or a path rather than the key itself?"
        )
    # Checked before the footer: a key whose line breaks were lost fails that test too, and
    # "no PEM footer" sends the reader looking for the wrong problem.
    if len(lines) < 3:
        sys.exit(
            f"{SSH_KEY_VARIABLE} is {len(lines)} line(s) long. A private key is many lines, so "
            "its line breaks were lost on the way into the secret - store the file's contents "
            "verbatim, newlines and all."
        )
    if not lines[-1].startswith("-----END"):
        sys.exit(f"{SSH_KEY_VARIABLE} does not end with a PEM footer; it ends {lines[-1][:20]!r}")


def fingerprint(environment: dict[str, str]) -> str:
    """
    Returns the MD5 fingerprint of the key in the agent, which is what the API reports.

    Asked of the agent rather than derived from a file, so the key stays where it is.
    """
    listed = subprocess.run(["ssh-add", "-l", "-E", "md5"], capture_output=True, text=True,
                            env=environment)
    if listed.returncode != 0:
        sys.exit(f"The agent holds no key: {listed.stdout.strip()} {listed.stderr.strip()}")
    for field in listed.stdout.split():
        if field.startswith("MD5:"):
            return field[len("MD5:"):]
    sys.exit(f"Could not parse a fingerprint from: {listed.stdout.strip()}")


def ssh_key(hcloud_client: Client, name: str | None, environment: dict[str, str]):
    """
    Returns the SSH key to create servers with.

    Matched to the private key in hand rather than named, when no name is given. Naming it means
    keeping two things in step - the secret holding the private half and the key registered in the
    project - and when they drift the server is created with a public key nobody holds, which
    shows up as a connection refused twenty lines later. The fingerprint cannot drift.

    :param name: Explicit name, which wins when given.
    :param environment: Reaches the agent holding the key that will be used to connect.
    :return: The matching key in the project.
    """
    available = list(hcloud_client.ssh_keys.get_all())
    if name:
        for key in available:
            if key.name == name:
                return key
        sys.exit(f"No SSH key named '{name}' in the project. "
                 f"Available: {[k.name for k in available] or 'none'}")

    wanted = fingerprint(environment)
    for key in available:
        if key.fingerprint == wanted:
            print(f"ssh key '{key.name}' matches the private key in hand")
            return key
    sys.exit(
        f"No key in the project matches the private key ({wanted}).\n"
        f"  in the project: {[(k.name, k.fingerprint) for k in available] or 'none'}\n"
        "  add its public half to the project, or pass --ssh-key to use one of the above"
    )


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


def newest_snapshot(hcloud_client: Client, operating_system: str) -> Image:
    """
    Returns the most recent snapshot for one operating system.

    By label rather than by id, so a workflow does not carry a number that goes stale the next
    time a snapshot is rebuilt. Newest wins, because rebuilding is how an image is updated.

    :param operating_system: The `os` label a snapshot was built with, such as `fedora`.
    """
    selector = f"{LABEL_SELECTOR},os={operating_system}"
    snapshots = [
        i for i in hcloud_client.images.get_all(type="snapshot", label_selector=selector)
        if i.status == "available"
    ]
    if not snapshots:
        available = sorted(
            i.labels.get("os", "?")
            for i in hcloud_client.images.get_all(type="snapshot", label_selector=LABEL_SELECTOR)
        )
        sys.exit(
            f"No snapshot for '{operating_system}'. Built: {available or 'none'}. "
            "Build one with the snapshot provisioner before running a leg."
        )
    newest = max(snapshots, key=lambda i: i.created)
    print(f"snapshot {newest.id}: {newest.description} ({newest.created.isoformat()})")
    return newest


# Where a CI server may be created. A zone rather than a location on purpose: a single location
# runs out - fsn1 offered zero server types on 2026-09-06 while nbg1 and hel1 offered eighteen -
# and a hard-coded one fails with "unsupported location for server type", which reads like a
# wrong type or a bad token rather than a full datacentre.
NETWORK_ZONE = "eu-central"


def location_for(hcloud_client: Client, server_type: str) -> str:
    """
    Picks a location in the zone that can actually create this server type right now.

    Asked rather than assumed: availability is per datacentre and changes, and Hetzner reports a
    location that cannot serve a type the same way it reports a nonsense one.

    :param server_type: Type the server will be created with.
    :return: Location name.
    :raises SystemExit: If nothing in the zone has it, listing what was asked.
    """
    wanted = hcloud_client.server_types.get_by_name(server_type)
    if wanted is None:
        raise SystemExit(f"no such server type: {server_type}")

    tried = []
    for datacenter in hcloud_client.datacenters.get_all():
        if datacenter.location.network_zone != NETWORK_ZONE:
            continue
        available = {t.id for t in datacenter.server_types.available}
        tried.append(f"{datacenter.name}={'yes' if wanted.id in available else 'no'}")
        if wanted.id in available:
            print(f"location {datacenter.location.name} has {server_type} ({', '.join(tried)})")
            return datacenter.location.name

    raise SystemExit(f"no location in {NETWORK_ZONE} currently offers {server_type}: "
                     + ", ".join(tried))


# How long to keep asking when the project is at its server limit, and how often. Three
# repositories rent machines now - the core's two acceptance legs and an agent's two - and
# nothing coordinates them, so overlapping runs collide. The API says resource_limit_exceeded
# and the run dies twenty minutes in, having already built everything.
LIMIT_WAIT_SECONDS = 30
LIMIT_ATTEMPTS = 20


def create_when_there_is_room(hcloud_client: Client, *, name: str, server_type: str,
                              image: Image, location: str, key):
    """
    Creates a server, waiting rather than failing while the project is at its limit.

    Only that one error is retried. Anything else - a bad image, a full datacentre, a rejected
    token - is a mistake that waiting cannot fix, and burning ten minutes before reporting it
    would be worse than failing now.

    :param key: SSH key to create the server with.
    :return: The create response.
    :raises SystemExit: If there was still no room after LIMIT_ATTEMPTS.
    """
    for attempt in range(1, LIMIT_ATTEMPTS + 1):
        try:
            return hcloud_client.servers.create(
                name=name,
                server_type=ServerType(name=server_type),
                image=image,
                location=Location(name=location),
                ssh_keys=[key],
                labels={**LABEL, RUN_LABEL: run_id()},
            )
        except APIException as failure:
            if failure.code != "resource_limit_exceeded":
                raise
            if attempt == LIMIT_ATTEMPTS:
                raise SystemExit(
                    f"the project was still at its server limit after "
                    f"{LIMIT_ATTEMPTS * LIMIT_WAIT_SECONDS // 60} minutes. Another run is "
                    f"holding machines, or something leaked one: check with sweep.py --list")
            print(f"  at the project's server limit, waiting {LIMIT_WAIT_SECONDS}s "
                  f"({attempt}/{LIMIT_ATTEMPTS})")
            time.sleep(LIMIT_WAIT_SECONDS)
    raise SystemExit("unreachable")


@contextmanager
def provisioned(hcloud_client: Client, *, name: str, server_type: str, image_name: str,
                location: str, ssh_key_name: str | None, environment: dict[str, str],
                keep: bool = False, image_override: Image | None = None):
    """
    Creates a server and destroys it again, whatever happens in between.

    The delete is in a `finally` and is not conditional on success: the expensive mistake is a
    server that outlives a script which failed on line three, not one that is deleted twice.

    :param keep: Leaves the server running, for debugging. Prints what it will cost per day and
        how to remove it, because the whole point of this module is that nothing is left running
        by accident.
    :param environment: Reaches the ssh-agent holding the key.
    :param image_override: An image already in hand, for a snapshot. Snapshots are found by
        description and label rather than by name, so they cannot be looked up the way a system
        image can.
    """
    key = ssh_key(hcloud_client, ssh_key_name, environment)
    found = image_override if image_override is not None else image(hcloud_client, image_name)

    print(f"creating {name}: {server_type}, {image_name}, {location}")
    response = create_when_there_is_room(
        hcloud_client, name=name, server_type=server_type, image=found, location=location,
        key=key)
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


def ssh_argv(address: str, command: str, user: str = "root") -> list[str]:
    """
    Builds an ssh invocation that takes its key from the agent.

    No -i: the key is in the agent, not on disk. StrictHostKeyChecking is off and known-hosts is
    /dev/null because the host is new every time, at an address the API has just told us, so
    there is no key that could have been known.
    """
    return ["ssh", "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null",
            "-o", "LogLevel=ERROR", "-o", "ConnectTimeout=15", f"{user}@{address}", command]


def ssh(address: str, environment: dict[str, str], command: str, *, check: bool = True) -> str:
    """Runs one command on the server and returns its output."""
    result = subprocess.run(
        ssh_argv(address, command), env=environment,
        capture_output=True, text=True, timeout=1800,
    )
    if check and result.returncode != 0:
        sys.exit(f"failed on {address}: {command}\n{result.stdout}\n{result.stderr}")
    return (result.stdout + result.stderr).strip()


def delete_mine(hcloud_client: Client) -> int:
    """
    Deletes the servers this run created, and nothing else.

    Separate from {@link sweep} on purpose. Deleting by age catches another run's server when that
    run is slow, and the symptom - ssh dying part way through a build - is close to undebuggable.
    A run should only ever remove what it made; everything else belongs to the scheduled sweep.

    :return: How many were deleted.
    """
    selector = f"{LABEL_SELECTOR},{RUN_LABEL}={run_id()}"
    deleted = 0
    for server in hcloud_client.servers.get_all(label_selector=selector):
        print(f"deleting {server.name}, made by this run")
        hcloud_client.servers.delete(server).wait_until_finished()
        deleted += 1
    if deleted == 0:
        print("this run left nothing behind")
    return deleted


def sweep(hcloud_client: Client, *, older_than: timedelta, dry_run: bool = True) -> int:
    """
    Deletes servers left behind by a run that could not clean up after itself.

    Age rather than state: a server doing useful work is younger than an hour, and one older than
    that is either forgotten or a run so slow it should be looked at anyway.

    This is for the scheduled sweep, not for a job cleaning up after itself - use
    {@link delete_mine} for that. Running this at the end of a job deletes whatever another job
    happens to have running.

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
