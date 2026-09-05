#!/usr/bin/env python3
"""
Deletes test servers that a run left behind.

This exists because the cleanup in `provisioned()` cannot cover every case: a process killed
between two statements, a runner cancelled mid-job, a laptop closed. At 0.111 EUR/hour a server
nobody notices costs 81 EUR a month, which is more than a year of intended use.

Dry by default. Pass --now to actually delete.

    export REMOTE_BUILD="$(cat ~/.claude/.ssh/hetzner-api-token.txt)"
    python3 buildtools/ci/sweep.py            # says what it would do
    python3 buildtools/ci/sweep.py --now      # does it
"""

from __future__ import annotations

import argparse
import sys
from datetime import timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import hetzner  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--now", action="store_true",
                        help="delete, rather than saying what would be deleted")
    parser.add_argument("--mine", action="store_true",
                        help="delete only what this run created, regardless of age. What a job "
                             "should use to clean up after itself - deleting by age catches "
                             "another run's server when that run is slow")
    parser.add_argument("--older-than", type=int, default=60, metavar="MINUTES",
                        help="age at which a server counts as forgotten (default: %(default)s)")
    args = parser.parse_args()

    if args.mine:
        hetzner.delete_mine(hetzner.client())
        return 0

    deleted = hetzner.sweep(hetzner.client(),
                            older_than=timedelta(minutes=args.older_than),
                            dry_run=not args.now)
    if deleted and not args.now:
        print("\nnothing was deleted - pass --now")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
