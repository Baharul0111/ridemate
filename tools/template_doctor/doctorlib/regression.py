"""The regression gate a proposal bundle must pass.

1. Templates: every recorded post the baseline config classified correctly is still classified the same way
   (labelled posts keep their label; unlabelled posts keep the baseline event; OTHER posts stay unmatched).
2. Parser: every golden screen the baseline parser agreed with still agrees.
3. Config: the staged payloads pass the same schema + RE2 validation as `config/validate.py`
   (`ridemate_config.validation.validate_key`), and — for the final gate — `config/build_template.py
   --bundled-only` and `config/validate.py` themselves exit 0 on a scratch copy of `config/` holding the patch.
No sample text or fare value is ever written into a result: only sample ids and reason codes.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ridemate_config.keys import KEYS_BY_NAME
from ridemate_config.validation import validate_key

from . import paths
from .misses import notification_miss, parse_golden, screen_issues
from .samples import PostSample, ScreenSample
from .templates import classify

SCRIPT_TIMEOUT_S = 180


@dataclass
class RegressionResult:
    template_failures: list[str] = field(default_factory=list)
    parser_failures: list[str] = field(default_factory=list)
    config_errors: list[str] = field(default_factory=list)
    checked_posts: int = 0
    checked_screens: int = 0
    validate_script: str = "skipped"  # passed | failed | skipped

    @property
    def ok(self) -> bool:
        return not (self.template_failures or self.parser_failures or self.config_errors) and (
            self.validate_script != "failed"
        )

    def as_dict(self) -> dict[str, Any]:
        return {
            "ok": self.ok,
            "template_failures": self.template_failures,
            "parser_failures": self.parser_failures,
            "config_errors": self.config_errors,
            "checked_posts": self.checked_posts,
            "checked_screens": self.checked_screens,
            "validate_script": self.validate_script,
        }


def template_regression(
    baseline: dict[str, Any], staged: dict[str, Any], posts: Sequence[PostSample]
) -> tuple[list[str], int]:
    failures: list[str] = []
    checked = 0
    for sample in posts:
        before = classify(baseline, sample)
        if notification_miss(sample, before) is not None:
            continue  # it was already wrong; not fixing it is not a regression
        checked += 1
        after = classify(staged, sample)
        miss = notification_miss(sample, after)
        if miss is not None:
            failures.append(f"{sample.id}: was correct, now {miss.kind}")
        elif sample.label is None and before is not None and (after is None or after.event != before.event):
            failures.append(
                f"{sample.id}: event changed {before.event} -> {after.event if after else 'none'}"
            )
    return failures, checked


def parser_regression(
    baseline: dict[str, Any], staged: dict[str, Any], screens: Sequence[ScreenSample]
) -> tuple[list[str], int]:
    failures: list[str] = []
    checked = 0
    for screen in screens:
        before = parse_golden(baseline, screen)
        if before is None or screen_issues(screen, before):
            continue
        checked += 1
        after = parse_golden(staged, screen)
        issues = screen_issues(screen, after) if after is not None else ("unparsed",)
        if issues:
            failures.append(f"{screen.id}: agreed before, now {', '.join(issues)}")
    return failures, checked


def config_errors(staged: Mapping[str, Any]) -> list[str]:
    errors: list[str] = []
    for name, payload in staged.items():
        errors.extend(validate_key(KEYS_BY_NAME[name], payload))
    return errors


def dump_payload(payload: Any) -> str:
    """The on-disk format of config/remote_config/*.json (same as build_template.py writes)."""
    return json.dumps(payload, ensure_ascii=False, indent=2) + "\n"


def run_validate_script(staged: Mapping[str, Any], config_dir: Path | None = None) -> tuple[str, list[str]]:
    """Run config/build_template.py --bundled-only and config/validate.py on a scratch copy holding the patch.

    The scratch copy is a self-contained repo root (keys.py derives every path from its own location), so the
    real checkout and the APK assets are never written. Returns ("passed" | "failed" | "skipped", lines)."""
    source = config_dir or paths.CONFIG_DIR
    if not (source / "validate.py").exists() or not (source / "build_template.py").exists():
        return "skipped", ["config/validate.py not present; schema + RE2 validation ran in-process only"]
    with tempfile.TemporaryDirectory(prefix="template-doctor-") as tmp:
        root = Path(tmp)
        target = root / "config"
        shutil.copytree(
            source,
            target,
            ignore=shutil.ignore_patterns("tests", "__pycache__", ".pytest_cache", "*.pyc", ".ruff_cache"),
        )
        for name, payload in staged.items():
            (target / "remote_config" / f"{name}.json").write_text(dump_payload(payload), encoding="utf-8")
        lines: list[str] = []
        for script, args in (("build_template.py", ["--bundled-only"]), ("validate.py", [])):
            proc = subprocess.run(
                [sys.executable, str(target / script), *args],
                cwd=root,
                capture_output=True,
                text=True,
                timeout=SCRIPT_TIMEOUT_S,
                check=False,
            )
            failed = [
                ln for ln in (proc.stdout + proc.stderr).splitlines() if ln.startswith(("FAIL", "STALE"))
            ]
            if proc.returncode != 0:
                tail = failed or (proc.stdout + proc.stderr).strip().splitlines()[-5:]
                return "failed", [f"config/{script} exited {proc.returncode}", *tail]
            lines.append(f"config/{script}: ok")
        return "passed", lines


def run_regression(
    *,
    baseline_templates: dict[str, Any],
    staged_templates: dict[str, Any],
    baseline_parsers: dict[str, Any],
    staged_parsers: dict[str, Any],
    posts: Sequence[PostSample],
    screens: Sequence[ScreenSample],
    run_script: bool = False,
    config_dir: Path | None = None,
) -> RegressionResult:
    result = RegressionResult()
    result.template_failures, result.checked_posts = template_regression(
        baseline_templates, staged_templates, posts
    )
    result.parser_failures, result.checked_screens = parser_regression(
        baseline_parsers, staged_parsers, screens
    )
    staged = {paths.TEMPLATES_KEY: staged_templates, paths.PARSERS_KEY: staged_parsers}
    result.config_errors = config_errors(staged)
    if run_script and not result.config_errors:
        status, lines = run_validate_script(staged, config_dir)
        result.validate_script = status
        if status == "failed":
            result.config_errors.extend(lines)
    return result
