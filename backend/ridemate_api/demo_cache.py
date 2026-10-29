"""Try the demo on the server side (architecture §4.18 M9).

The allowlist is the SHA-256 of every bundled demo image (`demo_manifest.json`, written by
scripts/build_demo_manifest.py). A demo read is answered from the hot-store cache, else by one live model call
charged to the demo sub-budget (then cached), else — whenever AI is unavailable, paused, switched off or out of
budget — by the recorded Muse Spark Open output bundled under `recorded/`. Pointing on demo images is cached the
same way, without a recorded fallback.

Demo requests are anonymous, so what they can cost is bounded here as well as in the spend meter (the demo
sub-budget and its own daily cap): every cache key is drawn from a closed set (16 bundled images; × the point
targets for pointing), unreadable answers are cached too (retried at most hourly, so one image can't miss again and
again), and at most DEMO_LIVE_MISSES_PER_DAY live calls are made per IST day across all containers, counted with
put_if_absent slots in the hot store. Past that count a read is answered from the recorded output and pointing
pauses until midnight. Cache reads and writes (Modal Dict) and the recorded-file read run in the worker-thread
pool, never on the event loop.
"""

from __future__ import annotations

import hashlib
import json
import logging
from collections.abc import Awaitable, Callable, Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .clock import Clock, SystemClock, ist_day, next_ist_midnight
from .interfaces import ApiError, Metrics
from .offload import off_loop
from .prompts import validate_read
from .settings import DEMO_LIVE_MISSES_PER_DAY
from .store import HotStore, StoreUnavailable

log = logging.getLogger(__name__)

PACKAGE_DIR = Path(__file__).resolve().parent
MANIFEST_PATH = PACKAGE_DIR / "demo_manifest.json"
RECORDED_DIR = PACKAGE_DIR / "recorded"
KEY_PREFIX = "demo:"
LIVE_PREFIX = "demo_live:"  # demo_live:{IST day} = hint; demo_live:{IST day}:{n} = one live call's slot
UNREADABLE_RETRY_S = 3600.0  # a cached "unreadable" read is tried live again after this long
# ApiError statuses that mean "no live answer right now" (paused, quota, breaker, meter, kill switch, timeout).
AI_UNAVAILABLE_STATUSES = frozenset({429, 503, 504})

# A live call: returns (validated payload, model id). Raises ApiError when AI is unavailable.
LiveCall = Callable[[], Awaitable[tuple[dict[str, Any], str]]]


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def crops_key(crops: Iterable[tuple[str, str]]) -> str:
    """Cache key for a reread: the (row_id, crop sha256) pairs in a stable order."""
    canonical = json.dumps(sorted(crops), separators=(",", ":"))
    return sha256_hex(canonical.encode("utf-8"))


@dataclass(frozen=True)
class DemoImage:
    app: str
    screen: str


@dataclass(frozen=True)
class DemoAnswer:
    payload: dict[str, Any]
    source: str  # live | cache | recorded
    model: str


class DemoCache:
    def __init__(
        self,
        *,
        store: HotStore,
        metrics: Metrics,
        images: dict[str, DemoImage],
        recorded_dir: Path | None = RECORDED_DIR,
        clock: Clock | None = None,
        live_per_day: int = DEMO_LIVE_MISSES_PER_DAY,
    ) -> None:
        self._store = store
        self._metrics = metrics
        self._images = images
        self._recorded_dir = recorded_dir
        self._clock = clock or SystemClock()
        self._live_per_day = live_per_day

    @classmethod
    def from_manifest(
        cls,
        *,
        store: HotStore,
        metrics: Metrics,
        manifest_path: Path = MANIFEST_PATH,
        recorded_dir: Path | None = RECORDED_DIR,
        clock: Clock | None = None,
    ) -> DemoCache:
        return cls(
            store=store,
            metrics=metrics,
            images=load_manifest(manifest_path),
            recorded_dir=recorded_dir,
            clock=clock,
        )

    def is_allowlisted(self, sha256: str) -> bool:
        return sha256 in self._images

    def all_allowlisted(self, hashes: Iterable[str]) -> bool:
        hashes = list(hashes)
        return bool(hashes) and all(h in self._images for h in hashes)

    def image(self, sha256: str) -> DemoImage | None:
        return self._images.get(sha256)

    async def read(self, sha256: str, live: LiveCall | None) -> DemoAnswer:
        """cache → live (then cached) → recorded. `live` is None when AI is switched off for this request, and is
        skipped once today's live demo calls are used up. A cached "unreadable" answer is served for an hour, then
        tried live once more (and served again if that try can't be made)."""
        key = f"{KEY_PREFIX}read:{sha256}"
        cached = await off_loop(self._get, key)
        if cached is not None and not self._retry_unreadable(cached):
            return self._hit("read", cached)
        failure: ApiError | None = None
        if live is not None and not await off_loop(self._claim_live):
            failure = self._limit_reached("read")
            live = None
        if live is not None:
            try:
                payload, model = await live()
            except ApiError as e:
                if e.status not in AI_UNAVAILABLE_STATUSES:
                    raise
                failure = e
                self._metrics.incr("demo_live_unavailable", kind="read", code=e.code)
            else:
                # Unreadable answers are cached too (and retried at most hourly), so one image can't miss forever.
                await off_loop(self._put, key, payload, model)
                self._metrics.incr("demo_answer", kind="read", source="live")
                return DemoAnswer(payload, "live", model)
        if cached is not None:
            return self._hit("read", cached)
        recorded = await off_loop(self.recorded, sha256)
        if recorded is None:
            raise failure or ApiError(503, "ai_unavailable", reason="kill_switch")
        self._metrics.incr("demo_answer", kind="read", source="recorded")
        return recorded

    async def cached_call(self, kind: str, key_hash: str, live: LiveCall | None) -> DemoAnswer:
        """Rereads and pointing on demo crops: cache → live (then cached). No recorded fallback."""
        key = f"{KEY_PREFIX}{kind}:{key_hash}"
        cached = await off_loop(self._get, key)
        if cached is not None:
            return self._hit(kind, cached)
        if live is None:
            raise ApiError(503, "ai_unavailable", reason="kill_switch")
        if not await off_loop(self._claim_live):
            raise self._limit_reached(kind)
        payload, model = await live()
        await off_loop(self._put, key, payload, model)
        self._metrics.incr("demo_answer", kind=kind, source="live")
        return DemoAnswer(payload, "live", model)

    def recorded(self, sha256: str) -> DemoAnswer | None:
        if (
            self._recorded_dir is None
            or len(sha256) != 64
            or not all(c in "0123456789abcdef" for c in sha256)
        ):
            return None
        path = self._recorded_dir / f"{sha256}.json"
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        if (
            not isinstance(doc, dict)
            or doc.get("image_sha256") != sha256
            or not isinstance(doc.get("read"), dict)
        ):
            log.warning("recorded demo output %s is malformed", sha256[:12])
            return None
        return DemoAnswer(validate_read(doc["read"]), "recorded", str(doc.get("model") or ""))

    def _claim_live(self) -> bool:
        """One of today's DEMO_LIVE_MISSES_PER_DAY live calls, shared by every container; False when they are all
        used or the count can't be read (no live call then: fail closed)."""
        base = f"{LIVE_PREFIX}{ist_day(self._clock.now_utc())}"
        try:
            try:
                hint = max(0, int(self._store.get(base) or 0))
            except (TypeError, ValueError):
                hint = 0
            for n in range(hint, self._live_per_day):
                if self._store.put_if_absent(f"{base}:{n}", 1):
                    self._store.put(base, n + 1)
                    return True
        except StoreUnavailable:
            log.warning("demo live-call count unreadable; answering without a live call")
        return False

    def _limit_reached(self, kind: str) -> ApiError:
        self._metrics.incr("demo_live_limited", kind=kind)
        return ApiError(
            429, "ai_paused", reason="day_cap", resume_at=next_ist_midnight(self._clock.now_utc())
        )

    def _retry_unreadable(self, cached: dict[str, Any]) -> bool:
        """A cached unreadable read older than UNREADABLE_RETRY_S may be tried live once more."""
        if cached["payload"].get("screen_type") not in (None, "unreadable"):
            return False
        try:
            at = float(cached.get("at", 0.0))
        except (TypeError, ValueError):
            at = 0.0
        return self._clock.now_utc().timestamp() - at >= UNREADABLE_RETRY_S

    def _hit(self, kind: str, cached: dict[str, Any]) -> DemoAnswer:
        self._metrics.incr("demo_answer", kind=kind, source="cache")
        return DemoAnswer(cached["payload"], "cache", str(cached.get("model") or ""))

    def _get(self, key: str) -> dict[str, Any] | None:
        try:
            value = self._store.get(key)
        except StoreUnavailable:
            return None
        if isinstance(value, dict) and isinstance(value.get("payload"), dict):
            return value
        return None

    def _put(self, key: str, payload: dict[str, Any], model: str) -> None:
        try:
            self._store.put(
                key, {"payload": payload, "model": model, "at": self._clock.now_utc().timestamp()}
            )
        except StoreUnavailable:
            log.warning("demo cache write skipped: store unavailable")


def load_manifest(path: Path = MANIFEST_PATH) -> dict[str, DemoImage]:
    """{"images": {"<sha256>": {"app": "demo_kestrel", "screen": "fare_options"}}}; missing file → empty."""
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        log.warning("demo manifest %s not found; demo allowlist is empty", path.name)
        return {}
    images = doc.get("images") if isinstance(doc, dict) else None
    if not isinstance(images, dict):
        raise TypeError(f"demo manifest {path} has no 'images' object")
    out: dict[str, DemoImage] = {}
    for sha, meta in images.items():
        if isinstance(sha, str) and len(sha) == 64 and isinstance(meta, dict):
            out[sha.lower()] = DemoImage(app=str(meta.get("app", "")), screen=str(meta.get("screen", "")))
    return out
