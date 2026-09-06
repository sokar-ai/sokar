#!/usr/bin/env python3
"""
Compares a freshly built bill of materials against the published one, and says whether
publishing may proceed without a person.

This is what the other bills were for. An automated update is only safe if it knows what it is
not allowed to decide, and two of those things are questions about third-party code:

  * the set of dependencies changed - new code entering a containment tool is exactly what
    pinning exists to make visible;
  * a licence changed - the package redistributes that code, so this is an obligation.

Neither is answerable without comparing two bills, which is why nothing here could be written
before they existed.

    compare-bills.py <built.json> <published-url>

Exit codes:

    0   nothing that needs deciding: publish
    1   something changed that a person must look at
    2   the comparison could not be made, which is not the same as "nothing changed"

The third is deliberate. A fetch that fails must never read as "no published bill", because
that is indistinguishable from a first release - and treating a network error as a first
release publishes exactly the change this is meant to stop.
"""
from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request


def components(bom: dict) -> dict[str, dict]:
    """
    Flattens a bill to a mapping of identity to component.

    Keyed by purl, because a name is not an identity: cyclonedx-npm strips the scope, so
    '@earendil-works/pi-coding-agent' appears as 'pi-coding-agent' and would collide with
    anything else of that name. Components nest, so this walks rather than iterates.
    """
    found: dict[str, dict] = {}

    def walk(items):
        for component in items or []:
            key = component.get("purl") or f"{component.get('name')}@{component.get('version')}"
            found[key] = component
            walk(component.get("components"))

    walk(bom.get("components"))
    return found


def licences(component: dict) -> set[str]:
    """Every licence named on a component, however it is expressed."""
    named = set()
    for entry in component.get("licenses") or []:
        licence = entry.get("license") or {}
        name = licence.get("id") or licence.get("name") or entry.get("expression")
        if name:
            named.add(name)
    return named


def fetch(url: str) -> dict | None:
    """
    Reads the published bill, or None when there genuinely is not one yet.

    Redirects are followed: Artifactory serves a small bill directly and offloads a large one
    to cloud storage with a 302, so a fetcher that stops there gets an empty body for exactly
    the packages with the most to say.
    """
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as failure:
        if failure.code == 404:
            return None
        unreadable(f"{url}: HTTP {failure.code}")
    except Exception as failure:  # noqa: BLE001 - any failure here must stop the publish
        unreadable(f"{url}: {failure}")
    return None  # unreachable; unreadable() exits


def unreadable(reason: str):
    """
    Stops with the code that means "could not compare", which is not code 1.

    Distinct on purpose: 1 says a person must look at a real change, 2 says the question was
    never answered. A caller that treats them alike will eventually treat a network failure as
    a clean comparison.
    """
    print(f"could not read the published bill - {reason}", file=sys.stderr)
    print("This is not the same as 'nothing changed'.", file=sys.stderr)
    sys.exit(2)


def main() -> int:
    built_path, published_url = sys.argv[1], sys.argv[2]

    with open(built_path, encoding="utf-8") as handle:
        built = components(json.load(handle))

    published_bom = fetch(published_url)
    if published_bom is None:
        print("nothing published yet, so there is nothing to compare - publishing")
        return 0
    published = components(published_bom)

    added = sorted(set(built) - set(published))
    removed = sorted(set(published) - set(built))
    relicensed = sorted(
        key for key in set(built) & set(published)
        if licences(built[key]) != licences(published[key]))

    for label, keys in (("added", added), ("removed", removed)):
        for key in keys:
            print(f"  {label:9} {key}")
    for key in relicensed:
        before = ", ".join(sorted(licences(published[key]))) or "none"
        after = ", ".join(sorted(licences(built[key]))) or "none"
        print(f"  relicensed {key}: {before} -> {after}")

    if not (added or removed or relicensed):
        print(f"the same {len(built)} components, unchanged licences - publishing")
        return 0

    print()
    print(f"STOP: {len(added)} added, {len(removed)} removed, {len(relicensed)} relicensed.")
    print("New third-party code or a changed licence is not something to decide automatically.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
