"""The proposal bundle written to `tools/template_doctor/out/<timestamp>/`:

- `notification_templates.json` / `fare_parsers.json`: the full patched payloads (only for keys that changed and
  only when the final regression passed), in the exact on-disk format of `config/remote_config/`
- `patch.diff`: unified diff against the baseline payloads
- `report.md`: the human-readable report (sample ids and reason codes only: no notification text, no fares)
- `proposal.json`: the machine-readable result
"""

from __future__ import annotations

import difflib
import json
from datetime import datetime
from pathlib import Path
from typing import Any

from . import paths
from .regression import dump_payload


def bundle_dir(out_root: Path, now: datetime) -> Path:
    stamp = now.strftime("%Y%m%dT%H%M%SZ")
    candidate = out_root / stamp
    n = 1
    while candidate.exists():
        n += 1
        candidate = out_root / f"{stamp}-{n}"
    return candidate


def unified_diff(name: str, before: Any, after: Any) -> str:
    rel = f"config/remote_config/{name}.json"
    return "".join(
        difflib.unified_diff(
            dump_payload(before).splitlines(keepends=True),
            dump_payload(after).splitlines(keepends=True),
            fromfile=f"a/{rel}",
            tofile=f"b/{rel}",
        )
    )


def write_bundle(
    out_root: Path,
    now: datetime,
    *,
    baseline: dict[str, Any],
    staged: dict[str, Any],
    include_patch: bool,
    report_md: str,
    result: dict[str, Any],
) -> tuple[Path, list[str]]:
    """Write the bundle; returns (directory, names of the patched keys written)."""
    directory = bundle_dir(out_root, now)
    directory.mkdir(parents=True)
    changed = [name for name in (paths.TEMPLATES_KEY, paths.PARSERS_KEY) if staged[name] != baseline[name]]
    written: list[str] = []
    if include_patch and changed:
        diffs = []
        for name in changed:
            (directory / f"{name}.json").write_text(dump_payload(staged[name]), encoding="utf-8")
            diffs.append(unified_diff(name, baseline[name], staged[name]))
            written.append(name)
        (directory / "patch.diff").write_text("".join(diffs), encoding="utf-8")
    (directory / "report.md").write_text(report_md, encoding="utf-8")
    (directory / "proposal.json").write_text(
        json.dumps({**result, "patched_keys": written}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return directory, written
