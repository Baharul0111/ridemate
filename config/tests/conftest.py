from __future__ import annotations

import sys
from pathlib import Path

CONFIG_DIR = Path(__file__).resolve().parent.parent
if str(CONFIG_DIR) not in sys.path:
    sys.path.insert(0, str(CONFIG_DIR))
