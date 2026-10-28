"""Repository locations and the import path for the shared config package.

`ridemate_api` (backend) is installed into this tool's environment as an editable path dependency.
`ridemate_config` lives in `config/` without its own project file, so it is put on `sys.path` here.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

TOOL_DIR = Path(__file__).resolve().parent.parent
REPO_ROOT = Path(os.environ.get("RIDEMATE_REPO_ROOT", TOOL_DIR.parent.parent)).resolve()
CONFIG_DIR = REPO_ROOT / "config"
DEFAULTS_DIR = CONFIG_DIR / "remote_config"
OUT_DIR = TOOL_DIR / "out"

TEMPLATES_KEY = "notification_templates"
PARSERS_KEY = "fare_parsers"


def bootstrap() -> None:
    """Make `ridemate_config` and `ridemate_api` importable when run from a checkout."""
    for path in (CONFIG_DIR, REPO_ROOT / "backend"):
        if path.is_dir() and str(path) not in sys.path:
            sys.path.insert(0, str(path))


bootstrap()
