"""Day-1 spikes 4–5 (architecture §8) against the live Muse Spark Open endpoint, metered under Purpose.PROBE.

Run from backend/ with the owner's .env loaded (values are never printed):
    set -a; . ../.env; set +a
    uv run python ../tools/spikes/day1_spikes.py --max-usd 0.40 [--burst 30] [--out ../docs/spikes.md]

What it measures:
  * structured-output mode the endpoint accepts for an image read (json_schema → tool → prompt fallback);
  * grounding ("Show me"): boxes for the Cancel button on the demo cancel screens vs the labelled box;
  * a parallel burst of text calls to see whether the shared endpoint rate-limits;
  * latency/cost per call.
Every call goes through the backend's ProviderRouter, so the all-spend meter reserves and settles it.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import statistics
import sys
import uuid
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "backend"))

from ridemate_api import prompts  # noqa: E402
from ridemate_api.deps_prod import build_production_deps  # noqa: E402
from ridemate_api.interfaces import ApiError, Purpose  # noqa: E402
from ridemate_api.model_gateway import ImagePart  # noqa: E402
from ridemate_api.router import TaskKind  # noqa: E402

LABELS = REPO / "eval" / "demo_labels.json"
POINT_HIT_TOLERANCE = 40  # on the 0–1000 grid: the arrow is drawn beside the box, so the centre must land inside ± 4 %


def _centre(box: list[int]) -> tuple[float, float]:
    return (box[0] + box[2]) / 2, (box[1] + box[3]) / 2


def _point_hits(predicted: list[int] | None, truth: list[int]) -> bool:
    if not predicted:
        return False
    cx, cy = _centre(predicted)
    return (truth[0] - POINT_HIT_TOLERANCE <= cx <= truth[2] + POINT_HIT_TOLERANCE
            and truth[1] - POINT_HIT_TOLERANCE <= cy <= truth[3] + POINT_HIT_TOLERANCE)


async def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-usd", type=float, default=0.40)
    ap.add_argument("--burst", type=int, default=30)
    ap.add_argument("--out", type=Path, default=REPO / "docs" / "spikes.md")
    args = ap.parse_args()

    deps = build_production_deps()
    spend, router = deps.spend, deps.router
    if spend is None or router is None:
        print("spend meter or router missing — refusing to call the model", file=sys.stderr)
        return 2
    start_spent = spend.summary()["ai_spent_usd"]

    def spent() -> float:
        return spend.summary()["ai_spent_usd"] - start_spent

    async def call(request, task: TaskKind):
        if spent() > args.max_usd:
            raise RuntimeError("spike budget reached")
        return await router.call(request, task=task, purpose=Purpose.PROBE, principal=None,
                                 request_id=f"probe-spike:{uuid.uuid4()}")

    labels = json.loads(LABELS.read_text())["screens"]
    report: dict[str, object] = {"model_present": await router.model_available(TaskKind.READ)}

    # Spike 4: one image read — vision + which structured mode is accepted.
    fare = next(s for s in labels if s["screen_type"] == "fare_options")
    img = (REPO / fare["file"]).read_bytes()
    res = await call(prompts.read_request(ImagePart(img, "image/png")), TaskKind.READ)
    read = prompts.validate_read(res.data)
    report["read"] = {"mode": str(res.mode), "latency_ms": res.latency_ms, "cost_usd": round(res.cost_usd, 5),
                      "attempts": res.attempts, "screen_type": read.get("screen_type"),
                      "options": len(read.get("options") or []), "rows_labelled": len(fare["rows"])}

    # Spike 5: grounding for "Show me" on every demo cancel screen.
    hits, point_rows = 0, []
    cancel_screens = [s for s in labels if s["screen_type"] == "cancel_flow"]
    for s in cancel_screens:
        truth = (s.get("targets") or {}).get("cancel_button")
        r = await call(prompts.point_request(ImagePart((REPO / s["file"]).read_bytes(), "image/png"), "cancel_button"),
                       TaskKind.POINT)
        box = prompts.validate_point(r.data)
        hit = bool(truth) and _point_hits(box, truth)
        hits += hit
        point_rows.append({"screen": s["file"].split("/demo/")[-1], "hit": hit, "latency_ms": r.latency_ms})
    report["point"] = {"screens": len(cancel_screens), "hits": hits, "rows": point_rows,
                       "supported": bool(cancel_screens) and hits == len(cancel_screens)}

    # Spike 4: burst of parallel text calls (rate limit of the shared endpoint).
    note = {"app": "demo_pepper", "version_code": 1, "title": "Pepper Rides", "text": "Your captain is 4 min away",
            "channel_id": "ride", "category": "status", "is_ongoing": True, "leg_state": "SEARCHING"}

    async def one() -> tuple[str, int | None]:
        try:
            r = await call(prompts.parse_request(note), TaskKind.PARSE)
            return "ok", r.latency_ms
        except ApiError as e:
            return e.code, None
        except Exception as e:  # noqa: BLE001 - record the class only
            return type(e).__name__, None

    burst = await asyncio.gather(*(one() for _ in range(args.burst)))
    outcomes: dict[str, int] = {}
    for code, _ in burst:
        outcomes[code] = outcomes.get(code, 0) + 1
    lat = sorted(ms for code, ms in burst if ms is not None)
    report["burst"] = {"calls": args.burst, "outcomes": outcomes,
                       "p50_ms": statistics.median(lat) if lat else None,
                       "p95_ms": lat[int(0.95 * (len(lat) - 1))] if lat else None}
    report["total_spike_usd"] = round(spent(), 4)

    print(json.dumps(report, indent=1))
    args.out.write_text(
        "# Day-1 spikes (architecture §8, items 4–5)\n\n"
        "Run with `tools/spikes/day1_spikes.py` through the backend router, so every call was metered "
        "(Purpose.PROBE). No fare values are recorded here.\n\n```json\n" + json.dumps(report, indent=1) + "\n```\n"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
