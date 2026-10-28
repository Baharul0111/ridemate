"""Fetch the official TRV10 example replies for the in-process demo seller, at image-build time only.

The examples come from github.com/ONDC-Official/mobility-specification at a pinned commit and are written as
<out>/<on_action>.json ({"context": …, "message": …}); they are never committed. Without them the mock seller uses
its built-in minimal payloads, so a failed fetch only warns unless --strict is given.

    uv run --with pyyaml python scripts/fetch_trv10_examples.py --out /opt/ridemate/trv10_examples
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any
from urllib.parse import quote

import httpx

REPO = "ONDC-Official/mobility-specification"
PINNED_REF = "40953d6a8a6d56d948448e5d963188f04ed8ea04"  # branch release-TRV10-2.0.1 (TRV10 core 2.0.1)
EXAMPLES_ROOT = "api/components/examples/on-demand"
DEFAULT_OUT = Path("/opt/ridemate/trv10_examples")
TIMEOUT = httpx.Timeout(20.0, connect=5.0)
MAX_FILE_BYTES = 512 * 1024
# The example that best matches what the demo seller does, by a fragment of its file name; else the first.
PREFERRED = {
    "on_search": "Seller_Collecting",
    "on_select": "quote_offered",
    "on_init": "Seller_Collecting",
    "on_confirm": "latest_fulfillment_details",
    "on_status": "en-route",
    "on_cancel": "soft-cancel",
}


def pick_paths(tree_paths: list[str]) -> dict[str, str]:
    chosen: dict[str, str] = {}
    for action, hint in PREFERRED.items():
        prefix = f"{EXAMPLES_ROOT}/{action}/"
        candidates = sorted(
            p for p in tree_paths if p.startswith(prefix) and p.endswith((".yaml", ".yml", ".json"))
        )
        if candidates:
            chosen[action] = next((p for p in candidates if hint.lower() in p.lower()), candidates[0])
    return chosen


def extract_example(doc: Any) -> dict[str, Any] | None:
    """Spec examples are OpenAPI example objects ({summary, value: {context, message}}) or bare payloads."""
    if isinstance(doc, dict) and isinstance(doc.get("value"), dict):
        doc = doc["value"]
    if isinstance(doc, dict) and isinstance(doc.get("message"), dict):
        return {
            "context": doc.get("context") if isinstance(doc.get("context"), dict) else {},
            "message": doc["message"],
        }
    return None


def _parse(path: str, text: str) -> Any:
    if path.endswith(".json"):
        return json.loads(text)
    import yaml  # build-time only: `uv run --with pyyaml`

    try:
        return yaml.safe_load(text)
    except yaml.YAMLError as e:
        raise ValueError(f"{path}: not YAML") from e


def fetch(out: Path, ref: str) -> list[str]:
    """Writes the examples; returns the problems met (empty when every action was fetched)."""
    problems: list[str] = []
    out.mkdir(parents=True, exist_ok=True)
    with httpx.Client(timeout=TIMEOUT, follow_redirects=True) as client:
        tree = client.get(f"https://api.github.com/repos/{REPO}/git/trees/{ref}", params={"recursive": "1"})
        tree.raise_for_status()
        paths = [entry["path"] for entry in tree.json().get("tree", []) if entry.get("type") == "blob"]
        chosen = pick_paths(paths)
        for action in PREFERRED:
            path = chosen.get(action)
            if path is None:
                problems.append(f"{action}: no example in {EXAMPLES_ROOT}")
                continue
            raw = client.get(f"https://raw.githubusercontent.com/{REPO}/{ref}/{quote(path)}")
            if raw.status_code != 200 or len(raw.content) > MAX_FILE_BYTES:
                problems.append(f"{action}: HTTP {raw.status_code}, {len(raw.content)} bytes")
                continue
            example = extract_example(_parse(path, raw.text))
            if example is None:
                problems.append(f"{action}: {path} has no message")
                continue
            target = out / f"{action}.json"
            target.write_text(json.dumps(example, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            print(f"{action}: {path}")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--ref", default=PINNED_REF, help="commit to fetch (default: the pinned one)")
    parser.add_argument("--strict", action="store_true", help="fail when any example can't be fetched")
    args = parser.parse_args(argv)
    try:
        problems = fetch(args.out, args.ref)
    except (httpx.HTTPError, ValueError, ImportError) as e:
        problems = [f"fetch failed: {type(e).__name__}: {e}"]
    for problem in problems:
        print(f"warning: {problem}", file=sys.stderr)
    if problems and args.strict:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
