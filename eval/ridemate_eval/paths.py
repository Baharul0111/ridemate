"""Repository locations used by the eval tooling, resolved from this file (never from the CWD)."""

from __future__ import annotations

import sys
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent.parent
REPO_ROOT = EVAL_DIR.parent
CONFIG_DIR = REPO_ROOT / "config"
FONTS_DIR = EVAL_DIR / "fonts"
DEMO_ASSETS_DIR = REPO_ROOT / "android/app/src/main/assets/demo"
DEMO_SCRIPT = DEMO_ASSETS_DIR / "script.json"
DEMO_LABELS = EVAL_DIR / "demo_labels.json"
SYNTHETIC_DIR = EVAL_DIR / "synthetic"
INJECTION_DIR = EVAL_DIR / "injection"
RECORDED_DIR = EVAL_DIR / "recorded"
SCHEMAS_DIR = EVAL_DIR / "schemas"
DEFAULT_GOLDEN_OUT = EVAL_DIR / "private/golden"
ACCURACY_RESULTS_DIR = REPO_ROOT / "android/core/build/accuracy"


def use_config_package() -> None:
    """Make `ridemate_config` (config/) importable: tooling shares the phone's templates and parsers."""
    if str(CONFIG_DIR) not in sys.path:
        sys.path.insert(0, str(CONFIG_DIR))


def repo_relative(path: Path) -> str:
    return path.resolve().relative_to(REPO_ROOT).as_posix()
