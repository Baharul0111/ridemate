"""Runtime settings for ridemate-api. Every value comes from the environment (Modal Secrets) or a constant here.

Money constants follow architecture.md §4.2 / §4.10 / §4.13 and are the single source for the spend meter.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field

# Muse Spark Open on Modal's shared endpoint, USD per 1M tokens (architecture.md §4.10).
PRICE_INPUT_PER_M = 1.25
PRICE_CACHED_INPUT_PER_M = 0.15
PRICE_OUTPUT_PER_M = 4.25

# Modal CPU/memory list prices (architecture.md §10, verified 28 Oct 2026).
PRICE_CPU_PER_CORE_S = 0.0000131
PRICE_MEM_PER_GIB_S = 0.00000222

PINNED_MODEL_ID = "meta-models/Muse-Spark-Open-816B-A42B"

# Budget split of the $100 Modal credit (owner's fixed choice): AI $80 · compute $12 · margin $8.
BUDGET_AI_USD = 80.0
BUDGET_COMPUTE_USD = 12.0
BUDGET_MARGIN_USD = 8.0
# Sub-budgets carved out of the AI budget.
SUB_BUDGET_EVALS_USD = 5.0  # evals + Template Doctor, ≤ $0.50 per run
SUB_BUDGET_EVAL_RUN_USD = 0.50
SUB_BUDGET_PROBES_USD = 2.0
SUB_BUDGET_JUDGING_USD = 15.0
SUB_BUDGET_DEMO_USD = 3.0  # demo-image cache misses

# Fair share (architecture.md §4.2 Abuse limits).
QUOTA_READS_PER_DAY = 6
QUOTA_PARSES_PER_DAY = 10
QUOTA_READS_YOUNG = 3
QUOTA_PARSES_YOUNG = 5
QUOTA_READS_JUDGE = 20
QUOTA_PARSES_JUDGE = 40
YOUNG_ACCOUNT_S = 24 * 3600
IP_BUCKET_PER_MIN = 60

# Pacing.
PACING_MULTIPLIER = 1.5
TIER_PAUSE_FRACTION = 0.70
JUDGING_DAY_CAP_USD = 3.0

# Request limits (B8).
MAX_IMAGE_BYTES = 1_000_000
MAX_JSON_BYTES = 64 * 1024

# Server deadlines in seconds (E4), re-pinned by the day-1 spike: text calls take 5–8 s at the lowest reasoning effort.
DEADLINE_READ_S = 12.0
DEADLINE_PARSE_S = 10.0
DEADLINE_PLACE_S = 10.0
DEADLINE_POINT_S = 12.0
DEADLINE_ONDC_OUTBOUND_S = 5.0
DEADLINE_FIRESTORE_S = 3.0
TAP_BUDGET_S = 20.0
RETRY_MIN_REMAINING_S = 6.0

# Output-token ceilings (L4). Reasoning tokens count against them and the endpoint cannot switch reasoning off
# (day-1 spike, docs/spikes.md), so each ceiling leaves room for ~400–650 reasoning tokens before the answer.
MAX_TOKENS_READ = 2000
MAX_TOKENS_REREAD = 1600
MAX_TOKENS_POINT = 700
MAX_TOKENS_PARSE = 700
MAX_TOKENS_PLACE = 600

# Container shape used by the compute meter (architecture.md §4.13).
WEB_CPU_CORES = 0.25
WEB_MEMORY_MIB = 512
CRON_CPU_CORES = 0.25
CRON_MEMORY_MIB = 512


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default)


def _env_list(name: str) -> list[str]:
    return [v.strip().lower() for v in _env(name).split(",") if v.strip()]


@dataclass(frozen=True)
class Settings:
    """Environment-backed settings. Construct with `Settings.from_env()`; tests pass explicit values."""

    model_base_url: str = ""
    model_token_id: str = ""
    model_token_secret: str = ""
    firebase_project_id: str = ""
    firebase_web_api_key: str = ""
    hmac_keys: tuple[str, ...] = ()  # current first, previous second (30-day dual read, H5)
    owner_emails: tuple[str, ...] = ()
    healthchecks_heartbeat_url: str = ""
    healthchecks_alert_url: str = ""
    ondc_mode: str = "off"  # off | mock | preprod | prod
    environment: str = "main"  # Modal environment
    hot_dict_name: str = "ridemate-hot"
    budget_horizon_start: str = "2026-10-28"
    budget_horizon_days: int = 90
    credit_expiry_date: str = "unknown"
    public_base_url: str = ""
    web_sa_json: str = field(default="", repr=False)
    ops_sa_json: str = field(default="", repr=False)
    owner_fcm_token: str = field(default="", repr=False)

    @property
    def model_api_key(self) -> str:
        return f"{self.model_token_id}.{self.model_token_secret}"

    @property
    def ai_configured(self) -> bool:
        return bool(self.model_base_url and self.model_token_id and self.model_token_secret)

    @classmethod
    def from_env(cls) -> Settings:
        hmac_keys = tuple(k for k in (_env("RIDEMATE_HMAC_KEY"), _env("RIDEMATE_HMAC_KEY_PREVIOUS")) if k)
        return cls(
            model_base_url=_env("MODAL_SPARK_OPEN_URL").rstrip("/"),
            model_token_id=_env("MODAL_PROXY_TOKEN_ID"),
            model_token_secret=_env("MODAL_PROXY_TOKEN_SECRET"),
            firebase_project_id=_env("FIREBASE_PROJECT_ID"),
            firebase_web_api_key=_env("FIREBASE_WEB_API_KEY"),
            hmac_keys=hmac_keys,
            owner_emails=tuple(_env_list("OWNER_EMAILS")),
            healthchecks_heartbeat_url=_env("HEALTHCHECKS_HEARTBEAT_URL"),
            healthchecks_alert_url=_env("HEALTHCHECKS_ALERT_URL"),
            ondc_mode=_env("ONDC_MODE", "off"),
            environment=_env("MODAL_ENVIRONMENT", "main"),
            hot_dict_name=_env("RIDEMATE_HOT_DICT", "ridemate-hot"),
            budget_horizon_start=_env("BUDGET_HORIZON_START", "2026-10-28"),
            budget_horizon_days=int(_env("BUDGET_HORIZON_DAYS", "90")),
            credit_expiry_date=_env("CREDIT_EXPIRY_DATE", "unknown"),
            public_base_url=_env("RIDEMATE_PUBLIC_BASE_URL").rstrip("/"),
            web_sa_json=_env("FIRESTORE_WEB_SA_JSON"),
            ops_sa_json=_env("FIRESTORE_OPS_SA_JSON"),
            owner_fcm_token=_env("OWNER_FCM_TOKEN"),
        )
