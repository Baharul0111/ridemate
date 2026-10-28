from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

import pytest

EVAL_DIR = Path(__file__).resolve().parent.parent
if str(EVAL_DIR) not in sys.path:
    sys.path.insert(0, str(EVAL_DIR))

from ridemate_eval.jsonio import load_json as load  # noqa: E402
from ridemate_eval.paths import DEMO_LABELS, DEMO_SCRIPT, SCHEMAS_DIR, use_config_package  # noqa: E402

use_config_package()


@pytest.fixture(scope="session")
def labels() -> dict[str, Any]:
    return load(DEMO_LABELS)


@pytest.fixture(scope="session")
def script() -> dict[str, Any]:
    return load(DEMO_SCRIPT)


@pytest.fixture(scope="session")
def schema_validator():
    from jsonschema import Draft202012Validator
    from referencing import Registry, Resource

    schemas = {p.name.removesuffix(".schema.json"): load(p) for p in SCHEMAS_DIR.glob("*.schema.json")}
    registry = Registry().with_resources((s["$id"], Resource.from_contents(s)) for s in schemas.values())

    def validate(name: str, instance: Any) -> list[str]:
        v = Draft202012Validator(schemas[name], registry=registry)
        return [f"/{'/'.join(map(str, e.absolute_path))}: {e.message}" for e in v.iter_errors(instance)]

    return validate


@pytest.fixture(scope="session")
def demo_build():
    from ridemate_eval.demo_assets import build_demo

    return build_demo()
