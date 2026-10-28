"""The single list of Remote Config keys (docs/contracts.md §2) and how each is stored and published.

Storage rule (config/README.md): every key has `config/remote_config/<key>.json`.
- JSON-valued keys hold the payload itself (an object with "schema", or a list for `owner_emails_sha256`).
- Scalar keys (BOOLEAN / NUMBER / STRING) hold `{"value": <scalar>}`.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Literal

ValueType = Literal["STRING", "BOOLEAN", "NUMBER", "JSON"]

CONFIG_DIR = Path(__file__).resolve().parent.parent
REPO_ROOT = CONFIG_DIR.parent
DEFAULTS_DIR = CONFIG_DIR / "remote_config"
SCHEMAS_DIR = CONFIG_DIR / "schemas"
ANDROID_ASSETS_CONFIG_DIR = REPO_ROOT / "android/app/src/main/assets/config"
ANDROID_DEFAULTS_XML = REPO_ROOT / "android/app/src/main/res/xml/remote_config_defaults.xml"
TEMPLATE_PATH = CONFIG_DIR / "remoteconfig.template.json"

REAL_PACKAGES: tuple[str, ...] = ("com.ubercab", "com.olacabs.customer", "com.rapido.passenger")
DEMO_PACKAGES: tuple[str, ...] = ("demo.kestrel", "demo.pepper", "demo.tamarind")
ALL_PACKAGES: tuple[str, ...] = REAL_PACKAGES + DEMO_PACKAGES

# Wording required in `notes` for every real-app entry until the owner records that app (architecture §4.19).
UNVERIFIED_NOTE = "unverified until recorded with the owner's Recorder"


@dataclass(frozen=True)
class KeySpec:
    name: str
    value_type: ValueType
    description: str
    # Value written into the APK-bundled defaults instead of the default file's value (None = same value).
    bundled_value: object | None = None

    @property
    def is_json(self) -> bool:
        return self.value_type == "JSON"

    @property
    def per_app(self) -> bool:
        """Payloads keyed by Android package under "apps"."""
        return self.name in PER_APP_KEYS

    @property
    def required_packages(self) -> tuple[str, ...]:
        """Packages a per-app payload must cover (empty for keys that are not per app)."""
        if not self.per_app:
            return ()
        return REAL_PACKAGES if self.name == "launch_templates" else ALL_PACKAGES

    @property
    def default_path(self) -> Path:
        return DEFAULTS_DIR / f"{self.name}.json"

    @property
    def schema_path(self) -> Path:
        return SCHEMAS_DIR / f"{self.name}.schema.json"


PER_APP_KEYS = frozenset(
    {"notification_templates", "fare_parsers", "cancel_cards", "fee_rules", "launch_templates"}
)

# launch_templates covers only the real apps. The phone's LaunchMode has no demo mode (DemoSource shows
# assets/demo/<provider>/ itself, contracts §5), and one unknown mode makes the phone reject the whole payload.
LAUNCH_MODES: tuple[str, ...] = ("uri", "launch_paste", "launch")
LAUNCH_PLACEHOLDERS = frozenset({"plat", "plng", "pname", "dlat", "dlng", "dname"})

KEYS: tuple[KeySpec, ...] = (
    KeySpec(
        "launch_templates", "JSON", "How each ride app is opened with the route (uri / launch_paste / launch)."
    ),
    KeySpec(
        "notification_templates", "JSON", "RE2 templates turning ride-app notifications into ride events."
    ),
    KeySpec("fare_parsers", "JSON", "Channel B fare parser rules per app versionCode."),
    KeySpec("cancel_cards", "JSON", "Per-app cancel instructions (en/hi) and cancelled-screen keywords."),
    KeySpec("fee_rules", "JSON", "Cancellation fee windows and caps per app and state."),
    KeySpec("sanity_bands", "JSON", "Price/ETA sanity bands used by the verification gate."),
    KeySpec("ai_reader_enabled", "BOOLEAN", "Kill switch for all AI reading (manual mode when false)."),
    KeySpec("reader_enabled_uber", "BOOLEAN", "Per-app reader kill switch: Uber."),
    KeySpec("reader_enabled_ola", "BOOLEAN", "Per-app reader kill switch: Ola."),
    KeySpec("reader_enabled_rapido", "BOOLEAN", "Per-app reader kill switch: Rapido."),
    KeySpec("ondc_enabled", "BOOLEAN", "ONDC flow on/off (hackathon build: mock seller)."),
    KeySpec("demo_enabled", "BOOLEAN", "Try the demo on/off."),
    KeySpec("model_route", "JSON", "Which provider route serves each AI task."),
    KeySpec("latest_version", "JSON", "Newest released app version for the update prompt."),
    KeySpec("min_supported_version", "NUMBER", "Oldest app versionCode still served."),
    KeySpec("cap_profile", "STRING", "Spend cap profile: normal | judging."),
    KeySpec("credit_expiry_date", "STRING", "Modal credit expiry (YYYY-MM-DD) or unknown."),
    KeySpec("budget_horizon_days", "NUMBER", "Pacing horizon in days when the credit expiry is unknown."),
    KeySpec("budget_horizon_start", "STRING", "Start date (YYYY-MM-DD) of the pacing horizon."),
    KeySpec(
        "api_base_url",
        "STRING",
        "Base URL of the ridemate-api Modal web app (set at deploy).",
        bundled_value="",
    ),
    KeySpec(
        "judge_allowlist", "JSON", "SHA-256 of lower-cased judge/tester emails with a larger fair share."
    ),
    KeySpec("owner_emails_sha256", "JSON", "SHA-256 of lower-cased owner emails (enables developer mode)."),
)

KEYS_BY_NAME: dict[str, KeySpec] = {k.name: k for k in KEYS}
