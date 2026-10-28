#!/usr/bin/env python3
"""check_base_image.py: is the backend's digest-pinned base image still the registry's current build?

Architecture §4.18 C4 ("monthly base-image rebuild"). backend/modal_app.py pins its base image by digest
(`python:3.12-slim@sha256:...`), so Modal only rebuilds it when that pin changes. This check runs monthly in
.github/workflows/base-image.yml: when the tag has moved on (Debian/CPython security fixes), the run fails and
GitHub emails the owner, who updates the pin and redeploys. CI never deploys and needs no credentials.

What it does
  - Finds every `<image>:<tag>@sha256:<digest>` pin in the file(s) given (default: backend/modal_app.py), and
    every unpinned literal passed to `from_registry("...")` (an error: the image must be digest-pinned).
  - Asks Docker Hub for each tag's current digest: an anonymous pull token from auth.docker.io, then a HEAD
    request for the manifest (HEAD requests do not count against Docker Hub's pull limits). Read-only, free.
  - Exit 0: every pin is current (or --allow-stale was given); 1: a pin is stale; 2: usage, parse or network
    error (no pin found, an unpinned image, a registry other than Docker Hub, the registry unreachable).

To update a stale pin: replace the digest in backend/modal_app.py with the one printed, run the backend tests,
commit, and (owner only) redeploy so Modal rebuilds the image from the new base.

Usage: tools/ci/check_base_image.py [--allow-stale] [FILE ...]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FILES = [REPO_ROOT / "backend" / "modal_app.py"]
AUTH_URL = "https://auth.docker.io/token"
REGISTRY_URL = "https://registry-1.docker.io"
MANIFEST_TYPES = ", ".join((
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
))
TIMEOUT_S = 20

_PIN = re.compile(
    r"(?P<ref>(?P<name>[a-z0-9][a-z0-9._/-]*):(?P<tag>[A-Za-z0-9_][A-Za-z0-9._-]{0,127})"
    r"@(?P<digest>sha256:[0-9a-f]{64}))"
)
_FROM_REGISTRY = re.compile(r"""from_registry\(\s*(?P<q>["'])(?P<ref>[^"']+)(?P=q)""")


@dataclass(frozen=True)
class Pin:
    file: str
    line: int
    name: str
    tag: str
    digest: str

    @property
    def image(self) -> str:
        return f"{self.name}:{self.tag}"


class CheckError(Exception):
    """A problem that makes the check impossible (exit 2)."""


def find_pins(text: str, file: str) -> tuple[list[Pin], list[tuple[int, str]]]:
    """(digest pins, unpinned from_registry literals as (line, ref)) in one file's text."""
    pins: list[Pin] = []
    unpinned: list[tuple[int, str]] = []
    for lineno, line in enumerate(text.splitlines(), start=1):
        for m in _PIN.finditer(line):
            pins.append(Pin(file, lineno, m.group("name"), m.group("tag"), m.group("digest")))
        for m in _FROM_REGISTRY.finditer(line):
            if "@sha256:" not in m.group("ref"):
                unpinned.append((lineno, m.group("ref")))
    return pins, unpinned


def docker_hub_repository(name: str) -> str:
    """`python` -> `library/python`; `org/img` stays; a registry host (`ghcr.io/...`) is not supported."""
    first = name.split("/", 1)[0]
    if "/" in name and ("." in first or ":" in first or first == "localhost"):
        if first not in ("docker.io", "index.docker.io", "registry-1.docker.io"):
            raise CheckError(f"{name}: only Docker Hub images are supported (registry {first})")
        name = name.split("/", 1)[1]
    return name if "/" in name else f"library/{name}"


Fetch = Callable[[urllib.request.Request], tuple[dict[str, str], bytes]]


def _urlopen(req: urllib.request.Request) -> tuple[dict[str, str], bytes]:
    if not req.full_url.startswith(("https://auth.docker.io/", "https://registry-1.docker.io/")):
        raise CheckError(f"refusing to contact {req.full_url}")
    with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:  # noqa: S310 (fixed https hosts above)
        return {k.lower(): v for k, v in resp.headers.items()}, resp.read()


def current_digest(name: str, tag: str, fetch: Fetch = _urlopen) -> str:
    repo = docker_hub_repository(name)
    query = urllib.parse.urlencode({"service": "registry.docker.io", "scope": f"repository:{repo}:pull"})
    try:
        _, body = fetch(urllib.request.Request(f"{AUTH_URL}?{query}"))
        token = json.loads(body.decode("utf-8")).get("token")
        if not token:
            raise CheckError(f"{name}: Docker Hub returned no pull token")
        headers, _ = fetch(urllib.request.Request(
            f"{REGISTRY_URL}/v2/{repo}/manifests/{urllib.parse.quote(tag, safe='')}",
            method="HEAD", headers={"Authorization": f"Bearer {token}", "Accept": MANIFEST_TYPES}))
    except (urllib.error.URLError, TimeoutError, ValueError) as exc:
        raise CheckError(f"{name}:{tag}: registry lookup failed: {exc}") from exc
    digest = headers.get("docker-content-digest", "")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise CheckError(f"{name}:{tag}: the registry did not return a sha256 manifest digest")
    return digest


def _annotate(level: str, pin: Pin | None, message: str) -> None:
    if os.environ.get("GITHUB_ACTIONS") == "true":
        where = f" file={pin.file},line={pin.line}" if pin else ""
        print(f"::{level}{where}::{message}")


def main(argv: list[str] | None = None, fetch: Fetch = _urlopen) -> int:
    ap = argparse.ArgumentParser(description="Check digest-pinned base images against Docker Hub (C4).")
    ap.add_argument("files", nargs="*", type=Path, help="files to scan (default: backend/modal_app.py)")
    ap.add_argument("--allow-stale", action="store_true",
                    help="report a stale pin as a warning (exit 0); errors still fail")
    args = ap.parse_args(argv)
    files = args.files or DEFAULT_FILES

    pins: list[Pin] = []
    try:
        for path in files:
            if not path.is_file():
                raise CheckError(f"{path} not found")
            try:
                shown = str(path.resolve().relative_to(REPO_ROOT))
            except ValueError:
                shown = str(path)
            found, unpinned = find_pins(path.read_text(encoding="utf-8"), shown)
            if unpinned:
                lineno, ref = unpinned[0]
                raise CheckError(f"{shown}:{lineno}: from_registry(\"{ref}\") is not pinned by digest")
            pins += found
        if not pins:
            raise CheckError("no digest-pinned base image (<image>:<tag>@sha256:...) found in "
                             + ", ".join(str(f) for f in files))
        stale = 0
        for pin in pins:
            now = current_digest(pin.name, pin.tag, fetch)
            if now == pin.digest:
                print(f"current  {pin.file}:{pin.line}  {pin.image}@{pin.digest}")
                continue
            stale += 1
            message = (f"{pin.image} has a newer build: pinned {pin.digest}, registry now {now}. "
                       f"Update the pin to {pin.image}@{now}, run the backend tests, then redeploy (C4).")
            print(f"STALE    {pin.file}:{pin.line}  {message}")
            _annotate("warning" if args.allow_stale else "error", pin, message)
    except CheckError as exc:
        print(f"check_base_image: {exc}", file=sys.stderr)
        _annotate("error", None, str(exc))
        return 2
    if stale and not args.allow_stale:
        print(f"check_base_image: {stale} stale pin(s)", file=sys.stderr)
        return 1
    print(f"check_base_image: OK ({len(pins)} pin(s) checked{', stale allowed' if stale else ''})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
